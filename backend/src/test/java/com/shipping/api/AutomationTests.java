package com.shipping.api;

import com.shipping.api.repository.*;
import com.shipping.api.service.*;
import com.shipping.api.controller.EmailController;
import com.shipping.api.gemini.*;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutomationTests {
    JdbcTemplate jdbc; EmailDataService service; MockMvc mvc; ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds); DatabaseFixtures.schema(jdbc); service = new EmailDataService(new EmailRepository(jdbc));
        service.importJson(mapper.writeValueAsBytes(Map.of("email_id", "one", "from", "sender@example.com", "subject", "Verify", "body", "Compare", "attachments", List.of("attachments/SI.txt", "attachments/BL.txt"))));
        mvc = MockMvcBuilders.standaloneSetup(new EmailController(service)).build();
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    List<EmailWorkflow.Field> fields(boolean different) {
        return EmailWorkflow.KEYS.stream().map(key -> new EmailWorkflow.Field(key, key.equals("shipper") ? "Company" : "1", key.equals("shipper") ? different ? "Company " : "Company" : "1", null, null)).toList();
    }
    EmailWorkflow.Snapshot save(String category, boolean different, Double percentage) {
        return service.saveExtraction("one", new EmailWorkflow.Extraction("test", fields(different), service.workflow("one").revision(), category,
            new VerificationPolicy.Assessment("OK", null, List.of(), percentage, "Advisory explanation")));
    }
    @Test void exactOnlyApprovalAndHundredPercentEstimateCannotOverrideDifference() {
        assertThat(save("BL_COMPARISON", false, null).status()).isEqualTo("verified");
        var different = save("BL_COMPARISON", true, 100.0);
        assertThat(different.status()).isEqualTo("needs_review");
        assertThat(different.assessment().status()).isEqualTo("MISMATCH");
        assertThat(different.assessment().defectFields()).containsExactly("shipper");
        assertThat(different.assessment().matchPercentage()).isEqualTo(100.0);
        assertThat(service.getEmailDetail("one").orElseThrow().fields().get(0).status()).isEqualTo("mismatch");
    }
    @Test void classifiedCategoryIsPersistentAndExportHasExactlySampleFields() throws Exception {
        save("INVOICE_QUERY", false, null);
        assertThat(service.getAllEmailSummaries().get(0).category()).isEqualTo("INVOICE_QUERY");
        assertThat(service.getAllEmailSummaries().get(0).status()).isEqualTo("classified");
        var response = mvc.perform(post("/api/emails/export-results?format=json").contentType("application/json").content("{\"emailIds\":[\"one\"]}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        var data = mapper.readTree(response.getContentAsByteArray());
        assertThat(data.size()).isEqualTo(1);
        var record = data.path("one");
        assertThat(record.size()).isEqualTo(5);
        assertThat(record.path("category").asText()).isEqualTo("INVOICE_QUERY");
        assertThat(record.path("status").asText()).isEqualTo("OK");
        assertThat(record.path("review_reason").isNull()).isTrue();
        assertThat(record.path("defect_fields").isEmpty()).isTrue();
        assertThat(record.path("has_defect").asBoolean()).isFalse();
        mvc.perform(get("/api/emails/export-results?format=csv")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("email_id,category,status,review_reason,defect_fields,has_defect")));
    }
    @Test void unprocessedExportIsBlockedAndWrongTypeCannotBeApproved() throws Exception {
        mvc.perform(get("/api/emails/export-results")).andExpect(status().isConflict());
        var saved = service.saveExtraction("one", new EmailWorkflow.Extraction("test", fields(false), "none", "BL_COMPARISON", new VerificationPolicy.Assessment("NEEDS_REVIEW", "wrong_doc_type", List.of(), null, null)));
        assertThat(saved.status()).isEqualTo("needs_review");
        mvc.perform(post("/api/emails/one/reviews").contentType("application/json").content(mapper.writeValueAsBytes(new EmailWorkflow.Review(saved.revision(), "APPROVE", "Tester", "Should not approve"))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/emails/export-results")).andExpect(jsonPath("$.one.review_reason").value("wrong_doc_type"));
    }
    @Test void leasePreventsDuplicateClaimsAndReviewedDifferencesAreNotRequeued() {
        var queue = service.queue(); var claim = queue.next().orElseThrow();
        assertThat(new VerificationQueue(jdbc).next()).isEmpty();
        assertThat(queue.claim("one", true)).isEmpty();
        save("BL_COMPARISON", true, 95.0); queue.finish(claim, true);
        assertThat(queue.next()).isEmpty();
        service.saveAttachment("one", "SI.txt", "changed bytes".getBytes());
        assertThat(queue.next()).isPresent();
    }
    @Test void retriesAreBoundedAndManualRetryRecovers() {
        var queue = service.queue();
        for (int i=0;i<3;i++) {
            var claim = queue.next().orElseThrow(); queue.finish(claim, false);
            assertThat(queue.next()).isEmpty();
            jdbc.update("UPDATE verification_jobs SET next_attempt_at=NULL");
        }
        assertThat(queue.next()).isEmpty();
        assertThat(queue.failures()).hasSize(1);
        assertThat(queue.claim("one", true)).isPresent();
    }
    @Test void expiredLeasesRecoverAndOldWorkerCannotReleaseNewLease() {
        var queue = service.queue(); var old = queue.next().orElseThrow();
        jdbc.update("UPDATE verification_jobs SET lease_until=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)));
        var current = queue.next().orElseThrow(); queue.finish(old, true);
        assertThat(queue.claim("one", true)).isEmpty();
        queue.finish(current, false);
    }
    @Test void onlyDifferencesCallSimilarityAndPersistItsEstimate() throws Exception {
        service.saveAttachment("one", "SI.txt", "SI document".getBytes()); service.saveAttachment("one", "BL.txt", "BL document".getBytes());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var processor = new DataProcessor(service, email -> "BL_COMPARISON", text -> {
            var node = mapper.createObjectNode(); node.put("document_type", text.startsWith("SI") ? "SI" : "BL");
            for (String key : EmailWorkflow.KEYS) node.put(key, key.equals("shipper") && text.startsWith("BL") ? "different" : "1");
            return node.toString();
        }, values -> { calls.incrementAndGet(); return new SimilarityAssessor.Similarity(98.0, "One company name differs; review required."); });
        var result = processor.process("one");
        assertThat(calls.get()).isEqualTo(1); assertThat(result.path("human_review_required").asBoolean()).isTrue();
        assertThat(new EmailDataService(new EmailRepository(jdbc)).workflow("one").assessment().matchPercentage()).isEqualTo(98.0);
    }
}
