package com.shipping.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

/** Reads the existing source tables; schema creation and imports remain separate. */
@Repository
public class EmailRepository {

    private static final String EMAIL_SELECT = """
            SELECT e.email_id, e.sender_address, e.subject, e.body,
                   (SELECT COUNT(*) FROM attachments a WHERE a.email_id = e.email_id) AS attachment_count
            FROM emails e
            """;

    private static final RowMapper<EmailRow> EMAIL_MAPPER = (rs, rowNum) -> new EmailRow(
            rs.getString("email_id"), rs.getString("sender_address"),
            rs.getString("subject"), rs.getString("body"), rs.getInt("attachment_count"));

    private static final RowMapper<AttachmentRow> ATTACHMENT_MAPPER = (rs, rowNum) -> new AttachmentRow(
            rs.getString("file_name"), rs.getString("source_path"), rs.getLong("byte_size"));

    private final JdbcTemplate jdbc;

    private com.shipping.api.document.AttachmentObjectStore objects;

    @org.springframework.beans.factory.annotation.Autowired
    public EmailRepository(JdbcTemplate jdbc, com.shipping.api.document.AttachmentObjectStore objects) { this.jdbc = jdbc; this.objects = objects; }

    /** Test fixture constructor; production always injects GCS. */
    public EmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VerificationQueue queue() { return new VerificationQueue(jdbc); }

    public EmailWorkflow workflow() { return new EmailWorkflow(jdbc); }

    public boolean hasAttachmentContent(String emailId, String filename) {
        return !objectNames(emailId, filename).isEmpty() || jdbc.queryForObject("SELECT COUNT(*) FROM attachment_contents WHERE email_id = ? AND file_name = ?", Integer.class, emailId, filename) > 0;
    }
    private List<String> objectNames(String id, String filename) { return jdbc.queryForList("SELECT object_name FROM attachment_objects WHERE email_id=? AND file_name=?", String.class, id, filename); }
    public Optional<byte[]> attachmentContent(String emailId, String filename) {
        var names = objectNames(emailId, filename);
        if (!names.isEmpty()) return Optional.of(objects.read(names.getFirst()));
        return jdbc.query("SELECT content FROM attachment_contents WHERE email_id = ? AND file_name = ?", (rs, n) -> rs.getBytes(1), emailId, filename).stream().findFirst();
    }
    public void saveAttachmentContent(String emailId, String filename, byte[] bytes) {
        var tx = new TransactionTemplate(new JdbcTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT email_id FROM emails WHERE email_id = ? FOR UPDATE", String.class, emailId).isEmpty()
                    || findAttachments(emailId).stream().noneMatch(a -> a.filename().equals(filename)))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Registered attachment not found.");
            var previous = attachmentContent(emailId, filename);
            if (previous.isPresent() && java.util.Arrays.equals(previous.get(), bytes) && (objects == null || !objectNames(emailId, filename).isEmpty())) return;
            var oldObjects = objectNames(emailId, filename);
            String objectName = objects == null ? null : objects.put(bytes);
            if (objectName != null) org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        var obsolete = completion == STATUS_COMMITTED ? oldObjects : List.of(objectName);
                        for (String name : obsolete) try { objects.delete(name); }
                        catch (RuntimeException failure) {
                            org.slf4j.LoggerFactory.getLogger(EmailRepository.class).warn("Attachment object cleanup failed; orphan reconciliation may be required.");
                        }
                    }
                });
            // Changed source bytes invalidate extraction and make old approvals historical.
            jdbc.update("DELETE FROM shipment_fields WHERE email_id = ?", emailId);
            jdbc.update("DELETE FROM email_extractions WHERE email_id = ?", emailId);
            jdbc.update("INSERT INTO email_extractions (email_id, revision, model_name, category) VALUES (?, ?, ?, ?)", emailId, java.util.UUID.randomUUID().toString(), "Awaiting extraction after attachment update", "UNCLASSIFIED");
            jdbc.update("DELETE FROM attachment_contents WHERE email_id = ? AND file_name = ?", emailId, filename);
            if (objects == null) jdbc.update("INSERT INTO attachment_contents (email_id, file_name, content) VALUES (?, ?, ?)", emailId, filename, bytes);
            else {
                jdbc.update("DELETE FROM attachment_objects WHERE email_id=? AND file_name=?", emailId, filename);
                jdbc.update("INSERT INTO attachment_objects (email_id,file_name,object_name) VALUES (?,?,?)", emailId, filename, objectName);
            }
            try {
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                jdbc.update("UPDATE attachments SET byte_size = ?, sha256 = ? WHERE email_id = ? AND file_name = ?", bytes.length, hash, emailId, filename);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        });
    }

    public void uploadAttachment(String emailId, String filename, byte[] bytes) {
        var tx = new TransactionTemplate(new JdbcTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status -> {
            var raw = jdbc.queryForList("SELECT raw_email FROM emails WHERE email_id=? FOR UPDATE", String.class, emailId);
            if (raw.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Email not found.");
            if (findAttachments(emailId).stream().noneMatch(a -> a.filename().equals(filename))) {
                if (findAttachments(emailId).size() >= 50) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "At most 50 attachments per email.");
                String path = "attachments/" + emailId + "/" + filename;
                jdbc.update("INSERT INTO attachments (attachment_id,email_id,source_path,attachment_order,file_name,mime_type,byte_size,sha256) VALUES (?,?,?,?,?,?,?,?)", java.util.UUID.randomUUID().toString(), emailId, path, findAttachments(emailId).size(), filename, "application/octet-stream", 0, "");
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var node = mapper.readTree(raw.getFirst());
                    ((com.fasterxml.jackson.databind.node.ArrayNode) node.get("attachments")).add(path);
                    jdbc.update("UPDATE emails SET raw_email=? WHERE email_id=?", node.toString(), emailId);
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }
            saveAttachmentContent(emailId, filename, bytes);
        });
    }

    public List<EmailRow> findAll() {
        return jdbc.query(EMAIL_SELECT + " ORDER BY e.email_id", EMAIL_MAPPER);
    }

    public Optional<EmailRow> findById(String id) {
        return jdbc.query(EMAIL_SELECT + " WHERE e.email_id = ?", EMAIL_MAPPER, id)
                .stream().findFirst();
    }

    public List<AttachmentRow> findAttachments(String emailId) {
        return jdbc.query("""
                SELECT file_name, source_path, byte_size FROM attachments
                WHERE email_id = ? ORDER BY attachment_order, attachment_id
                """, ATTACHMENT_MAPPER, emailId);
    }

    public List<String> findAllRawEmails() {
        return jdbc.query("SELECT raw_email FROM emails ORDER BY email_id", (rs, row) -> rs.getString(1));
    }

    public List<String> insertSourceEmails(List<SourceImport> records) {
        var transaction = new TransactionTemplate(new JdbcTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        return java.util.Objects.requireNonNull(transaction.execute(status -> {
            List<String> insertedIds = new java.util.ArrayList<>();
            for (SourceImport record : records) {
                JsonNode email = record.email();
                String id = email.path("email_id").asText();
                try {
                    jdbc.update("INSERT INTO emails (email_id, sender_address, subject, body, raw_email) VALUES (?, ?, ?, ?, ?)",
                            id, email.path("from").asText(), email.path("subject").asText(), email.path("body").asText(), email.toString());
                } catch (DuplicateKeyException duplicate) {
                    // Existing emails and their verification/review history are never overwritten.
                    continue;
                }
                for (AttachmentImport attachment : record.attachments()) {
                    jdbc.update("""
                            INSERT INTO attachments (attachment_id, email_id, source_path, attachment_order,
                                file_name, mime_type, byte_size, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, attachment.id(), id, attachment.path(), attachment.order(), attachment.filename(),
                            attachment.mimeType(), attachment.bytes(), attachment.sha256());
                }
                insertedIds.add(id);
            }
            return List.copyOf(insertedIds);
        }));
    }

    public record SourceImport(JsonNode email, List<AttachmentImport> attachments) {}
    // An empty hash and zero size represent an unresolved reference, not an uploaded file.
    public record AttachmentImport(String id, String path, int order, String filename, String mimeType,
                                   long bytes, String sha256) {}

    public record EmailRow(String id, String sender, String subject, String body, int attachmentCount) {}

    public record AttachmentRow(String filename, String sourcePath, long byteSize) {}
}
