# Database-only storage and rollout

All application email reads, imports, exports, extracted shipment fields, review decisions, and attachment downloads now use MySQL. There is no `no-db` profile or runtime bundled-inbox fallback. An empty database produces an empty inbox. Failed reads/writes return errors, not fixture data or a successful temporary import.

## Before deploying this version

1. Back up the existing database using your normal Cloud SQL backup procedure.
2. Connect to **shipping_db** in Cloud SQL Studio using a database account permitted to create tables. Run the contents of [src/main/resources/db/persistence.sql](src/main/resources/db/persistence.sql). This creates four additional tables without replacing existing emails/attachments. It assumes the existing `emails` and `attachments` source tables already exist. The application account needs SELECT/INSERT/UPDATE/DELETE on the new tables. Schema creation is explicit; the app does not auto-create tables.
3. Build and run the new backend locally against that database. Check `/api/database/status` and `/api/emails`. The status endpoint checks connectivity only; the inbox also checks the workflow tables.
4. Copy the existing fixture attachment bytes into MySQL with the explicit seed tool below. Existing emails and existing stored attachment bytes are skipped. Unavailable fixture files remain visibly `Not uploaded`.
5. Deploy backend and frontend together. The fixture directory is excluded from the runtime JAR; attachments that were never copied into MySQL cannot be downloaded after rollout.

No Cloud SQL migration, seed, or deployment is performed simply by building/testing this repository.

## Local setup

Use JDK 25 (matching the Maven target, CI, and Docker image). Cloud SQL is the default profile. Set `DB_NAME`, `DB_USER`, `DB_PASSWORD` (or the Spring override `SPRING_DATASOURCE_PASSWORD`), and `INSTANCE_CONNECTION_NAME`, with Application Default Credentials configured. See MYSQL_SETUP.md for Google authentication.

PowerShell, from the backend module:

```powershell
$env:SPRING_PROFILES_ACTIVE = "cloudsql"
$env:INSTANCE_CONNECTION_NAME = "hackathon-509104:asia-southeast1:shipping-mysql"
$env:DB_NAME = "shipping_db"
$env:DB_USER = "shipping_app"
# Set DB_PASSWORD privately in this terminal or obtain it from your configured secret.
.\mvnw.cmd spring-boot:run
```

Cloud SQL is the only supported application database, including when running the backend on your laptop. There is no local MySQL profile or local database file. Automated tests use disposable in-memory H2 databases only; H2 is excluded from the deployed application and tests never modify Cloud SQL.

## Explicit fixture import

From the backend module, with Python 3.9+ and the new backend running:

```powershell
python tools/seed_database.py --bundle src/main/resources/data/bundle --api http://localhost:8081
```

This command writes to whichever database the API is configured to use. It imports source JSON via the normal transactional importer and separately uploads missing registered attachment bytes. It can resume after interruption. It never runs on startup. Test fixtures are loaded explicitly into isolated H2 databases during tests; H2 is test-only.

## Stored data

- Existing `emails`: ID, sender, subject, body, original JSON (including additional properties).
- Existing `attachments`: references and metadata. JSON imports do not upload attachment files.
- `attachment_contents`: actual bytes, keyed by email and filename. PUT of registered attachment bytes updates size/hash transactionally. Changed bytes invalidate current extraction and approval; uploading identical bytes is a no-op.
- `email_extractions`: latest extraction revision, model/source, classification and save time.
- `shipment_fields`: seven rows per completed extraction, with separate SI/BL values and SI/BL evidence. Missing values are null. These are the latest extracted fields, not a history of all model outputs.
- `email_reviews`: append-only reviewer decisions, notes, time, and extraction revision. A new extraction makes previous decisions historical. Reviewer names are **self-reported**, not authenticated identities; this change does not add application login.

## API contracts

- `GET /api/emails/{id}/workflow`: current revision, category, extraction fields and review history.
- `PUT /api/emails/{id}/extraction`: `{ "model": "source/model", "category": "DOCUMENT_COMPARISON", "expectedRevision": "none", "fields": [...] }`. Read the current revision first; use `none` only before any extraction/attachment update. Each field has `key`, `si`, `bl`, `siEvidence`, `blEvidence`. Include exactly `shipper`, `consignee`, `notify_party`, `port_of_loading`, `port_of_discharge`, `container_count`, `gross_weight_kg`. Null SI/BL values represent missing information. Missing evidence stays null.
- `POST /api/emails/{id}/reviews`: `{ "revision": "current revision", "decision": "APPROVE", "reviewer": "Your name", "note": "Reason" }`; decision is APPROVE or FLAG. Approval requires complete SI and BL values. Decisions never overwrite extracted evidence. Stale revisions return 409.
- `PUT /api/emails/{id}/attachments/{filename}`: raw bytes, `Content-Type: application/octet-stream`, at most 5 MB. Reference must already belong to that email; otherwise 404.
- `POST /api/gemini/process/{id}`: now loads database source/attachment data and saves classification and extraction. This replaces the old GET endpoint because processing writes data and invokes Gemini. AI failure returns 502 without claiming a saved result; database failure returns 503. Missing/ambiguous/unreadable documents retain null fields requiring review. The current extractor does not return evidence snippets, so they remain unavailable rather than invented.

The frontend Run Verification button invokes the persisted Gemini pipeline. Approve/Flag saves to the review API and only updates status after success. Comparison currently normalizes whitespace/case and numeric formatting; it is not a full business synonym or tolerance engine. Standalone ShippingAgent reads through `SHIPPING_API_URL` (default localhost:8081), not local fixture files; its exploratory tool outputs are not automatically saved. Use the web pipeline to persist a verification.

Source JSON/CSV export remains separate from workflow results. Use the workflow endpoint to retrieve evidence and review history.
