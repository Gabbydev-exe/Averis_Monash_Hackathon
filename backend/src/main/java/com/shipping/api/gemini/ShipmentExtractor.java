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
                Extract exactly these seven shipment fields from this document:

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
                - Match different labels that clearly refer to the same field.
                - Extract only values explicitly present in the document.
                - Never guess or invent a value.
                - If a required field is missing, return null.
                - Preserve the value from the document.
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