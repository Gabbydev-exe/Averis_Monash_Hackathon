---
sessionId: session-260920-185114-1dck
---

# Requirements

### Overview & Goals
Replace hardcoded mock emails in the shipping document inbox with actual dataset records loaded from the project's dataset bundle (`backend/src/main/resources/data/bundle/`). Because raw incoming carrier emails have not yet been processed by the automated Vertex AI verification pipeline, all emails will default to a `pending` status. The system is designed to seamlessly accept and overlay automated verification reports generated later without requiring frontend structural redesigns.

### Scope
- **In Scope**:
  - Backend ingestion and serving of 500+ raw emails from `backend/src/main/resources/data/bundle/inbox/`.
  - Spring Boot REST API (`/api/emails`, `/api/emails/{id}`) delivering summarized and detailed email representations.
  - Defaulting verification status to `pending` across all ingested records and comparison fields.
  - Attachment metadata exposure and content access for SI/BL documents.
  - Refactoring `frontend/src/views/InboxView.vue` to fetch data asynchronously from the backend API.
  - Designing a clean integration contract for future offline/async Vertex AI verification report JSON overlay.
- **Out of Scope**:
  - Executing live Vertex AI extraction/inference or running Gemini/Vertex pipelines (deferred to a subsequent batch verification script).
  - Modifying database schemas or requiring MySQL running services for basic bundle reading (uses Spring Boot filesystem/classpath bundle provider).

### User Stories
- **As a Shipping Operator**, I want to view all incoming carrier emails from the actual dataset in my inbox so that I have complete visibility over pending shipping instruction and bill of lading documents.
- **As a System Developer**, I want email records to be served through a standardized REST API with a decoupled report ingestion architecture so that future Vertex AI verification results can be plugged in instantly without frontend disruption.

### Functional Requirements
- **FR-1: Dataset Parsing & Ingestion**: The backend must parse all `email_*.json` files located in the bundle directory upon service start, extracting `email_id`, `from`, `subject`, `body`, and `attachments`.
- **FR-2: Default Status Handling**: All raw emails without an accompanying verification verdict must expose `status: "pending"` with field comparisons initialized to `pending`.
- **FR-3: Summary & Detail REST Endpoints**:
  - `GET /api/emails`: Returns an array of email summaries (`id`, `sender`, `senderName`, `subject`, `date`, `status`, `bookingNo`, `attachmentsCount`).
  - `GET /api/emails/{id}`: Returns complete email metadata, body text, attachment lists, and verification comparison fields.
- **FR-4: Future Report Compatibility**: If a verification report JSON (e.g. `submission.json` or `verification_report.json`) is present in the data folder, the service must merge verdict fields (`category`, `status`, `defect_fields`, `has_defect`) into the corresponding email record.
- **FR-5: Frontend Reactivity**: The Vue `InboxView` must fetch the email list on mount, compute aggregate summary statistics (Total, Pending, Discrepancies, Verified), and update the detail panel when selecting an email.

### Non-Functional Requirements
- **Performance**: In-memory caching of parsed email summaries guarantees sub-10ms response times for the inbox list.
- **Resilience**: Missing attachment files or malformed optional fields degrade gracefully without crashing the endpoint.
- **Maintainability**: Clear separation of data models, storage parsing, and API controller layers.

# Technical Design

### Systems Design Assessment

Evaluating the proposed architecture from an operational and utilitarian perspective (maximizing system throughput, reliability, cost efficiency, and developer velocity while eliminating user-facing friction):

#### 1. Decoupling AI Inference from the User Request Path
- **High Aggregate Utility**: Running Vertex AI / LLM extraction in an asynchronous batch script rather than synchronously within HTTP request handlers is significantly superior. Live document OCR and multi-field cross-comparison take 2–6 seconds per document and consume costly API quotas.
- **Zero UI Latency**: Operators experience immediate, zero-latency inbox browsing (`O(1)` read latency) from pre-computed states, rather than waiting for on-demand LLM calls.
- **Fault Isolation**: If Vertex AI encounters rate limits, network partitions, or quota exhaustion, inbox operations and manual document inspection remain 100% available and unaffected.

#### 2. Progressive Enhancement & Default "Pending" State
- **Operational Clarity**: Marking unverified emails as `pending` provides an accurate operational mental model for operators. Incoming documents enter the queue immediately and get verified once the AI pipeline runs.
- **Backward & Forward Compatibility**: A unified email schema where `status: "pending"` transitions to `"verified"` or `"discrepancy"` based on report availability ensures that neither the backend REST contracts nor the frontend UI components need breaking changes when Vertex AI reports are introduced.

#### 3. Serving via Spring REST Service vs Direct Static Bundling
- **Efficiency & Scalability**: Centralizing bundle reading in Spring Boot keeps the client bundle lightweight (avoiding shipping 500+ JSON files to browser memory) and enables future database transitions (e.g. Cloud SQL/MySQL) without changing frontend code.

---

### Key Decisions
1. **In-Memory Parsed Bundle Cache in Spring Boot**:
   - *Choice*: Parse raw JSON files at startup / on demand into an in-memory repository.
   - *Rationale*: Eliminates repetitive disk I/O on every request, delivering instantaneous frontend list filtering and sorting.
2. **Standardized Domain Model Overlay**:
   - *Choice*: Construct domain DTOs (`EmailSummaryDto`, `EmailDetailDto`) that adhere to the standard 7-field verification contract (`shipper`, `consignee`, `notify_party`, `port_of_loading`, `port_of_discharge`, `container_count`, `gross_weight_kg`).
   - *Rationale*: Matches the official SDOC hackathon schema and allows zero-effort integration with `submission.json` outputs.

---

### Proposed Changes & Architecture

#### Architecture Diagram
```mermaid
graph LR
  subgraph Ingestion Layer
    Bundle[Raw Bundle JSON & Attachments]
    Report[Vertex AI Report JSON]
  end

  subgraph Backend Service (Spring Boot)
    Parser[EmailBundleLoader]
    Repo[EmailRepository]
    Controller[EmailController /api/emails]
  end

  subgraph Client (Vue 3)
    Store[Inbox State & Fetcher]
    InboxUI[InboxView Component]
  end

  Bundle --> Parser
  Report -.->|Overlay if present| Parser
  Parser --> Repo
  Repo --> Controller
  Controller -->|REST JSON| Store
  Store --> InboxUI
```

#### API Data Contracts

##### `GET /api/emails`
```json
[
  {
    "id": "email_001",
    "sender": "aziztz@safqa.co.ke",
    "senderName": "Willy Situmorang (APRIL Fine Paper)",
    "subject": "TO CONFIRM DOCS _ 5RSG-00133 _ CALLAO_PERU _ MOORIM SP CO., LTD _ MEDUUD104332",
    "date": "Received",
    "status": "pending",
    "bookingNo": "MEDUUD104332",
    "attachmentsCount": 2
  }
]
```

##### `GET /api/emails/{id}`
```json
{
  "id": "email_001",
  "sender": "aziztz@safqa.co.ke",
  "senderName": "Willy Situmorang",
  "subject": "TO CONFIRM DOCS _ 5RSG-00133 _ CALLAO_PERU",
  "date": "Received",
  "status": "pending",
  "bookingNo": "MEDUUD104332",
  "vessel": "Pending extraction",
  "bodyText": "Hi Najiha,\n\nAttached are the SI and draft BL...",
  "attachments": [
    { "name": "email_001_SI.txt", "path": "attachments/email_001_SI.txt", "type": "SI" },
    { "name": "email_001_BL.txt", "path": "attachments/email_001_BL.txt", "type": "BL" }
  ],
  "fields": [
    { "label": "Shipper", "si": "Pending", "bl": "Pending", "status": "pending" },
    { "label": "Consignee", "si": "Pending", "bl": "Pending", "status": "pending" },
    { "label": "Notify Party", "si": "Pending", "bl": "Pending", "status": "pending" },
    { "label": "Port of Loading", "si": "Pending", "bl": "Pending", "status": "pending" },
    { "label": "Port of Discharge", "si": "Pending", "bl": "Pending", "status": "pending" },
    { "label": "Container Count", "si": "Pending", "bl": "Pending", "status": "pending" },
    { "label": "Gross Weight (kg)", "si": "Pending", "bl": "Pending", "status": "pending" }
  ]
}
```

---

### File Structure Modifications

```
backend/src/main/java/com/shipping/api/
├── controller/
│   └── EmailController.java             [NEW] REST endpoints for /api/emails
├── model/
│   ├── EmailSummaryDto.java             [NEW] DTO for list view
│   ├── EmailDetailDto.java              [NEW] DTO for detail view & comparison fields
│   ├── EmailFieldDto.java               [NEW] DTO for individual field comparison
│   └── EmailAttachmentDto.java          [NEW] DTO for attachment references
└── service/
    └── EmailDataService.java            [NEW] Service reading bundle JSONs and reports

frontend/src/views/
└── InboxView.vue                        [MODIFIED] Replace mock array with API fetch
```

# Testing

### Validation Approach
Verification of the implementation will be performed through automated endpoint checks, schema contract validations, and frontend API data flow verification.

### Key Scenarios
1. **API Dataset Retrieval (`GET /api/emails`)**:
   - Verify that the endpoint returns HTTP 200 with the full list of 500+ parsed email summaries.
   - Verify that each record contains `id`, `sender`, `subject`, `status: "pending"`, and valid attachment counts.
2. **API Single Email Detail (`GET /api/emails/{id}`)**:
   - Verify that `GET /api/emails/email_004` returns the complete email body, 2 attachments (`email_004_SI.txt`, `email_004_BL.txt`), and 7 comparison fields with `status: "pending"`.
3. **Frontend Integration & Rendering**:
   - Verify that `InboxView.vue` successfully requests `/api/emails`, populates the email list sidebar, calculates correct total count stats, and displays detail data when clicked.
4. **Search and Filter Responsiveness**:
   - Verify that client-side search by sender, subject, and booking number filters the live dataset smoothly.
   - Verify filter tabs (`All`, `Pending`, `Discrepancies`, `Verified`) reflect accurate counts (with 100% in Pending in the initial phase).

### Edge Cases
- **Missing or Non-Standard File Extensions**: Verify that emails with binary attachments (`.pdf`, `.docx`, `.xlsx`) or varying file name casings are safely presented with correct file type badges.
- **Absent Report File**: Verify that when no `verification_report.json` exists in the data directory, the service operates seamlessly with pure default pending states without raising file-not-found exceptions.

# Delivery Steps

### ✓ Step 1: Implement Spring Boot Dataset Ingestion Service and REST Controller
Spring Boot backend exposes REST endpoints returning the full parsed email dataset from the bundle with pending verification statuses.

- Create `EmailSummaryDto`, `EmailDetailDto`, and `EmailAttachmentDto` model records in `com.shipping.api.model`.
- Implement `EmailDataService` to scan and parse all `email_*.json` files from `backend/src/main/resources/data/bundle/inbox/`, caching summaries in memory on startup for sub-millisecond response times.
- Implement `EmailController` exposing `GET /api/emails` (returning list summaries) and `GET /api/emails/{id}` (returning full email details and attachment metadata).
- Ensure all emails default to `status: "pending"` with comparison fields initialized in the pending state.

### ✓ Step 2: Add Report Overlay Integration and Attachment Endpoint
The backend provides optional report overlay capability and secure reading of raw attachment text content.

- Add support in `EmailDataService` to scan for a future `verification_report.json` or `submission.json` report file if present in the data path, gracefully falling back to raw pending defaults if absent.
- Implement `GET /api/emails/{id}/attachments/{filename}` endpoint in `EmailController` to retrieve raw text and previewable document content from `backend/src/main/resources/data/bundle/attachments/`.
- Handle binary and text attachments safely with content-type headers and error boundaries.

### ✓ Step 3: Connect Vue InboxView to Backend REST API
InboxView in Vue dynamically fetches and renders live emails from Spring Boot, supporting searching, filtering, and pending inspection.

- Replace hardcoded static `emails` ref in `frontend/src/views/InboxView.vue` with dynamic async fetching from `/api/emails`.
- Implement loading state indicator, error alert fallback, and selection synchronization for `selectedEmailId`.
- Preserve existing search, status filtering (All, Pending, Discrepancy, Verified), and detail inspection UI while rendering real sender, subject, and attachment names from the bundle.
- Ensure the verification action buttons gracefully reflect the pending nature of unverified raw emails.