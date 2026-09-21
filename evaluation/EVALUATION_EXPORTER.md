# Evaluation exporter

This folder contains a strict exporter for the optional SDOC self-evaluation format.
It does **not** contain or use organizer ground truth.

The official participant format is one JSON object keyed by `email_id`. Every record contains exactly:

- `category`
- `status`
- `review_reason`
- `defect_fields`
- `has_defect`

The exporter uses the participant `sample_submission.json` only as the source of required email IDs and output shape. It refuses to write a submission if an email is missing, an unexpected email is present, or a comparison result contains an invalid category/status/reason/field.

## Accepted input

The team's pipeline can supply either:

1. an object keyed by email ID, or
2. an array of objects containing `email_id`.

For non-`BL_COMPARISON` categories, only classification is required by the participant guide. The exporter therefore writes the remaining fields as `OK`, `null`, `[]`, and `false` to match the sample format.

For `BL_COMPARISON`:

- `OK`: no defect fields and no review reason.
- `MISMATCH`: at least one of the seven allowed `defect_fields`; exporter writes `has_defect: true`.
- `NEEDS_REVIEW`: requires one of `wrong_doc_type`, `missing_attachment`, `unreadable`, `missing_value`; exporter writes no defect fields and `has_defect: false`.

## Test the exporter

From the repository root:

```powershell
cd evaluation
python -m unittest -v test_export_submission.py
cd ..
```

## Full 520-email format smoke test

Until the team's verification pipeline produces its own results, the participant `sample_submission.json` can be used only to test exporter structure/coverage:

```powershell
python evaluation/export_submission.py `
  --results backend/src/main/resources/data/bundle/sample_submission.json `
  --template backend/src/main/resources/data/bundle/sample_submission.json `
  --output evaluation/submission-format-smoke-test.json
```

Expected:

```text
PASS: wrote 520 unique email results ...
PASS: each record contains exactly: category, status, review_reason, defect_fields, has_defect
```

That smoke-test file is **not an evaluated prediction**; all values come from the provided format template. Delete it after testing or leave it uncommitted.

## Export real pipeline results later

When the classification/comparison pipeline can produce all 520 results:

```powershell
python evaluation/export_submission.py `
  --results path/to/pipeline-results.json `
  --template backend/src/main/resources/data/bundle/sample_submission.json `
  --output evaluation/submission.json
```

Only `evaluation/submission.json` should then be sent to the permitted self-evaluation mechanism (`POST /submit` or `Inbox.submit(...)`).
