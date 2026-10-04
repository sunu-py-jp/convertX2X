#!/usr/bin/env python3
"""Build exact per-sheet Markdown excerpts and a portable screenshot/Markdown ZIP.

The screenshots show this site's HTML rendering of the source workbook cells,
not the Excel application. Capturing them is a separate browser step.
"""

from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from render import HERE, section_bodies, verify_capture


PAIRS = HERE / "pairs"
SCREENSHOTS = HERE / "screenshots"
BUNDLE = HERE / "pairs.zip"
SHEET_HEADING = re.compile(r"(?m)^# \[(.*?)\] シート[ \t]*$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ZIP_DATE = (1980, 1, 1, 0, 0, 0)


def sha256_file(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


def exact_sections(markdown: str, cases: list[dict]) -> dict[str, bytes]:
    """Return byte-for-byte excerpts, including each source heading and spacing."""
    # Reuse the report-aware sheet ordering validation from the comparison page.
    section_bodies(markdown, cases)
    headings = list(SHEET_HEADING.finditer(markdown))
    sections = {}
    for index, (case, heading) in enumerate(zip(cases, headings)):
        sheet = re.sub(r"\\(.)", r"\1", heading.group(1))
        if sheet != case["sheet"]:
            raise ValueError(f"Markdown sheet order differs at {case['id']}: {sheet}")
        end = headings[index + 1].start() if index + 1 < len(headings) else len(markdown)
        sections[case["id"]] = markdown[heading.start():end].encode("utf-8")
    if b"".join(sections[case["id"]] for case in cases) != markdown.encode("utf-8"):
        raise ValueError("Per-case Markdown does not reassemble the captured document")
    return sections


def zip_write(archive: ZipFile, name: str, data: bytes) -> None:
    info = ZipInfo(name, ZIP_DATE)
    info.compress_type = ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data)


def read_png(path: Path) -> bytes | None:
    if not path.exists():
        return None
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE) or len(data) < 24:
        raise ValueError(f"Not a valid PNG screenshot: {path}")
    return data


def main() -> None:
    cases_manifest = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    report = json.loads((HERE / "output/report.json").read_text(encoding="utf-8"))
    run = json.loads((HERE / "run.json").read_text(encoding="utf-8"))
    markdown = (HERE / "output/document.md").read_text(encoding="utf-8")
    verify_capture(cases_manifest, report, run, markdown)
    cases = cases_manifest["cases"]
    excerpts = exact_sections(markdown, cases)
    blocks = {block["section"]: block for block in report["blocks"] if block["type"] == "table"}
    if len(blocks) != len(cases):
        raise ValueError("Report table count differs from the case count")

    PAIRS.mkdir(exist_ok=True)
    records = []
    screenshots = {}
    for case in cases:
        case_id = case["id"]
        case_dir = PAIRS / case_id
        case_dir.mkdir(exist_ok=True)
        document = case_dir / "document.md"
        document.write_bytes(excerpts[case_id])
        screenshot = SCREENSHOTS / f"{case_id}.png"
        screenshot_data = read_png(screenshot)
        if screenshot_data is not None:
            screenshots[case_id] = screenshot_data
        block = blocks[case["sheet"]]
        records.append({
            "id": case_id,
            "title": case["title"],
            "sheet": case["sheet"],
            "category": case["category"],
            "inputRange": case["tableRange"],
            "mergedRanges": case["mergedRanges"],
            "hiddenRows": case["hiddenRows"],
            "sourceWorkbook": cases_manifest["input"],
            "screenshot": f"screenshots/{case_id}.png",
            "screenshotInBundle": f"{case_id}/screenshot.png",
            "screenshotKind": "HTML rendering of source workbook cells, fill, and merges",
            "screenshotSha256": sha256(screenshot_data).hexdigest() if screenshot_data else None,
            "markdown": f"pairs/{case_id}/document.md",
            "markdownInBundle": f"{case_id}/document.md",
            "markdownSha256": sha256(excerpts[case_id]).hexdigest(),
            "report": {
                "section": block["section"],
                "range": block["range"],
                "header": block["header"],
                "headerSourceRows": block["headerSourceRows"],
                "sourceColumnSpans": block["sourceColumnSpans"],
                "warnings": [
                    {"code": warning["code"], "range": warning.get("range")}
                    for warning in report["warnings"] if warning["section"] == case["sheet"]
                ],
                "information": [
                    {"code": item["code"], "range": item.get("range")}
                    for item in report.get("information", []) if item["section"] == case["sheet"]
                ],
            },
        })

    manifest = {
        "version": 1,
        "description": f"{len(cases)} paired HTML input-grid screenshots and exact captured Markdown sheet excerpts",
        "capture": {
            "capturedAt": run["capturedAt"],
            "mode": run["mode"],
            "httpStatus": run["response"]["status"],
            "sourceCommit": run["sourceCommit"],
            "sourceWorkbook": cases_manifest["input"],
            "sourceWorkbookSha256": sha256_file(HERE / cases_manifest["input"]),
            "document": "output/document.md",
            "documentSha256": sha256_file(HERE / "output/document.md"),
            "report": "output/report.json",
            "reportSha256": sha256_file(HERE / "output/report.json"),
        },
        "cases": records,
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    (PAIRS / "manifest.json").write_bytes(manifest_bytes)

    index = [
        "# Excel結合・見出しのスクリーンショットと変換後Markdown",
        "",
        "各スクリーンショットは入力ブックのセル・直接塗り・結合を比較ページ上でHTML表示したものです。Excelアプリ画面ではありません。Markdownは実際の変換結果 `output/document.md` から、対応するシートの文字列を変えずに切り出しています。",
        "",
        "| ID | 入力パターン・シート | スクリーンショット | 変換後Markdown |",
        "| --- | --- | --- | --- |",
    ]
    for case in cases:
        case_id = case["id"]
        index.append(
            f"| {case_id} | {case['title']}<br><code>{case['sheet']}</code> | "
            f"[PNG](../screenshots/{case_id}.png) | [Markdown]({case_id}/document.md) |"
        )
    index.extend(["", "入力全体: [input.xlsx](../input.xlsx) · [document.md](../output/document.md) · [report.json](../output/report.json) · [実行記録](../run.json)", ""])
    (PAIRS / "index.md").write_text("\n".join(index), encoding="utf-8")

    missing = [case["id"] for case in cases if case["id"] not in screenshots]
    if missing:
        if BUNDLE.exists():
            BUNDLE.unlink()
        print(f"Wrote {len(cases)} exact Markdown excerpts and manifest; ZIP awaits screenshots: {', '.join(missing)}")
        return

    bundle_index = [
        "# Excel結合・見出しのスクリーンショットと変換後Markdown",
        "",
        "各ケースのフォルダーには、入力ブックのセル・直接塗り・結合をHTML表示した画面のスクリーンショットと、実際の変換結果から切り出したMarkdownが入っています。Excelアプリ画面のスクリーンショットではありません。",
        "",
        "| ID | 入力パターン・シート | スクリーンショット | 変換後Markdown |",
        "| --- | --- | --- | --- |",
    ]
    for case in cases:
        case_id = case["id"]
        bundle_index.append(
            f"| {case_id} | {case['title']}<br><code>{case['sheet']}</code> | "
            f"[PNG]({case_id}/screenshot.png) | [Markdown]({case_id}/document.md) |"
        )
    bundle_index.extend(["", "元データ: [input.xlsx](source/input.xlsx) · [document.md](source/document.md) · [report.json](source/report.json) · [実行記録](source/run.json)", ""])
    with ZipFile(BUNDLE, "w") as archive:
        zip_write(archive, "manifest.json", manifest_bytes)
        zip_write(archive, "index.md", "\n".join(bundle_index).encode("utf-8"))
        for case in cases:
            case_id = case["id"]
            zip_write(archive, f"{case_id}/screenshot.png", screenshots[case_id])
            zip_write(archive, f"{case_id}/document.md", excerpts[case_id])
        zip_write(archive, "source/input.xlsx", (HERE / cases_manifest["input"]).read_bytes())
        zip_write(archive, "source/document.md", (HERE / "output/document.md").read_bytes())
        zip_write(archive, "source/report.json", (HERE / "output/report.json").read_bytes())
        zip_write(archive, "source/run.json", (HERE / "run.json").read_bytes())
    print(f"Wrote {len(cases)} complete screenshot/Markdown pairs to {BUNDLE}")


if __name__ == "__main__":
    main()
