package com.shipping.api.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Compares the seven shipment fields without modifying stored source values. */
public final class ShipmentComparison {
    private ShipmentComparison() {}
    private static final Pattern COMPANY_AND_ADDRESS = Pattern.compile(
            "^(.+?\\b(?:SDN\\.?\\s+BHD\\.?|CO\\.,?\\s*LTD\\.?|PTE\\.?\\s+LTD\\.?|LTD\\.?|LIMITED|INC\\.?|LLC))\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDRESS_START = Pattern.compile(
            "^(?:(?:TOWER|LEVEL|FLOOR|BUILDING|SUITE|UNIT)\\s+\\d+\\b|\\d+[A-Z]?\\s*[,/-]).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDRESS_WORD = Pattern.compile(
            "\\b(?:TOWER|AVENUE|ROAD|STREET|JALAN|DAERO|FLOOR|BUILDING|SUITE|POSTCODE|POSTAL)\\b",
            Pattern.CASE_INSENSITIVE);

    public static String status(String key, String si, String bl) {
        if (si == null || bl == null || si.isBlank() || bl.isBlank()) return "pending";
        if (List.of("container_count", "gross_weight_kg").contains(key)) {
            try { return new BigDecimal(si.strip()).compareTo(new BigDecimal(bl.strip())) == 0 ? "match" : "mismatch"; }
            catch (NumberFormatException invalid) { return "pending"; }
        }
        String left = whitespace(si), right = whitespace(bl);
        if (List.of("shipper", "consignee", "notify_party").contains(key)) {
            left = companyName(left);
            right = companyName(right);
        }
        return left.equalsIgnoreCase(right) ? "match" : "mismatch";
    }

    private static String whitespace(String value) { return value.strip().replaceAll("\\s+", " "); }
    private static String companyName(String value) {
        var match = COMPANY_AND_ADDRESS.matcher(value);
        if (match.matches()) {
            String tail = match.group(2);
            // A legal suffix alone is insufficient: require a recognizable address start
            // AND an address word. Never accept arbitrary prefix/substring company matches.
            if (ADDRESS_START.matcher(tail).matches() && ADDRESS_WORD.matcher(tail).find())
                value = match.group(1);
        }
        // Formatting punctuation is insignificant; legal suffixes and name words remain.
        return whitespace(value.replace('.', ' ').replace(',', ' ')).toUpperCase(Locale.ROOT);
    }
}
