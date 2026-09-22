package com.shipping.api.service;

import com.shipping.api.repository.EmailWorkflow.Field;
import java.util.*;

/** Exact-field auto approval; similarity estimates never authorize approval. */
public final class VerificationPolicy {
    private VerificationPolicy() {}
    public record Assessment(String status, String reviewReason, List<String> defectFields, Double matchPercentage, String explanation) {}
    public static Assessment assess(String category, List<Field> fields, String reason, Double percentage, String explanation) {
        if (!category.equals("BL_COMPARISON")) return new Assessment("OK", null, List.of(), null, "Classification complete; SI/BL comparison is not applicable.");
        if (reason != null) return new Assessment("NEEDS_REVIEW", reason, List.of(), null, "Human review required: " + reason);
        if (fields.size() != 7 || fields.stream().anyMatch(f -> ShipmentComparison.status(f.key(), f.si(), f.bl()).equals("pending")))
            return new Assessment("NEEDS_REVIEW", "missing_value", List.of(), null, "One or more required SI/BL values are missing or invalid.");
        var differences = fields.stream().filter(f -> !f.si().equals(f.bl())).map(Field::key).toList();
        if (differences.isEmpty()) return new Assessment("OK", null, List.of(), 100.0, "All seven extracted field values are literally identical.");
        if (percentage != null && (!Double.isFinite(percentage) || percentage < 0 || percentage > 100)) throw new IllegalArgumentException("Match percentage must be between 0 and 100.");
        return new Assessment("MISMATCH", null, differences, percentage,
                explanation == null ? "Values differ. Human review is required; AI similarity is unavailable." : explanation);
    }
}
