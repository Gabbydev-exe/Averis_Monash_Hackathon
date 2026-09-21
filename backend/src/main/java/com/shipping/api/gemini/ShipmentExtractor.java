package com.shipping.api.gemini;

public class ShipmentExtractor {

    private final Vertex vertex;

    public ShipmentExtractor() {
        this.vertex = new Vertex();
    }

    public String extract(String documentText) {

        String schema = """
        {
          "type": "object",
          "properties": {
          "document_type": {
                    "type": "string",
                    "enum": ["SI", "BL", "OTHER"]
                  },
            "shipper": {
              "type": "string",
              "nullable": true
            },
            "consignee": {
              "type": "string",
              "nullable": true
            },
            "notify_party": {
              "type": "string",
              "nullable": true
            },
            "port_of_loading": {
              "type": "string",
              "nullable": true
            },
            "port_of_discharge": {
              "type": "string",
              "nullable": true
            },
            "container_count": {
              "type": "integer",
              "nullable": true
            },
            "gross_weight_kg": {
              "type": "number",
              "nullable": true
            }
          },
          "required": [
            "document_type",
            "shipper",
            "consignee",
            "notify_party",
            "port_of_loading",
            "port_of_discharge",
            "container_count",
            "gross_weight_kg"
          ]
        }
        """;

        String prompt = """
                First identify the document type, then extract exactly these seven shipment fields.
                
                document_type must be exactly one of:
                - SI: Shipping Instruction
                - BL: Bill of Lading
                - OTHER: the document is neither a Shipping Instruction nor a Bill of Lading
                
                Determine document_type from the document content, not from the filename.
                
                Extract:

                - shipper
                - consignee
                - notify_party
                - port_of_loading
                - port_of_discharge
                - container_count
                - gross_weight_kg

                IMPORTANT FIELD LABEL VARIATIONS:

                Shipper may appear as:
                - Shipper
                - Shipper (Principal or Seller)

                Consignee may appear as:
                - Consignee
                - CONSIGNEE
                - Consignee (Non-Negotiable)

                Notify party may appear as:
                - Notify Party
                - NOTIFY PARTY

                Port of loading may appear as:
                - Port of Loading
                - Load Port
                - POL

                Port of discharge may appear as:
                - Port of Discharge
                - Discharge Port

                Container count may appear as:
                - Container Count
                - No. of Containers
                - No. of Containers or Packages

                Gross weight may appear as:
                - Gross Weight
                - Gross Wt
                - Gross Wt (kgs)
                - Gross Weight (KGS)
                - Gross Weight重(KGS)

                Rules:
                - Determine document_type only from the document content.
                - Do not assume the document type from a filename or attachment label.
                - If the document is not clearly a Shipping Instruction or Bill of Lading, use OTHER.
                - Match different labels that clearly refer to the same field.
                - Extract only values explicitly present in the document.
                - Never guess or invent a value.
                - If a required field is missing, return null.
                - shipper, consignee and notify_party mean the party NAME ONLY.
                - Exclude postal addresses, building/floor/unit details, postal codes,
                  telephone/fax numbers, email addresses and contact-person details from names.
                - Preserve the complete company name, including its legal suffix and
                  meaningful branch/division name. Never shorten different companies to a shared prefix.
                - For example, "MOORIM SP CO., LTD" followed by an address starting
                  "656, GANGNAM-DAERO" must return "MOORIM SP CO., LTD" only.
                - Address differences are not part of the seven required comparison fields.
                - Preserve each field's value from the document within the scope above.
                - container_count must be the numeric number of containers.
                - gross_weight_kg must be the numeric gross weight in kilograms.
                - Ignore other fields such as vessel, voyage number, description,
                  booking number, freight, HS code, B/L number, and OC number.
                - Return only JSON matching the provided schema.

                Document:
                %s
                """
                .formatted(documentText);

        return vertex.generateJson(prompt, schema);
    }
}