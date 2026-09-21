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
                text -> new ShipmentExtractor().extract(text));
    }
    public DataProcessor(EmailDataService emails,
            java.util.function.Function<com.shipping.api.model.EmailDetailDto, String> classifier,
            java.util.function.Function<String, String> extractor) {
        this.emails = emails; this.classifier = classifier; this.extractor = extractor;
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

        try {
            category = classifier.apply(email);

            if (category.equals("BL_COMPARISON")) {
                var sis = email.attachments().stream()
                        .filter(a -> a.type().equals("SI"))
                        .toList();

                var bls = email.attachments().stream()
                        .filter(a -> a.type().equals("BL"))
                        .toList();

                if (sis.size() != 1 || bls.size() != 1) {
                    reviewReason = "missing_attachment";
                } else {
                    var siExtraction = extract(emailId, sis.get(0));
                    var blExtraction = extract(emailId, bls.get(0));

                    si = siExtraction.fields();
                    bl = blExtraction.fields();

                    if (siExtraction.readStatus() != AttachmentReadStatus.OK
                            || blExtraction.readStatus() != AttachmentReadStatus.OK) {
                        reviewReason = "unreadable";
                    } else {
                        String siDocumentType = si.path("document_type").asText("");
                        String blDocumentType = bl.path("document_type").asText("");

                        if (!siDocumentType.equals("SI")
                                || !blDocumentType.equals("BL")) {
                            reviewReason = "wrong_doc_type";
                        }
                    }
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
                    null,
                    null));
        }

        var saved = emails.saveExtraction(
                emailId,
                new EmailWorkflow.Extraction(
                        "gemini-2.5-flash",
                        fields,
                        revision,
                        category));

        var defectFields = new ArrayList<String>();

        if (category.equals("BL_COMPARISON") && reviewReason == null) {
            for (var field : fields) {
                String fieldStatus = EmailWorkflow.fieldStatus(field);

                if (fieldStatus.equals("pending")) {
                    reviewReason = "missing_value";
                    break;
                }

                if (fieldStatus.equals("mismatch")) {
                    defectFields.add(field.key());
                }
            }
        }

        String evaluationStatus;

        if (!category.equals("BL_COMPARISON")) {
            evaluationStatus = "OK";
        } else if (reviewReason != null) {
            evaluationStatus = "NEEDS_REVIEW";
        } else if (!defectFields.isEmpty()) {
            evaluationStatus = "MISMATCH";
        } else {
            evaluationStatus = "OK";
        }

        var result = mapper.createObjectNode();

        result.put("email_id", emailId);
        result.put("category", category);
        result.put("status", evaluationStatus);
        result.put("has_defect", evaluationStatus.equals("MISMATCH"));

        var defects = result.putArray("defect_fields");
        defectFields.forEach(defects::add);

        if (reviewReason == null) {
            result.putNull("review_reason");
        } else {
            result.put("review_reason", reviewReason);
        }

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
