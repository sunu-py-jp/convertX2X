#!/usr/bin/env python3
"""Save the actual local office2md HTTP result for the merge/header matrix."""

import argparse
from datetime import datetime, timezone
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import zipfile


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
MEMBER = re.compile(r"images/[a-z]+-[0-9]+\.[a-z0-9]{1,8}\Z")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def record(path: Path) -> dict:
    data = path.read_bytes()
    return {"path": str(path.relative_to(HERE)), "sizeBytes": len(data), "sha256": sha256(data)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:7074")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    parsed = urlparse(base)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1"):
        parser.error("Only a local HTTP Functions host may be captured.")

    input_path = HERE / "input.xlsx"
    input_bytes = input_path.read_bytes()
    url = base + "/api/convert?filename=merge-matrix.xlsx"
    request = Request(url, data=input_bytes, method="POST",
                      headers={"Content-Type": "application/octet-stream"})
    started = time.monotonic()
    with urlopen(request, timeout=180) as response:
        status = response.status
        content_type = response.headers.get("Content-Type", "")
        archive_bytes = response.read()
    elapsed_ms = round((time.monotonic() - started) * 1000)
    if status != 200:
        raise AssertionError(f"Conversion returned HTTP {status}")

    output = HERE / "output"
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True)
    with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not {"document.md", "report.json"} <= set(names):
            raise AssertionError("Unexpected ZIP member set")
        for name in names:
            if name not in ("document.md", "report.json") and not MEMBER.fullmatch(name):
                raise AssertionError(f"Unexpected ZIP member: {name}")
            destination = output / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(name))
    (HERE / "result.zip").write_bytes(archive_bytes)
    report = json.loads((output / "report.json").read_text(encoding="utf-8"))
    if report["source"]["sha256"] != sha256(input_bytes):
        raise AssertionError("Report source hash does not match input")

    files = [record(HERE / "result.zip")]
    files.extend(record(path) for path in sorted(output.rglob("*")) if path.is_file())
    run = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "mode": "actual local Functions HTTP",
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "sourceWorkingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
        "input": record(input_path),
        "caseManifest": record(HERE / "cases.json"),
        "request": {"method": "POST", "path": "/api/convert?filename=merge-matrix.xlsx",
                    "contentType": "application/octet-stream"},
        "response": {"status": status, "contentType": content_type, "elapsedMs": elapsed_ms},
        "sectionCount": report["sectionCount"],
        "tableCount": sum(block["type"] == "table" for block in report["blocks"]),
        "assetCount": len(report["assets"]),
        "warningCount": len(report["warnings"]),
        "files": files,
    }
    (HERE / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"HTTP {status}: {run['sectionCount']} sections, {run['tableCount']} tables, "
          f"{run['warningCount']} warnings, {elapsed_ms} ms")


if __name__ == "__main__":
    main()
