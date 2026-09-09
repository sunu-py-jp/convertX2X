#!/usr/bin/env python3
"""Convert a real workbook through HTTP, preserve the response, and verify its actual artifacts.

Python standard library only. Does not start a host or alter the workbook. Use an empty/new --out
folder. The optional EXCEL2MD_FUNCTIONS_KEY is sent in a header and is never recorded.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import html
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

DEFAULT_LIMITS = {
    "maxInputBytes": 20 * 1024 * 1024, "maxSheets": 50,
    "maxReadCells": 200_000, "maxTableCells": 1_000_000,
    "maxMarkdownBytes": 20 * 1024 * 1024, "maxImages": 200,
    "maxImageBytes": 20 * 1024 * 1024, "maxOutputBytes": 100 * 1024 * 1024,
    "maxShapes": 1000, "maxGroupDepth": 16, "maxImagePixels": 20_000_000,
}
SAFE_HEADERS = {"content-type", "content-length", "content-disposition", "x-sheet-count",
                "x-warning-count", "cache-control", "etag", "date", "request-id", "x-ms-request-id"}
ASSET_PATH = re.compile(r"images/[a-z]+-[0-9]+\.[a-z0-9]{1,8}")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def plain_markdown(value):
    plain = html.unescape(re.sub(r"\\([!\"#$%&'()*+,\-./:;<=>?@\[\]\\^_`{|}~])", r"\1", value))
    return re.sub(r" {2,}\n", "\n", plain)


def escaped(text, position):
    count = 0
    while position > 0 and text[position - 1] == "\\":
        count += 1
        position -= 1
    return count % 2 == 1


def links(markdown):
    # The converter emits inline links with escaped labels and percent-escaped destinations.
    pattern = re.compile(r"(!?)\[((?:\\.|[^\]\\])*)\]\(([^\s)]+)\)")
    for match in pattern.finditer(markdown):
        if not escaped(markdown, match.start()):
            yield bool(match.group(1)), match.group(3)


def load_expectations(path):
    if path is None:
        return {}
    require(path.stat().st_size <= 1024 * 1024, "Expected manifest is too large")
    value = json.loads(path.read_text(encoding="utf-8"))
    fields = {"requiredText", "forbiddenText", "expectedHeadings", "minimumTables", "requiredWarningCodes"}
    require(isinstance(value, dict) and set(value) <= fields, "Unknown expected-manifest field")
    for key, item in value.items():
        if key == "minimumTables":
            require(type(item) is int and item >= 0, "minimumTables must be a nonnegative integer")
        else:
            require(isinstance(item, list) and all(isinstance(text, str) and text for text in item),
                    key + " must be a list of nonempty strings")
    return value


def opener():
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, message, headers, new_url):
            return None
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def extract(archive_path, output, limits):
    files = {}
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        require(len(entries) <= limits["maxImages"] + limits["maxShapes"] + 2, "ZIP entry count exceeds the limit")
        declared = 0
        for entry in entries:
            name = entry.filename
            require(name in ("document.md", "report.json") or ASSET_PATH.fullmatch(name), "Unexpected ZIP path")
            require(name not in files, "Duplicate ZIP path")
            mode = stat.S_IFMT(entry.external_attr >> 16)
            require(mode in (0, stat.S_IFREG), "ZIP contains a non-regular file")
            require(not entry.is_dir() and not entry.flag_bits & 1, "Directories/encrypted ZIP entries are unsupported")
            require(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "Unsupported ZIP compression")
            maximum = limits["maxMarkdownBytes"] if name == "document.md" else (
                limits["maxImageBytes"] if name.startswith("images/") else limits["maxOutputBytes"])
            require(0 <= entry.file_size <= maximum, "ZIP member exceeds its limit")
            declared += entry.file_size
            require(declared <= limits["maxOutputBytes"], "Expanded ZIP exceeds the output limit")
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            written = 0
            with archive.open(entry) as source, target.open("xb") as destination:
                while chunk := source.read(64 * 1024):
                    written += len(chunk)
                    require(written <= entry.file_size and written <= maximum, "ZIP member exceeds declared size")
                    destination.write(chunk)
            require(written == entry.file_size, "ZIP member length mismatch")
            files[name] = {"sizeBytes": written, "sha256": digest_file(target)}
    require("document.md" in files and "report.json" in files, "Required ZIP artifacts are missing")
    return files


def collect_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from collect_strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from collect_strings(item)


def table_count(markdown):
    count = 0
    for line in markdown.splitlines():
        stripped = line.strip()
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = stripped[1:-1].split("|")
            if cells and all(re.fullmatch(r"\s*:?-{3,}:?\s*", cell) for cell in cells):
                count += 1
    return count


def verify(output, files, request, limits, expected, checks):
    markdown = (output / "document.md").read_text(encoding="utf-8")
    report = json.loads((output / "report.json").read_text(encoding="utf-8"))
    require(isinstance(report, dict), "report.json must be an object")
    def check(name, passed, actual=None, expectation=None):
        checks.append({"name": name, "passed": bool(passed), "actual": actual, "expected": expectation})
    check("レポートの形式バージョン", report.get("specVersion") == 1, report.get("specVersion"), 1)
    check("入力SHA-256とレポートの一致", report.get("source", {}).get("sha256") == request["inputSha256"],
          report.get("source", {}).get("sha256"), request["inputSha256"])
    headings = [plain_markdown(match.group(1)) for match in re.finditer(r"^# ([^\n]+)$", markdown, re.MULTILINE)]
    higher = re.findall(r"^#{2,6}\s+.*$", markdown, re.MULTILINE)
    check("H1数とシート数の一致", len(headings) == report.get("sheetCount"), len(headings), report.get("sheetCount"))
    check("H2以下の見出しを生成しない", not higher, len(higher), 0)
    if "expectedHeadings" in expected:
        check("出力対象シートと順序", headings == expected["expectedHeadings"], headings, expected["expectedHeadings"])
    tables = table_count(markdown)
    blocks = report.get("blocks", [])
    require(isinstance(blocks, list), "Report blocks must be a list")
    block_types = Counter(item.get("type", "unknown") for item in blocks if isinstance(item, dict))
    check("Markdown罫線表数とレポートの一致", tables == block_types.get("table", 0), tables, block_types.get("table", 0))
    if "minimumTables" in expected:
        check("罫線表の最低件数", tables >= expected["minimumTables"], tables, expected["minimumTables"])
    plain = plain_markdown(markdown)
    for text in expected.get("requiredText", []):
        check("必要な出力テキスト", text in markdown or text in plain, text, "Markdown内に存在")
    # Only converted content is checked: exclude source filename/hash metadata, never scan the input workbook.
    report_content = {key: value for key, value in report.items() if key != "source"}
    converted_text = plain + "\n" + html.unescape("\n".join(collect_strings(report_content)))
    for text in expected.get("forbiddenText", []):
        check("非表示・取消等の除外テキスト", text not in converted_text, text, "Markdownとレポート内容に不存在")
    warnings = report.get("warnings", [])
    require(isinstance(warnings, list), "Report warnings must be a list")
    warning_codes = Counter(item.get("code", "unknown") for item in warnings if isinstance(item, dict))
    for code in expected.get("requiredWarningCodes", []):
        check("必要な警告コード", warning_codes[code] > 0, code, "レポートに存在")
    assets = report.get("assets", [])
    require(isinstance(assets, list), "Report assets must be a list")
    listed, image_details = set(), []
    for asset in assets:
        require(isinstance(asset, dict) and isinstance(asset.get("path"), str), "Invalid report asset")
        path = asset["path"]
        require(ASSET_PATH.fullmatch(path) and path not in listed, "Unsafe/duplicate report asset path")
        listed.add(path)
        actual = files.get(path)
        check("添付ファイルの存在: " + path, actual is not None)
        if actual is None:
            continue
        check("添付ファイルのSHA-256: " + path, actual["sha256"] == asset.get("sha256"), actual["sha256"], asset.get("sha256"))
        check("添付ファイルのサイズ: " + path, actual["sizeBytes"] == asset.get("sizeBytes"), actual["sizeBytes"], asset.get("sizeBytes"))
        detail = {"path": path, **actual, "contentType": asset.get("contentType")}
        with (output / path).open("rb") as source:
            header = source.read(24)
        if path.endswith(".png"):
            valid = len(header) == 24 and header[:8] == b"\x89PNG\r\n\x1a\n" and header[12:16] == b"IHDR"
            check("PNG形式: " + path, valid and asset.get("contentType") == "image/png")
            if valid:
                width, height = struct.unpack(">II", header[16:24])
                detail.update(width=width, height=height)
                check("PNG寸法: " + path, width > 0 and height > 0, [width, height])
        elif path.endswith((".jpg", ".jpeg")):
            check("JPEG形式: " + path, header.startswith(b"\xff\xd8\xff") and asset.get("contentType") == "image/jpeg")
        image_details.append(detail)
    emitted = {name for name in files if name.startswith("images/")}
    check("ZIPとレポートの添付一覧の一致", emitted == listed, sorted(emitted), sorted(listed))
    references, remote_links = [], []
    for image, target in links(markdown):
        if target.startswith("images/"):
            references.append(target)
            check("Markdown添付参照: " + target, target in emitted)
        elif image:
            check("外部画像を参照しない", False, target)
        elif urllib.parse.urlsplit(target).scheme in ("http", "https", "mailto"):
            remote_links.append(target)
        else:
            check("予期しない相対参照がない", False, target)
    check("全添付がMarkdownから参照される", emitted == set(references), sorted(set(references)), sorted(emitted))
    counts = {
        "sheets": len(headings), "headings": headings, "tables": tables, "blocks": dict(block_types),
        "boldFragments": len(re.findall(r"\*\*[^*\n]+\*\*|<strong>.*?</strong>", markdown)),
        "hardLineBreaks": len(re.findall(r" {2}\n|<br\s*/?>", markdown)),
        "externalHyperlinks": len(remote_links), "assetFiles": len(emitted), "assetReferences": len(references),
        "pngFiles": sum(item["path"].endswith(".png") for item in image_details),
        "warnings": len(warnings), "warningCodes": dict(warning_codes),
        "information": len(report.get("information", [])), "zipBytes": (output / "result.zip").stat().st_size,
        "expandedBytes": sum(item["sizeBytes"] for item in files.values()), "markdownBytes": files["document.md"]["sizeBytes"],
    }
    return counts, image_details


def cell(value):
    if isinstance(value, (list, dict)):
        value = json.dumps(value, ensure_ascii=False)
    return html.escape(str(value)).replace("|", "\\|").replace("\n", "<br>")


def write_summary(output, verification):
    request, counts = verification.get("request", {}), verification.get("counts", {})
    lines = ["# Excel実変換の検証結果", "", "**" + ("PASS" if verification["passed"] else "FAIL") + "**", "",
             "起動済みHTTP APIへ実ファイルを送り、受信した成果物を検証しました。", "",
             "- 入力: " + cell(request.get("filename", "不明")),
             "- 入力SHA-256: `" + request.get("inputSha256", "未取得") + "`",
             "- HTTP要求〜ZIP受信: " + str(request.get("elapsedMs", "未取得")) + " ms",
             "- HTTP状態: " + str(request.get("status", "未取得")), "",
             "[変換後Markdown](document.md) · [実応答ZIP](result.zip) · [変換レポート](report.json) · [検証JSON](verification.json)", "",
             "| 項目 | 実測結果 |", "| --- | --- |"]
    for label, key in (("シート／H1", "sheets"), ("罫線表", "tables"), ("太字断片", "boldFragments"),
                       ("明示改行", "hardLineBreaks"), ("外部ハイパーリンク", "externalHyperlinks"),
                       ("画像・添付ファイル", "assetFiles"), ("Markdown内の添付参照", "assetReferences"),
                       ("PNG", "pngFiles"), ("警告", "warnings"), ("ZIP bytes", "zipBytes")):
        lines.append("| " + label + " | " + cell(counts.get(key, "未取得")) + " |")
    lines += ["", "外部ハイパーリンク先の通信確認は行いません。画像の見た目の確認には下のPNGとMarkdownを使ってください。", "",
              "## 内容・整合性チェック", "", "| 結果 | 項目 | 実際／対象 | 期待 |", "| --- | --- | --- | --- |"]
    for check in verification["checks"]:
        lines.append("| " + ("PASS" if check["passed"] else "FAIL") + " | " + cell(check["name"]) + " | "
                     + cell(check.get("actual", "")) + " | " + cell(check.get("expected", "")) + " |")
    lines += ["", "## 出力PNG・添付", ""]
    for image in verification.get("assets", []):
        name = image["path"]
        lines.append("### " + name)
        lines += ["", str(image["sizeBytes"]) + " bytes · SHA-256 `" + image["sha256"] + "`", ""]
        if name.endswith(".png"):
            lines += ["![" + name + "](" + name + ")", ""]
        else:
            lines += ["[添付を開く](" + name + ")", ""]
    (output / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def run(args, output, verification):
    expected = load_expectations(args.expect)
    key = os.environ.get(args.key_env, "")
    headers = {"x-functions-key": key} if key else {}
    client = opener()
    limits = dict(DEFAULT_LIMITS)
    with client.open(urllib.request.Request(args.base + "/api/capabilities", headers=headers), timeout=args.timeout) as response:
        data = response.read(64 * 1024 + 1)
        require(len(data) <= 64 * 1024, "Capabilities response is too large")
        capabilities = json.loads(data)
    for name, fallback in DEFAULT_LIMITS.items():
        value = capabilities.get(name, fallback)
        require(type(value) is int and value > 0, "Invalid capability limit: " + name)
        limits[name] = value
    verification["limits"] = limits
    require(args.input.is_file(), "Input file does not exist")
    require(args.input.stat().st_size <= limits["maxInputBytes"], "Input exceeds the configured limit")
    with args.input.open("rb") as source:
        body = source.read(limits["maxInputBytes"] + 1)
    require(0 < len(body) <= limits["maxInputBytes"], "Input is empty or exceeds the configured limit")
    request_info = {"method": "POST", "filename": args.input.name, "inputSizeBytes": len(body),
                    "inputSha256": hashlib.sha256(body).hexdigest(), "startedAt": utc_now()}
    verification["request"] = request_info
    path = "/api/convert?" + urllib.parse.urlencode({"filename": args.input.name})
    request_info["path"] = path
    request = urllib.request.Request(args.base + path, data=body, headers={**headers, "Content-Type": "application/octet-stream"})
    started = time.perf_counter()
    try:
        response = client.open(request, timeout=args.timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        request_info["status"] = response.status
        safe_headers = {name: value.replace(key, "[REDACTED]") if key else value
                        for name, value in response.headers.items() if name.lower() in SAFE_HEADERS}
        write_json(output / "response-headers.json", safe_headers)
        if response.status != 200:
            error_body = response.read(64 * 1024).decode("utf-8", errors="replace")
            if key:
                error_body = error_body.replace(key, "[REDACTED]")
            (output / "response-error.txt").write_text(error_body, encoding="utf-8")
            raise ValueError("Conversion returned HTTP " + str(response.status))
        size = 0
        with (output / "result.zip").open("xb") as destination:
            while chunk := response.read(64 * 1024):
                size += len(chunk)
                require(size <= limits["maxOutputBytes"], "HTTP ZIP exceeds the configured output limit")
                destination.write(chunk)
    request_info["elapsedMs"] = round((time.perf_counter() - started) * 1000, 3)
    request_info["completedAt"] = utc_now()
    write_json(output / "request.json", request_info)
    verification["checks"].append({"name": "実HTTP変換とZIP受信", "passed": True, "actual": request_info["status"], "expected": 200})
    files = extract(output / "result.zip", output, limits)
    verification["counts"], verification["assets"] = verify(output, files, request_info, limits, expected, verification["checks"])
    verification["passed"] = all(check["passed"] for check in verification["checks"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--base", default="http://127.0.0.1:7072")
    parser.add_argument("--expect", type=Path, help="Optional JSON expectations for text/headings/tables/warnings")
    parser.add_argument("--key-env", default="EXCEL2MD_FUNCTIONS_KEY")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    base = urllib.parse.urlsplit(args.base)
    if base.scheme not in ("http", "https") or not base.netloc or base.username or base.password or base.query or base.fragment or base.path not in ("", "/"):
        parser.error("--base must be an HTTP(S) origin without credentials, path or query.")
    args.base = args.base.rstrip("/")
    if args.timeout < 1:
        parser.error("--timeout must be positive.")
    if args.out.exists() and (not args.out.is_dir() or any(args.out.iterdir())):
        parser.error("--out must be new or empty; existing evidence is never overwritten.")
    args.out.mkdir(parents=True, exist_ok=True)
    verification = {"passed": False, "verifiedAt": utc_now(), "checks": []}
    try:
        run(args, args.out, verification)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        message = str(error)
        key = os.environ.get(args.key_env, "")
        if key:
            message = message.replace(key, "[REDACTED]")
        verification["checks"].append({"name": "処理・保存・整合性検証", "passed": False, "actual": type(error).__name__ + ": " + message})
    finally:
        write_json(args.out / "verification.json", verification)
        if "request" in verification:
            write_json(args.out / "request.json", verification["request"])
        write_summary(args.out, verification)
    print(("PASS" if verification["passed"] else "FAIL") + ": " + str(args.out / "README.md"))
    return 0 if verification["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
