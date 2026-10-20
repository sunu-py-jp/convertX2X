#!/usr/bin/env python3
"""Run the three public Excel examples through a real Excel2Img HTTP endpoint.

Defaults to the repository's additional/inputs directory and localhost:7076/api.
Writes PNG/PDF ZIP responses, safely extracts their allowed artifacts, and records
request timings, reports and workbook picture relationships in additional/run.json.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import stat
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone

MODULE_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE_FOLDER = MODULE_ROOT.parents[1] / "docs/APIDocs/excel2img/examples/additional/inputs"
SAMPLES = (
    ("wide-table", "48列・可読性不足"),
    ("embedded-images", None),
    ("wide-with-images", "52列・可読性不足"),
)
ALLOWED_ARTIFACT = re.compile(r"(?:report\.json|document\.pdf|pages/page-[0-9]{3,}\.png)\Z")
NS = {
    "main": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
    "rel": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "package": "http://schemas.openxmlformats.org/package/2006/relationships",
    "drawing": "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def relative(path: Path, root: Path) -> str:
    return os.path.relpath(path, root).replace(os.sep, "/")


def relationships(archive: zipfile.ZipFile, source: str) -> dict[str, str]:
    directory, basename = posixpath.split(source)
    part = posixpath.join(directory, "_rels", basename + ".rels")
    if part not in archive.namelist():
        return {}
    result = {}
    for relation in ET.fromstring(archive.read(part)):
        if relation.get("TargetMode") == "External":
            continue
        target = relation.get("Target", "")
        resolved = posixpath.normpath(target.lstrip("/") if target.startswith("/") else posixpath.join(directory, target))
        if resolved.startswith("../"):
            raise ValueError("Workbook relationship escapes the archive root")
        result[relation.attrib["Id"]] = resolved
    return result


def picture_metadata(data: bytes) -> dict:
    """Read saved picture relationships and anchor cells; this does not assert pixel fidelity."""
    sheets, pictures = [], []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        workbook_links = relationships(archive, "xl/workbook.xml")
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        for entry in workbook.findall("main:sheets/main:sheet", NS):
            name = entry.attrib["name"]
            sheets.append(name)
            source = workbook_links[entry.attrib[f"{{{NS['rel']}}}id"]]
            sheet = ET.fromstring(archive.read(source))
            sheet_links = relationships(archive, source)
            for reference in sheet.findall("main:drawing", NS):
                drawing_path = sheet_links[reference.attrib[f"{{{NS['rel']}}}id"]]
                drawing = ET.fromstring(archive.read(drawing_path))
                drawing_links = relationships(archive, drawing_path)
                for anchor in drawing:
                    for picture in anchor.findall("drawing:pic", NS):
                        blip = picture.find("drawing:blipFill/a:blip", NS)
                        if blip is None:
                            continue
                        media = drawing_links[blip.attrib[f"{{{NS['rel']}}}embed"]]
                        image = archive.read(media)
                        props = picture.find("drawing:nvPicPr/drawing:cNvPr", NS)
                        record = {"sheet": name, "name": props.get("name") if props is not None else None,
                                  "drawingPart": drawing_path, "mediaPart": media, "mediaBytes": len(image),
                                  "mediaSha256": hashlib.sha256(image).hexdigest(), "anchorType": anchor.tag.split("}")[-1]}
                        for point in ("from", "to"):
                            marker = anchor.find(f"drawing:{point}", NS)
                            if marker is not None:
                                record[point] = {axis: int(marker.findtext(f"drawing:{axis}", "0", NS))
                                                 for axis in ("col", "row", "colOff", "rowOff")}
                        pictures.append(record)
        media_parts = sorted(part for part in archive.namelist() if part.startswith("xl/media/") and not part.endswith("/"))
    return {"sheetNames": sheets, "pictureCount": len(pictures), "mediaParts": media_parts, "pictures": pictures}


def cells(address: str) -> tuple[int, int, int, int]:
    def point(value: str) -> tuple[int, int]:
        match = re.fullmatch(r"\$?([A-Z]+)\$?([1-9][0-9]*)", value.upper())
        if match is None:
            raise ValueError(f"Invalid report range: {value}")
        column = 0
        for character in match[1]:
            column = column * 26 + ord(character) - ord("A") + 1
        return column - 1, int(match[2]) - 1
    first, _, last = address.partition(":")
    col1, row1 = point(first)
    col2, row2 = point(last or first)
    return col1, row1, col2, row2


def picture_evidence(metadata: dict, report: dict) -> dict:
    evidence = []
    for picture in metadata["pictures"]:
        pages = []
        if "from" in picture:
            start = picture["from"]
            end = picture.get("to", start)
            for page in report.get("pages", []):
                if page.get("sheet") != picture["sheet"]:
                    continue
                left, top, right, bottom = cells(page["range"])
                top = max(top, page.get("firstRow", top + 1) - 1)
                bottom = min(bottom, page.get("lastRow", bottom + 1) - 1)
                if start["col"] <= right and end["col"] >= left and start["row"] <= bottom and end["row"] >= top:
                    pages.append(page["page"])
        evidence.append({"sheet": picture["sheet"], "mediaPart": picture["mediaPart"], "reportedPagesOverlappingAnchor": pages})
    warnings = [warning for warning in report.get("warnings", [])
                if any(word in str(warning.get("code", "")) for word in ("IMAGE", "PICTURE", "DRAWING"))]
    return {"inputPictureCount": metadata["pictureCount"],
            "picturesCoveredByReportedPages": sum(bool(item["reportedPagesOverlappingAnchor"]) for item in evidence),
            "pictures": evidence, "drawingWarnings": warnings,
            "verification": "Workbook picture relationships and reported page/anchor coverage; visual fidelity is checked separately."}


def read_bounded(response, maximum: int) -> bytes:
    data = response.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("HTTP response exceeds the configured output limit")
    return data


def request(url: str, data: bytes | None, timeout: float, maximum: int, key: str) -> tuple[int, dict, bytes, int]:
    headers = {"x-functions-key": key} if key else {}
    if data is not None:
        headers["Content-Type"] = "application/octet-stream"
    message = urllib.request.Request(url, data=data, headers=headers)
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(message, timeout=timeout)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        body = read_bounded(response, maximum)
        return response.status, dict(response.headers), body, round((time.monotonic() - started) * 1000)


def extract_result(data: bytes, destination: Path, output_format: str, maximum: int) -> dict:
    """Allow only server-owned result names, never traversal, symlinks or arbitrary ZIP paths."""
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if len(entries) > 10000:
            raise ValueError("ZIP contains too many files")
        seen, total = set(), 0
        for entry in entries:
            name = entry.filename
            if name in seen or not ALLOWED_ARTIFACT.fullmatch(name) or entry.is_dir() or entry.flag_bits & 1:
                raise ValueError(f"Unexpected ZIP artifact: {name}")
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("ZIP symlinks are not allowed")
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name:
                raise ValueError("Unsafe ZIP path")
            target = root.joinpath(*path.parts)
            if not target.resolve().is_relative_to(root):
                raise ValueError("ZIP destination escapes its output directory")
            seen.add(name)
            total += entry.file_size
            if total > maximum:
                raise ValueError("Expanded ZIP exceeds the output limit")
        if "report.json" not in seen:
            raise ValueError("ZIP is missing report.json")
        expected = {"report.json", "document.pdf"}
        if output_format == "pdf" and seen != expected:
            raise ValueError("PDF ZIP must contain document.pdf and report.json")
        if output_format == "png" and (len(seen) < 2 or "document.pdf" in seen):
            raise ValueError("PNG ZIP must contain pages and report.json")
        report = None
        for entry in entries:
            with archive.open(entry) as source:
                content = source.read(min(entry.file_size, maximum) + 1)
            if len(content) != entry.file_size:
                raise ValueError("ZIP artifact size is inconsistent")
            if entry.filename.endswith(".png") and not content.startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError("PNG artifact has an invalid signature")
            if entry.filename.endswith(".pdf") and not content.startswith(b"%PDF-"):
                raise ValueError("PDF artifact has an invalid signature")
            target = root.joinpath(*PurePosixPath(entry.filename).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            if entry.filename == "report.json":
                report = json.loads(content)
        if report is None or report.get("format") != output_format:
            raise ValueError("Report format does not match the request")
        return report


def summary(report: dict) -> dict:
    return {"pageCount": report.get("pageCount"), "sectionCount": report.get("sectionCount"),
            "warningCount": len(report.get("warnings", [])), "outcome": report.get("outcome"),
            "ranges": [{key: item[key] for key in ("sheet", "range", "selection", "status", "reason", "message", "pixelsPerPoint", "pages") if key in item}
                       for item in report.get("ranges", [])]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:7076/api")
    parser.add_argument("--fixture-folder", type=Path, default=DEFAULT_FIXTURE_FOLDER)
    parser.add_argument("--output-folder", type=Path, help="Defaults to a sibling outputs directory")
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    parsed = urllib.parse.urlsplit(args.base_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.query or parsed.fragment:
        parser.error("--base-url must be an HTTP(S) API URL without credentials, query or fragment")
    fixture_folder = args.fixture_folder.resolve()
    output_folder = (args.output_folder or fixture_folder.parent / "outputs").resolve()
    run_path = output_folder.parent / "run.json"
    for name, _ in SAMPLES:
        if not (fixture_folder / f"{name}.xlsx").is_file():
            parser.error(f"Missing fixture: {fixture_folder / (name + '.xlsx')}")
    base = args.base_url.rstrip("/")
    key = os.environ.get("EXCEL2IMG_FUNCTION_KEY", "")
    status, _, raw, _ = request(base + "/capabilities", None, args.timeout, 65536, key)
    if status != 200:
        raise RuntimeError(f"Capabilities returned HTTP {status}")
    capabilities = json.loads(raw)
    maximum = int(capabilities["maxOutputBytes"])
    runs = {"startedAt": datetime.now(timezone.utc).isoformat(), "transport": "real HTTP", "baseUrl": base,
            "capabilities": capabilities, "fixtures": {}, "requests": []}
    for name, oversized in SAMPLES:
        source = fixture_folder / f"{name}.xlsx"
        data = source.read_bytes()
        metadata = picture_metadata(data)
        if name != "wide-table" and metadata["pictureCount"] == 0:
            raise ValueError(f"{name} must contain embedded pictures")
        runs["fixtures"][name] = {"path": relative(source, run_path.parent), "bytes": len(data),
                                  "sha256": hashlib.sha256(data).hexdigest(), **metadata}
        for output_format in ("png", "pdf"):
            params = {"filename": source.name, "format": output_format}
            url = base + "/convert?" + urllib.parse.urlencode(params)
            status, headers, response, elapsed = request(url, data, args.timeout, maximum, key)
            if status != 200:
                raise RuntimeError(f"{name}/{output_format}: HTTP {status}: {response.decode('utf-8', 'replace')[:2000]}")
            if "application/zip" not in headers.get("Content-Type", ""):
                raise ValueError("Conversion response is not a ZIP")
            destination = output_folder / name
            report = extract_result(response, destination / output_format, output_format, maximum)
            zip_path = destination / f"{output_format}.zip"
            zip_path.write_bytes(response)
            evidence = picture_evidence(metadata, report)
            if name != "wide-table" and not any(item["reportedPagesOverlappingAnchor"] for item in evidence["pictures"]):
                raise ValueError(f"{name} has no embedded picture anchor covered by any rendered page")
            failed_drawings = {"DRAWING_RENDER_FAILED", "DRAWING_UNREADABLE", "IMAGE_UNREADABLE", "IMAGE_FORMAT_UNSUPPORTED", "IMAGE_SOURCE_PIXEL_LIMIT"}
            if any(warning.get("code") in failed_drawings for warning in evidence["drawingWarnings"]):
                raise ValueError(f"{name} contains a picture rendering failure in its report")
            runs["requests"].append({"fixture": name, "method": "POST", "endpoint": "/convert", "parameters": params,
                                     "httpStatus": status, "elapsedMs": elapsed, "responseBytes": len(response),
                                     "zip": relative(zip_path, run_path.parent),
                                     "report": relative(destination / output_format / "report.json", run_path.parent),
                                     **summary(report), "pictureEvidence": evidence})
            write_json(run_path, runs)
            print(f"{name} {output_format}: HTTP {status}, {report['pageCount']} pages, {report['outcome']}, {elapsed} ms", flush=True)
        if oversized:
            params = {"filename": source.name, "format": "png", "sheet": oversized}
            status, _, response, elapsed = request(base + "/convert?" + urllib.parse.urlencode(params), data, args.timeout, maximum, key)
            error = json.loads(response)
            if status != 422 or error.get("error", {}).get("code") != "NO_RENDERABLE_RANGE":
                raise ValueError(f"{name}: expected safe NO_RENDERABLE_RANGE HTTP 422, received HTTP {status}")
            path = output_folder / name / "oversized-error.json"
            write_json(path, error)
            runs["requests"].append({"fixture": name, "method": "POST", "endpoint": "/convert", "parameters": params,
                                     "httpStatus": status, "elapsedMs": elapsed, "responseBytes": len(response),
                                     "errorResponse": relative(path, run_path.parent), "errorCode": error["error"]["code"],
                                     **summary(error["error"].get("details", {}))})
            write_json(run_path, runs)
            print(f"{name} oversized sheet: expected HTTP 422 NO_RENDERABLE_RANGE, {elapsed} ms", flush=True)
    runs["completedAt"] = datetime.now(timezone.utc).isoformat()
    write_json(run_path, runs)
    print(f"Recorded {len(runs['requests'])} real HTTP requests in {run_path}")


if __name__ == "__main__":
    main()
