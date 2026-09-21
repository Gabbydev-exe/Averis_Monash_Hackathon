
# Deployment & Operations Guide

## 1. Manual Fallback Deployment

If GitHub Actions is blocked or experiencing delays, deploy manually using the CLI.

### Backend (Google Cloud Run)

#### On macOS / Linux

```bash
cd backend
DB_PASS=$(gcloud secrets versions access latest --secret=shipping-db-password --project=hackathon-509104)

gcloud run deploy shipping-api \
  --source . \
  --region asia-southeast1 \
  --project hackathon-509104 \
  --platform managed \
  --allow-unauthenticated \
  --add-cloudsql-instances hackathon-509104:asia-southeast1:shipping-mysql \
  --remove-secrets DB_PASSWORD \
  --set-env-vars SPRING_PROFILES_ACTIVE=cloudsql,INSTANCE_CONNECTION_NAME=hackathon-509104:asia-southeast1:shipping-mysql,DB_NAME=shipping_db,DB_USER=shipping_app,SPRING_DATASOURCE_PASSWORD="${DB_PASS}"
```

#### On Windows (PowerShell)

```powershell
cd backend
$dbPassword = gcloud secrets versions access latest --secret=shipping-db-password --project=hackathon-509104

gcloud run deploy shipping-api `
  --source . `
  --region asia-southeast1 `
  --project hackathon-509104 `
  --platform managed `
  --allow-unauthenticated `
  --add-cloudsql-instances hackathon-509104:asia-southeast1:shipping-mysql `
  --remove-secrets DB_PASSWORD `
  --set-env-vars SPRING_PROFILES_ACTIVE=cloudsql,INSTANCE_CONNECTION_NAME=hackathon-509104:asia-southeast1:shipping-mysql,DB_NAME=shipping_db,DB_USER=shipping_app,SPRING_DATASOURCE_PASSWORD=$dbPassword
```

---

### Frontend (Firebase Hosting)

#### On macOS / Linux

```bash
cd frontend
npm ci
npm run build
firebase deploy --only hosting --project hackathon-509104
```

#### On Windows (PowerShell / CMD)

```powershell
cd frontend
npm.cmd ci
npm.cmd run build
firebase deploy --only hosting --project hackathon-509104
```

---

## 2. Emergency Rollback Process

### Cloud Run (Backend)

1. List previous deployment revisions:
   ```bash
   gcloud run revisions list --service shipping-api --region asia-southeast1 --project hackathon-509104
   ```
2. Route 100% of traffic back to the previous stable revision:
   ```bash
   gcloud run services update-traffic shipping-api \
     --region asia-southeast1 \
     --project hackathon-509104 \
     --to-revisions <PREVIOUS_REVISION_NAME>=100
   ```

### Firebase Hosting (Frontend)

1. Open the [Firebase Hosting Console](https://console.firebase.google.com/project/hackathon-509104/hosting/sites).
2. Locate the **Release History** section.
3. Click the three dots next to the previous working deployment and select **Rollback**.
