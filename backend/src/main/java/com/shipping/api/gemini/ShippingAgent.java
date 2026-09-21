package com.shipping.api.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

public class ShippingAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_BASE = System.getenv().getOrDefault("SHIPPING_API_URL", "http://localhost:8081");
    private static JsonNode get(String path) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(API_BASE + path))
                .timeout(java.time.Duration.ofSeconds(30)).GET().build();
        var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Backend returned HTTP " + response.statusCode());
        return MAPPER.readTree(response.body());
    }
    private static String segment(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"); }

    public static void main(String[] args) {

        LlmAgent shippingAgent = LlmAgent.builder()
                .name("shipping_document_agent")
                .description(
                        "An agent that classifies shipping emails, finds SI and BL documents, "
                                + "extracts shipment fields, compares SI against BL, "
                                + "and requests human review when evidence is insufficient."
                )
                .model("gemini-2.5-flash")
                .instruction("""
                        You are a shipping document verification agent.

                        Process each email using this workflow:

                        1. Use getEmail to retrieve the email.

                        2. Classify it as exactly one of:
                           DOCUMENT_COMPARISON
                           NEW_SI_REQUEST
                           INVOICE_QUERY
                           GENERAL
                           SPAM

                        3. Only DOCUMENT_COMPARISON emails continue to document checking.

                        4. For DOCUMENT_COMPARISON, use listAttachments to find
                           the attachments belonging to the email.

                        5. Identify the Shipping Instruction (SI) and Bill of Lading (BL).
                           If their roles are unclear, use readAttachment to inspect them.

                        6. Read both documents using readAttachment.

                        7. Use extractShipmentFields on both documents.

                        8. Extract exactly these seven fields:
                           - shipper
                           - consignee
                           - notify_party
                           - port_of_loading
                           - port_of_discharge
                           - container_count
                           - gross_weight_kg

                        9. The SI is the reference document.

                        10. Use compareFiles with the extracted SI and BL JSON.

                        11. If every field matches, report:
                            "No mismatch detected."

                        12. If fields differ, report each differing field
                            with its SI value and BL value.

                        13. If an attachment is missing, cannot be read,
                            a document role is ambiguous, a required value
                            is missing or ambiguous, or extraction fails,
                            return REVIEW_REQUIRED instead of guessing.

                        Important:
                        - Never invent shipment information.
                        - Use the actual email and attachment contents as evidence.
                        - The attachment reader accepts filenames returned by
                          listAttachments, in email ID/filename format.
                        - Keep the final answer concise but include the category,
                          status, and comparison result.
                        """)
                .tools(
                        FunctionTool.create(ShippingAgent.class, "getEmail"),
                        FunctionTool.create(
                                ShippingAgent.class,
                                "listAttachments"
                        ),
                        FunctionTool.create(
                                ShippingAgent.class,
                                "readAttachment"
                        ),
                        FunctionTool.create(
                                ShippingAgent.class,
                                "extractShipmentFields"
                        ),
                        FunctionTool.create(
                                ShippingAgent.class,
                                "compareFiles"
                        )
                )
                .build();

        AdkWebServer.start(shippingAgent);
    }

    @Schema(
            name = "get_email",
            description = "Retrieve the complete email JSON record using its email ID."
    )
    public static Map<String, Object> getEmail(
            @Schema(
                    name = "email_id",
                    description = "The email ID, for example email_009."
            )
            String emailId
    ) {
        try { return Map.of("result", get("/api/emails/" + segment(emailId)).toString()); }
        catch (Exception e) { return Map.of("result", "REVIEW_REQUIRED: Could not load persisted email."); }
    }

    @Schema(
            name = "list_attachments",
            description = "List all attachments belonging to an email. "
                    + "The returned filenames can be passed directly to read_attachment."
    )
    public static Map<String, Object> listAttachments(
            @Schema(
                    name = "email_id",
                    description = "The email ID, for example email_009."
            )
            String emailId
    ) {
        try {
            List<String> files = new ArrayList<>();
            for (var attachment : get("/api/emails/" + segment(emailId)).path("attachments"))
                files.add(emailId + "/" + attachment.path("name").asText());
            return Map.of("result", String.join("\n", files));
        } catch (Exception e) { return Map.of("result", "REVIEW_REQUIRED: Could not load persisted attachments."); }
    }

    @Schema(
            name = "read_attachment",
            description = "Read an SI or BL attachment. "
                    + "Accept either a filename or an attachment path."
    )
    public static Map<String, Object> readAttachment(
            @Schema(
                    name = "filename",
                    description = "The attachment filename or path returned "
                            + "by listAttachments."
            )
            String filename
    ) {
        try {
            String[] parts = filename.split("/", 2);
            if (parts.length != 2) return Map.of("result", "REVIEW_REQUIRED: Use the email ID/filename returned by listAttachments.");
            var text = get("/api/emails/" + segment(parts[0]) + "/attachments/" + segment(parts[1]) + "/text");
            return Map.of("result", text.path("status").asText().equals("OK") ? text.path("text").asText() : "REVIEW_REQUIRED: Attachment has no readable text.");
        } catch (Exception e) { return Map.of("result", "REVIEW_REQUIRED: Could not read persisted attachment."); }
    }

    @Schema(
            name = "extract_shipment_fields",
            description = "Extract the seven required SI or BL shipment "
                    + "comparison fields from document text."
    )
    public static Map<String, Object> extractShipmentFields(
            @Schema(
                    name = "document_text",
                    description = "The complete text of an SI or BL document."
            )
            String documentText
    ) {
        try {
            if (documentText == null
                    || documentText.isBlank()) {

                return Map.of(
                        "result",
                        "REVIEW_REQUIRED: Document text is empty."
                );
            }

            ShipmentExtractor extractor =
                    new ShipmentExtractor();

            String result =
                    extractor.extract(documentText);

            if (result == null || result.isBlank()) {
                return Map.of(
                        "result",
                        "REVIEW_REQUIRED: Shipment extraction "
                                + "returned empty data."
                );
            }

            return Map.of(
                    "result",
                    result
            );

        } catch (Exception e) {
            return Map.of(
                    "result",
                    "REVIEW_REQUIRED: Shipment extraction failed: "
                            + e.getMessage()
            );
        }
    }

    @Schema(
            name = "compare_files",
            description = "Compare the seven required shipment fields "
                    + "between SI and BL JSON. SI is the reference."
    )
    public static Map<String, Object> compareFiles(
            @Schema(
                    name = "si_json",
                    description = "JSON containing the seven extracted SI fields."
            )
            String siJson,

            @Schema(
                    name = "bl_json",
                    description = "JSON containing the seven extracted BL fields."
            )
            String blJson
    ) {
        try {
            JsonNode si =
                    MAPPER.readTree(siJson);

            JsonNode bl =
                    MAPPER.readTree(blJson);

            String[] fields = {
                    "shipper",
                    "consignee",
                    "notify_party",
                    "port_of_loading",
                    "port_of_discharge",
                    "container_count",
                    "gross_weight_kg"
            };

            List<String> mismatches =
                    new ArrayList<>();

            for (String field : fields) {

                JsonNode siValue =
                        si.get(field);

                JsonNode blValue =
                        bl.get(field);

                if (siValue == null
                        || siValue.isNull()
                        || blValue == null
                        || blValue.isNull()) {

                    return Map.of(
                            "result",
                            "REVIEW_REQUIRED: Missing required field '"
                                    + field
                                    + "' in SI or BL."
                    );
                }

                String siText =
                        normalise(siValue);

                String blText =
                        normalise(blValue);

                if (!siText.equalsIgnoreCase(blText)) {
                    mismatches.add(
                            field
                                    + " | SI: "
                                    + siText
                                    + " | BL: "
                                    + blText
                    );
                }
            }

            if (mismatches.isEmpty()) {
                return Map.of(
                        "result",
                        "No mismatch detected."
                );
            }

            return Map.of(
                    "result",
                    String.join("\n", mismatches)
            );

        } catch (Exception e) {
            return Map.of(
                    "result",
                    "REVIEW_REQUIRED: Comparison failed: "
                            + e.getMessage()
            );
        }
    }

    private static String normalise(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }

        String value = node.asText().trim().replaceAll("\\s+", " ");

        try {
            return new BigDecimal(value)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }
}