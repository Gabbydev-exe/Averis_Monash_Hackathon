package com.shipping.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipping.api.model.EmailAttachmentDto;
import com.shipping.api.model.EmailDetailDto;
import com.shipping.api.model.EmailFieldDto;
import com.shipping.api.model.EmailSummaryDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    private final Map<String, EmailDetailDto> emailDetailMap = new ConcurrentHashMap<>();
    private final List<EmailSummaryDto> emailSummaryList = new ArrayList<>();

    public EmailDataService() {
        this(new ObjectMapper());
    }

    public EmailDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @PostConstruct
    public synchronized void initialize() {
        loadEmailsAndReports();
    }

    public synchronized void loadEmailsAndReports() {
        emailDetailMap.clear();
        emailSummaryList.clear();

        Map<String, JsonNode> reports = loadVerificationReports();

        try {
            Resource[] resources = resourceResolver.getResources("classpath:data/bundle/inbox/*.json");
            log.info("Found {} email dataset files in bundle inbox", resources.length);

            List<EmailDetailDto> loadedDetails = new ArrayList<>();

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    JsonNode rootNode = objectMapper.readTree(is);
                    String emailId = rootNode.path("email_id").asText();
                    if (emailId == null || emailId.isBlank()) {
                        String filename = resource.getFilename();
                        if (filename != null && filename.endsWith(".json")) {
                            emailId = filename.substring(0, filename.length() - 5);
                        } else {
                            continue;
                        }
                    }

                    String from = rootNode.path("from").asText("");
                    String subject = rootNode.path("subject").asText("");
                    String body = rootNode.path("body").asText("");

                    List<EmailAttachmentDto> attachments = new ArrayList<>();
                    JsonNode attArray = rootNode.path("attachments");
                    if (attArray.isArray()) {
                        for (JsonNode attNode : attArray) {
                            String attPath = attNode.asText("");
                            String attName = attPath.contains("/") ? attPath.substring(attPath.lastIndexOf('/') + 1) : attPath;
                            String type = detectAttachmentType(attName);
                            String size = calculateAttachmentSize(attPath, attName);
                            attachments.add(new EmailAttachmentDto(attName, attPath, type, size));
                        }
                    }

                    String senderName = extractSenderName(from, body);
                    String bookingNo = extractBookingNumber(subject, body, emailId);
                    String date = "Received";

                    JsonNode reportNode = reports.get(emailId);
                    String status = "pending";
                    List<EmailFieldDto> fields = new ArrayList<>();

                    if (reportNode != null && !reportNode.isNull() && !reportNode.isMissingNode()) {
                        boolean hasDefect = reportNode.path("has_defect").asBoolean(false);
                        String repStatus = reportNode.path("status").asText("").toUpperCase(Locale.ROOT);
                        Set<String> defectFields = new HashSet<>();
                        JsonNode defects = reportNode.path("defect_fields");
                        if (defects.isArray()) {
                            for (JsonNode d : defects) {
                                defectFields.add(d.asText().toLowerCase(Locale.ROOT).replace(" ", "_"));
                            }
                        }

                        if (hasDefect || "DEFECT".equals(repStatus) || !defectFields.isEmpty()) {
                            status = "discrepancy";
                        } else if ("OK".equals(repStatus) || "VERIFIED".equals(repStatus)) {
                            status = "verified";
                        }

                        for (String label : STANDARD_FIELD_LABELS) {
                            String normalizedLabelKey = label.toLowerCase(Locale.ROOT).replace(" ", "_");
                            boolean isDefect = defectFields.contains(normalizedLabelKey)
                                    || defectFields.contains(label.toLowerCase(Locale.ROOT));

                            String fieldStatus;
                            if ("pending".equals(status)) {
                                fieldStatus = "pending";
                            } else {
                                fieldStatus = isDefect ? "mismatch" : "match";
                            }
                            String note = isDefect ? "Automated verification discrepancy flagged" : null;
                            fields.add(new EmailFieldDto(label, "Pending extraction", "Pending extraction", fieldStatus, note));
                        }
                    } else {
                        for (String label : STANDARD_FIELD_LABELS) {
                            fields.add(new EmailFieldDto(label, "Pending", "Pending", "pending", null));
                        }
                    }

                    EmailDetailDto detail = new EmailDetailDto(
                            emailId,
                            from,
                            senderName,
                            subject,
                            date,
                            status,
                            bookingNo,
                            "Pending extraction",
                            "Pending extraction",
                            "Pending extraction",
                            body,
                            attachments,
                            fields
                    );

                    loadedDetails.add(detail);
                } catch (Exception e) {
                    log.warn("Failed to parse email resource {}: {}", resource.getFilename(), e.getMessage());
                }
            }

            loadedDetails.sort(Comparator.comparingInt(d -> extractIndex(d.id())));

            for (EmailDetailDto detail : loadedDetails) {
                emailDetailMap.put(detail.id(), detail);
                emailSummaryList.add(new EmailSummaryDto(
                        detail.id(),
                        detail.sender(),
                        detail.senderName(),
                        detail.subject(),
                        detail.date(),
                        detail.status(),
                        detail.bookingNo(),
                        detail.attachments().size()
                ));
            }

            log.info("Successfully loaded and cached {} emails from dataset bundle", emailDetailMap.size());

        } catch (Exception e) {
            log.error("Error reading bundle emails: {}", e.getMessage(), e);
        }
    }

    private Map<String, JsonNode> loadVerificationReports() {
        Map<String, JsonNode> reports = new HashMap<>();
        String[] possibleReportLocations = {
                "classpath:data/bundle/verification_report.json",
                "classpath:data/bundle/submission.json"
        };

        for (String location : possibleReportLocations) {
            try {
                Resource resource = resourceResolver.getResource(location);
                if (resource.exists()) {
                    try (InputStream is = resource.getInputStream()) {
                        JsonNode root = objectMapper.readTree(is);
                        if (root.isObject()) {
                            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                            while (fields.hasNext()) {
                                Map.Entry<String, JsonNode> entry = fields.next();
                                reports.put(entry.getKey(), entry.getValue());
                            }
                            log.info("Loaded {} verification reports from {}", reports.size(), location);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Verification report not found or unreadable at {}: {}", location, e.getMessage());
            }
        }
        return reports;
    }

    public List<EmailSummaryDto> getAllEmailSummaries() {
        return Collections.unmodifiableList(emailSummaryList);
    }

    public Optional<EmailDetailDto> getEmailDetail(String id) {
        return Optional.ofNullable(emailDetailMap.get(id));
    }

    public Optional<byte[]> getAttachmentContent(String attachmentPathOrFilename) {
        try {
            String filename = attachmentPathOrFilename.contains("/")
                    ? attachmentPathOrFilename.substring(attachmentPathOrFilename.lastIndexOf('/') + 1)
                    : attachmentPathOrFilename;

            Resource resource = resourceResolver.getResource("classpath:data/bundle/attachments/" + filename);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return Optional.of(is.readAllBytes());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load attachment content for {}: {}", attachmentPathOrFilename, e.getMessage());
        }
        return Optional.empty();
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
        if (upper.contains("SI") || upper.contains("SHIPPING_INSTRUCTION")) {
            return "SI";
        } else if (upper.contains("BL") || upper.contains("BILL_OF_LADING") || upper.contains("DRAFT")) {
            return "BL";
        }
        return "DOC";
    }

    private String calculateAttachmentSize(String path, String filename) {
        try {
            Resource res = resourceResolver.getResource("classpath:data/bundle/attachments/" + filename);
            if (res.exists()) {
                long len = res.contentLength();
                if (len > 1024 * 1024) {
                    return String.format(Locale.ROOT, "%.1f MB", len / (1024.0 * 1024.0));
                } else if (len > 0) {
                    return Math.max(1, len / 1024) + " KB";
                }
            }
        } catch (Exception ignored) {
        }
        return "15 KB";
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
