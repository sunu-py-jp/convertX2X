#!/usr/bin/env python3
"""Verify the published input cases, HTTP result, and before/after page."""

from collections import Counter
from hashlib import sha256
from html import escape
import json
from pathlib import Path
from zipfile import ZipFile

from build_pairs import BUNDLE, PAIRS, SCREENSHOTS, exact_sections
from render import PAGE, HERE, section_bodies, verify_capture


EXPECTED_HEADERS = {
    "C01": [1], "C02": [1, 2], "C03": [1, 2, 3], "C04": [],
    "C05": [], "C06": [1], "C07": [1], "C08": [1],
    "C09": [1], "C10": [1, 2], "C11": [1, 2], "C12": [1],
    "C13": [1, 2], "C14": [1], "C15": [1], "C16": [1, 2],
    "C17": [1, 2, 3], "C18": [], "C19": [1], "C20": [1],
    "C21": [1], "C22": [1], "C23": [1, 2], "C24": [1, 2, 3],
}
COLLAPSED_COLUMNS = {
    "C12": [(1, 2), (3, 3)],
    "C13": [(1, 2), (3, 3)],
    "C18": [(1, 2), (3, 3)],
    "C19": [(1, 2), (3, 3)],
    "C20": [(1, 2), (3, 4)],
}
BODY_NOTES = {
    "C14": "結合セル A2:B2「共通説明」は明細行の複数列に共通です。",
    "C15": "結合セル A2:A3「営業」は複数の明細行に共通です。",
}


def main() -> None:
    manifest = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    report = json.loads((HERE / "output/report.json").read_text(encoding="utf-8"))
    run = json.loads((HERE / "run.json").read_text(encoding="utf-8"))
    markdown = (HERE / "output/document.md").read_text(encoding="utf-8")
    verify_capture(manifest, report, run, markdown)
    assert run["mode"] == "actual local Functions HTTP"
    assert run["response"]["contentType"].startswith("application/zip")
    case_count = len(manifest["cases"])
    assert case_count == len(EXPECTED_HEADERS) == run["sectionCount"] == run["tableCount"] == 24
    assert len(report["warnings"]) == run["warningCount"]
    assert report["specVersion"] == 1
    assert {case["id"] for case in manifest["cases"]} == set(EXPECTED_HEADERS)
    assert len({case["sheet"] for case in manifest["cases"]}) == case_count
    assert Counter(block["type"] for block in report["blocks"]) == {"table": case_count, "text": 4}
    by_sheet = {block["section"]: block for block in report["blocks"] if block["type"] == "table"}
    assert set(by_sheet) == {case["sheet"] for case in manifest["cases"]}
    bodies = section_bodies(markdown, manifest["cases"])
    excerpts = exact_sections(markdown, manifest["cases"])
    pairs_manifest = json.loads((PAIRS / "manifest.json").read_text(encoding="utf-8"))
    assert [record["id"] for record in pairs_manifest["cases"]] == [case["id"] for case in manifest["cases"]]
    assert pairs_manifest["capture"]["sourceWorkbookSha256"] == run["input"]["sha256"]
    assert pairs_manifest["capture"]["documentSha256"] == sha256((HERE / "output/document.md").read_bytes()).hexdigest()
    page = PAGE.read_text(encoding="utf-8")
    assert page.count('<section class="merge-case"') == case_count
    assert page.count('class="merge-screenshot"') == case_count
    with ZipFile(BUNDLE) as archive:
        assert len(archive.namelist()) == 2 + 2 * case_count + 4
        assert archive.read("manifest.json") == (PAIRS / "manifest.json").read_bytes()
        assert archive.read("source/document.md") == (HERE / "output/document.md").read_bytes()
        for case, record in zip(manifest["cases"], pairs_manifest["cases"]):
            case_id = case["id"]
            assert record["sheet"] == case["sheet"]
            assert record["sourceWorkbook"] == "input.xlsx"
            screenshot = SCREENSHOTS / f"{case_id}.png"
            document = PAIRS / case_id / "document.md"
            assert screenshot.is_file(), case_id
            assert document.read_bytes() == excerpts[case_id], case_id
            assert record["screenshotSha256"] == sha256(screenshot.read_bytes()).hexdigest(), case_id
            assert record["markdownSha256"] == sha256(document.read_bytes()).hexdigest(), case_id
            assert archive.read(record["screenshotInBundle"]) == screenshot.read_bytes(), case_id
            assert archive.read(record["markdownInBundle"]) == document.read_bytes(), case_id
            assert f'examples/excel-merge-matrix/screenshots/{case_id}.png' in page, case_id
            assert f'examples/excel-merge-matrix/pairs/{case_id}/document.md' in page, case_id
    for case in manifest["cases"]:
        case_id, sheet = case["id"], case["sheet"]
        block = by_sheet[sheet]
        assert block["headerSourceRows"] == EXPECTED_HEADERS[case_id], case_id
        spans = [(item["first"], item["last"]) for item in block["sourceColumnSpans"]]
        assert spans == COLLAPSED_COLUMNS.get(case_id, [(col, col) for col in range(1, spans[-1][1] + 1)]), case_id
        assert block["mergedRanges"] == case["mergedRanges"], case_id
        snippet = bodies[sheet]
        assert snippet.count("| --- |") == 1 or "| --- | --- |" in snippet, case_id
        if case_id in BODY_NOTES:
            assert snippet.startswith(BODY_NOTES[case_id] + "\n\n"), case_id
            assert escape(BODY_NOTES[case_id]) in page
        else:
            assert "結合セル " not in snippet, case_id
        assert f'id="case-{case_id.lower()}"' in page, case_id
        assert escape(snippet) in page, case_id
    assert "| 共通説明 |  | 完了 |" in bodies[manifest["cases"][13]["sheet"]]
    assert "| 営業 | 佐藤 | 進行中 |\n|  | 鈴木 | 完了 |" in bodies[manifest["cases"][14]["sheet"]]
    assert "|  |  |\n| --- | --- |\n| 項目 | 状態 |" in bodies[manifest["cases"][17]["sheet"]]
    assert by_sheet[manifest["cases"][18]["sheet"]]["sourceRows"] == [1, 3, 4]
    for case in manifest["cases"]:
        borderless = [cell["address"] for row in case["rows"] for cell in row["cells"]
                      if not any(cell["directBorders"].values())]
        assert borderless == {
            "C21": ["A1"], "C22": ["D1"],
            "C23": ["A1", "B1"], "C24": ["A1", "B1", "E1", "F1"],
        }.get(case["id"], []), case["id"]
    for case_id in ("C21", "C22"):
        case = next(item for item in manifest["cases"] if item["id"] == case_id)
        assert any(item["code"] == "TABLE_OPEN_TOP_CELL" and item["section"] == case["sheet"]
                   for item in report["information"]), case_id
        assert f'表検出: <code>TABLE_OPEN_TOP_CELL</code> / セル <code>{"A1" if case_id == "C21" else "D1"}</code>' in page
    for case_id in ("C23", "C24"):
        case = next(item for item in manifest["cases"] if item["id"] == case_id)
        snippet = bodies[case["sheet"]]
        assert snippet.startswith("集計対象: 店舗　単位: 千円\n\n"), case_id
        assert [item["range"] for item in report["information"]
                if item["section"] == case["sheet"] and item["code"] == "TABLE_OPEN_TOP_NOTE"] == ["A1", "B1"], case_id
        assert any(item["code"] == "MATRIX_HEADER_INFERRED" and item["section"] == case["sheet"]
                   for item in report["information"]), case_id
    c23 = bodies[next(case["sheet"] for case in manifest["cases"] if case["id"] == "C23")]
    assert "| ID | 場所 | 売上 / 1月 | 売上 / 2月 | 売上 / 3月 |" in c23
    c24 = bodies[next(case["sheet"] for case in manifest["cases"] if case["id"] == "C24")]
    assert "| ID | 場所 | 売上 / 前半 / 1月 | 売上 / 後半 / 1月 | 前半 / 2月 | 後半 / 2月 |" in c24
    print(f"PASS excel-merge-matrix: {case_count} source cases, actual HTTP tables, screenshot/Markdown pairs")


if __name__ == "__main__":
    main()
