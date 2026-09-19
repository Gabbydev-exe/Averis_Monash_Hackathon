# Shipping API deployment starter

This starter verifies Java-to-container-to-Cloud Run deployment. It does not yet
implement Gemini, document comparison, authentication, or PostgreSQL.

## Local Java run (PowerShell)

Requires JDK 25. Maven is downloaded by the included wrapper.

```powershell
cd C:\Users\111\Desktop\hackerthon\backend
.\mvnw.cmd verify
$env:PORT = "8081"
.\mvnw.cmd spring-boot:run
```

Open http://localhost:8081/api/status and http://localhost:8081/actuator/health.
Port 8081 avoids the supplied dataset server's port 8080. Press Ctrl+C to stop.

## Build and upload with Google Cloud Shell (Bash)

The provided backend-source.zip contains only this backend. In Google Cloud
Console, select project hackathon-509104 and activate Cloud Shell (>_).
Use its Upload file menu to upload backend-source.zip into your home directory.

Run each command separately and proceed only when it succeeds. These commands
create cloud resources and may incur charges. Billing must be linked.

```bash
unzip backend-source.zip -d shipping-starter-v1
cd shipping-starter-v1/backend
gcloud config set project hackathon-509104
gcloud services enable artifactregistry.googleapis.com run.googleapis.com
gcloud artifacts repositories list --location=asia-southeast1
```

If shipping-images does not already exist IN SINGAPORE, create it:

```bash
gcloud artifacts repositories create shipping-images --repository-format=docker --location=asia-southeast1 --description="Shipping API container images"
```

An existing shipping-images repository in africa-south1 is separate. Do not
delete it; the following commands use the Singapore repository.

```bash
gcloud auth configure-docker asia-southeast1-docker.pkg.dev
IMAGE="asia-southeast1-docker.pkg.dev/hackathon-509104/shipping-images/shipping-api:v1"
docker build --platform linux/amd64 -t "$IMAGE" .
docker run --rm -d --name shipping-api-check -p 8080:8080 "$IMAGE"
docker logs shipping-api-check
```

Once the logs say the application started, verify both endpoints:

```bash
curl --fail http://localhost:8080/api/status
curl --fail http://localhost:8080/actuator/health
docker stop shipping-api-check
docker push "$IMAGE"
```

If port 8080 is busy, use -p 8081:8080 and port 8081 in curl.
Do not paste the commands starting with docker run again while that container
is already running. Use docker logs to inspect startup errors.

## Deploy through the console

1. Open Artifact Registry > shipping-images in asia-southeast1; confirm the
   shipping-api image and v1 tag are present.
2. Open Cloud Run > Deploy container (Create service). If shipping-api already
   exists, open it and choose Edit & deploy new revision instead.
3. Select the image or paste:
   asia-southeast1-docker.pkg.dev/hackathon-509104/shipping-images/shipping-api:v1
4. Service shipping-api; Singapore (asia-southeast1); port 8080; 1 vCPU; 1 GiB;
   request-based billing; minimum instances 0; maximum instances 2.
5. Allow public access for this non-sensitive status-only starter. Add application
   authentication before exposing documents or paid AI processing.
6. Create/deploy, wait for readiness, then open the service URL and
   /actuator/health. Expect a JSON status response and {"status":"UP"}.

This image listens on 0.0.0.0 and honors Cloud Run's PORT environment variable.
Use a new tag such as v2 for the next image you build and push.

## Permissions and failures

Repository creation needs permission to create Artifact Registry repositories;
upload needs Artifact Registry Writer on the target repository. Deployment also
needs Cloud Run deployment permissions and permission to use its runtime service
account. If an operation reports PERMISSION_DENIED, inspect that exact error and
have the project administrator grant the missing scoped permission.

Cloud Run cannot deploy a repository URL or a tag that has not been pushed.
Keep all dataset/scoring files and credentials outside this backend build context.

## Official references

- https://docs.cloud.google.com/artifact-registry/docs/docker/pushing-and-pulling
- https://docs.cloud.google.com/run/docs/deploying
- https://docs.cloud.google.com/run/docs/container-contract
