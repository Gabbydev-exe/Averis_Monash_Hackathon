package com.shipping.api.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipping.api.service.EmailDataService;
import com.shipping.api.repository.EmailWorkflow;
import com.shipping.api.document.AttachmentReadStatus;
import com.shipping.api.model.EmailAttachmentDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Processes only persisted emails and attachments, and saves the resulting extraction. */
public class DataProcessor {
    private final EmailDataService emails;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.function.Function<com.shipping.api.model.EmailDetailDto, String> classifier;
    private final java.util.function.Function<String, String> extractor;
    private final java.util.function.Function<List<EmailWorkflow.Field>, SimilarityAssessor.Similarity> assessor;
    private record DocumentExtraction(
            JsonNode fields,
            AttachmentReadStatus readStatus
    ) {
        static DocumentExtraction ok(JsonNode fields) {
            return new DocumentExtraction(fields, AttachmentReadStatus.OK);
        }

        static DocumentExtraction failed(AttachmentReadStatus status, ObjectMapper mapper) {
            return new DocumentExtraction(mapper.createObjectNode(), status);
        }
    }
    public DataProcessor(EmailDataService emails) {
        this(emails, email -> new EmailClassifier().classify(email.id(), email.subject(), email.bodyText()),
                text -> new ShipmentExtractor().extract(text), fields -> new SimilarityAssessor().assess(fields));
    }
    public DataProcessor(EmailDataService emails,
            java.util.function.Function<com.shipping.api.model.EmailDetailDto, String> classifier,
            java.util.function.Function<String, String> extractor) {
        this(emails, classifier, extractor, fields -> new SimilarityAssessor.Similarity(null, "Similarity unavailable in this test/custom processor."));
    }
    public DataProcessor(EmailDataService emails,
            java.util.function.Function<com.shipping.api.model.EmailDetailDto, String> classifier,
            java.util.function.Function<String, String> extractor,
            java.util.function.Function<List<EmailWorkflow.Field>, SimilarityAssessor.Similarity> assessor) {
        this.emails = emails; this.classifier = classifier; this.extractor = extractor; this.assessor = assessor;
    }
    public JsonNode process(String emailId) {
        String revision = emails.workflow(emailId).revision();
        var email = emails.getEmailDetail(emailId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Email not found."));

        String category;
        JsonNode si = mapper.createObjectNode();
        JsonNode bl = mapper.createObjectNode();

        String reviewReason = null;
        String siSource = null, blSource = null;

        try {
            category = classifier.apply(email);

            if (category.equals("BL_COMPARISON") || category.equals("SI_REQUEST")) {
                var siCandidates = new ArrayList<JsonNode>();
                var blCandidates = new ArrayList<JsonNode>();
                var siSources = new ArrayList<String>();
                var blSources = new ArrayList<String>();
                // Bound model calls to fit a durable job lease. Larger sets require review.
                if (email.attachments().size() > 4) reviewReason = "missing_value";
                for (var attachment : email.attachments().stream().limit(4).toList()) {
                    var extracted = extract(emailId, attachment);
                    if (extracted.readStatus() != AttachmentReadStatus.OK) {
                        reviewReason = attachment.size().equals("Not uploaded") ? "missing_attachment" : "unreadable";
                        continue;
                    }
                    String type = extracted.fields().path("document_type").asText("");
                    if (reviewReason == null && (attachment.type().equals("SI") || attachment.type().equals("BL"))
                            && !attachment.type().equals(type)) reviewReason = "wrong_doc_type";
                    if (type.equals("SI")) { siCandidates.add(extracted.fields()); siSources.add(attachment.name()); }
                    else if (type.equals("BL")) { blCandidates.add(extracted.fields()); blSources.add(attachment.name()); }
                    else if (reviewReason == null && (attachment.type().equals("SI") || attachment.type().equals("BL"))) reviewReason = "wrong_doc_type";
                }
                // The body can supply the missing SI (or be the SI for a new instruction).
                if (siCandidates.isEmpty() && email.bodyText() != null && !email.bodyText().isBlank()) {
                    JsonNode bodyFields = mapper.readTree(extractor.apply("Current email body (ignore quoted history and signatures):\n" + email.bodyText()));
                    if (bodyFields.path("document_type").asText().equals("SI")) {
                        siCandidates.add(bodyFields); siSources.add("Email body");
                    }
                }
                if (siCandidates.size() == 1) { si = siCandidates.getFirst(); siSource = siSources.getFirst(); }
                if (blCandidates.size() == 1) { bl = blCandidates.getFirst(); blSource = blSources.getFirst(); }
                if (category.equals("BL_COMPARISON") && reviewReason == null) {
                    if (siCandidates.size() > 1 || blCandidates.size() > 1) reviewReason = "missing_value";
                    else if (siCandidates.isEmpty() || blCandidates.isEmpty()) reviewReason = "missing_attachment";
                }
            }
        } catch (ResponseStatusException | org.springframework.dao.DataAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI processing failed. No extraction was saved; please retry.");
        }

        var fields = new ArrayList<EmailWorkflow.Field>();

        for (String key : EmailWorkflow.KEYS) {
            fields.add(new EmailWorkflow.Field(
                    key,
                    value(si, key),
                    value(bl, key),
                    value(si, key) == null ? null : siSource,
                    value(bl, key) == null ? null : blSource));
        }

        var assessed = com.shipping.api.service.VerificationPolicy.assess(category, fields, reviewReason, null, null);
        if (assessed.status().equals("MISMATCH")) {
            var similarity = assessor.apply(fields);
            assessed = com.shipping.api.service.VerificationPolicy.assess(category, fields, null, similarity.percentage(), similarity.explanation());
        }
        var saved = emails.saveExtraction(emailId, new EmailWorkflow.Extraction("gemini-2.5-flash", fields, revision, category, assessed));
        var result = mapper.createObjectNode();
        result.put("email_id", emailId);
        result.put("category", saved.category());
        result.put("status", saved.assessment().status());
        result.put("workflow_status", saved.status());
        result.put("has_defect", saved.assessment().status().equals("MISMATCH"));
        result.set("defect_fields", mapper.valueToTree(saved.assessment().defectFields()));
        result.set("review_reason", mapper.valueToTree(saved.assessment().reviewReason()));
        result.set("match_percentage", mapper.valueToTree(saved.assessment().matchPercentage()));
        result.put("explanation", saved.assessment().explanation());
        result.put("human_review_required", saved.status().equals("needs_review"));
        result.put("revision", saved.revision());
        result.set("si", si);
        result.set("bl", bl);

        return result;
    }
    private DocumentExtraction extract(String id, EmailAttachmentDto attachment) throws Exception {
        var text = emails.getAttachmentText(id, attachment.name());

        if (text.isEmpty()) {
            return DocumentExtraction.failed(AttachmentReadStatus.UNREADABLE, mapper);
        }

        if (text.get().status() != AttachmentReadStatus.OK) {
            return DocumentExtraction.failed(text.get().status(), mapper);
        }

        return DocumentExtraction.ok(
                mapper.readTree(extractor.apply(text.get().text()))
        );
    }
    private String value(JsonNode document, String key) {
        JsonNode value = document.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }
}
