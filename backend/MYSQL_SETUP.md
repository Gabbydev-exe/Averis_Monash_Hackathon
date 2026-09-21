# Initialize Cloud SQL for MySQL and connect Spring Boot

Project: hackathon-509104. Region: asia-southeast1 (Singapore).
Cloud Run service: shipping-api. Database engine: MySQL.

These steps create billable resources. Review the console's cost estimate before
creating the instance. A running Cloud SQL instance can incur charges even when
Cloud Run scales to zero. No cloud resources were created by preparing this file.

## 1. Create the instance

In Google Cloud Console, select the project, then SQL > Create instance > MySQL.
Select Enterprise edition and MySQL 8.4. Use instance ID shipping-mysql, region
Singapore, single-zone availability, the smallest development machine offered
(shared core if available), and 10 GB SSD or the minimum offered. Avoid accepting
an Enterprise Plus production preset for this prototype. Keep automated backups
enabled. Review storage auto-growth and its limit in the cost estimate.

Set a strong root administrator password and save it privately. Enable public IP
for this connector-based setup, leave authorized networks empty, and select
Google-managed per-instance CA if asked. The Java connector authenticates with
Google IAM and encrypts the connection; no 0.0.0.0/0 network rule is needed.

Once ready, copy the connection name from Overview. Expected value:
hackathon-509104:asia-southeast1:shipping-mysql

## 2. Create the database and a limited application user

Inside the instance, open Databases > Create database. Name it shipping_db and
use utf8mb4. Then open Cloud SQL Studio and sign in as root. Choose shipping_db.
Run the following privately, replacing the placeholder with a separate strong
application password. Do not save the completed SQL in Git or send the password
in chat. This is a one-time user creation step; do not rerun for an existing user.

```sql
CREATE USER 'shipping_app'@'%' IDENTIFIED BY 'REPLACE_WITH_PRIVATE_APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON shipping_db.* TO 'shipping_app'@'%';
```

The application user is not root and cannot change the schema. Use an administrator
or a separate migration identity for future table migrations. There are no domain
tables yet. SELECT 1 verifies connectivity, not shipment persistence.

## 3. Store the application password in Secret Manager

In Cloud Shell (Bash), enable APIs:

```bash
gcloud config set project hackathon-509104
gcloud services enable sqladmin.googleapis.com secretmanager.googleapis.com
```

In the Google Cloud console, open Secret Manager > Create secret:

- Name: shipping-db-password
- Secret value: the exact shipping_app password, without a trailing newline
- Create the secret and note its version number (normally 1)

Use the application password, not the root password. Secret values never belong
in frontend code, Docker images, Git, or the ordinary environment-variable editor.

## 4. Grant the existing Cloud Run identity access

In Cloud Shell, find the service account used by shipping-api:

```bash
RUN_SA=$(gcloud run services describe shipping-api --region=asia-southeast1 --project=hackathon-509104 --format='value(spec.template.spec.serviceAccountName)')
echo "$RUN_SA"
```

Confirm this prints the runtime service account email; if empty, inspect the
service Security settings before proceeding. Grant database connection access:

```bash
gcloud projects add-iam-policy-binding hackathon-509104 --member="serviceAccount:$RUN_SA" --role=roles/cloudsql.client
gcloud secrets add-iam-policy-binding shipping-db-password --project=hackathon-509104 --member="serviceAccount:$RUN_SA" --role=roles/secretmanager.secretAccessor
```

The first role authorizes the connector. The MySQL username/password and SQL
grants separately authorize database access. The secret role is scoped to this
one secret. No downloaded Google service-account key is required.

## 5. Publish this backend version

On your Windows computer, from C:\Users\111\Desktop\hackerthon\backend:

```powershell
git add backend
git diff --cached --name-only
```

Review the staged files. Other frontend changes were already staged when this
guide was prepared; commit only the changes you intend to include. Then commit
and push through your team's usual branch/PR workflow.

After the changes reach main, in your Cloud Shell Git checkout:

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

If you have not cloned the repo in Cloud Shell yet, clone your GitHub repository
into ~/shipping-repo first. Run each command only after the previous succeeds.

## 6. Deploy with the cloudsql profile and password reference

Use the actual connection name from step 1 and secret version from step 3.
The command below assumes the documented names and secret version 1:

```bash
gcloud run deploy shipping-api \
  --project=hackathon-509104 \
  --region=asia-southeast1 \
  --image="$IMAGE" \
  --update-env-vars="SPRING_PROFILES_ACTIVE=cloudsql,INSTANCE_CONNECTION_NAME=hackathon-509104:asia-southeast1:shipping-mysql,DB_NAME=shipping_db,DB_USER=shipping_app" \
  --update-secrets="DB_PASSWORD=shipping-db-password:1"
```

This preserves the existing service identity and invocation policy. The Java
connector connects directly; you do not also need to add a Cloud SQL Unix socket
attachment or set --add-cloudsql-instances. Public IP must be enabled on the
instance. Private-IP-only instances instead require a VPC network path and an
explicit connector configuration change.

Console alternative: Cloud Run > shipping-api > Edit & deploy new revision.
Choose the new image. Add the four environment variables above, then add a
secret reference named DB_PASSWORD to shipping-db-password, version 1. Deploy.

## 7. Verify the actual cloud connection

Open /api/database/status on your Cloud Run service URL, or on your Firebase
Hosting URL (the existing /api/** rewrite forwards it).

Successful HTTP 200 response:

```json
{"database":"mysql","status":"connected"}
```

This response is returned only after the backend executes SELECT 1 on MySQL.
The existing /api/status endpoint still checks only application availability.
The new endpoint does not accept SQL input or return stored business records.

HTTP 503 statuses:

- not_configured: cloudsql profile is not active. Check Cloud Run env vars.
- unavailable: query/connection failed. Inspect Cloud Run logs and instance status;
  verify the instance connection name, public IP, Cloud SQL Client role, database,
  username, and secret value. The response deliberately omits driver details.
- HTTP 404: the old backend image is still deployed or receiving traffic.
- Revision fails to start with secret access error: check secret version and the
  actual runtime service account's Secret Accessor role.

## Local development

The default profile is cloudsql and a database is required. There is no no-db
fallback. Apply [PERSISTENCE.md](PERSISTENCE.md) before running this version.
A developer with gcloud installed can use cloudsql locally after:

```powershell
gcloud auth application-default login
gcloud auth application-default set-quota-project hackathon-509104
```

That developer account also needs Cloud SQL Client and Service Usage Consumer
on the project. Set the same four nonsecret variables in your IDE run configuration
and supply DB_PASSWORD privately. Never store passwords in a shared .idea run
configuration. Cloud Run obtains credentials from its attached service account.

The pool is limited to five connections per app instance. Keep Cloud Run maximum
instances small for the prototype, and size database connections as traffic grows.

## References

- https://docs.cloud.google.com/sql/docs/mysql/connect-run
- https://docs.cloud.google.com/sql/docs/mysql/create-instance
- https://docs.cloud.google.com/run/docs/configuring/services/secrets
