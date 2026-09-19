# Vue frontend and Firebase Hosting starter

This is a real connectivity page, not the full shipping verification interface.
It calls /api/status and displays the live Spring Boot response or a retryable
error. It contains no Gemini keys, fabricated shipment data, or database secrets.

## Project setup

In https://console.firebase.google.com/, add Firebase to the existing Google
Cloud project hackathon-509104. If it is already listed, open it. Select Hosting
under Build (not App Hosting). Analytics is optional. Use the same Google account
as your Cloud Run project. Billing must be linked for the Cloud Run integration.

Both firebase.json and .firebaserc are already initialized. Do not run firebase
init over these files: a default SPA rewrite can replace the API routing.
You do not need the Firebase JavaScript SDK or a firebaseConfig object for this
Hosting-only frontend.

## Deploy using Cloud Shell (Bash)

Upload output/frontend-source.zip from your computer using the Cloud Shell
Upload file menu. Upload to your Cloud Shell home directory, then run:

```bash
cd ~
unzip frontend-source.zip -d shipping-frontend-v1
cd shipping-frontend-v1/frontend
node --version
npm --version
```

Use Node 24 LTS. If Node is older than the project's requirement and nvm is
available in Cloud Shell, use `nvm install 24` followed by `nvm use 24`.

```bash
npm ci
npm run build
firebase --version
```

If firebase is unavailable, install it with `npm install -g firebase-tools`.
Cloud Shell usually supplies authentication. Check project access:

```bash
firebase projects:list
```

If it asks you to log in, run `firebase login --no-localhost` and follow the
browser instructions using the Google account that owns the project.
Confirm hackathon-509104 is listed, then deploy:

```bash
firebase deploy --only hosting --project hackathon-509104
```

Open the Hosting URL printed by the CLI. The connection check should show
Backend connected and the JSON from shipping-api. If the default Hosting site
does not exist yet, finish Hosting setup in the Firebase console and retry.

## How routing works

Browser GET / -> Firebase Hosting serves dist/index.html and Vue assets.
Browser GET /api/status -> Hosting forwards the request to Cloud Run service
shipping-api in asia-southeast1. The path is preserved. The API rewrite must
precede the SPA fallback. This is a same-origin request in the browser, so the
backend does not need permissive CORS for this integration.

The Cloud Run service name, region and Google Cloud project must match the
backend deployment. If yours differ, update firebase.json and .firebaserc.
The current status-only Cloud Run service must allow public invocation. The
rewrite is routing, not authentication: add user authorization when building
document and paid AI endpoints.

Firebase Hosting's backend rewrite has a 60-second timeout. Use a task/status
workflow when introducing processing that may exceed that limit.

## Local development (Windows PowerShell)

Requires a normal Node installation with npm. In one terminal, start the backend
on port 8081 using the backend README. In another:

```powershell
cd C:\Users\111\Desktop\hackerthon\frontend
npm.cmd ci
npm.cmd run dev
```

Open the URL Vite prints. vite.config.js proxies /api to localhost:8081 during
development. `npm run preview` only serves the static build and does not emulate
Firebase-to-Cloud-Run routing; use the deployed site to verify that integration.

## Troubleshooting

- Expected JSON / received HTML: check the API rewrite is above the SPA fallback.
- HTTP 403: check Cloud Run invocation access and project permissions.
- HTTP 404: check service name, region and /api/status on the backend.
- Timeout: retry after the container starts, then check Cloud Run logs.
- Default Firebase welcome page: ensure public is dist, run npm run build, and
  deploy from this frontend folder.

## References

- https://firebase.google.com/docs/hosting/quickstart
- https://firebase.google.com/docs/hosting/cloud-run
- https://vuejs.org/guide/quick-start
