package com.shipping.api.gemini;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("gemini-demo")
@RestController
public class GeminiTestController {

    private final EmailClassifier emailClassifier = new EmailClassifier();

    private final ShipmentExtractor shipmentExtractor =
            new ShipmentExtractor();

    @GetMapping("/api/gemini/classify")
    public String classify() {
        String email = """
                Subject: Please check shipping documents

                Hi team,

                Please verify the attached Shipping Instruction against
                the draft Bill of Lading and let us know if there are
                any discrepancies.

                Thanks.
                """;

        return emailClassifier.classify("email_001",
                "YOUR REAL SUBJECT",
                "YOUR REAL BODY");
    }

    @GetMapping("/api/gemini/extract")
    public String extract() {

        String document = """
                SHIPPING INSTRUCTION

                Shipper: ABC Trading Ltd
                Consignee: XYZ Imports
                Notify Party: XYZ Logistics
                Port of Loading: Port Klang
                Port of Discharge: Singapore
                Container Count: 3
                Gross Weight: 22000 KG
                """;
        return shipmentExtractor.extract(document);
    }
}