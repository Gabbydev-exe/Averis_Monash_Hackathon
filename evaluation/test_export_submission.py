import json
import tempfile
import unittest
from pathlib import Path

from export_submission import ExportError, build_submission


class ExportSubmissionTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.template = self.root / "sample_submission.json"
        self.template.write_text(json.dumps({
            "email_001": {
                "category": "GENERAL", "status": "OK", "review_reason": None,
                "defect_fields": [], "has_defect": False,
            },
            "email_002": {
                "category": "GENERAL", "status": "OK", "review_reason": None,
                "defect_fields": [], "has_defect": False,
            },
        }), encoding="utf-8")

    def tearDown(self):
        self.tempdir.cleanup()

    def _write_results(self, value):
        path = self.root / "results.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_exports_exact_key_order_and_non_bl_defaults(self):
        results = self._write_results({
            "email_001": {"category": "spam"},
            "email_002": {"category": "GENERAL"},
        })
        output = build_submission(self.template, results)
        self.assertEqual(list(output), ["email_001", "email_002"])
        self.assertEqual(list(output["email_001"]), [
            "category", "status", "review_reason", "defect_fields", "has_defect"
        ])
        self.assertEqual(output["email_001"]["category"], "SPAM")
        self.assertEqual(output["email_001"]["status"], "OK")
        self.assertFalse(output["email_001"]["has_defect"])

    def test_mismatch_derives_has_defect(self):
        results = self._write_results({
            "email_001": {
                "category": "BL_COMPARISON",
                "status": "MISMATCH",
                "defect_fields": ["consignee", "gross_weight_kg"],
            },
            "email_002": {"category": "GENERAL"},
        })
        output = build_submission(self.template, results)
        self.assertTrue(output["email_001"]["has_defect"])
        self.assertEqual(output["email_001"]["defect_fields"], ["consignee", "gross_weight_kg"])

    def test_needs_review_requires_known_reason(self):
        results = self._write_results({
            "email_001": {
                "category": "BL_COMPARISON",
                "status": "NEEDS_REVIEW",
                "review_reason": "unreadable",
            },
            "email_002": {"category": "GENERAL"},
        })
        output = build_submission(self.template, results)
        self.assertEqual(output["email_001"]["review_reason"], "unreadable")
        self.assertFalse(output["email_001"]["has_defect"])

    def test_rejects_missing_email(self):
        results = self._write_results({"email_001": {"category": "GENERAL"}})
        with self.assertRaisesRegex(ExportError, "missing 1 email_id"):
            build_submission(self.template, results)

    def test_rejects_extra_email(self):
        results = self._write_results({
            "email_001": {"category": "GENERAL"},
            "email_002": {"category": "GENERAL"},
            "email_999": {"category": "GENERAL"},
        })
        with self.assertRaisesRegex(ExportError, "unexpected 1 email_id"):
            build_submission(self.template, results)

    def test_rejects_duplicate_array_email_id(self):
        results = self._write_results([
            {"email_id": "email_001", "category": "GENERAL"},
            {"email_id": "email_001", "category": "SPAM"},
            {"email_id": "email_002", "category": "GENERAL"},
        ])
        with self.assertRaisesRegex(ExportError, "Duplicate email_id"):
            build_submission(self.template, results)

    def test_rejects_unknown_defect_field(self):
        results = self._write_results({
            "email_001": {
                "category": "BL_COMPARISON",
                "status": "MISMATCH",
                "defect_fields": ["vessel"],
            },
            "email_002": {"category": "GENERAL"},
        })
        with self.assertRaisesRegex(ExportError, "invalid defect field"):
            build_submission(self.template, results)


if __name__ == "__main__":
    unittest.main()
