package com.shipping.api.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    public record EmailRow(String id, String sender, String subject, String body, int attachmentCount) {}

    public record AttachmentRow(String filename, String sourcePath, long byteSize) {}
}
