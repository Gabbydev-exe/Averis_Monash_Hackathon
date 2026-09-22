package com.shipping.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipping.api.model.EmailAttachmentDto;
import com.shipping.api.model.EmailDetailDto;
import com.shipping.api.model.EmailFieldDto;
import com.shipping.api.model.EmailSummaryDto;
import com.shipping.api.document.AttachmentTextReader;
import com.shipping.api.document.AttachmentTextResult;
import com.shipping.api.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmailDataService {

    private static final Logger log = LoggerFactory.getLogger(EmailDataService.class);

    private static final List<String> STANDARD_FIELD_LABELS = List.of(
            "Shipper",
            "Consignee",
            "Notify Party",
            "Port of Loading",
            "Port of Discharge",
            "Container Count",
            "Gross Weight (kg)"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailRepository emailRepository;
    private final AttachmentTextReader attachmentTextReader = new AttachmentTextReader();

    public EmailDataService(EmailRepository emailRepository) {
        this.emailRepository = Objects.requireNonNull(emailRepository, "Database repository is required");
    }

    public synchronized List<EmailSummaryDto> getAllEmailSummaries() {
        var statuses = emailRepository.workflow().statuses();
        var categories = emailRepository.workflow().categories();
            return emailRepository.findAll().stream()
                    .sorted(Comparator.comparingInt((EmailRepository.EmailRow row) -> extractIndex(row.id()))
                            .thenComparing(EmailRepository.EmailRow::id))
                    .map(row -> new EmailSummaryDto(
                            row.id(), row.sender(), extractSenderName(row.sender(), row.body()),
                            row.subject(), "Received", statuses.getOrDefault(row.id(), "pending"),
                            extractBookingNumber(row.subject(), row.body(), row.id()), row.attachmentCount(), categories.getOrDefault(row.id(), "UNCLASSIFIED")))
                    .toList();
    }

    public synchronized Optional<EmailDetailDto> getEmailDetail(String id) {
            return emailRepository.findById(id).map(this::toDatabaseDetail);
    }

    public synchronized ImportResult importJson(byte[] data) {
        List<JsonNode> records = EmailJsonData.parse(objectMapper, data);
        List<String> insertedIds;
            List<EmailRepository.SourceImport> imports = records.stream().map(email -> {
                List<EmailRepository.AttachmentImport> attachments = new ArrayList<>();
                int order = 0;
                for (JsonNode reference : email.path("attachments")) {
                    String path = reference.asText();
                    String filename = path.substring(path.lastIndexOf('/') + 1);
                    String mimeType = switch (filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
                        case "txt" -> "text/plain";
                        case "pdf" -> "application/pdf";
                        case "doc" -> "application/msword";
                        case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                        case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                        default -> "application/octet-stream";
                    };
                    attachments.add(new EmailRepository.AttachmentImport(
                            sha256((email.path("email_id").asText() + "\0" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            path, order++, filename, mimeType, 0L, ""));
                }
                return new EmailRepository.SourceImport(email, attachments);
            }).toList();
            insertedIds = emailRepository.insertSourceEmails(imports);
        return new ImportResult(records.size(), insertedIds.size(), records.size() - insertedIds.size(), "database", insertedIds);
    }

    public synchronized List<JsonNode> exportSourceEmails() {
        return emailRepository.findAllRawEmails().stream().map(raw -> {
            try { return objectMapper.readTree(raw); }
            catch (java.io.IOException exception) { throw new IllegalStateException("Stored email JSON is invalid.", exception); }
        }).toList();
    }

    public byte[] exportJson() {
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(exportSourceEmails()); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Could not export emails.", exception); }
    }

    public com.shipping.api.repository.VerificationQueue queue() { return emailRepository.queue(); }

    public com.shipping.api.repository.EmailWorkflow.Snapshot workflow(String id) { return emailRepository.workflow().read(id); }
    public com.shipping.api.repository.EmailWorkflow.Snapshot saveExtraction(String id, com.shipping.api.repository.EmailWorkflow.Extraction input) { return emailRepository.workflow().saveExtraction(id, input); }
    public com.shipping.api.repository.EmailWorkflow.Snapshot saveReview(String id, com.shipping.api.repository.EmailWorkflow.Review input) { return emailRepository.workflow().saveReview(id, input); }
    public void saveAttachment(String id, String filename, byte[] bytes) { emailRepository.saveAttachmentContent(id, filename, bytes); }

    public void uploadAttachment(String id, String filename, byte[] bytes) {
        if (filename == null || filename.length() > 255 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)
                || !filename.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|doc|docx|xlsx|txt)"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Choose a PDF, DOC, DOCX, XLSX or TXT attachment with a plain filename.");
        if (bytes == null || bytes.length == 0 || bytes.length > EmailJsonData.MAX_BYTES)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Attachment must contain 1 byte to 5 MB.");
        emailRepository.uploadAttachment(id, filename, bytes);
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record ImportResult(int received, int inserted, int skipped, String storage, List<String> insertedIds) {}

    private EmailDetailDto toDatabaseDetail(EmailRepository.EmailRow row) {
        List<EmailAttachmentDto> attachments = emailRepository.findAttachments(row.id()).stream()
                .map(attachment -> new EmailAttachmentDto(
                        attachment.filename(), attachment.sourcePath(), detectAttachmentType(attachment.filename()),
                        emailRepository.hasAttachmentContent(row.id(), attachment.filename())
                                ? formatAttachmentSize(attachment.byteSize()) : "Not uploaded"))
                .toList();
        var workflow = emailRepository.workflow().read(row.id());
        List<EmailFieldDto> fields = new ArrayList<>();
        for (int i = 0; i < STANDARD_FIELD_LABELS.size(); i++) {
            String key = com.shipping.api.repository.EmailWorkflow.KEYS.get(i);
            var field = workflow.fields().stream().filter(f -> f.key().equals(key)).findFirst();
            fields.add(field.map(f -> new EmailFieldDto(STANDARD_FIELD_LABELS.get(com.shipping.api.repository.EmailWorkflow.KEYS.indexOf(key)),
                    f.si() == null || f.si().isBlank() ? "Pending" : f.si(), f.bl() == null || f.bl().isBlank() ? "Pending" : f.bl(),
                    com.shipping.api.repository.EmailWorkflow.fieldStatus(f).equals("pending") ? "pending" : f.si().equals(f.bl()) ? "match" : "mismatch",
                    "SI evidence: " + Objects.toString(f.siEvidence(), "Unavailable") + " | BL evidence: " + Objects.toString(f.blEvidence(), "Unavailable")))
                    .orElseGet(() -> new EmailFieldDto(STANDARD_FIELD_LABELS.get(com.shipping.api.repository.EmailWorkflow.KEYS.indexOf(key)), "Pending", "Pending", "pending", null)));
        }
        return new EmailDetailDto(
                row.id(), row.sender(), extractSenderName(row.sender(), row.body()), row.subject(),
                "Received", workflow.status(), extractBookingNumber(row.subject(), row.body(), row.id()),
                "Pending extraction", "Pending extraction", "Pending extraction", row.body(), attachments, fields);
    }

    private String formatAttachmentSize(long bytes) {
        if (bytes == 0) return "0 B";
        if (bytes > 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    public Optional<byte[]> getAttachmentContent(String emailId, String filename) {
        if (filename == null || filename.contains("/") || filename.contains("\\")
                || filename.contains("\r") || filename.contains("\n") || filename.contains("\"")) {
            return Optional.empty();
        }
        // Resolve only attachments belonging to the requested email, in database storage.
        return getEmailDetail(emailId)
                .flatMap(detail -> detail.attachments().stream()
                        .filter(attachment -> attachment.name().equals(filename)).findFirst())
                .flatMap(attachment -> emailRepository.attachmentContent(emailId, filename));
    }

    public Optional<AttachmentTextResult> getAttachmentText(String emailId, String filename) {
        return getAttachmentContent(emailId, filename)
                .map(bytes -> attachmentTextReader.read(filename, bytes));
    }

    private int extractIndex(String id) {
        try {
            String digits = id.replaceAll("\\D+", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private String detectAttachmentType(String filename) {
        String upper = filename.toUpperCase(Locale.ROOT);
        if (upper.matches(".*(?:^|[^A-Z])SI(?:[^A-Z]|$).*") || upper.contains("SHIPPING_INSTRUCTION")) {
            return "SI";
        } else if (upper.matches(".*(?:^|[^A-Z])BL(?:[^A-Z]|$).*") || upper.contains("BILL_OF_LADING") || upper.contains("DRAFT")) {
            return "BL";
        }
        return "DOC";
    }

    private String extractSenderName(String from, String body) {
        if (from != null && from.contains("<") && from.contains(">")) {
            String name = from.substring(0, from.indexOf('<')).trim().replace("\"", "");
            if (!name.isBlank()) return name;
        }
        if (body != null) {
            Pattern pattern = Pattern.compile("(?:Best Regards|Regards|Thanks and regards|Sincerely),\\s*\\n+([A-Za-z\\s\\.]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) {
                String signoff = matcher.group(1).trim();
                if (signoff.length() > 2 && signoff.length() < 40 && !signoff.contains("\n")) {
                    return signoff;
                }
            }
        }
        if (from != null && from.contains("@")) {
            String local = from.substring(0, from.indexOf('@'));
            return local.replace('.', ' ').replace('_', ' ');
        }
        return "Documentation Desk";
    }

    private String extractBookingNumber(String subject, String body, String fallbackId) {
        if (subject != null) {
            Pattern bkgPattern = Pattern.compile("(?:Booking\\s*#?\\s*|BKG\\s*#?\\s*|OC\\s+|PO\\s+|BL\\s+|[A-Z]{3,4}[0-9]{6,}|[A-Z0-9]{4,}-[A-Z0-9]+)", Pattern.CASE_INSENSITIVE);
            Matcher m = bkgPattern.matcher(subject);
            if (m.find()) {
                String match = m.group().trim();
                if (match.length() >= 5) {
                    return match.replace("Booking #", "").replace("Booking ", "").trim();
                }
            }
            String[] parts = subject.split("[_\\|\\-]");
            for (int i = parts.length - 1; i >= 0; i--) {
                String token = parts[i].trim();
                if (token.matches("[A-Za-z0-9]{6,}")) {
                    return token;
                }
            }
        }
        return fallbackId.toUpperCase(Locale.ROOT);
    }
}
