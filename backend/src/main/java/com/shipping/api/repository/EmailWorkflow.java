package com.shipping.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Stores the latest extraction and append-only human decisions independently of source JSON. */
public class EmailWorkflow {
    public static final List<String> KEYS = List.of("shipper", "consignee", "notify_party", "port_of_loading", "port_of_discharge", "container_count", "gross_weight_kg");
    private final JdbcTemplate jdbc;
    public EmailWorkflow(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Field(String key, String si, String bl, String siEvidence, String blEvidence) {}
    public record Extraction(String model, List<Field> fields, String expectedRevision, String category) {
        public Extraction(String model, List<Field> fields, String expectedRevision) { this(model, fields, expectedRevision, "DOCUMENT_COMPARISON"); }
    }
    public record Review(String revision, String decision, String reviewer, String note) {}
    public record SavedReview(String id, String revision, String decision, String reviewer, String note, String savedAt) {}
    public record Snapshot(String revision, String model, String category, List<Field> fields, List<SavedReview> reviews, String status) {}

    private void lockEmail(String id) {
        if (jdbc.queryForList("SELECT email_id FROM emails WHERE email_id = ? FOR UPDATE", String.class, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email not found.");
    }
    private TransactionTemplate transaction() { return new TransactionTemplate(new JdbcTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))); }
    public static String fieldStatus(Field f) {
        return com.shipping.api.service.ShipmentComparison.status(f.key(), f.si(), f.bl());
    }
    public Map<String, String> statuses() {
        return transaction().execute(tx -> {
            Map<String, String> revisions = new HashMap<>();
            jdbc.query("SELECT email_id, revision FROM email_extractions", rs -> { revisions.put(rs.getString(1), rs.getString(2)); });
            Map<String, List<Field>> fields = new HashMap<>();
            jdbc.query("SELECT email_id, field_key, si_value, bl_value FROM shipment_fields", rs -> {
                fields.computeIfAbsent(rs.getString(1), key -> new ArrayList<>()).add(new Field(rs.getString(2), rs.getString(3), rs.getString(4), null, null));
            });
            Map<String, String> statuses = new HashMap<>();
            fields.forEach((id, values) -> statuses.put(id, values.size() != 7 || values.stream().anyMatch(f -> fieldStatus(f).equals("pending")) ? "pending"
                    : values.stream().anyMatch(f -> fieldStatus(f).equals("mismatch")) ? "discrepancy" : "verified"));
            Set<String> reviewed = new HashSet<>();
            jdbc.query("SELECT email_id, extraction_revision, decision FROM email_reviews ORDER BY saved_at DESC, review_id DESC", rs -> {
                String id = rs.getString(1);
                if (rs.getString(2).equals(revisions.getOrDefault(id, "none")) && reviewed.add(id))
                    statuses.put(id, rs.getString(3).equals("APPROVE") ? "verified" : "discrepancy");
            });
            return statuses;
        });
    }

    public Snapshot read(String id) {
        // Same row lock as writers prevents a mixed revision/field snapshot.
        return transaction().execute(tx -> { lockEmail(id); return snapshot(id); });
    }
    private Snapshot snapshot(String id) {
        var revisions = jdbc.query("SELECT revision, model_name, category FROM email_extractions WHERE email_id = ?",
                (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        String revision = revisions.isEmpty() ? "none" : revisions.get(0).get(0);
        String model = revisions.isEmpty() ? "" : revisions.get(0).get(1);
        var fields = jdbc.query("SELECT field_key, si_value, bl_value, si_evidence, bl_evidence FROM shipment_fields WHERE email_id = ?",
                (rs, n) -> new Field(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
        var reviews = jdbc.query("SELECT review_id, extraction_revision, decision, reviewer, note, saved_at FROM email_reviews WHERE email_id = ? ORDER BY saved_at DESC, review_id DESC",
                (rs, n) -> new SavedReview(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), id);
        String status = fields.size() != 7 || fields.stream().anyMatch(f -> fieldStatus(f).equals("pending")) ? "pending"
                : fields.stream().anyMatch(f -> fieldStatus(f).equals("mismatch")) ? "discrepancy" : "verified";
        var currentReview = reviews.stream().filter(r -> r.revision().equals(revision)).findFirst();
        if (currentReview.isPresent()) status = currentReview.get().decision().equals("APPROVE") ? "verified" : "discrepancy";
        return new Snapshot(revision, model, revisions.isEmpty() ? "UNCLASSIFIED" : revisions.get(0).get(2), fields, reviews, status);
    }
    public Snapshot saveExtraction(String id, Extraction input) {
        if (input == null || input.expectedRevision() == null || input.model() == null || input.model().isBlank() || input.model().length() > 200 || input.fields() == null || input.fields().size() != 7)
            throw bad("Provide a model/source name and all seven shipment fields (use null for missing values).");
        if (input.category() != null && !List.of("DOCUMENT_COMPARISON", "NEW_SI_REQUEST", "INVOICE_QUERY", "GENERAL", "SPAM").contains(input.category())) throw bad("Unknown email category.");
        var keys = new HashSet<String>();
        for (var f : input.fields()) {
            if (f == null || !KEYS.contains(f.key()) || !keys.add(f.key())) throw bad("Each shipment field must appear exactly once.");
            for (var value : Arrays.asList(f.si(), f.bl(), f.siEvidence(), f.blEvidence()))
                if (value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16000) throw bad("Field values and evidence must be at most 16,000 UTF-8 bytes.");
        }
        return transaction().execute(tx -> {
            lockEmail(id);
            if (!snapshot(id).revision().equals(input.expectedRevision()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email data or extraction changed. Reload before saving.");
            jdbc.update("DELETE FROM shipment_fields WHERE email_id = ?", id);
            jdbc.update("DELETE FROM email_extractions WHERE email_id = ?", id);
            jdbc.update("INSERT INTO email_extractions (email_id, revision, model_name, category) VALUES (?, ?, ?, ?)", id, UUID.randomUUID().toString(), input.model(), input.category() == null ? "DOCUMENT_COMPARISON" : input.category());
            for (var f : input.fields()) jdbc.update("INSERT INTO shipment_fields (email_id, field_key, si_value, bl_value, si_evidence, bl_evidence) VALUES (?, ?, ?, ?, ?, ?)", id, f.key(), f.si(), f.bl(), f.siEvidence(), f.blEvidence());
            return snapshot(id);
        });
    }
    public Snapshot saveReview(String id, Review input) {
        if (input == null || input.revision() == null || !List.of("APPROVE", "FLAG").contains(input.decision() == null ? "" : input.decision())
                || input.reviewer() == null || input.reviewer().isBlank() || input.reviewer().length() > 200
                || input.note() == null || input.note().isBlank() || input.note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16000)
            throw bad("Provide a reviewer name, decision, extraction revision, and review note.");
        return transaction().execute(tx -> {
            lockEmail(id);
            var current = snapshot(id);
            if (!current.revision().equals(input.revision())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Extraction changed. Reload before reviewing.");
            if (input.decision().equals("APPROVE") && (current.fields().size() != 7 || current.fields().stream().anyMatch(f -> fieldStatus(f).equals("pending"))))
                throw bad("Cannot approve incomplete extraction. Save all seven SI and BL values first.");
            jdbc.update("INSERT INTO email_reviews (review_id, email_id, extraction_revision, decision, reviewer, note) VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), id, current.revision(), input.decision(), input.reviewer().strip(), input.note());
            return snapshot(id);
        });
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
