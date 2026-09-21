# Ship AI Verifier — Shipping Document Verification System

> **Averis x Monash Hackathon 2026**  
> *AI-Assisted Automated Email Triage, Shipping Document Extraction, Discrepancy Detection & Human-in-the-Loop Verification Pipeline.*

---

## 📌 Project Overview

Logistics and shipping operations teams handle large volumes of emails daily containing transport paperwork. Operations personnel must manually review incoming messages, separate routine operational queries from document verification requests, and compare **Shipping Instructions (SI)** against draft **Bills of Lading (BL)**.

Manual document verification across various layouts, terminology differences (e.g., "Port of Loading" vs. "Load Port"), and unstructured formats is time-consuming, repetitive, and vulnerable to human error. Unnoticed discrepancies lead to costly cargo delays, administrative re-work, and legal liabilities.

**Ship AI Verifier** automates inbox triage and document checking. It intelligently classifies incoming emails, extracts structured shipment metrics using **Google Gemini AI**, normalizes field values, detects discrepancies across seven core shipment fields, and provides an intuitive web interface with evidence-backed human-in-the-loop oversight.

---

## ✨ Key Capabilities

1. **Intelligent Email Classification**:
   * Automatically sorts incoming inbox emails into 5 distinct categories:
     * `Document-Comparison Request` (triggers verification pipeline)
     * `New SI Request`
     * `Invoice Query`
     * `General Operational Update`
     * `Spam`

2. **Document Field Extraction & Normalization**:
   * Extracts text and structured data from SI and BL attachments (plain text, Word, PDF, and scanned documents).
   * Normalizes variations in labels, formatting, and unit representations across documents.

3. **Core 7-Field Comparison**:
   * Performs side-by-side discrepancy checks on the 7 required shipment fields:
     * **Shipper Name**
     * **Consignee Name**
     * **Notify Party**
     * **Port of Loading (POL)**
     * **Port of Discharge (POD)**
     * **Container Count**
     * **Gross Weight (kg)**

4. **Actionable Verification & Evidence-Backed Review**:
   * Generates tri-state verification outcomes: `OK` (No mismatch detected), `MISMATCH`, or `NEEDS_REVIEW`.
   * Displays extracted SI values, BL values, and exact source text snippets side-by-side.
   * Escalates missing, unreadable, or ambiguous documents to human reviewers without failing silently.

---

## 🏗️ System Architecture & Tech Stack

```
[ Incoming Emails & Attachments ]
              │
              ▼
    [ Vue 3 Frontend UI ] ◄────── (Firebase Hosting)
              │
              ▼
  [ Spring Boot REST API ] ◄────── (Google Cloud Run)
        │           │
        │           ├─► [ Google Gemini AI ] (Classification & Extraction)
        │           │
        │           ├─► [ Cloud Storage ] (Raw SI / BL Files)
        │           │
        └───────────┴─► [ Cloud SQL MySQL ] (Emails, Extracted JSON, Human Reviews)
```

* **Frontend**: Vue.js (Node 24), Axios, Bootstrap 5 CSS — hosted on **Firebase Hosting**.
* **Backend**: Java 25 / Spring Boot 2.5+, Spring Data JPA, Hibernate, Maven — containerized on **Google Cloud Run**.
* **Database**: MySQL on **Google Cloud SQL** (`hackathon-509104:asia-southeast1:shipping-mysql`).
* **AI & Cloud Services**: **Google Gemini API** (schema-constrained field extraction), **Google Secret Manager**, **Google Artifact Registry**.
* **CI/CD**: **GitHub Actions** (`ci.yml` PR checks and `deploy.yml` continuous deployment).

---

## 🛠️ Local Development & Quickstart

### Prerequisites
* **Java 25** (`java -version`)
* **Node.js 24 & npm** (`node --version`, `npm --version`)
* **Google Cloud SDK / gcloud CLI** (`gcloud version`)

### 1. Google Cloud Authentication
Authenticate your terminal session with GCP project permissions:
```bash
gcloud auth login
gcloud config set project hackathon-509104
gcloud auth application-default login
gcloud auth application-default set-quota-project hackathon-509104
```

### 2. Backend Setup (Spring Boot)
Open a terminal in `backend/` and load the database secret before starting the server:

* **On macOS / Linux (zsh/bash)**:
  ```bash
  cd backend
  DB_PASS=$(gcloud secrets versions access latest --secret=shipping-db-password --project=hackathon-509104)
  
  SPRING_PROFILES_ACTIVE=cloudsql   INSTANCE_CONNECTION_NAME="hackathon-509104:asia-southeast1:shipping-mysql"   DB_NAME="shipping_db"   DB_USER="shipping_app"   SPRING_DATASOURCE_PASSWORD="${DB_PASS}"   ./mvnw spring-boot:run
  ```

* **On Windows (PowerShell)**:
  ```powershell
  cd backend
  $env:PORT = "8081"
  $env:SPRING_PROFILES_ACTIVE = "cloudsql"
  $env:INSTANCE_CONNECTION_NAME = "hackathon-509104:asia-southeast1:shipping-mysql"
  $env:DB_NAME = "shipping_db"
  $env:DB_USER = "shipping_app"
  $dbPassword = gcloud secrets versions access latest --secret=shipping-db-password --project=hackathon-509104
  $env:SPRING_DATASOURCE_PASSWORD = $dbPassword
  .\mvnw.cmd spring-boot:run
  ```

The backend REST API will run locally at `http://localhost:8081/`.

### 3. Frontend Setup (Vue.js)
In a separate terminal window, initialize and start the Vue client:
```bash
cd frontend
npm ci
npm run dev
```
The Vue application will run locally at `http://localhost:5173` or `http://localhost:8081`.

---

## 📡 API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/status` | Backend operational status & health check. |
| `GET` | `/api/inbox` | Retrieves all parsed email records and metadata. |
| `GET` | `/api/emails/{id}` | Retrieves specific email details, extracted SI/BL fields, and verification status. |
| `POST` | `/api/verify` | Triggers document extraction & discrepancy detection engine. |
| `POST` | `/api/review` | Saves human reviewer approval or override decisions to MySQL. |
| `POST` | `/submit` | Benchmark evaluation endpoint for dataset validation. |

---

## 🧪 Evaluation & Test Bench (`loader.py`)

To evaluate the pipeline against the dataset reference benchmark:
1. Run local testing via `loader.py`:
   ```python
   from loader import Inbox

   inbox = Inbox("data")  # Or Inbox("http://localhost:8080")
   for email in inbox:
       print("Processing Email ID:", email["id"], email["subject"])
       if "attachments" in email and email["attachments"]:
           doc_text = inbox.read_text(email["attachments"][0])
           print("Attachment Snippet:", doc_text[:100])
       break
   ```
2. Format extracted results according to `sample_submission.json`.
3. Post predictions to `POST /submit` or via `inbox.submit(...)` to generate accuracy scores.

---

## 🚀 Cloud Deployment & CI/CD

### Automated Continuous Deployment
Pushes to the `main` branch trigger `.github/workflows/deploy.yml`, which runs unit/build checks and deploys:
* **Spring Boot API** to **Google Cloud Run** (`shipping-api`).
* **Vue Frontend** to **Firebase Hosting** (`hackathon-509104.web.app`).

### Manual Fallback & Operations
For detailed manual fallback deployment commands and Cloud Run/Firebase rollback runbooks across macOS, Linux, and Windows, see [`DEPLOYMENT.md`](./DEPLOYMENT.md).

---

## 👥 Team Roles & Responsibilities

* **Member 1**: Email Triage & Classification Engine (AI Prompting & Parsing).
* **Member 2**: Document Information Extraction & Normalization.
* **Member 3**: Discrepancy Comparison Engine & Rule Set Logic.
* **Member 4**: Vue.js Frontend UI & Human Reviewer Workflow Interface.
* **Member 5**: Cloud Infrastructure Delivery, CI/CD Pipeline, QA Coordination & Submission.

---

## 📄 License & Attribution

Developed for **Averis x Monash Hackathon 2026** by team participants from Monash University Malaysia.
