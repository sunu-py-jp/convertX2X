#!/usr/bin/env python3
"""Capture the real localhost PDF response for the expanded Word example."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import struct
import subprocess
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


HERE = Path(__file__).resolve().parent
CASE = HERE / "word-complex"
OUTPUT = CASE / "pdf"
ROOT = HERE.parents[3]
HOST_ARTIFACT = ROOT / "functions/office2md/target/azure-functions/office2md-local/office2md-1.0.0-SNAPSHOT.jar"


def digest(data: bytes) -> str:
    return sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:7072")
    args = parser.parse_args()
    parsed = urlsplit(args.base)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1") or parsed.username or parsed.password:
        parser.error("Only a local HTTP Functions host is accepted")
    path = "/api/convert?filename=word-complex.docx&output=pdf"
    input_bytes = (CASE / "input.docx").read_bytes()
    host_artifact_hash = digest(HOST_ARTIFACT.read_bytes())
    request = Request(args.base.rstrip("/") + path, data=input_bytes,
                      headers={"Content-Type": "application/octet-stream"}, method="POST")
    with urlopen(request, timeout=180) as response:
        status = response.status
        content_type = response.headers.get_content_type()
        pdf_bytes = response.read()
        section_count = response.headers.get("X-Section-Count")
        warning_count = response.headers.get("X-Warning-Count")
    if status != 200 or content_type != "application/pdf" or not pdf_bytes.startswith(b"%PDF-"):
        raise ValueError("Unexpected PDF response")
    if digest(HOST_ARTIFACT.read_bytes()) != host_artifact_hash:
        raise ValueError("The local host artifact changed during PDF capture")

    OUTPUT.mkdir(exist_ok=True)
    pdf_path = OUTPUT / "document.pdf"
    pdf_path.write_bytes(pdf_bytes)
    screenshot = OUTPUT / "page-01.png"
    subprocess.run(["pdftoppm", "-f", "1", "-l", "1", "-scale-to", "1400",
                    "-singlefile", "-png", str(pdf_path), str(screenshot.with_suffix(""))], check=True)
    image_bytes = screenshot.read_bytes()
    if not image_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("PDF page rendering did not produce a PNG")
    width, height = struct.unpack(">II", image_bytes[16:24])
    info = subprocess.run(["pdfinfo", str(pdf_path)], check=True, capture_output=True, text=True).stdout
    pages = next(int(line.split(":", 1)[1].strip()) for line in info.splitlines() if line.startswith("Pages:"))
    run = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "mode": "actual local Functions HTTP",
        "hostArtifact": {"path": str(HOST_ARTIFACT.relative_to(ROOT)), "sha256": host_artifact_hash},
        "request": {"method": "POST", "path": path, "contentType": "application/octet-stream"},
        "response": {"status": status, "contentType": content_type,
                     "sectionCount": int(section_count) if section_count is not None else None,
                     "warningCount": int(warning_count) if warning_count is not None else None},
        "source": {"path": "../input.docx", "sizeBytes": len(input_bytes), "sha256": digest(input_bytes)},
        "pdf": {"path": "document.pdf", "sizeBytes": len(pdf_bytes), "sha256": digest(pdf_bytes), "pages": pages},
        "firstPage": {"path": "page-01.png", "sizeBytes": len(image_bytes), "sha256": digest(image_bytes),
                      "width": width, "height": height,
                      "rendering": "pdftoppm first page, scale-to 1400 pixels"},
    }
    (OUTPUT / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"HTTP {status} PDF: {pages} pages; first-page image {width}×{height}")


if __name__ == "__main__":
    main()
