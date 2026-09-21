# Manual Test Cases — Role 4

Created from the participant bundle only. No organizer answer key was used.

## Coverage

- 25 total cases
- BL_COMPARISON: 10
- SI_REQUEST: 4
- INVOICE_QUERY: 4
- GENERAL: 4
- SPAM: 3
- Human-review reasons covered: wrong_doc_type, missing_attachment, unreadable, missing_value
- Difficult classification examples include misleading subjects and keyword traps.

## Case list

| Case | Email | Expected category | Expected result | Difficulty | Why |
|---|---|---|---|---|---|
| TC01 | email_001 | BL_COMPARISON | OK | normal | Email explicitly asks to check SI against draft BL; all seven compared fields agree. |
| TC02 | email_004 | BL_COMPARISON | MISMATCH — consignee, notify_party | normal_mismatch | Consignee and notify party differ; the other five required fields match. |
| TC03 | email_013 | BL_COMPARISON | MISMATCH — port_of_discharge | normal_mismatch | The discharge-port text conflicts even though the BL retains the same parenthetical code. |
| TC04 | email_025 | BL_COMPARISON | MISMATCH — port_of_discharge, container_count | multiple_mismatches | Both discharge port and container count differ. |
| TC05 | email_031 | BL_COMPARISON | MISMATCH — container_count, gross_weight_kg | multiple_mismatches | Container count and gross weight differ; all other required fields agree. |
| TC06 | email_032 | BL_COMPARISON | OK | normal | Different field labels are used across SI/BL, but the seven semantic values match. |
| TC07 | email_501 | BL_COMPARISON | NEEDS_REVIEW — wrong_doc_type | human_review | The email requests SI-vs-BL checking, but the second attachment is a Commercial Invoice, not a draft BL. |
| TC08 | email_507 | BL_COMPARISON | NEEDS_REVIEW — missing_attachment | human_review | The email requests SI-vs-BL checking but only the SI attachment exists; the draft BL is missing. |
| TC09 | email_511 | BL_COMPARISON | NEEDS_REVIEW — unreadable | human_review | The BL attachment is a malformed/corrupt PDF; a PDF parser cannot read its xref/trailer. |
| TC10 | email_516 | BL_COMPARISON | NEEDS_REVIEW — missing_value | human_review | The SI gives Gross Weight as N/A, so a required comparison value is missing and the case must not be guessed. |
| TC11 | email_007 | SI_REQUEST | classification only | normal | The message provides shipping-instruction details and requests the draft BL; there are no SI+BL attachments to compare. |
| TC12 | email_030 | SI_REQUEST | classification only | normal | The body contains shipment/SI details and asks for the draft BL to be returned. |
| TC13 | email_057 | SI_REQUEST | classification only | normal | The email provides a new set of shipping-instruction details rather than asking for SI-vs-BL verification. |
| TC14 | email_293 | SI_REQUEST | classification only | normal | The message supplies POL/POD/shipper/consignee/notify/container/weight information and asks for a draft BL. |
| TC15 | email_010 | INVOICE_QUERY | classification only | normal | It explicitly asks whether THC/local charges are included in invoice 5250071354 and requests a breakdown. |
| TC16 | email_024 | INVOICE_QUERY | classification only | normal | The message concerns a missing GR for a specific invoice so billing can proceed. |
| TC17 | email_069 | INVOICE_QUERY | classification only | normal | The body asks for a missing GR to be posted for an invoice so billing can proceed. |
| TC18 | email_298 | INVOICE_QUERY | classification only | normal | The sender requests cancellation of invoice 5250070303 and reversal of PGI. |
| TC19 | email_012 | GENERAL | classification only | misleading_subject | Although the subject mentions submitting SI, the actual body is only a vessel berthing report/operational update. |
| TC20 | email_037 | GENERAL | classification only | normal | Operational delivery/loading update; it is not a comparison request, SI request, invoice query, or spam. |
| TC21 | email_053 | GENERAL | classification only | misleading_subject | Despite an update-summary subject, the body is a general New Year/office-resumption announcement. |
| TC22 | email_194 | GENERAL | classification only | keyword_trap | It contains the word Billing but is only an automated completion notification with 'No action required', not an invoice question. |
| TC23 | email_026 | SPAM | classification only | normal | Unsolicited 90%-off logistics-software promotion with urgency/marketing language. |
| TC24 | email_116 | SPAM | classification only | misleading_subject | The subject advertises a Bitcoin investment; the body is a suspicious parcel-payment phishing message. |
| TC25 | email_417 | SPAM | classification only | misleading_subject | The invoice-payment subject is misleading; the body is an unsolicited bank-officer scam asking for bank details. |

## Detailed BL comparison checks

### TC01 — email_001
- Expected status: **OK**
- Manual reason: Email explicitly asks to check SI against draft BL; all seven compared fields agree.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | APRIL FAR EAST (M) SDN BHD | APRIL FAR EAST (M) SDN BHD | yes |
| consignee | MOORIM SP CO., LTD | MOORIM SP CO., LTD | yes |
| notify_party | UAB NOVAKOPA | UAB NOVAKOPA | yes |
| port_of_loading | PORT KLANG (WESTPORT), MALAYSIA (MYPKG) | PORT KLANG (WESTPORT), MALAYSIA (MYPKG) | yes |
| port_of_discharge | CALLAO, PERU (PECLL) | CALLAO, PERU (PECLL) | yes |
| container_count | 1 | 1 | yes |
| gross_weight_kg | 21577 | 21577 | yes |

### TC02 — email_004
- Expected status: **MISMATCH**
- Defect fields: **consignee, notify_party**
- Manual reason: Consignee and notify party differ; the other five required fields match.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | APRIL FAR EAST (M) SDN BHD | APRIL FAR EAST (M) SDN BHD | yes |
| consignee | EAST BRIGHT FZ-LLC | UAB NOVAKOPA | NO |
| notify_party | EAST BRIGHT FZ-LLC | UAB NOVAKOPA | NO |
| port_of_loading | NANTONG, CHINA (CNNTG) | NANTONG, CHINA (CNNTG) | yes |
| port_of_discharge | KARACHI, PAKISTAN (PKKHI) | KARACHI, PAKISTAN (PKKHI) | yes |
| container_count | 6 | 6 | yes |
| gross_weight_kg | 131058 | 131058 | yes |

### TC03 — email_013
- Expected status: **MISMATCH**
- Defect fields: **port_of_discharge**
- Manual reason: The discharge-port text conflicts even though the BL retains the same parenthetical code.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | ASIA PACIFIC PAPERBOARD TRADING PTE LTD | ASIA PACIFIC PAPERBOARD TRADING PTE LTD | yes |
| consignee | ROXCEL TRADING GMBH | ROXCEL TRADING GMBH | yes |
| notify_party | ROXCEL TRADING GMBH | ROXCEL TRADING GMBH | yes |
| port_of_loading | SINGAPORE (SGSIN) | SINGAPORE (SGSIN) | yes |
| port_of_discharge | MOMBASA, KENYA (KEMBA) | TUTICORIN, INDIA (KEMBA) | NO |
| container_count | 3 | 3 | yes |
| gross_weight_kg | 67311 | 67311 | yes |

### TC04 — email_025
- Expected status: **MISMATCH**
- Defect fields: **port_of_discharge, container_count**
- Manual reason: Both discharge port and container count differ.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | APRIL FAR EAST (M) SDN BHD | APRIL FAR EAST (M) SDN BHD | yes |
| consignee | CERIEX | CERIEX | yes |
| notify_party | ROXCEL TRADING GMBH | ROXCEL TRADING GMBH | yes |
| port_of_loading | PORT KLANG (WESTPORT), MALAYSIA (MYPKG) | PORT KLANG (WESTPORT), MALAYSIA (MYPKG) | yes |
| port_of_discharge | FREMANTLE, AUSTRALIA (AUFRE) | BUSAN, SOUTH KOREA (AUFRE) | NO |
| container_count | 6 | 5 | NO |
| gross_weight_kg | 135126 | 135126 | yes |

### TC05 — email_031
- Expected status: **MISMATCH**
- Defect fields: **container_count, gross_weight_kg**
- Manual reason: Container count and gross weight differ; all other required fields agree.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | APRIL FINE PAPER TRADING (MIDDLE EAST) FZE | APRIL FINE PAPER TRADING (MIDDLE EAST) FZE | yes |
| consignee | VITAL SOLUTIONS PTE. LTD. | VITAL SOLUTIONS PTE. LTD. | yes |
| notify_party | VITAL SOLUTIONS PTE. LTD. | VITAL SOLUTIONS PTE. LTD. | yes |
| port_of_loading | NHAVA SHEVA, INDIA (INNSA) | NHAVA SHEVA, INDIA (INNSA) | yes |
| port_of_discharge | MOMBASA, KENYA (KEMBA) | MOMBASA, KENYA (KEMBA) | yes |
| container_count | 1 | 3 | NO |
| gross_weight_kg | 21114 | 23114 | NO |

### TC06 — email_032
- Expected status: **OK**
- Manual reason: Different field labels are used across SI/BL, but the seven semantic values match.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | APRIL FAR EAST (M) SDN BHD | APRIL FAR EAST (M) SDN BHD | yes |
| consignee | TOPKOPY MIDDLE EAST FZE | TOPKOPY MIDDLE EAST FZE | yes |
| notify_party | TOPKOPY MIDDLE EAST FZE | TOPKOPY MIDDLE EAST FZE | yes |
| port_of_loading | RUGAO/NANTONG/SHANGHAI, CHINA (CNSHA) | RUGAO/NANTONG/SHANGHAI, CHINA (CNSHA) | yes |
| port_of_discharge | KARACHI, PAKISTAN (PKKHI) | KARACHI, PAKISTAN (PKKHI) | yes |
| container_count | 3 | 3 | yes |
| gross_weight_kg | 64092 | 64092 | yes |

### TC07 — email_501
- Expected status: **NEEDS_REVIEW**
- Review reason: **wrong_doc_type**
- Manual reason: The email requests SI-vs-BL checking, but the second attachment is a Commercial Invoice, not a draft BL.

### TC08 — email_507
- Expected status: **NEEDS_REVIEW**
- Review reason: **missing_attachment**
- Manual reason: The email requests SI-vs-BL checking but only the SI attachment exists; the draft BL is missing.

### TC09 — email_511
- Expected status: **NEEDS_REVIEW**
- Review reason: **unreadable**
- Manual reason: The BL attachment is a malformed/corrupt PDF; a PDF parser cannot read its xref/trailer.

### TC10 — email_516
- Expected status: **NEEDS_REVIEW**
- Review reason: **missing_value**
- Manual reason: The SI gives Gross Weight as N/A, so a required comparison value is missing and the case must not be guessed.

| Field | SI | BL | Match |
|---|---|---|---|
| shipper | APRIL FINE PAPER TRADING | APRIL FINE PAPER TRADING / ON BEHALF OF VITAL SOLUTIONS PTE LTD | uncertain |
| consignee | KPP-ANTALIS (SINGAPORE) PTE. LTD. | KPP-ANTALIS (SINGAPORE) PTE. LTD. | yes |
| notify_party | KPP-ANTALIS (SINGAPORE) PTE. LTD. | KPP-ANTALIS (SINGAPORE) PTE. LTD. | yes |
| port_of_loading | NHAVA SHEVA, INDIA | NHAVA SHEVA, INDIA (INNSA) | yes |
| port_of_discharge | CONAKRY, GUINEA | CONAKRY, GUINEA (GNCKY) | yes |
| container_count | 10 | 10 | yes |
| gross_weight_kg | NULL | 235550 | uncertain |
