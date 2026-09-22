# Ship AI Verifier

> **Averis x Monash Hackathon 2026**  
> AI-assisted email classification, shipping-document extraction, SI/B/L comparison, and human review.

## Project overview

Shipping operations teams receive large volumes of unstructured email. They must identify the purpose of each message, locate Shipping Instructions (SI) and draft Bills of Lading (B/L), and compare shipment details before documents can be approved.

Ship AI Verifier turns that manual workflow into a persistent cloud application. Users import email data and source documents, Gemini classifies and extracts them, deterministic rules compare seven shipment fields, and reviewers inspect evidence before exporting competition results.

## Current feature set

### Import and persistent storage

- Import one email object, an array of emails, or an `{ "emails": [...] }` payload from JSON.
- Validate the complete batch before writing. A request can contain up to 2,000 records and 5 MB of JSON.
- Preserve the original JSON, including additional fields and Unicode text.
- Skip existing email IDs without overwriting their data, attachments, extraction, or review history.
- For one newly imported email, optionally upload zero, one, or two source documents from the Import / Export page.
- Accept PDF, DOC, DOCX, XLSX, and UTF-8 TXT attachments up to 5 MB each.
- Store all application records in Cloud SQL MySQL. There is no temporary session or local-database fallback.
- Store newly uploaded document bytes in a private Google Cloud Storage bucket. Cloud SQL links each email ID and filename to its GCS object.
- Continue reading older attachment BLOBs from Cloud SQL during migration to GCS.

### AI classification and extraction

- Classify the current email into one persisted category:
  - `BL_COMPARISON`
  - `SI_REQUEST`
  - `INVOICE_QUERY`
  - `GENERAL`
  - `SPAM`
- Treat quoted history, signatures, email text, and document content as untrusted input.
- Distinguish a new SI request from a document-comparison request. Asking for a future draft B/L does not imply that a B/L already exists.
- Read text-based PDF, legacy DOC, DOCX, XLSX, and UTF-8 TXT documents.
- Identify SI and B/L document roles from their content rather than trusting filenames.
- Use the current email body as an SI source when it contains explicit shipping instructions and no SI attachment was identified.
- Extract and persist these seven fields:
  - Shipper
  - Consignee
  - Notify Party
  - Port of Loading
  - Port of Discharge
  - Container Count
  - Gross Weight in kilograms
- Save the source filename or `Email body` with each extracted value so reviewers can trace its origin.
- Send corrupt, encrypted, unsupported, image-only, or unreadable documents to human review instead of inventing values. OCR for scanned images is not currently included.

### Automatic verification queue

- Process unverified emails automatically while the Inbox is open.
- Remember the browser's automatic-processing checkbox after reload or navigation.
- Support unattended processing through Google Cloud Scheduler calling the same backend queue.
- Claim one email at a time with a durable Cloud SQL lease, preventing duplicate work across browser tabs and Cloud Run instances.
- Retry failures after 60 seconds, stop automatic retries after three attempts, and allow an explicit manual retry with **Run Verification**.
- Recover expired leases after an interrupted request or Cloud Run restart.
- Avoid automatically reopening completed assessments. Uploading changed attachment bytes invalidates the current extraction and makes that email eligible again.

### SI/B/L comparison and review

- Compare all seven persisted SI and B/L values.
- Automatically verify only when all seven stored values are literally identical.
- Mark any differing value as `MISMATCH` and require human review.
- Mark missing values, missing or unreadable attachments, ambiguous document sets, and incorrect document types as `NEEDS_REVIEW`.
- Ask Gemini for a semantic match percentage and explanation only when complete values differ. The percentage is advisory and can never approve a mismatch.
- Display categories in Inbox tabs and show `Unprocessed`, `Classified`, `Verified`, `Human review required`, or reviewer-flagged status.
- Display SI and B/L values side by side, source evidence filenames, original email text, and downloadable source documents.
- Show valid evidence in black, discrepancies in red, and pending evidence in amber.
- Save reviewer name, decision, note, extraction revision, and review history separately from AI evidence.
- Prevent approval when required values or document processing are incomplete.

### Import, export, and user experience

- Search emails by sender and select individual records or all visible matches.
- Export original source records separately as JSON or spreadsheet-safe CSV.
- Export processed competition results as JSON or CSV with:
  - `category`
  - `status`
  - `review_reason`
  - `defect_fields`
  - `has_defect`
- Refuse result export when a selected email has not been processed since its latest update.
- Preserve selected attachment uploads across partial failures so retrying continues with the remaining file.
- Show database/detail loading failures and a Retry action instead of an empty email panel.
- Provide a compact API connection indicator and an updated **How It Works** guide.

## Processing flow

```text
JSON email + optional documents
            |
            v
Vue Import / Export page
            |
            +--> Cloud SQL: email, attachment metadata, workflow state
            +--> GCS: private attachment bytes
            |
            v
Durable verification queue
            |
            v
Gemini classification + document-role detection + field extraction
            |
            v
Seven-field deterministic comparison
       /                    \
exact values          difference/incomplete
     |                        |
auto verified        human review required
       \                    /
        persisted result + JSON/CSV export
```

## Architecture and technology

```text
[ Firebase Hosting: Vue 3 + Vite ]
                  |
                  v
[ Cloud Run: Java 25 + Spring Boot REST API ]
       |                  |                 |
       v                  v                 v
[ Cloud SQL MySQL ] [ Vertex AI Gemini ] [ Private GCS bucket ]
       ^
       |
[ Cloud Scheduler: unattended queue trigger ]
```

- **Frontend:** Vue 3, Vite, Vue Router, native Fetch API, Firebase Hosting.
- **Backend:** Java 25, Spring Boot 4.1.1, JDBC, Maven, Docker, Cloud Run.
- **AI:** Google Gen AI Java SDK with Gemini on Vertex AI; Google ADK tools are available for agent-assisted investigation and persisted verification.
- **Data:** Cloud SQL for source emails, metadata, categories, extracted fields, assessments, jobs, and human reviews; GCS for new attachment bytes.
- **Delivery:** GitHub Actions tests and builds both applications before deploying Cloud Run and Firebase Hosting from `main`.

## Database and cloud rollout

The backend does not create production tables automatically. Apply these scripts to the `shipping_db` Cloud SQL database before deploying the matching code:

1. `backend/src/main/resources/db/persistence.sql`
2. `backend/src/main/resources/db/automation.sql`
3. `backend/src/main/resources/db/gcs.sql`

Then create the private GCS bucket, grant the Cloud Run runtime service account object access, set `ATTACHMENTS_GCS_BUCKET`, and configure Cloud Scheduler if processing must continue while the Inbox is closed.

Detailed instructions:

- [Cloud SQL and GCS setup](backend/GCS_SETUP.md)
- [Automatic verification and Cloud Scheduler](backend/AUTOMATION.md)
- [Cloud deployment and rollback](DEPLOYMENT.md)

## Local development

Local application runs use the real Cloud SQL database and GCS bucket. Tests use a disposable in-memory H2 database and a fake object store; they do not make paid Gemini calls.

### Prerequisites

- Java 25
- Node.js 24 and npm
- Google Cloud CLI
- Access to project `hackathon-509104`, Cloud SQL, Vertex AI, Secret Manager, and the attachment bucket

### Authenticate with Google Cloud

```powershell
gcloud auth login
gcloud config set project hackathon-509104
gcloud auth application-default login
gcloud auth application-default set-quota-project hackathon-509104
```

### Run the backend on Windows PowerShell

```powershell
cd C:\Users\111\Desktop\hackerthon\backend\backend

$env:PORT = "8081"
$env:SPRING_PROFILES_ACTIVE = "cloudsql"
$env:INSTANCE_CONNECTION_NAME = "hackathon-509104:asia-southeast1:shipping-mysql"
$env:DB_NAME = "shipping_db"
$env:DB_USER = "shipping_app"
$env:ATTACHMENTS_GCS_BUCKET = "hackathon-509104-shipping-attachments"

$dbPassword = gcloud secrets versions access latest `
  --secret=shipping-db-password `
  --project=hackathon-509104
if ($LASTEXITCODE -ne 0) { throw "Could not read shipping-db-password." }
$env:SPRING_DATASOURCE_PASSWORD = $dbPassword
Remove-Variable dbPassword

.\mvnw.cmd spring-boot:run
```

The backend listens at `http://localhost:8081`.

### Run the frontend

Open a second PowerShell terminal:

```powershell
cd C:\Users\111\Desktop\hackerthon\backend\frontend
npm ci
npm run dev
```

The frontend listens at `http://localhost:5173` and proxies API requests to the backend.

## Main API endpoints

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/status` | API connectivity status. |
| `GET` | `/api/database/status` | Cloud SQL connectivity status. |
| `GET` | `/api/emails` | Email summaries with status and category. |
| `GET` | `/api/emails/{id}` | Email details, attachment metadata, and comparison fields. |
| `POST` | `/api/emails/import` | Validate and persist JSON email records. |
| `GET/POST` | `/api/emails/export?format=json\|csv` | Export all or selected original source records. |
| `GET/POST` | `/api/emails/export-results?format=json\|csv` | Export all or selected processed competition results. |
| `POST` | `/api/emails/{id}/attachments/{filename}` | Add a supported document to a newly imported email. |
| `PUT` | `/api/emails/{id}/attachments/{filename}` | Replace bytes for a registered attachment and invalidate its extraction. |
| `GET` | `/api/emails/{id}/attachments/{filename}` | View or download attachment content. |
| `GET` | `/api/emails/{id}/attachments/{filename}/text` | Read the backend's extracted document text and status. |
| `GET` | `/api/emails/{id}/workflow` | Read saved extraction, assessment, category, and review history. |
| `POST` | `/api/emails/{id}/reviews` | Save a human approval or issue flag. |
| `POST` | `/api/gemini/process/{emailId}` | Manually classify and verify one email. |
| `POST` | `/api/gemini/process-next` | Claim and process one eligible queued email. |
| `GET` | `/api/gemini/queue-errors` | Read safe summaries of bounded queue failures. |

## Verification and export rules

Machine result statuses are:

- `OK`: all seven comparison values are literally identical, or the category does not require an SI/B/L comparison.
- `MISMATCH`: complete SI and B/L values differ; `has_defect` is `true` and human review is required.
- `NEEDS_REVIEW`: evidence is missing, unreadable, ambiguous, incomplete, or the wrong document type; `has_defect` remains `false` because the system cannot establish a reliable discrepancy.

A later human decision does not rewrite the machine result. Review history and AI match percentages remain available in the Inbox and workflow API, while the competition export keeps its required five-field schema.

## Testing

Run backend tests:

```powershell
cd backend
.\mvnw.cmd test
```

Run the frontend production build:

```powershell
cd frontend
npm ci
npm run build
```

The test suite covers JSON validation and transaction rollback, durable persistence, attachment parsing, GCS replacement behavior, category and assessment persistence, exact-match policy, bounded queue retries and leases, export schema, stale-review protection, and missing/unreadable-document handling.

## CI/CD

Pushes to `main` run `.github/workflows/cd.yml`:

1. Run the Maven test suite with Java 25.
2. Build the Vue application with Node.js 24.
3. Deploy the Spring Boot source/Dockerfile to Cloud Run service `shipping-api` in `asia-southeast1`.
4. Deploy the Vue build to Firebase Hosting.

Database migrations, Cloud Scheduler, bucket IAM, Cloud Run runtime variables, and secrets remain explicit infrastructure operations and are not applied automatically by the workflow.

## Team responsibilities

- **Member 1:** Email classification, prompts, and sample validation.
- **Member 2:** Document parsing, field extraction, and evidence validation.
- **Member 3:** Comparison policy, workflow persistence, and human-review rules.
- **Member 4:** Vue Inbox, Import / Export, and reviewer experience.
- **Member 5:** Google Cloud infrastructure, CI/CD, security, testing, and submission coordination.

## License and attribution

Developed for the Averis x Monash Hackathon 2026 by Monash University Malaysia participants.
