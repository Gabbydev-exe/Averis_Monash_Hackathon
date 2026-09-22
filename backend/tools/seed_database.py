"""Explicitly import fixture emails and missing attachment bytes into the running API.
Run only against the intended database. Existing emails and stored attachment bytes are preserved.
"""
import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import quote


def seed(bundle, api):
    root = bundle.resolve()
    def request(path, data=None, method="GET", content_type="application/json"):
        req = Request(api.rstrip("/") + path, data=data, method=method,
                      headers={"Content-Type": content_type})
        with urlopen(req, timeout=60) as response:
            return response.read()
    files = sorted((root / "inbox").glob("*.json"))
    if not files:
        raise ValueError("No inbox JSON files found in the specified bundle directory")
    for file in files:
        email = json.loads(file.read_text(encoding="utf-8-sig"))
        result = json.loads(request("/api/emails/import", json.dumps(email).encode(), "POST"))
        print(email["email_id"], "inserted:", result["inserted"], "skipped:", result["skipped"])
        # Use persisted references, not potentially changed duplicate JSON.
        email_id = quote(email["email_id"], safe="")
        saved = json.loads(request("/api/emails/" + email_id))
        for attachment in saved["attachments"]:
            url = "/api/emails/" + email_id + "/attachments/" + quote(attachment["name"], safe="")
            try:
                request(url)
                continue
            except HTTPError as error:
                if error.code != 404:
                    raise
            source = (root / attachment["path"]).resolve()
            if not source.is_relative_to(root / "attachments"):
                raise ValueError("Attachment path escapes bundle attachments directory")
            if not source.is_file():
                print("Missing fixture attachment:", attachment["name"])
                continue
            request(url, source.read_bytes(), "PUT", "application/octet-stream")
            print("Stored attachment:", attachment["name"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--api", default="http://localhost:8081")
    args = parser.parse_args()
    seed(args.bundle, args.api)
