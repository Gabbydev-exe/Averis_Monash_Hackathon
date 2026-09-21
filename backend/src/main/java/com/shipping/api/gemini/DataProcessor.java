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
        var email = emails.getEmailDetail(emailId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Email not found."));
        String category;
        JsonNode si = mapper.createObjectNode(), bl = mapper.createObjectNode();
        try {
            category = classifier.apply(email);
            if (category.equals("DOCUMENT_COMPARISON")) {
                var sis = email.attachments().stream().filter(a -> a.type().equals("SI")).toList();
                var bls = email.attachments().stream().filter(a -> a.type().equals("BL")).toList();
                // Multiple candidates are ambiguous; keep missing values instead of choosing silently.
                if (sis.size() == 1) si = extract(emailId, sis.get(0));
                if (bls.size() == 1) bl = extract(emailId, bls.get(0));
            }
        } catch (ResponseStatusException | org.springframework.dao.DataAccessException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI processing failed. No extraction was saved; please retry."); }
        var fields = new ArrayList<EmailWorkflow.Field>();
        for (String key : EmailWorkflow.KEYS) fields.add(new EmailWorkflow.Field(key, value(si, key), value(bl, key), null, null));
        var saved = emails.saveExtraction(emailId, new EmailWorkflow.Extraction("gemini-2.5-flash", fields, revision, category));
        var result = mapper.createObjectNode();
        result.put("email_id", emailId);
        result.put("category", category);
        result.put("status", saved.status());
        result.put("revision", saved.revision());
        result.set("si", si);
        result.set("bl", bl);
        return result;
    }
    private JsonNode extract(String id, EmailAttachmentDto attachment) throws Exception {
        var text = emails.getAttachmentText(id, attachment.name());
        if (text.isEmpty() || text.get().status() != AttachmentReadStatus.OK) return mapper.createObjectNode();
        return mapper.readTree(extractor.apply(text.get().text()));
    }
    private String value(JsonNode document, String key) {
        JsonNode value = document.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }
}
