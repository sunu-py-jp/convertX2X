#!/usr/bin/env python3
"""Embed the captured Excel merge matrix's real Markdown/report in a static APIDocs page.

This renderer reads local files only. The generated page therefore also works via file://.
"""

from __future__ import annotations

from collections import defaultdict
from hashlib import sha256
from html import escape
import json
from pathlib import Path
import re
from zipfile import ZipFile


HERE = Path(__file__).resolve().parent
PAGE = HERE.parents[1] / "merge-cases.html"
START = "<!-- CASES:START -->"
END = "<!-- CASES:END -->"
ADDRESS = re.compile(r"([A-Z]+)([1-9][0-9]*)\Z")
RANGE = re.compile(r"([A-Z]+)([1-9][0-9]*):([A-Z]+)([1-9][0-9]*)\Z")
INDEX_GROUPS = [
    ("色付き見出しの行数", range(1, 4)),
    ("色の有無と見出しの境界", range(4, 8)),
    ("見出しセルの結合", range(8, 12)),
    ("全行で同じ幅の結合", range(12, 14)),
    ("明細行とずれた結合", range(14, 17)),
    ("境界・非表示・隣接結合", range(17, 21)),
    ("枠の欠けたマトリックス", range(21, 25)),
]


def columns_to_number(letters: str) -> int:
    number = 0
    for letter in letters:
        number = number * 26 + ord(letter) - ord("A") + 1
    return number


def number_to_columns(number: int) -> str:
    if number < 1:
        raise ValueError(number)
    letters = ""
    while number:
        number, index = divmod(number - 1, 26)
        letters = chr(ord("A") + index) + letters
    return letters


def parse_address(value: str) -> tuple[int, int]:
    match = ADDRESS.fullmatch(value)
    if not match:
        raise ValueError(f"Invalid cell address: {value}")
    return columns_to_number(match.group(1)), int(match.group(2))


def parse_range(value: str) -> tuple[int, int, int, int]:
    match = RANGE.fullmatch(value)
    if not match:
        raise ValueError(f"Invalid range: {value}")
    first_column, first_row = parse_address(match.group(1) + match.group(2))
    last_column, last_row = parse_address(match.group(3) + match.group(4))
    if last_column < first_column or last_row < first_row:
        raise ValueError(f"Backwards range: {value}")
    return first_column, first_row, last_column, last_row


def json_text(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def verify_capture(manifest: dict, report: dict, run: dict, markdown: str) -> None:
    manifest_bytes = (HERE / "cases.json").read_bytes()
    if sha256(manifest_bytes).hexdigest() != run["caseManifest"]["sha256"]:
        raise ValueError("Case manifest differs from the captured result")
    input_bytes = (HERE / manifest["input"]).read_bytes()
    digest = sha256(input_bytes).hexdigest()
    if digest != report["source"]["sha256"] or digest != run["input"]["sha256"]:
        raise ValueError("Input workbook differs from the captured result")
    if run["response"]["status"] != 200:
        raise ValueError("The capture was not a successful HTTP conversion")
    if report["sectionCount"] != len(manifest["cases"]):
        raise ValueError("The captured section count differs from the case manifest")
    with ZipFile(HERE / "result.zip") as archive:
        if archive.read("document.md") != markdown.encode("utf-8"):
            raise ValueError("document.md differs from the captured ZIP")
        if archive.read("report.json") != (HERE / "output/report.json").read_bytes():
            raise ValueError("report.json differs from the captured ZIP")
    for item in run["files"]:
        file = HERE / item["path"]
        if sha256(file.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError(f"Captured file hash mismatch: {file}")


def source_grid(case: dict) -> str:
    first_column, first_row, last_column, last_row = parse_range(case["tableRange"])
    merges = {}
    covered = set()
    for value in case["mergedRanges"]:
        left, top, right, bottom = parse_range(value)
        merges[(left, top)] = (right - left + 1, bottom - top + 1, value)
        for row in range(top, bottom + 1):
            for column in range(left, right + 1):
                if (column, row) != (left, top):
                    covered.add((column, row))
    heading = "".join(f"<th scope=\"col\" class=\"sheet-axis\">{number_to_columns(column)}</th>"
                      for column in range(first_column, last_column + 1))
    rows = [f'<table class="sheet-grid"><caption class="sr-only">{escape(case["sheet"])} の入力セル</caption>',
            f'<thead><tr><th class="sheet-axis"></th>{heading}</tr></thead><tbody>']
    for row in case["rows"]:
        number = row["number"]
        if number < first_row or number > last_row:
            continue
        values = {parse_address(cell["address"]): cell for cell in row["cells"]}
        hidden = bool(row.get("hidden"))
        marker = ' <small>非表示</small>' if hidden else ''
        row_class = ' class="sheet-hidden"' if hidden else ''
        pieces = [f'<tr{row_class}><th scope="row" class="sheet-axis">{number}{marker}</th>']
        for column in range(first_column, last_column + 1):
            location = (column, number)
            if location in covered:
                continue
            cell = values.get(location, {})
            span = merges.get(location)
            span_attrs = "" if not span else f' colspan="{span[0]}" rowspan="{span[1]}"'
            color = ' class="sheet-filled"' if cell.get("directFill") else ""
            borders = cell.get("directBorders", {})
            missing = [side for side in ("top", "bottom", "left", "right")
                       if borders.get(side) is False]
            border_style = (f' style="{";".join(f"border-{side}:0" for side in missing)}"'
                            if missing else "")
            address = f"{number_to_columns(column)}{number}"
            label = f'{address}; 明示結合 {span[2]}' if span else address
            if missing:
                label += "; 直接罫線なし: " + ", ".join(missing)
            value = escape(str(cell.get("value", ""))).replace("\n", "<br>") or "&nbsp;"
            pieces.append(f'<td{color}{span_attrs}{border_style} title="{escape(label, quote=True)}">{value}</td>')
        pieces.append("</tr>")
        rows.append("".join(pieces))
    rows.append("</tbody></table>")
    return "\n".join(rows)


def section_bodies(markdown: str, cases: list[dict]) -> dict[str, str]:
    headings = list(re.finditer(r"(?m)^# \[(.*?)\] シート[ \t]*$", markdown))
    if len(headings) != len(cases):
        raise ValueError("Markdown sheet headings differ from the manifest")
    bodies = {}
    for index, (case, heading) in enumerate(zip(cases, headings)):
        name = re.sub(r"\\(.)", r"\1", heading.group(1))
        if name != case["sheet"]:
            raise ValueError(f"Markdown sheet order differs at {case['id']}: {name}")
        end = headings[index + 1].start() if index + 1 < len(headings) else len(markdown)
        bodies[name] = markdown[heading.end():end].strip("\n")
    return bodies


def markdown_for_case(sheet: str, blocks: list[dict], lines: list[str], bodies: dict[str, str]) -> str:
    body = bodies[sheet]
    for block in blocks:
        first, last = block["markdownStartLine"], block["markdownEndLine"]
        if not 1 <= first <= last <= len(lines):
            raise ValueError(f"Invalid Markdown line range for {sheet}: {first}-{last}")
        table = "\n".join(lines[first - 1:last])
        if table not in body:
            raise ValueError(f"Table block is outside the Markdown section for {sheet}")
    # Keep adjacent diagnostics and uncollapsed body-merge notes, not just the table lines.
    return body


def spans_text(blocks: list[dict]) -> str:
    if not blocks:
        return "表未検出"
    values = []
    for block in blocks:
        spans = block.get("sourceColumnSpans", [])
        columns = "; ".join(f"{index + 1}列目={number_to_columns(span['first'])}"
                            + (f":{number_to_columns(span['last'])}" if span["last"] != span["first"] else "")
                            for index, span in enumerate(spans))
        values.append(columns)
    return " / ".join(values)


def case_html(case: dict, blocks: list[dict], snippet: str, information: list[dict]) -> str:
    case_id = escape(case["id"])
    screenshot = HERE / "screenshots" / f"{case['id']}.png"
    screenshot_href = f"examples/excel-merge-matrix/screenshots/{case_id}.png"
    markdown_href = f"examples/excel-merge-matrix/pairs/{case_id}/document.md"
    merged = ", ".join(case["mergedRanges"]) or "なし"
    hidden = ", ".join(str(number) for number in case["hiddenRows"]) or "なし"
    if blocks:
        headers = " / ".join(f'{block.get("header", "不明")}：'
                             + (", ".join(str(number) for number in block.get("headerSourceRows", [])) or "なし")
                             for block in blocks)
    else:
        headers = "表未検出"
    pair_links = [
        '<nav class="merge-pair-links" aria-label="このケースのファイル">',
    ]
    if screenshot.exists():
        pair_links.append(f'<a href="{screenshot_href}" download>入力スクショ（PNG）</a>')
    pair_links.append(f'<a href="{markdown_href}" download>変換後Markdown</a>')
    pair_links.append('</nav>')
    if screenshot.exists():
        source_panel = [
            '<div class="merge-panel"><h3>入力表のスクリーンショット</h3><div class="merge-panel-body">',
            '<figure class="merge-screenshot">',
            f'<a href="{screenshot_href}"><img src="{screenshot_href}" alt="{case_id} {escape(case["title"], quote=True)} の入力セル表" loading="lazy"></a>',
            '<figcaption>入力ブックのセル・色・結合をHTMLで表した画面です。Excelアプリの画面ではありません。</figcaption>',
            '</figure>',
            '<details class="merge-source-details"><summary>セル・結合をHTML表で確認</summary>',
            '<p class="merge-legend"><span class="merge-legend-swatch" aria-hidden="true"></span>緑のセル＝直接塗り。枠線は各セルの直接罫線。結合セルは実際の範囲で表示。</p>',
            source_grid(case),
            '</details>',
            '</div></div>',
        ]
    else:
        source_panel = [
            '<div class="merge-panel"><h3>入力Excelのセル・罫線・結合・色</h3><div class="merge-panel-body">',
            '<p class="merge-legend"><span class="merge-legend-swatch" aria-hidden="true"></span>緑のセル＝直接塗り。枠線は各セルの直接罫線。結合セルは実際の範囲で表示。</p>',
            source_grid(case),
            '</div></div>',
        ]
    return "\n".join([
        f'<section class="merge-case" aria-labelledby="case-{case_id.lower()}">',
        f'<h2 id="case-{case_id.lower()}"><span class="merge-case-id">{case_id}</span>{escape(case["title"])}</h2>',
        f'<p class="merge-note">{escape(case["note"])}</p>',
        *pair_links,
        '<div class="merge-comparison">',
        *source_panel,
        '<div class="merge-panel"><h3>実変換のMarkdown（該当シート）</h3><div class="merge-panel-body">',
        f'<pre><code class="language-markdown">{escape(snippet)}</code></pre>',
        '</div></div>',
        '</div>',
        '<div class="merge-facts">',
        f'<span>入力範囲: <code>{escape(case["tableRange"])}</code></span>',
        f'<span>明示結合: <code>{escape(merged)}</code></span>',
        f'<span>非表示行: <code>{escape(hidden)}</code></span>',
        f'<span>ヘッダー判定・元行: <code>{escape(headers)}</code></span>',
        f'<span>出力列→元列: <code>{escape(spans_text(blocks))}</code></span>',
        *(f'<span>表検出: <code>{escape(item["code"])}</code> / セル <code>{escape(item["range"])}</code></span>'
          for item in information),
        '</div>',
        '</section>',
    ])


def main() -> None:
    manifest = json_text(HERE / "cases.json")
    report = json_text(HERE / "output/report.json")
    run = json_text(HERE / "run.json")
    markdown = (HERE / "output/document.md").read_text(encoding="utf-8")
    verify_capture(manifest, report, run, markdown)
    cases = manifest["cases"]
    lines = markdown.splitlines()
    bodies = section_bodies(markdown, cases)
    by_section = defaultdict(list)
    for block in report["blocks"]:
        if block["type"] == "table":
            by_section[block["section"]].append(block)
    open_top_by_section = defaultdict(list)
    for item in report.get("information", []):
        if item["code"] in ("TABLE_OPEN_TOP_CELL", "TABLE_OPEN_TOP_NOTE"):
            open_top_by_section[item["section"]].append(item)
    by_id = {case["id"]: case for case in cases}
    if len(by_id) != len(cases) or {f"C{number:02d}" for _, group in INDEX_GROUPS for number in group} != set(by_id):
        raise ValueError("Case index groups differ from the manifest")
    index = ['<h2 id="cases">ケース一覧</h2>', '<nav class="merge-case-groups" aria-label="ケースの分類">']
    for label, numbers in INDEX_GROUPS:
        index.append('<div class="merge-index-group">')
        index.append(f'<h3>{escape(label)}</h3>')
        index.append('<ul class="merge-case-index">')
        for number in numbers:
            case = by_id[f"C{number:02d}"]
            index.append(f'<li><a href="#case-{escape(case["id"].lower())}"><span>{escape(case["id"])}</span>{escape(case["title"])}</a></li>')
        index.append('</ul></div>')
    index.append('</nav>')
    index.append(f'<p class="sample-meta">HTTP {run["response"]["status"]} · {len(cases)} ケース · '
                 f'{run["tableCount"]} 検出表 · {run["warningCount"]} 警告</p>')
    for case in cases:
        blocks = by_section[case["sheet"]]
        snippet = markdown_for_case(case["sheet"], blocks, lines, bodies)
        index.append(case_html(case, blocks, snippet, open_top_by_section[case["sheet"]]))
    page = PAGE.read_text(encoding="utf-8")
    if page.count(START) != 1 or page.count(END) != 1:
        raise ValueError("Page is missing unique case markers")
    prefix, rest = page.split(START, 1)
    _, suffix = rest.split(END, 1)
    result = prefix + START + "\n" + "\n".join(index) + "\n" + END + suffix
    PAGE.write_text(result, encoding="utf-8")
    print(f"Rendered {len(cases)} actual Excel merge cases into {PAGE}")


if __name__ == "__main__":
    main()
