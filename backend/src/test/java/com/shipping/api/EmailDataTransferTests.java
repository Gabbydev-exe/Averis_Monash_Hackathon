package com.shipping.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipping.api.controller.EmailController;
import com.shipping.api.repository.EmailRepository;
import com.shipping.api.service.EmailDataService;
import com.shipping.api.service.EmailJsonData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailDataTransferTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private EmailDataService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE emails (email_id VARCHAR(64) PRIMARY KEY, sender_address VARCHAR(320) NOT NULL,
                    subject TEXT NOT NULL, body TEXT NOT NULL, raw_email CLOB NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE attachments (attachment_id CHAR(64) PRIMARY KEY, email_id VARCHAR(64) REFERENCES emails(email_id),
                    source_path VARCHAR(512), attachment_order INT, file_name VARCHAR(255), mime_type VARCHAR(128),
                    byte_size BIGINT, sha256 CHAR(64))
                """);
        DatabaseFixtures.workflow(jdbc);
        service = new EmailDataService(new EmailRepository(jdbc));
        mvc = MockMvcBuilders.standaloneSetup(new EmailController(service)).build();
    }

    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    private Map<String, Object> email(String id) {
        var email = new LinkedHashMap<String, Object>();
        email.put("email_id", id);
        email.put("from", "O'Brien@example.com");
        email.put("subject", "Shipping, \"港口\"");
        email.put("body", "Line one\r\nLine two \\ folder");
        email.put("attachments", List.of());
        return email;
    }

    private byte[] json(Object value) throws Exception { return mapper.writeValueAsBytes(value); }

    @Test void exportsOnlySelectedEmailsInJsonAndCsv() throws Exception {
        service.importJson(json(List.of(email("selected_id"), email("excluded_id"))));
        for (String format : List.of("json", "csv")) {
            String output = mvc.perform(post("/api/emails/export?format=" + format)
                    .contentType("application/json").content(json(Map.of("emailIds", List.of("selected_id", "selected_id")))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(output).contains("selected_id").doesNotContain("excluded_id");
            if (format.equals("json")) assertThat(mapper.readTree(output).size()).isEqualTo(1);
        }
    }

    @Test void emptyInvalidAndMissingSelectionsNeverExportAllEmails() throws Exception {
        service.importJson(json(email("saved_id")));
        for (var ids : List.of(List.of(), List.of("../invalid"))) {
            mvc.perform(post("/api/emails/export").contentType("application/json")
                    .content(json(Map.of("emailIds", ids)))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/emails/export").contentType("application/json")
                .content(json(Map.of("emailIds", List.of("saved_id", "missing_id")))))
                .andExpect(status().isConflict());
    }

    @Test void missingFieldsRejectWholeBatchButBlankSubjectAndBodyAreAllowed() throws Exception {
        for (String field : List.of("email_id", "from", "subject", "body", "attachments")) {
            var incomplete = email("incomplete");
            incomplete.remove(field);
            mvc.perform(post("/api/emails/import").contentType("application/json")
                    .content(json(List.of(email("valid"), incomplete)))).andExpect(status().isBadRequest());
            assertThat(service.exportSourceEmails()).isEmpty();
        }
        var blank = email("blank_text");
        blank.put("subject", "");
        blank.put("body", "");
        assertThat(service.importJson(json(blank)).inserted()).isEqualTo(1);
    }

    @Test void importsAndExportsOriginalJsonIncludingExtraFieldsAndUnicode() throws Exception {
        var source = email("new_email_001");
        source.put("custom", Map.of("reference", "保留", "sequence", 9007199254740993L));
        mvc.perform(post("/api/emails/import").contentType("application/json").content(json(source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(1))
                .andExpect(jsonPath("$.storage").value("database"));
        assertThat(service.getEmailDetail("new_email_001").orElseThrow().subject()).isEqualTo(source.get("subject"));
        byte[] exported = mvc.perform(get("/api/emails/export?format=json")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"shipping-emails.json\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(mapper.readTree(exported).get(0)).isEqualTo(mapper.valueToTree(source));
        assertThat(service.importJson(exported).skipped()).isEqualTo(1);
    }

    @Test void duplicatesDoNotOverwriteExistingDataOrAttachments() throws Exception {
        var original = email("same_id");
        original.put("attachments", List.of("attachments/email_004_SI.txt"));
        service.importJson(json(original));
        var changed = email("same_id");
        changed.put("body", "Do not overwrite");
        var result = service.importJson(json(List.of(changed, email("new_id"))));
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.insertedIds()).containsExactly("new_id");
        assertThat(service.getEmailDetail("same_id").orElseThrow().bodyText()).isEqualTo(original.get("body"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachments", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsWholeBatchBeforeWritingIfAnyRecordIsInvalid() throws Exception {
        var invalid = email("invalid/path");
        mvc.perform(post("/api/emails/import").contentType("application/json")
                        .content(json(List.of(email("valid_id"), invalid))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emails", Integer.class)).isZero();
    }

    @Test void transactionRollsBackEarlierEmailsIfAttachmentInsertFails() throws Exception {
        jdbc.execute("ALTER TABLE attachments ADD CONSTRAINT reject_test_file CHECK (file_name <> 'reject.txt')");
        var failing = email("failing_id");
        failing.put("attachments", List.of("attachments/reject.txt"));
        mvc.perform(post("/api/emails/import").contentType("application/json")
                        .content(json(List.of(email("first_id"), failing))))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emails", Integer.class)).isZero();
    }

    @Test void rejectsTraversalAndDuplicateIdsAndMalformedJson() throws Exception {
        var source = email("safe_id");
        source.put("attachments", List.of("attachments/../../application.properties"));
        mvc.perform(post("/api/emails/import").contentType("application/json").content(json(source)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/emails/import").contentType("application/json")
                        .content(json(List.of(email("same"), email("same")))))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("{", "{} {}", "[]", "{\"email_id\":\"a\",\"email_id\":\"b\"}")) {
            mvc.perform(post("/api/emails/import").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void preservesReferencesWithoutPretendingToUploadFiles() throws Exception {
        var source = email("with_refs");
        source.put("attachments", List.of("attachments/email_004_SI.txt", "attachments/not_uploaded.pdf"));
        service.importJson(json(source));
        var detail = service.getEmailDetail("with_refs").orElseThrow();
        assertThat(detail.attachments()).hasSize(2);
        assertThat(detail.attachments().get(1).size()).isEqualTo("Not uploaded");
        assertThat(service.getAttachmentContent("with_refs", "not_uploaded.pdf")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT TRIM(sha256) FROM attachments WHERE file_name = 'email_004_SI.txt'", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT TRIM(sha256) FROM attachments WHERE file_name = 'not_uploaded.pdf'", String.class)).isEmpty();
    }

    @Test void csvQuotesMultilineFieldsAndNeutralizesSpreadsheetFormulas() throws Exception {
        var source = email("csv_id");
        source.put("from", "=HYPERLINK(\"https://example.invalid\")");
        service.importJson(json(Map.of("emails", List.of(source))));
        String csv = mvc.perform(get("/api/emails/export?format=csv")).andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=utf-8"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFemail_id,from,subject,body,attachments\r\n")
                .contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\"")
                .contains("\"Shipping, \"\"港口\"\"\"")
                .contains("\"Line one\r\nLine two \\ folder\"");
    }

    @Test void enforcesRequestLimitAndKnownExportFormats() throws Exception {
        mvc.perform(post("/api/emails/import").contentType("application/json").content(new byte[EmailJsonData.MAX_BYTES + 1]))
                .andExpect(status().is(413));
        mvc.perform(get("/api/emails/export?format=xml")).andExpect(status().isBadRequest());
    }

    @Test void importsSurviveServiceRecreationAndDatabaseFailureNeverFallsBack() throws Exception {
        service.importJson(json(email("persistent_email")));
        var restarted = new EmailDataService(new EmailRepository(jdbc));
        assertThat(restarted.getEmailDetail("persistent_email")).isPresent();
        assertThat(restarted.exportSourceEmails()).hasSize(1);
        jdbc.execute("DROP TABLE attachments");
        assertThatThrownBy(() -> restarted.getAllEmailSummaries()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> new EmailDataService(null)).isInstanceOf(NullPointerException.class);
    }
}
