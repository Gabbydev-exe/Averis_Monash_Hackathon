package com.shipping.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The existing participant email format, shared by data import and export. */
public final class EmailJsonData {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private EmailJsonData() {}

    public static List<JsonNode> parse(ObjectMapper mapper, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "JSON data must be at most 5 MB.");
        try {
            JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
            if (root == null) throw invalid("Choose a non-empty JSON file.");
            List<JsonNode> emails = new ArrayList<>();
            // The UI sends original file JSON inside this envelope to preserve large
            // numbers and allow server-side detection of duplicate property names.
            if (root.isObject() && !root.has("email_id") && root.has("files")) {
                if (!root.path("files").isArray()) throw invalid("files must be an array of JSON documents.");
                for (JsonNode document : root.path("files")) addDocument(document, emails);
            } else addDocument(root, emails);
            if (emails.isEmpty() || emails.size() > 2000) throw invalid("Import between 1 and 2,000 email records at a time.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < emails.size(); i++) {
                JsonNode email = emails.get(i);
                String label = "Record " + (i + 1) + ": ";
                if (!email.isObject()) throw invalid(label + "expected an object.");
                for (String key : List.of("email_id", "from", "subject", "body")) {
                    if (!email.path(key).isTextual()) throw invalid(label + key + " must be a string.");
                }
                String id = email.path("email_id").asText();
                if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw invalid(label + "email_id must contain 1–64 letters, numbers, underscores or hyphens.");
                if (!ids.add(id)) throw invalid(label + "duplicate email_id within this import: " + id);
                String sender = email.path("from").asText();
                if (sender.isBlank() || sender.codePointCount(0, sender.length()) > 320) throw invalid(label + "from must contain 1–320 characters.");
                if (email.path("subject").asText().getBytes(StandardCharsets.UTF_8).length > 65535) throw invalid(label + "subject is too long.");
                if (!email.path("attachments").isArray()) throw invalid(label + "attachments must be an array of existing document paths (or []).");
                if (email.path("attachments").size() > 50) throw invalid(label + "at most 50 attachment references are allowed.");
                Set<String> paths = new HashSet<>();
                for (JsonNode attachment : email.path("attachments")) {
                    String path = attachment.asText();
                    if (!attachment.isTextual() || !path.startsWith("attachments/") || path.length() > 512
                            || path.contains("\\") || path.chars().anyMatch(c -> c < 32 || c == 127)
                            || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))
                            || path.substring(path.lastIndexOf('/') + 1).length() > 255 || !paths.add(path)) {
                        throw invalid(label + "invalid or duplicate attachment reference.");
                    }
                }
            }
            return List.copyOf(emails);
        } catch (IOException exception) {
            throw invalid("Invalid JSON. Check syntax, duplicate property names, and trailing content.");
        }
    }

    private static void addDocument(JsonNode document, List<JsonNode> emails) {
        JsonNode records = document.isObject() && document.has("emails") && !document.has("email_id")
                ? document.get("emails") : document;
        if (records.isArray()) records.forEach(emails::add);
        else if (records.isObject() && records.has("email_id")) emails.add(records);
        else throw invalid("Expected an email object, an array of emails, or an object with an emails array.");
    }

    public static String csv(List<JsonNode> emails) {
        StringBuilder csv = new StringBuilder("\uFEFFemail_id,from,subject,body,attachments\r\n");
        for (JsonNode email : emails) {
            List<String> values = List.of(email.path("email_id").asText(), email.path("from").asText(),
                    email.path("subject").asText(), email.path("body").asText(), email.path("attachments").toString());
            csv.append(String.join(",", values.stream().map(EmailJsonData::csvCell).toList())).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvCell(String value) {
        // Keep spreadsheet applications from executing untrusted email text as a formula.
        String trimmed = value.stripLeading();
        if ((!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)
                || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
