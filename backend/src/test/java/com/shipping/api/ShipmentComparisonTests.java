package com.shipping.api;

import com.shipping.api.service.ShipmentComparison;
import com.shipping.api.gemini.ShippingAgent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShipmentComparisonTests {
    private static final String SHIPPER = "APRIL FAR EAST (M) SDN BHD";
    private static final String SHIPPER_ADDRESS = " TOWER 2, AVENUE 5, LEVEL 6; BANGSAR SOUTH CITY, NO. 8 JALAN KERINCHI; 59200 KUALA LUMPUR, MALAYSIA";
    private static final String CONSIGNEE = "MOORIM SP CO., LTD";
    private static final String CONSIGNEE_ADDRESS = " 656, GANGNAM-DAERO, GANGNAM-GU; SEOUL, SOUTH KOREA; T. 82-2-3485-1500";
    @Test void matchesUserExamplesWithAddressOnEitherSide() {
        assertThat(ShipmentComparison.status("shipper", SHIPPER, SHIPPER + SHIPPER_ADDRESS)).isEqualTo("match");
        assertThat(ShipmentComparison.status("consignee", CONSIGNEE, CONSIGNEE + CONSIGNEE_ADDRESS)).isEqualTo("match");
        assertThat(ShipmentComparison.status("consignee", CONSIGNEE + CONSIGNEE_ADDRESS, CONSIGNEE)).isEqualTo("match");
    }
    @Test void doesNotHideDifferentCompanyNamesOrMeaningfulSuffixes() {
        assertThat(ShipmentComparison.status("shipper", SHIPPER, "APRIL FAR EAST (S) SDN BHD" + SHIPPER_ADDRESS)).isEqualTo("mismatch");
        assertThat(ShipmentComparison.status("shipper", SHIPPER, SHIPPER + " LOGISTICS" )).isEqualTo("mismatch");
        assertThat(ShipmentComparison.status("consignee", CONSIGNEE, "MOORIM PAPER CO., LTD" + CONSIGNEE_ADDRESS)).isEqualTo("mismatch");
        assertThat(ShipmentComparison.status("consignee", CONSIGNEE, CONSIGNEE + " TRADING DIVISION")).isEqualTo("mismatch");
        assertThat(ShipmentComparison.status("shipper", "ABC LTD", "ABC LIMITED")).isEqualTo("mismatch");
    }
    @Test void addressesAreNotStrippedWithoutClearBoundariesAndNonPartyFieldsStayStrict() {
        assertThat(ShipmentComparison.status("shipper", "ABC LTD", "ABC LTD 123 HOLDINGS")).isEqualTo("mismatch");
        assertThat(ShipmentComparison.status("port_of_loading", SHIPPER, SHIPPER + SHIPPER_ADDRESS)).isEqualTo("mismatch");
        assertThat(ShipmentComparison.status("shipper", "", "ABC LTD")).isEqualTo("pending");
        assertThat(ShipmentComparison.status("consignee", "MOORIM SP CO LTD", CONSIGNEE)).isEqualTo("match");
    }
    @Test void agentAndWebUseTheSameComparisonRules() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var si = mapper.createObjectNode();
        var bl = mapper.createObjectNode();
        for (String field : com.shipping.api.repository.EmailWorkflow.KEYS) { si.put(field, "1"); bl.put(field, "1"); }
        si.put("shipper", SHIPPER); bl.put("shipper", SHIPPER + SHIPPER_ADDRESS);
        si.put("consignee", CONSIGNEE); bl.put("consignee", CONSIGNEE + CONSIGNEE_ADDRESS);
        assertThat(ShippingAgent.compareFiles(si.toString(), bl.toString()).get("result")).isEqualTo("No mismatch detected.");
        bl.put("shipper", "DIFFERENT COMPANY SDN BHD" + SHIPPER_ADDRESS);
        assertThat(ShippingAgent.compareFiles(si.toString(), bl.toString()).get("result").toString()).contains("shipper | SI:");
    }
}
