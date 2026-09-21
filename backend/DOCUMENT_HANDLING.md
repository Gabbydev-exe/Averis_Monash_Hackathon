# Attachment text handling

The backend exposes deterministic attachment text extraction at:

`GET /api/emails/{id}/attachments/{filename}/text`

The attachment must first belong to the requested email. Missing files, unknown emails, and cross-email attachment requests still return HTTP 404.

The JSON response uses one of these statuses:

- `OK`: text was extracted successfully.
- `EMPTY`: the attachment has zero bytes or no text content.
- `UNSUPPORTED`: text extraction is not implemented for the file type.
- `UNREADABLE`: the file type is supported but parsing failed, or a PDF contains no extractable text and may require OCR/human review.

Supported text extraction formats in this stage:

- TXT — strict UTF-8 decoding
- PDF — Apache PDFBox
- DOCX — Apache POI
- XLSX — Apache POI; worksheet cells are flattened into tab-separated text for downstream extraction

The original raw attachment download endpoint remains unchanged:

`GET /api/emails/{id}/attachments/{filename}`

This document reader is intended to provide clean source text to the later Gemini classification/extraction pipeline. It does not classify documents or invent missing values.
