package com.shipping.api;

import com.shipping.api.controller.EmailController;
import com.shipping.api.document.AttachmentReadStatus;
import com.shipping.api.repository.EmailRepository;
import com.shipping.api.service.EmailDataService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real JDBC queries against an isolated H2 test database; never connects to Cloud SQL. */
class DatabaseEmailIntegrationTests {
    private JdbcTemplate jdbc;
    private EmailRepository repository;
    private EmailController controller;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        // Only the source columns used by this feature. Production schema.sql remains MySQL-only.
        jdbc.execute("""
                CREATE TABLE emails (email_id VARCHAR(64) PRIMARY KEY,
                    sender_address VARCHAR(320), subject VARCHAR(1000), body VARCHAR(4000))
                """);
        jdbc.execute("""
                CREATE TABLE attachments (attachment_id VARCHAR(64) PRIMARY KEY,
                    email_id VARCHAR(64) REFERENCES emails(email_id), file_name VARCHAR(255),
                    source_path VARCHAR(512), byte_size BIGINT, attachment_order INT)
                """);
        insertEmail("email_010", "Database-only subject", "Database-only body");
        insertEmail("email_004", "Café — 船", "First line\nO'Brien \\ documentation");
        // Insert BL first to prove order comes from attachment_order, not insertion order.
        insertAttachment("bl", "email_004_BL.txt", "attachments/email_004_BL.txt", 1, 1024);
        insertAttachment("si", "email_004_SI.txt", "attachments/email_004_SI.txt", 0, 702);
        repository = new EmailRepository(jdbc);
        var service = new EmailDataService(Optional.of(repository));
        service.initialize();
        controller = new EmailController(service);
    }

    @AfterEach
    void closeDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    private void insertEmail(String id, String subject, String body) {
        jdbc.update("INSERT INTO emails VALUES (?, ?, ?, ?)", id, "sender@example.com", subject, body);
    }

    private void insertAttachment(String id, String filename, String path, int order, long bytes) {
        jdbc.update("INSERT INTO attachments VALUES (?, ?, ?, ?, ?, ?)",
                id, "email_004", filename, path, bytes, order);
    }

    @Test
    void listsDatabaseRowsOnlyIncludingEmailsWithoutAttachments() {
        var summaries = controller.getEmails();
        assertThat(summaries).extracting(summary -> summary.id()).containsExactly("email_004", "email_010");
        assertThat(summaries.get(0).attachmentsCount()).isEqualTo(2);
        assertThat(summaries.get(1).attachmentsCount()).isZero();
        assertThat(summaries).allMatch(summary -> summary.status().equals("pending"));
    }

    @Test
    void preservesStoredTextAndAttachmentOrderWithSevenPendingFields() {
        var response = controller.getEmailById("email_004");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var detail = response.getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.subject()).isEqualTo("Café — 船");
        assertThat(detail.bodyText()).isEqualTo("First line\nO'Brien \\ documentation");
        assertThat(detail.attachments()).extracting(attachment -> attachment.name())
                .containsExactly("email_004_SI.txt", "email_004_BL.txt");
        assertThat(detail.attachments().get(0).path()).isEqualTo("attachments/email_004_SI.txt");
        assertThat(detail.attachments().get(0).type()).isEqualTo("SI");
        assertThat(detail.fields()).hasSize(7).allMatch(field -> field.status().equals("pending"));
    }

    @Test
    void observesDatabaseChangesAfterInitialization() {
        jdbc.update("UPDATE emails SET subject = ? WHERE email_id = ?", "Changed in database", "email_004");
        assertThat(controller.getEmailById("email_004").getBody().subject()).isEqualTo("Changed in database");
        insertEmail("email_999", "New database email", "New body");
        assertThat(controller.getEmails()).hasSize(3);
    }

    @Test
    void missingIdReturns404EvenWhenThatIdExistsInBundledJson() {
        assertThat(controller.getEmailById("email_001").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void bindsIdsAsParametersInsteadOfInterpretingSql() {
        assertThat(repository.findById("' OR '1'='1")).isEmpty();
        assertThat(repository.findAttachments("' OR '1'='1")).isEmpty();
    }

    @Test
    void downloadsRegisteredAttachmentBytesFromBundle() {
        var response = controller.getAttachmentContent("email_004", "email_004_SI.txt");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("SHIPPING INSTRUCTION");
    }

    @Test
    void extractsRegisteredAttachmentTextInDatabaseMode() {
        var response = controller.getAttachmentText("email_004", "email_004_SI.txt");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(AttachmentReadStatus.OK);
        assertThat(response.getBody().text()).contains("SHIPPING INSTRUCTION");
    }

    @Test
    void rejectsAnotherEmailsAttachmentAndUnknownEmail() {
        assertThat(controller.getAttachmentContent("email_010", "email_004_SI.txt").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getAttachmentContent("email_missing", "email_004_SI.txt").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsUnregisteredOrMissingFilesAndTraversalPaths() {
        assertThat(controller.getAttachmentContent("email_004", "email_001_SI.txt").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        insertAttachment("missing", "absent.txt", "attachments/absent.txt", 2, 100);
        assertThat(controller.getAttachmentContent("email_004", "absent.txt").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        insertAttachment("invalid", "application.properties", "attachments/../../application.properties", 3, 100);
        assertThat(controller.getAttachmentContent("email_004", "application.properties").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reportsActualMetadataSizeIncludingEmptyFiles() {
        jdbc.update("UPDATE attachments SET byte_size = 0 WHERE attachment_id = 'si'");
        assertThat(controller.getEmailById("email_004").getBody().attachments().get(0).size()).isEqualTo("0 B");
        jdbc.update("UPDATE attachments SET byte_size = 2097152 WHERE attachment_id = 'si'");
        assertThat(controller.getEmailById("email_004").getBody().attachments().get(0).size()).isEqualTo("2.0 MB");
    }
    @Test
    void emptyDatabaseReturnsEmptyListWithoutFallingBackToBundle() {
        jdbc.update("DELETE FROM attachments");
        jdbc.update("DELETE FROM emails");
        assertThat(controller.getEmails()).isEmpty();
    }

    @Test
    void failedDatabaseQueryReturns503ThroughHttpHandler() throws Exception {
        jdbc.execute("DROP TABLE attachments");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/emails"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                        .value("unavailable"));
    }
}
