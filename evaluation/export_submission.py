#!/usr/bin/env python3
"""Build and validate SDOC self-evaluation submission JSON.

The exporter deliberately does not contain ground truth. It only reshapes and
validates your pipeline's results against the participant sample_submission.json.
"""
from __future__ import annotations

import argparse
import json
from collections import OrderedDict
from pathlib import Path
from typing import Any

CATEGORIES = {"BL_COMPARISON", "SI_REQUEST", "INVOICE_QUERY", "GENERAL", "SPAM"}
STATUSES = {"OK", "MISMATCH", "NEEDS_REVIEW"}
REVIEW_REASONS = {"wrong_doc_type", "missing_attachment", "unreadable", "missing_value"}
DEFECT_FIELDS = {
    "shipper",
    "consignee",
    "notify_party",
    "port_of_loading",
    "port_of_discharge",
    "container_count",
    "gross_weight_kg",
}
OUTPUT_KEYS = ("category", "status", "review_reason", "defect_fields", "has_defect")


class ExportError(ValueError):
    pass


def _read_json(path: Path) -> Any:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle, object_pairs_hook=OrderedDict)
    except FileNotFoundError as exc:
        raise ExportError(f"File not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ExportError(f"Invalid JSON in {path}: {exc}") from exc


def _load_template_ids(template_path: Path) -> list[str]:
    template = _read_json(template_path)
    if not isinstance(template, dict):
        raise ExportError("sample_submission.json must be a JSON object keyed by email_id")
    if not template:
        raise ExportError("sample_submission.json is empty")

    expected_keys = set(OUTPUT_KEYS)
    for email_id, record in template.items():
        if not isinstance(email_id, str) or not email_id:
            raise ExportError("Template contains an invalid email_id")
        if not isinstance(record, dict) or set(record.keys()) != expected_keys:
            raise ExportError(
                f"Template record {email_id} does not contain exactly: {', '.join(OUTPUT_KEYS)}"
            )
    return list(template.keys())


def _load_results(results_path: Path) -> dict[str, dict[str, Any]]:
    raw = _read_json(results_path)

    if isinstance(raw, dict):
        results: dict[str, dict[str, Any]] = {}
        for email_id, record in raw.items():
            if not isinstance(record, dict):
                raise ExportError(f"Result for {email_id} must be an object")
            results[email_id] = record
        return results

    if isinstance(raw, list):
        results = {}
        for index, record in enumerate(raw):
            if not isinstance(record, dict):
                raise ExportError(f"Result at array index {index} must be an object")
            email_id = record.get("email_id")
            if not isinstance(email_id, str) or not email_id:
                raise ExportError(f"Result at array index {index} is missing a valid email_id")
            if email_id in results:
                raise ExportError(f"Duplicate email_id in result array: {email_id}")
            results[email_id] = record
        return results

    raise ExportError("Results must be either an object keyed by email_id or an array of result objects")


def _normalize_category(value: Any, email_id: str) -> str:
    if not isinstance(value, str):
        raise ExportError(f"{email_id}: category is required and must be a string")
    category = value.strip().upper()
    if category not in CATEGORIES:
        raise ExportError(f"{email_id}: invalid category {value!r}")
    return category


def _normalize_status(value: Any, email_id: str) -> str:
    if not isinstance(value, str):
        raise ExportError(f"{email_id}: BL_COMPARISON requires status")
    status = value.strip().upper()
    if status not in STATUSES:
        raise ExportError(f"{email_id}: invalid status {value!r}")
    return status


def _normalize_defect_fields(value: Any, email_id: str) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ExportError(f"{email_id}: defect_fields must be an array")

    normalized: list[str] = []
    seen: set[str] = set()
    for field in value:
        if not isinstance(field, str):
            raise ExportError(f"{email_id}: defect_fields entries must be strings")
        field = field.strip()
        if field not in DEFECT_FIELDS:
            raise ExportError(f"{email_id}: invalid defect field {field!r}")
        if field in seen:
            raise ExportError(f"{email_id}: duplicate defect field {field!r}")
        seen.add(field)
        normalized.append(field)
    return normalized


def _build_record(email_id: str, source: dict[str, Any]) -> OrderedDict[str, Any]:
    category = _normalize_category(source.get("category"), email_id)

    # The participant guide says non-comparison categories only need classification.
    # The sample format still includes the other four fields, so canonicalize them.
    if category != "BL_COMPARISON":
        return OrderedDict([
            ("category", category),
            ("status", "OK"),
            ("review_reason", None),
            ("defect_fields", []),
            ("has_defect", False),
        ])

    status = _normalize_status(source.get("status"), email_id)

    if status == "OK":
        supplied_defects = _normalize_defect_fields(source.get("defect_fields", []), email_id)
        if supplied_defects:
            raise ExportError(f"{email_id}: OK cannot contain defect_fields")
        if source.get("review_reason") not in (None, ""):
            raise ExportError(f"{email_id}: OK cannot contain review_reason")
        return OrderedDict([
            ("category", category),
            ("status", status),
            ("review_reason", None),
            ("defect_fields", []),
            ("has_defect", False),
        ])

    if status == "MISMATCH":
        defects = _normalize_defect_fields(source.get("defect_fields"), email_id)
        if not defects:
            raise ExportError(f"{email_id}: MISMATCH requires at least one defect_fields entry")
        if source.get("review_reason") not in (None, ""):
            raise ExportError(f"{email_id}: MISMATCH cannot contain review_reason")
        return OrderedDict([
            ("category", category),
            ("status", status),
            ("review_reason", None),
            ("defect_fields", defects),
            ("has_defect", True),
        ])

    reason = source.get("review_reason")
    if not isinstance(reason, str) or reason.strip() not in REVIEW_REASONS:
        raise ExportError(
            f"{email_id}: NEEDS_REVIEW requires review_reason in {sorted(REVIEW_REASONS)}"
        )
    supplied_defects = _normalize_defect_fields(source.get("defect_fields", []), email_id)
    if supplied_defects:
        raise ExportError(f"{email_id}: NEEDS_REVIEW cannot contain defect_fields")
    return OrderedDict([
        ("category", category),
        ("status", status),
        ("review_reason", reason.strip()),
        ("defect_fields", []),
        ("has_defect", False),
    ])


def build_submission(template_path: Path, results_path: Path) -> OrderedDict[str, Any]:
    required_ids = _load_template_ids(template_path)
    results = _load_results(results_path)

    required_set = set(required_ids)
    result_set = set(results)
    missing = sorted(required_set - result_set)
    extra = sorted(result_set - required_set)

    if missing or extra:
        pieces = []
        if missing:
            preview = ", ".join(missing[:10])
            pieces.append(f"missing {len(missing)} email_id(s): {preview}{' ...' if len(missing) > 10 else ''}")
        if extra:
            preview = ", ".join(extra[:10])
            pieces.append(f"unexpected {len(extra)} email_id(s): {preview}{' ...' if len(extra) > 10 else ''}")
        raise ExportError("Result coverage does not match the dataset: " + "; ".join(pieces))

    submission: OrderedDict[str, Any] = OrderedDict()
    for email_id in required_ids:
        submission[email_id] = _build_record(email_id, results[email_id])
    return submission


def main() -> int:
    parser = argparse.ArgumentParser(description="Export SDOC results in sample_submission.json format")
    parser.add_argument("--results", required=True, type=Path, help="Pipeline result JSON")
    parser.add_argument("--template", required=True, type=Path, help="Participant sample_submission.json")
    parser.add_argument("--output", required=True, type=Path, help="Output submission JSON")
    args = parser.parse_args()

    try:
        submission = build_submission(args.template, args.results)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(submission, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
    except ExportError as exc:
        parser.exit(2, f"ERROR: {exc}\n")

    print(f"PASS: wrote {len(submission)} unique email results to {args.output}")
    print(f"PASS: each record contains exactly: {', '.join(OUTPUT_KEYS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
