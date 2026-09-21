# Email API integration with the existing MySQL database

## Scope and behavior

The existing `cloudsql` profile now enables `EmailRepository`. `EmailDataService`
queries it on each request instead of reading/caching inbox JSON. The default
`no-db` profile keeps the existing bundled JSON workflow. Do not activate both
profiles together: `no-db` excludes datasource auto-configuration.

- `GET /api/emails`: unchanged JSON array contract, numeric email ordering,
  current database source fields and attachment counts; includes emails with no attachments.
- `GET /api/emails/{id}`: current database email body and ordered attachment metadata.
- `GET /api/emails/{id}/attachments/{filename}`: first validates ownership using
  the selected email's metadata, then reads the registered path in the bundled resources.
  Missing/unregistered files and cross-email attachment requests return 404.
- Database query failures return HTTP 503 with a generic error, without falling
  back to bundled emails or exposing credentials/query details.
- Database mode uses `pending` for source records and seven pending comparison
  fields. It does not yet consume the processing/review tables. The existing optional
  JSON report overlay is retained in no-db mode only.
- No schema changes, imports, cloud resource changes, deployment, or frontend edits.

The 250 attachment files remain packaged in the backend. Null `gcs_uri` values
are therefore acceptable for this stage. Cloud Storage retrieval is separate work.
The filename-based SI/BL badge remains a display hint; it is not AI classification.
There is no received timestamp in the source data; `Received` remains the display label.

## Tests

Run from `backend` in Windows PowerShell, with JDK 21 or newer:

```powershell
.\mvnw.cmd test
```

Tests use isolated H2 databases in MySQL compatibility mode. H2 is a test-only
dependency, not a replacement for the production MySQL database. Tests exercise
actual JdbcTemplate queries, stored Unicode/text, email/attachment relationships,
attachment order, missing IDs, parameter binding, database changes after startup,
empty databases, file access and HTTP 503 handling. Existing no-db tests remain.
Profile tests verify that cloudsql creates the repository and no-db does not.
These tests do not require your real MySQL password.

Validation in the preparation environment:
- Confirmed 520 unique dataset email IDs and 250 attachment references.
- All attachment bytes/sizes/SHA-256 hashes match the database package's manifest.
- Reviewed queries against the exact schema.sql from database.rar.
- Maven could not resolve the existing Spring Boot 4.1.1 parent because this
  environment cannot resolve repo.maven.apache.org. Compilation/JUnit tests and
  live Cloud SQL verification have NOT been completed. The dependency versions
  already in the team's project were not changed. Run tests before merging.

## Run with Cloud SQL in Cloud Shell

This starts a temporary app in Cloud Shell to verify the integration. It does not
update the deployed Cloud Run service. Use the supplied `backend-source.zip` from
this integration package, which contains the backend with the changes applied.
Upload `backend-source.zip` using Cloud Shell's Upload menu.

In the first Cloud Shell Bash tab, run:

```bash
cd ~
check_dir=$(mktemp -d "$HOME/shipping-email-check.XXXXXX")
unzip -q backend-source.zip -d "$check_dir"
cd "$check_dir/backend"
java -version
bash mvnw -B test
```

Require JDK 21+ and BUILD SUCCESS before proceeding. If tests fail, retain the
error output and stop. The original backend targets Java 21.

Then in the same tab:

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

Enter the private **shipping_app password**, not the root or Google password.
Do not paste the password into a script, chat, or Git. If you do not have it, ask
the teammate who configured the backend. Cloud Shell already provided working
Application Default Credentials for your MySQL proxy. If the Java connector
reports missing credentials, run `gcloud auth application-default login` in this
Cloud Shell session, then retry. Your identity needs Cloud SQL Client access;
SQL grants for shipping_app are separate from Google IAM permissions.

The log should include `Email API uses MySQL; bundled inbox JSON is not loaded`
and then the normal application-started message. Startup alone does not prove
connectivity because the existing pool is intentionally lazy.

Open a second Cloud Shell terminal tab and run:

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
curl -s -o /dev/null -w 'Missing email HTTP %{http_code}\n' http://localhost:8081/api/emails/email_missing
curl -s -o /dev/null -w 'Wrong owner HTTP %{http_code}\n' http://localhost:8081/api/emails/email_003/attachments/email_004_SI.txt
```

Expect `connected`, the PASS message, email_004 with two attachments and seven
pending fields, SI text, and 404 for both final requests. If data has deliberately
changed since import, compare against updated SQL counts. On any unexpected
result stop and inspect the error rather than proceeding to deployment.

Press Ctrl+C in the first tab when done and run `unset DB_PASSWORD`.
If port 8081 is already in use, use a free port consistently in both tabs.

## Team handoff

After local tests and live Cloud SQL checks pass, review the patch on a feature
branch and use the team's normal pull request process. The existing main-branch
workflow automatically deploys after merge; this package does not trigger it.
Do not re-run schema.sql or seed.sql to install this code.

The current Vue Run Verification / Approve / Flag actions change browser state
only. They do not invoke Gemini or save to MySQL. This integration does not change
those buttons; persistence and AI verification remain separate team tasks.

Implementation references:
- https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html
- https://docs.spring.io/spring-framework/reference/core/beans/environment.html
