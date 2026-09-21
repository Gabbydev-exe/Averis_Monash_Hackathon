package com.shipping.api.repository;

import org.springframework.context.annotation.Profile;
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
@Profile("cloudsql")
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

    public EmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    public int insertSourceEmails(List<SourceImport> records) {
        var transaction = new TransactionTemplate(new JdbcTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        return java.util.Objects.requireNonNull(transaction.execute(status -> {
            int inserted = 0;
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
                inserted++;
            }
            return inserted;
        }));
    }

    public record SourceImport(JsonNode email, List<AttachmentImport> attachments) {}
    // An empty hash and zero size represent an unresolved reference, not an uploaded file.
    public record AttachmentImport(String id, String path, int order, String filename, String mimeType,
                                   long bytes, String sha256) {}

    public record EmailRow(String id, String sender, String subject, String body, int attachmentCount) {}

    public record AttachmentRow(String filename, String sourcePath, long byteSize) {}
}
