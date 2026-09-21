# Email data import and export

Open **Import / Export** in the frontend. This is a structured-data feature,
separate from document-file uploads and AI processing.

- Choose JSON files in the participant email format, then click **Import email data**.
- Accepts one email, an array of emails, or `{ "emails": [...] }`.
- Required fields: `email_id`, `from`, `subject`, `body`, `attachments`.
- IDs use 1–64 letters, numbers, underscores or hyphens. Use `[]` for no attachments.
- Maximum combined import: 5 MB / 2,000 records / 50 attachment references per email.
- Every record is validated before insertion. Duplicate IDs within the request are
  rejected; IDs already stored are skipped without overwriting existing records.
- All imports save new records in one MySQL transaction; write errors roll back the batch.
  There is no temporary/session mode. Apply the migration in [PERSISTENCE.md](PERSISTENCE.md) before deployment.
- Attachments remain references under `attachments/`. They use an empty hash and
  zero size until their actual bytes are uploaded to database storage, and appear
  as **Not uploaded** until then.
  No attachment bytes are uploaded and no AI verification runs are created.

**Download JSON** exports the selected saved source records, including additional source
properties, as an array compatible with import. **Download CSV** exports the five
source fields, one email per row, with `attachments` encoded as a JSON array in
one cell. CSV uses UTF-8 with a BOM, quoted cells, escaped quotes, and CRLF row
endings. Formula-like cells receive a leading apostrophe for spreadsheet safety;
JSON is the lossless data exchange format. Exports omit attachment file contents
and verification/review reports. They are not the competition submission format.

API:

```text
POST /api/emails/import              Content-Type: application/json
GET  /api/emails/export?format=json
GET  /api/emails/export?format=csv
```

The POST response contains `received`, `inserted`, `skipped`, and `storage`
(always `database`). Invalid data returns 400, oversize data 413, and database
errors 503. These endpoints use the application's existing access model.

The application now opens directly at `/inbox`. API connection status appears
below the brand at the upper left; click it to refresh. It checks backend
reachability, not MySQL connectivity or AI availability.

## Selecting exports
The Import / Export screen supports individual checkboxes, Select all, and case-insensitive sender name/address search. While searching, Select all matches affects only visible matches. Selections remain when the search changes; the selected count includes hidden selections. Clear selection resets everything. Download is disabled until at least one email is selected.

POST /api/emails/export?format=json (or csv) accepts a JSON object with an emailIds array. Empty/invalid selections return 400; unavailable selected IDs return 409 instead of a partial download. GET export remains available for existing clients that export all records.

Duplicate detection uses email_id, not message content. Existing IDs are skipped without updates; repeated IDs inside one import reject that batch. Missing required fields reject the entire batch. Empty subject/body strings and attachments: [] are valid; a blank sender is invalid. This import validation does not determine whether the shipping documentation is complete.

