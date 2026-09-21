# Shipping API

Backend for the shipping-document verification prototype: a Spring Boot service that stores emails and attachments in Cloud SQL for MySQL, extracts text from attachments, runs a Gemini classification/extraction pipeline, and records human review decisions. It runs on Cloud Run (`shipping-api`) and is fronted by the Vue frontend.

> **Database is mandatory.** All email reads, imports, exports, extracted shipment fields, review decisions and attachment downloads use MySQL. There is no `no-db` profile, no local database option and no bundled-inbox fallback. An empty database produces an empty inbox; failed reads/writes return errors, never fixture data.
>
> **No application authentication yet.** Reviewer names are self-reported. Add authentication before exposing documents or paid AI processing publicly.

## Contents

1. [Project reference](#1-project-reference)
2. [Prerequisites](#2-prerequisites)
3. [Cloud SQL setup (one time)](#3-cloud-sql-setup-one-time)
4. [Schema migration and fixture import](#4-schema-migration-and-fixture-import)
5. [Configuration](#5-configuration)
6. [Run locally](#6-run-locally)
7. [Tests](#7-tests)
8. [Verify against live Cloud SQL (Cloud Shell)](#8-verify-against-live-cloud-sql-cloud-shell)
9. [Build, push and deploy to Cloud Run](#9-build-push-and-deploy-to-cloud-run)
10. [Data model](#10-data-model)
11. [API reference](#11-api-reference)
12. [Attachment text extraction](#12-attachment-text-extraction)
13. [Email import and export](#13-email-import-and-export)
14. [Frontend behavior](#14-frontend-behavior)
15. [Limitations](#15-limitations)
16. [Troubleshooting](#16-troubleshooting)
17. [References](#17-references)

---

## 1. Project reference

| Item | Value |
|---|---|
| Google Cloud project | `hackathon-509104` |
| Region | `asia-southeast1` (Singapore) |
| Cloud Run service | `shipping-api` |
| Cloud SQL instance | `shipping-mysql` (MySQL 8.4, Enterprise edition) |
| Instance connection name | `hackathon-509104:asia-southeast1:shipping-mysql` |
| Database | `shipping_db` (`utf8mb4`) |
| Application DB user | `shipping_app` (SELECT/INSERT/UPDATE/DELETE only) |
| Secret Manager secret | `shipping-db-password` |
| Artifact Registry repo | `asia-southeast1-docker.pkg.dev/hackathon-509104/shipping-images` |
| Local port | `8081` (avoids the dataset server's port 8080) |

The dataset contains 520 unique emails and 250 attachment references.

## 2. Prerequisites

- **JDK 25** (matches the Maven target, CI and Docker image). Maven is downloaded by the included wrapper.
- **Python 3.9+** for the fixture seed tool.
- **gcloud CLI** with Application Default Credentials for local runs.
- Google IAM: **Cloud SQL Client** and **Service Usage Consumer** on the project for any developer connecting from a laptop.
- Docker (or Cloud Shell) to build the container image.

## 3. Cloud SQL setup (one time)

These steps create billable resources. Review the console cost estimate first: a running Cloud SQL instance incurs charges even when Cloud Run scales to zero. Skip this section if the instance already exists.

### 3.1 Create the instance

In Google Cloud Console: **SQL > Create instance > MySQL**.

- Edition: **Enterprise**, version **MySQL 8.4** (avoid the Enterprise Plus production preset).
- Instance ID `shipping-mysql`, region Singapore, single-zone availability.
- Smallest development machine (shared core if available), 10 GB SSD or the minimum offered. Keep automated backups enabled and review storage auto-growth limits.
- Set a strong **root** password and save it privately.
- Enable **public IP**, leave **authorized networks empty**, and choose the Google-managed per-instance CA if asked. The Java connector authenticates through Google IAM and encrypts the connection, so no `0.0.0.0/0` rule is needed.

Copy the connection name from Overview; it should equal `hackathon-509104:asia-southeast1:shipping-mysql`.

### 3.2 Create the database and application user

Under **Databases > Create database**, create `shipping_db` with `utf8mb4`. Then open Cloud SQL Studio, sign in as root, select `shipping_db` and run the following privately (replace the placeholder; do not save the completed SQL in Git or send the password in chat). This is a one-time step; do not rerun it for an existing user.

```sql
CREATE USER 'shipping_app'@'%' IDENTIFIED BY 'REPLACE_WITH_PRIVATE_APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON shipping_db.* TO 'shipping_app'@'%';
```

The application user is not root and cannot change the schema. Use an administrator or separate migration identity for table migrations.

### 3.3 Store the application password in Secret Manager

```bash
gcloud config set project hackathon-509104
gcloud services enable sqladmin.googleapis.com secretmanager.googleapis.com
```

In the console, open **Secret Manager > Create secret**:

- Name: `shipping-db-password`
- Value: the exact `shipping_app` password, without a trailing newline
- Note the version number (normally `1`)

Use the application password, not the root password. Secrets never belong in frontend code, Docker images, Git or the ordinary environment-variable editor.

### 3.4 Grant the Cloud Run identity access

```bash
RUN_SA=$(gcloud run services describe shipping-api --region=asia-southeast1 --project=hackathon-509104 --format='value(spec.template.spec.serviceAccountName)')
echo "$RUN_SA"
```

Confirm this prints the runtime service account email; if it is empty, inspect the service's Security settings before continuing. Then grant:

```bash
gcloud projects add-iam-policy-binding hackathon-509104 --member="serviceAccount:$RUN_SA" --role=roles/cloudsql.client
gcloud secrets add-iam-policy-binding shipping-db-password --project=hackathon-509104 --member="serviceAccount:$RUN_SA" --role=roles/secretmanager.secretAccessor
```

`roles/cloudsql.client` authorizes the connector; the MySQL username/password and SQL grants authorize database access separately. The secret role is scoped to this one secret. No downloaded service-account key is required.

## 4. Schema migration and fixture import

Do these before running or deploying the current version. Building or testing this repository never migrates, seeds or deploys anything.

1. **Back up** the database using your normal Cloud SQL backup procedure.
2. **Base tables.** The `emails` and `attachments` source tables come from the database package's `schema.sql`. Do not re-run `schema.sql` or `seed.sql` on a database that already holds data.
3. **Workflow tables.** In Cloud SQL Studio, connect to `shipping_db` with an account permitted to create tables and run [`src/main/resources/db/persistence.sql`](src/main/resources/db/persistence.sql). It adds four tables (see [Data model](#10-data-model)) without replacing existing emails or attachments. Schema creation is explicit; the app never auto-creates tables. The `shipping_app` grant on `shipping_db.*` already covers the new tables.
4. **Run the backend locally** against that database and check `/api/database/status` and `/api/emails`. The status endpoint checks connectivity only; the inbox also exercises the workflow tables.
5. **Import fixtures explicitly.** With the new backend running (see [Run locally](#6-run-locally)), from the backend module:

   ```powershell
   python tools/seed_database.py --bundle src/main/resources/data/bundle --api http://localhost:8081
   ```

   This writes to whichever database the API is configured to use. It imports source JSON through the normal transactional importer, then uploads any missing registered attachment bytes. Existing emails and existing stored attachment bytes are skipped, and the command can resume after interruption. It never runs on startup. Attachments whose files are unavailable stay visibly **Not uploaded**.
6. **Deploy backend and frontend together.** The fixture directory is excluded from the runtime JAR, so attachments never copied into MySQL cannot be downloaded after rollout.

## 5. Configuration

Cloud SQL (`cloudsql`) is the default and only supported application profile, including on a laptop.

| Variable | Purpose | Secret? |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `cloudsql` | no |
| `INSTANCE_CONNECTION_NAME` | `hackathon-509104:asia-southeast1:shipping-mysql` | no |
| `DB_NAME` | `shipping_db` | no |
| `DB_USER` | `shipping_app` | no |
| `DB_PASSWORD` (or Spring override `SPRING_DATASOURCE_PASSWORD`) | `shipping_app` password | **yes** |
| `PORT` | HTTP port (Cloud Run injects 8080; use 8081 locally) | no |
| `SHIPPING_API_URL` | Base URL used by the standalone ShippingAgent (default `http://localhost:8081`) | no |

Connection pool: five connections per app instance. Keep Cloud Run maximum instances small for the prototype and resize as traffic grows.

Never store passwords in a shared `.idea` run configuration, script, chat or Git.

## 6. Run locally

One-time Google authentication for the developer account:

```powershell
gcloud auth application-default login
gcloud auth application-default set-quota-project hackathon-509104
```

Then, from the backend module (PowerShell):

```powershell
$env:SPRING_PROFILES_ACTIVE = "cloudsql"
$env:INSTANCE_CONNECTION_NAME = "hackathon-509104:asia-southeast1:shipping-mysql"
$env:DB_NAME = "shipping_db"
$env:DB_USER = "shipping_app"
# Set DB_PASSWORD privately in this terminal or obtain it from your configured secret.
$env:PORT = "8081"
.\mvnw.cmd spring-boot:run
```

Check:

- <http://localhost:8081/api/status> (application availability only)
- <http://localhost:8081/actuator/health> (expect `{"status":"UP"}`)
- <http://localhost:8081/api/database/status> (runs `SELECT 1` on MySQL)

Press Ctrl+C to stop.

## 7. Tests

From the backend module, with JDK 25:

```powershell
.\mvnw.cmd verify
```

Tests use disposable in-memory H2 databases in MySQL compatibility mode. H2 is a test-only dependency: it is excluded from the deployed application, is not a replacement for MySQL, and tests never touch Cloud SQL or need your real password. Fixtures are loaded explicitly into the isolated H2 databases.

Coverage includes actual `JdbcTemplate` queries, stored Unicode/text, email/attachment relationships and ordering, missing IDs, parameter binding, database changes after startup, empty databases, attachment access, and HTTP 503 handling.

> The Maven parent (Spring Boot 4.1.1) could not be resolved in the environment where the original database integration was prepared, so compilation/JUnit and live Cloud SQL verification were not completed there. Run the tests before merging.

## 8. Verify against live Cloud SQL (Cloud Shell)

This starts a temporary app in Cloud Shell to check the integration. It does **not** update the deployed Cloud Run service. Complete [section 4](#4-schema-migration-and-fixture-import) first, otherwise the data checks below will not match.

Upload the supplied `backend-source.zip` (the backend with all changes applied) using Cloud Shell's Upload menu, then in the first Bash tab:

```bash
cd ~
check_dir=$(mktemp -d "$HOME/shipping-email-check.XXXXXX")
unzip -q backend-source.zip -d "$check_dir"
cd "$check_dir/backend"
java -version
bash mvnw -B test
```

Require **JDK 25** and **BUILD SUCCESS** before proceeding. If tests fail, keep the error output and stop. Then, in the same tab:

```bash
export SPRING_PROFILES_ACTIVE=cloudsql
export INSTANCE_CONNECTION_NAME=hackathon-509104:asia-southeast1:shipping-mysql
export DB_NAME=shipping_db
export DB_USER=shipping_app
export PORT=8081
read -r -s -p 'shipping_app MySQL password: ' DB_PASSWORD
printf '\n'
export DB_PASSWORD
bash mvnw spring-boot:run
```

Enter the private **shipping_app password**, not the root or Google password. If you do not have it, ask the teammate who configured the backend. Cloud Shell already provides Application Default Credentials; if the Java connector reports missing credentials, run `gcloud auth application-default login` in this session and retry. Your identity needs Cloud SQL Client access (SQL grants for `shipping_app` are separate from Google IAM).

Startup alone does not prove connectivity because the pool is intentionally lazy. In a second Cloud Shell tab:

```bash
curl --fail-with-body http://localhost:8081/api/database/status
curl --fail-with-body http://localhost:8081/api/emails -o /tmp/shipping-emails.json
python3 - <<'PY'
import json
with open('/tmp/shipping-emails.json') as f:
    rows = json.load(f)
assert len(rows) == 520, f'Expected 520 emails, got {len(rows)}'
assert len({row['id'] for row in rows}) == 520
assert sum(row['attachmentsCount'] for row in rows) == 250
print('PASS: 520 unique emails and 250 attachment references from the database API')
PY
curl --fail-with-body http://localhost:8081/api/emails/email_004
curl --fail-with-body http://localhost:8081/api/emails/email_004/attachments/email_004_SI.txt
curl --fail-with-body http://localhost:8081/api/emails/email_004/attachments/email_004_SI.txt/text
curl -s -o /dev/null -w 'Missing email HTTP %{http_code}\n' http://localhost:8081/api/emails/email_missing
curl -s -o /dev/null -w 'Wrong owner HTTP %{http_code}\n' http://localhost:8081/api/emails/email_003/attachments/email_004_SI.txt
```

Expected: `connected`; the PASS message; `email_004` with two attachments; the SI text (raw and extracted); and **404** for both final requests. If data was deliberately changed since import, compare against the updated SQL counts. On any unexpected result, stop and inspect the error rather than deploying.

When finished, press Ctrl+C in the first tab and run `unset DB_PASSWORD`. If port 8081 is in use, pick a free port and use it consistently in both tabs.

## 9. Build, push and deploy to Cloud Run

Run each command separately and continue only when it succeeds. These steps create cloud resources and may incur charges; billing must be linked.

### 9.1 First-time setup (Cloud Shell, Bash)

Upload `backend-source.zip` (contains only this backend) to your Cloud Shell home directory, then:

```bash
unzip backend-source.zip -d shipping-starter-v1
cd shipping-starter-v1/backend
gcloud config set project hackathon-509104
gcloud services enable artifactregistry.googleapis.com run.googleapis.com
gcloud artifacts repositories list --location=asia-southeast1
```

If `shipping-images` does not already exist **in Singapore**, create it. An existing `shipping-images` repository in `africa-south1` is separate; do not delete it.

```bash
gcloud artifacts repositories create shipping-images --repository-format=docker --location=asia-southeast1 --description="Shipping API container images"
```

### 9.2 Build and push the image

From your Cloud Shell checkout of the repo (after changes reach `main`; clone your GitHub repository into `~/shipping-repo` first if needed):

```bash
cd ~/shipping-repo
git switch main
git pull --ff-only origin main
cd backend
gcloud auth configure-docker asia-southeast1-docker.pkg.dev
TAG=$(git rev-parse --short HEAD)
IMAGE="asia-southeast1-docker.pkg.dev/hackathon-509104/shipping-images/shipping-api:$TAG"
docker build --platform linux/amd64 -t "$IMAGE" .
docker push "$IMAGE"
```

Use a new tag for every build. Cloud Run cannot deploy a repository URL or a tag that has not been pushed.

**Container smoke tests** (`docker run -p 8080:8080 "$IMAGE"`, then `curl` `/api/status` and `/actuator/health`) now also require the database configuration and access to Google credentials. Without them the container will not start healthy. If port 8080 is busy use `-p 8081:8080`. Do not re-run `docker run` while the container is still running; use `docker logs shipping-api-check` to inspect startup errors, and `docker stop shipping-api-check` when finished.

### 9.3 Deploy with the `cloudsql` profile

```bash
gcloud run deploy shipping-api \
  --project=hackathon-509104 \
  --region=asia-southeast1 \
  --image="$IMAGE" \
  --update-env-vars="SPRING_PROFILES_ACTIVE=cloudsql,INSTANCE_CONNECTION_NAME=hackathon-509104:asia-southeast1:shipping-mysql,DB_NAME=shipping_db,DB_USER=shipping_app" \
  --update-secrets="DB_PASSWORD=shipping-db-password:1"
```

This keeps the existing service identity and invocation policy. The Java connector connects directly, so you do **not** also need `--add-cloudsql-instances` or a Unix-socket attachment. Public IP must be enabled on the instance; a private-IP-only instance requires a VPC path and an explicit connector configuration change. Use the actual connection name and secret version if they differ from the documented values.

**Console alternative:** Cloud Run > `shipping-api` > *Edit & deploy new revision*, choose the new image, add the four environment variables, add a secret reference `DB_PASSWORD` to `shipping-db-password` (version 1), and deploy.

For a brand-new service, the console settings used for the starter were: service `shipping-api`, Singapore, port 8080, 1 vCPU, 1 GiB, request-based billing, minimum instances 0, maximum instances 2. The image listens on `0.0.0.0` and honors Cloud Run's `PORT`. Public access was allowed for the original status-only starter; because the service now serves email data and can trigger paid Gemini processing, revisit that before real use.

### 9.4 Verify the deployment

Open `/api/database/status` on the Cloud Run URL (or the Firebase Hosting URL; the `/api/**` rewrite forwards it). Expect HTTP 200:

```json
{"database":"mysql","status":"connected"}
```

### 9.5 Team handoff

After local tests and live checks pass, review the change on a feature branch and use the normal pull request process. The `main`-branch workflow deploys automatically after merge. Before committing, review staged files (`git diff --cached --name-only`); other frontend changes may already be staged, so commit only what you intend.

## 10. Data model

| Table | Contents |
|---|---|
| `emails` | ID, sender, subject, body, original JSON (including additional source properties) |
| `attachments` | References and metadata. JSON imports create references only; they do not upload files |
| `attachment_contents` | Actual attachment bytes, keyed by email and filename. Uploading registered bytes updates size/hash transactionally. Changed bytes invalidate the current extraction and approval; identical bytes are a no-op |
| `email_extractions` | Latest extraction revision: model/source, classification, save time |
| `shipment_fields` | Seven rows per completed extraction with separate SI and BL values and SI/BL evidence. Missing values are `null`. Holds the latest extraction only, not a history of model outputs |
| `email_reviews` | Append-only reviewer decisions with note, time and extraction revision. A new extraction makes earlier decisions historical |

The seven field keys are `shipper`, `consignee`, `notify_party`, `port_of_loading`, `port_of_discharge`, `container_count`, `gross_weight_kg`.

`attachment_contents`, `email_extractions`, `shipment_fields` and `email_reviews` are created by `persistence.sql`; `emails` and `attachments` come from the base schema.

## 11. API reference

### Health and status

| Method and path | Description |
|---|---|
| `GET /api/status` | Application availability only |
| `GET /actuator/health` | Spring health (`{"status":"UP"}`) |
| `GET /api/database/status` | Runs `SELECT 1`; accepts no SQL input and returns no business records |

`/api/database/status` returns **200** `{"database":"mysql","status":"connected"}`, or **503** with `not_configured` (the `cloudsql` profile is not active) or `unavailable` (query/connection failed; driver details are deliberately omitted). A **404** means an old backend image is still serving traffic.

### Emails and attachments

| Method and path | Description |
|---|---|
| `GET /api/emails` | JSON array of emails in numeric ID order with source fields and attachment counts; includes emails with no attachments. Empty database returns an empty array |
| `GET /api/emails/{id}` | Email body and ordered attachment metadata |
| `GET /api/emails/{id}/attachments/{filename}` | Raw attachment download from MySQL. Ownership is validated against the email first |
| `PUT /api/emails/{id}/attachments/{filename}` | Store raw bytes (`Content-Type: application/octet-stream`, max 5 MB). The reference must already belong to the email, otherwise 404 |
| `GET /api/emails/{id}/attachments/{filename}/text` | Deterministic text extraction ([section 12](#12-attachment-text-extraction)) |

Missing/unregistered files, unknown emails and cross-email attachment requests return **404**. Database failures return **503** with a generic error and never fall back to fixture data or expose credentials or query details.

### Workflow, extraction and review

| Method and path | Description |
|---|---|
| `GET /api/emails/{id}/workflow` | Current revision, category, extracted fields and review history |
| `PUT /api/emails/{id}/extraction` | Save an extraction |
| `POST /api/emails/{id}/reviews` | Record an approve/flag decision |
| `POST /api/gemini/process/{id}` | Run the Gemini classification/extraction pipeline and persist the result |

**`PUT /api/emails/{id}/extraction`**

```json
{
  "model": "source/model",
  "category": "DOCUMENT_COMPARISON",
  "expectedRevision": "none",
  "fields": [
    { "key": "shipper", "si": "...", "bl": "...", "siEvidence": null, "blEvidence": null }
  ]
}
```

Read the current revision first; use `"none"` only before any extraction or attachment update. Include exactly the seven field keys. `null` SI/BL values mean missing information; missing evidence stays `null`.

**`POST /api/emails/{id}/reviews`**

```json
{ "revision": "current revision", "decision": "APPROVE", "reviewer": "Your name", "note": "Reason" }
```

`decision` is `APPROVE` or `FLAG`. Approval requires complete SI and BL values. Decisions never overwrite extracted evidence. A stale revision returns **409**.

**`POST /api/gemini/process/{id}`** replaces the old `GET` endpoint because processing writes data and invokes Gemini. It loads source and attachment data from the database, then saves the classification and extraction. AI failure returns **502** (no saved result is claimed); database failure returns **503**. Missing, ambiguous or unreadable documents leave fields `null` for human review. The extractor does not return evidence snippets, so evidence stays unavailable rather than invented.

### Import and export

See [section 13](#13-email-import-and-export).

## 12. Attachment text extraction

`GET /api/emails/{id}/attachments/{filename}/text` returns clean source text for the Gemini pipeline. The attachment must belong to the requested email; missing files, unknown emails and cross-email requests return 404. It does not classify documents or invent missing values.

| Status | Meaning |
|---|---|
| `OK` | Text extracted successfully |
| `EMPTY` | Zero bytes or no text content |
| `UNSUPPORTED` | Extraction not implemented for this file type |
| `UNREADABLE` | Supported type but parsing failed, or a PDF has no extractable text and may need OCR/human review |

Supported formats:

- **TXT**: strict UTF-8 decoding
- **PDF**: Apache PDFBox
- **DOCX**: Apache POI
- **XLSX**: Apache POI; worksheet cells are flattened into tab-separated text

The raw download endpoint (`GET /api/emails/{id}/attachments/{filename}`) is unchanged.

## 13. Email import and export

Use the frontend's **Import / Export** tab. This is a structured-data feature, separate from document-file uploads and AI processing.

### Import

- Choose JSON files in the participant email format and click **Import email data**.
- Accepts one email, an array of emails, or `{ "emails": [...] }`.
- Required fields: `email_id`, `from`, `subject`, `body`, `attachments`.
- IDs are 1–64 letters, numbers, underscores or hyphens. Use `[]` for no attachments. Empty subject/body strings are valid; a blank sender is invalid.
- Limits: 5 MB combined, 2,000 records, 50 attachment references per email.
- Every record is validated before insertion. Missing required fields, or duplicate IDs within the request, reject the whole batch. IDs already stored are skipped without overwriting.
- New records are saved in one MySQL transaction; write errors roll back the batch. There is no temporary/session mode.
- Attachments remain references under `attachments/` with an empty hash and zero size, and show as **Not uploaded** until their bytes are uploaded to database storage. Import uploads no bytes and runs no AI verification.
- Duplicate detection uses `email_id`, not message content. Validation does not judge whether the shipping documentation is complete.

### Export

- **Download JSON** exports selected saved source records, including additional source properties, as an array compatible with import. JSON is the lossless exchange format.
- **Download CSV** exports the five source fields, one email per row, with `attachments` as a JSON array in one cell. UTF-8 with BOM, quoted cells, escaped quotes, CRLF row endings; formula-like cells get a leading apostrophe for spreadsheet safety.
- Exports omit attachment contents and verification/review results (use the workflow endpoint for those) and are not the competition submission format.

The screen supports per-row checkboxes, **Select all**, and case-insensitive sender name/address search. While searching, **Select all** affects only visible matches; selections persist across searches and the count includes hidden selections. **Clear selection** resets everything. Download is disabled until at least one email is selected.

### Endpoints

```text
POST /api/emails/import                 Content-Type: application/json
GET  /api/emails/export?format=json     (all records, for existing clients)
GET  /api/emails/export?format=csv      (all records, for existing clients)
POST /api/emails/export?format=json|csv body: { "emailIds": [...] }
```

The import response contains `received`, `inserted`, `skipped` and `storage` (always `database`). Errors: **400** invalid data or empty/invalid selection, **413** oversize, **409** if any selected export ID is unavailable (no partial download), **503** database errors. These endpoints use the application's existing access model.

## 14. Frontend behavior

- The app opens directly at `/inbox`. A compact API connection indicator sits below the brand at the upper left; click it to refresh. It checks backend reachability only, not MySQL connectivity or AI availability.
- **Run Verification** invokes the persisted Gemini pipeline (`POST /api/gemini/process/{id}`).
- **Approve** and **Flag** save through the review API and update status only after the save succeeds.
- Comparison normalizes whitespace, case and numeric formatting. It is not a full business synonym or tolerance engine.
- The SI/BL badge in the inbox is a filename-based display hint, not AI classification. The source data has no received timestamp, so `Received` remains only a display label.
- The standalone ShippingAgent reads through `SHIPPING_API_URL`, not local fixture files. Its exploratory tool outputs are not saved automatically; use the web pipeline to persist a verification.

## 15. Limitations

- No application-user authentication; reviewer names are self-reported.
- Only the latest extraction is stored; review decisions are append-only.
- Extraction evidence snippets are not produced yet.
- Field comparison has no synonym or tolerance handling.
- Attachments not seeded into MySQL cannot be downloaded (fixtures are not in the runtime JAR).
- Cloud SQL is the only database; there is no offline or local fallback.
- Public IP is required for the connector-based setup as documented.

## 16. Troubleshooting

| Symptom | Check |
|---|---|
| `/api/database/status` returns `not_configured` | Cloud Run env vars; the `cloudsql` profile must be active |
| `/api/database/status` returns `unavailable` | Cloud Run logs and instance status; connection name, public IP, Cloud SQL Client role, database, username and secret value |
| `/api/database/status` returns 404 | Old image is still deployed or receiving traffic |
| Revision fails to start with a secret access error | Secret version, and Secret Accessor role on the actual runtime service account |
| Java connector reports missing credentials locally | `gcloud auth application-default login`, then set the quota project |
| `/api/emails` returns an empty array | Database is empty: run the [fixture import](#4-schema-migration-and-fixture-import) |
| Inbox fails but status is `connected` | Workflow tables are missing: run `persistence.sql` |
| Attachment shows **Not uploaded** / download 404 | Bytes were never uploaded; re-run the seed tool or `PUT` the attachment |
| `409` on extraction or review | Stale revision; read `/workflow` and retry with the current revision |
| `502` from `/api/gemini/process/{id}` | Gemini call failed; nothing was saved |
| `PERMISSION_DENIED` on Artifact Registry / Cloud Run | Read the exact error and have the project admin grant the missing scoped permission (Artifact Registry Writer, Cloud Run deploy, service-account use) |
| Port 8081 in use during Cloud Shell checks | Choose a free port and use it in both tabs |

## 17. References

- <https://docs.cloud.google.com/sql/docs/mysql/connect-run>
- <https://docs.cloud.google.com/sql/docs/mysql/create-instance>
- <https://docs.cloud.google.com/run/docs/configuring/services/secrets>
- <https://docs.cloud.google.com/run/docs/deploying>
- <https://docs.cloud.google.com/run/docs/container-contract>
- <https://docs.cloud.google.com/artifact-registry/docs/docker/pushing-and-pulling>
- <https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html>
- <https://docs.spring.io/spring-framework/reference/core/beans/environment.html>
