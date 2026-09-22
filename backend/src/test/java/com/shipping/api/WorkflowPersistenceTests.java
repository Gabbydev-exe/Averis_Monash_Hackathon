package com.shipping.api;

import com.shipping.api.controller.EmailController;
import com.shipping.api.repository.EmailRepository;
import com.shipping.api.repository.EmailWorkflow;
import com.shipping.api.service.EmailDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowPersistenceTests {
    JdbcTemplate jdbc;
    EmailDataService service;
    MockMvc mvc;
    ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        DatabaseFixtures.schema(jdbc);
        service = new EmailDataService(new EmailRepository(jdbc));
        service.importJson(mapper.writeValueAsBytes(Map.of("email_id", "saved", "from", "sender@example.com", "subject", "Shipment", "body", "Review", "attachments", List.of("attachments/SI.txt"))));
        mvc = MockMvcBuilders.standaloneSetup(new EmailController(service)).build();
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    EmailWorkflow.Extraction extraction(String revision, boolean incomplete) {
        return new EmailWorkflow.Extraction("test-model", EmailWorkflow.KEYS.stream().map(key -> new EmailWorkflow.Field(key,
                incomplete && key.equals("shipper") ? null : "1", "1.0", "SI source", "BL source")).toList(), revision);
    }
    @Test void extractionAndReviewPersistAcrossServiceRecreationWithoutChangingEvidence() throws Exception {
        var saved = service.saveExtraction("saved", extraction("none", false));
        mvc.perform(post("/api/emails/saved/reviews").contentType("application/json").content(mapper.writeValueAsBytes(new EmailWorkflow.Review(saved.revision(), "APPROVE", "Reviewer", "Checked differing company labels manually"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("verified"));
        var restarted = new EmailDataService(new EmailRepository(jdbc));
        var workflow = restarted.workflow("saved");
        assertThat(workflow.reviews()).hasSize(1);
        assertThat(workflow.fields().get(0).siEvidence()).isEqualTo("SI source");
        assertThat(restarted.getEmailDetail("saved").orElseThrow().fields()).anyMatch(f -> f.status().equals("mismatch"));
        assertThat(restarted.getAllEmailSummaries().get(0).status()).isEqualTo("verified");
    }
    @Test void incompleteExtractionCannotBeApprovedAndStaleReviewIsRejected() throws Exception {
        var first = service.saveExtraction("saved", extraction("none", true));
        mvc.perform(post("/api/emails/saved/reviews").contentType("application/json").content(mapper.writeValueAsBytes(new EmailWorkflow.Review(first.revision(), "APPROVE", "Reviewer", "Approve"))))
                .andExpect(status().isBadRequest());
        var second = service.saveExtraction("saved", extraction(first.revision(), false));
        mvc.perform(post("/api/emails/saved/reviews").contentType("application/json").content(mapper.writeValueAsBytes(new EmailWorkflow.Review(first.revision(), "FLAG", "Reviewer", "Old screen"))))
                .andExpect(status().isConflict());
        assertThat(service.workflow("saved").reviews()).isEmpty();
        assertThat(service.workflow("saved").revision()).isEqualTo(second.revision());
    }
    @Test void invalidExtractionCannotReplaceSavedFieldsAndOldApprovalsDoNotApplyToNewRuns() throws Exception {
        var first = service.saveExtraction("saved", extraction("none", false));
        service.saveReview("saved", new EmailWorkflow.Review(first.revision(), "APPROVE", "Reviewer", "Reviewed"));
        mvc.perform(put("/api/emails/saved/extraction").contentType("application/json").content(mapper.writeValueAsBytes(new EmailWorkflow.Extraction("model", List.of(), first.revision()))))
                .andExpect(status().isBadRequest());
        assertThat(service.workflow("saved").revision()).isEqualTo(first.revision());
        var next = service.saveExtraction("saved", extraction(first.revision(), true));
        assertThat(next.status()).isEqualTo("needs_review");
        assertThat(next.reviews()).hasSize(1);
    }
    @Test void attachmentBytesPersistAndReplacingThemInvalidatesExtraction() throws Exception {
        mvc.perform(put("/api/emails/saved/attachments/SI.txt").contentType("application/octet-stream").content("first document"))
                .andExpect(status().isNoContent());
        var restarted = new EmailDataService(new EmailRepository(jdbc));
        assertThat(new String(restarted.getAttachmentContent("saved", "SI.txt").orElseThrow())).isEqualTo("first document");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM attachments", String.class)).hasSize(64);
        var first = service.saveExtraction("saved", extraction(service.workflow("saved").revision(), false));
        service.saveReview("saved", new EmailWorkflow.Review(first.revision(), "APPROVE", "Reviewer", "Reviewed"));
        service.saveAttachment("saved", "SI.txt", "changed document".getBytes());
        assertThat(service.workflow("saved").status()).isEqualTo("pending");
        assertThat(service.workflow("saved").fields()).isEmpty();
        assertThat(service.workflow("saved").reviews()).hasSize(1);
        mvc.perform(put("/api/emails/saved/extraction").contentType("application/json").content(mapper.writeValueAsBytes(extraction(first.revision(), false))))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/emails/saved/attachments/unknown.txt").contentType("application/octet-stream").content("unknown"))
                .andExpect(status().isNotFound());
    }
    @Test void storageFailureReturns503InsteadOfSuccessOrMemoryFallback() throws Exception {
        jdbc.execute("DROP TABLE shipment_fields");
        mvc.perform(put("/api/emails/saved/extraction").contentType("application/json").content(mapper.writeValueAsBytes(extraction("none", false))))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_extractions", Integer.class)).isZero();
    }
    @Test void pipelineReadsPersistedDocumentsAndSavesModelValues() throws Exception {
        var source = Map.of("email_id", "pipeline", "from", "sender@example.com", "subject", "Compare", "body", "Verify", "attachments", List.of("attachments/SI.txt", "attachments/BL.txt"));
        service.importJson(mapper.writeValueAsBytes(source));
        service.saveAttachment("pipeline", "SI.txt", "SI stored in MySQL".getBytes());
        service.saveAttachment("pipeline", "BL.txt", "BL stored in MySQL".getBytes());
        var seen = new ArrayList<String>();
        var processor = new com.shipping.api.gemini.DataProcessor(service, email -> {
            assertThat(email.bodyText()).isEqualTo("Verify"); return "BL_COMPARISON";
        }, text -> {
            seen.add(text);

            String documentType = text.equals("SI stored in MySQL")
                    ? "SI"
                    : "BL";

            return """
            {
              "document_type":"%s",
              "shipper":"Company",
              "consignee":"Company",
              "notify_party":"Company",
              "port_of_loading":"Singapore",
              "port_of_discharge":"Klang",
              "container_count":2,
              "gross_weight_kg":2000
            }
            """.formatted(documentType);
        });

        var result = processor.process("pipeline");
        assertThat(result.path("status").asText()).isEqualTo("OK");
        assertThat(result.path("has_defect").asBoolean()).isFalse();
        assertThat(result.path("defect_fields").isEmpty()).isTrue();
        assertThat(result.path("review_reason").isNull()).isTrue();
        assertThat(seen).containsExactly("SI stored in MySQL", "BL stored in MySQL");
        var restarted = new EmailDataService(new EmailRepository(jdbc));
        assertThat(restarted.workflow("pipeline").fields()).hasSize(7);
        assertThat(restarted.workflow("pipeline").category()).isEqualTo("BL_COMPARISON");
        assertThat(restarted.workflow("pipeline").fields()).allMatch(f -> "SI.txt".equals(f.siEvidence()));
    }
    @Test void pipelineFailureNeverClaimsSavedExtractionAndMissingBytesNeedReview() {
        var failed = new com.shipping.api.gemini.DataProcessor(service, email -> { throw new RuntimeException("AI unavailable"); }, text -> "{}");
        assertThatThrownBy(() -> failed.process("saved")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(service.workflow("saved").revision()).isEqualTo("none");
        var missing = new com.shipping.api.gemini.DataProcessor(service, email -> "BL_COMPARISON", text -> { assertThat(text).startsWith("Current email body"); return "{\"document_type\":\"OTHER\"}"; });
        var missingResult = missing.process("saved");
        assertThat(missingResult.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(missingResult.path("has_defect").asBoolean()).isFalse();
        assertThat(missingResult.path("defect_fields").isEmpty()).isTrue();
        assertThat(missingResult.path("review_reason").asText()).isEqualTo("missing_attachment");
        assertThat(service.workflow("saved").fields()).hasSize(7).allMatch(f -> f.si() == null && f.bl() == null);
    }

    @Test void pipelineReportsMismatchAndDefectFields() throws Exception {
        var source = Map.of(
                "email_id", "mismatch",
                "from", "sender@example.com",
                "subject", "Compare",
                "body", "Verify",
                "attachments", List.of(
                        "attachments/SI.txt",
                        "attachments/BL.txt"));

        service.importJson(mapper.writeValueAsBytes(source));
        service.saveAttachment("mismatch", "SI.txt", "SI document".getBytes());
        service.saveAttachment("mismatch", "BL.txt", "BL document".getBytes());

        var processor = new com.shipping.api.gemini.DataProcessor(
                service,
                email -> "BL_COMPARISON",
                text -> {
                    if (text.equals("SI document")) {
                        return """
                            {
                            "document_type":"SI",
                              "shipper":"Company",
                              "consignee":"Buyer",
                              "notify_party":"Agent",
                              "port_of_loading":"Singapore",
                              "port_of_discharge":"Klang",
                              "container_count":2,
                              "gross_weight_kg":2000
                            }
                            """;
                    }

                    return """
                        {
                        "document_type":"BL",
                          "shipper":"Company",
                          "consignee":"Buyer",
                          "notify_party":"Agent",
                          "port_of_loading":"Singapore",
                          "port_of_discharge":"Klang",
                          "container_count":2,
                          "gross_weight_kg":2500
                        }
                        """;
                });

        var result = processor.process("mismatch");

        assertThat(result.path("status").asText()).isEqualTo("MISMATCH");
        assertThat(result.path("has_defect").asBoolean()).isTrue();
        assertThat(result.path("defect_fields").size()).isEqualTo(1);
        assertThat(result.path("defect_fields").get(0).asText())
                .isEqualTo("gross_weight_kg");
        assertThat(result.path("review_reason").isNull()).isTrue();
    }

    @Test void pipelineReportsUnreadableAttachmentForReview() throws Exception {
        var source = Map.of(
                "email_id", "unreadable",
                "from", "sender@example.com",
                "subject", "Compare",
                "body", "Verify",
                "attachments", List.of(
                        "attachments/SI.txt",
                        "attachments/BL.txt"));

        service.importJson(mapper.writeValueAsBytes(source));

        service.saveAttachment(
                "unreadable",
                "SI.txt",
                new byte[] {(byte) 0xC3, (byte) 0x28});

        service.saveAttachment(
                "unreadable",
                "BL.txt",
                "Valid BL document".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var processor = new com.shipping.api.gemini.DataProcessor(
                service,
                email -> "BL_COMPARISON",
                text -> """
                    {
                      "shipper":"Company",
                      "consignee":"Buyer",
                      "notify_party":"Agent",
                      "port_of_loading":"Singapore",
                      "port_of_discharge":"Klang",
                      "container_count":2,
                      "gross_weight_kg":2000
                    }
                    """);

        var result = processor.process("unreadable");

        assertThat(result.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.path("has_defect").asBoolean()).isFalse();
        assertThat(result.path("defect_fields").isEmpty()).isTrue();
        assertThat(result.path("review_reason").asText()).isEqualTo("unreadable");
    }

    @Test void pipelineReportsWrongDocumentTypeForReview() throws Exception {
        var source = Map.of(
                "email_id", "wrong-type",
                "from", "sender@example.com",
                "subject", "Compare",
                "body", "Verify",
                "attachments", List.of(
                        "attachments/SI.txt",
                        "attachments/BL.txt"));

        service.importJson(mapper.writeValueAsBytes(source));
        service.saveAttachment("wrong-type", "SI.txt", "Actually a BL".getBytes());
        service.saveAttachment("wrong-type", "BL.txt", "Valid BL".getBytes());

        var processor = new com.shipping.api.gemini.DataProcessor(
                service,
                email -> "BL_COMPARISON",
                text -> {
                    String documentType = text.equals("Actually a BL")
                            ? "BL"
                            : "BL";

                    return """
                        {
                          "document_type":"%s",
                          "shipper":"Company",
                          "consignee":"Buyer",
                          "notify_party":"Agent",
                          "port_of_loading":"Singapore",
                          "port_of_discharge":"Klang",
                          "container_count":2,
                          "gross_weight_kg":2000
                        }
                        """.formatted(documentType);
                });

        var result = processor.process("wrong-type");

        assertThat(result.path("status").asText())
                .isEqualTo("NEEDS_REVIEW");
        assertThat(result.path("has_defect").asBoolean())
                .isFalse();
        assertThat(result.path("defect_fields").isEmpty())
                .isTrue();
        assertThat(result.path("review_reason").asText())
                .isEqualTo("wrong_doc_type");
    }

    @Test void numericFormattingDoesNotCreateFalseMismatch() {
        assertThat(EmailWorkflow.fieldStatus(new EmailWorkflow.Field("gross_weight_kg", "2000", "2000.00", null, null))).isEqualTo("match");
        assertThat(EmailWorkflow.fieldStatus(new EmailWorkflow.Field("gross_weight_kg", "unknown", "2000", null, null))).isEqualTo("pending");
    }
}
