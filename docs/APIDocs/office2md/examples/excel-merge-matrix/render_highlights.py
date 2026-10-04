#!/usr/bin/env python3
"""Embed verified Excel before/after highlights in the Office2MD examples page."""

from hashlib import sha256
from html import escape
import json
from pathlib import Path

from build_pairs import exact_sections
from render import HERE, number_to_columns, verify_capture


EXAMPLES = HERE.parents[1] / "examples.html"
START = "<!-- EXCEL-MATRIX-HIGHLIGHTS:START -->"
END = "<!-- EXCEL-MATRIX-HIGHLIGHTS:END -->"
CASE_IDS = ("C02", "C08", "C11", "C12", "C14", "C15", "C21", "C22", "C23", "C24")


def spans(block: dict) -> str:
    result = []
    for index, span in enumerate(block["sourceColumnSpans"], 1):
        first = number_to_columns(span["first"])
        last = number_to_columns(span["last"])
        result.append(f"{index}列目={first}" + (f":{last}" if first != last else ""))
    return "; ".join(result)


def highlight(case: dict, block: dict, markdown: str) -> str:
    case_id = case["id"]
    title = escape(case["title"])
    note = escape(case["note"])
    header_rows = ", ".join(str(number) for number in block["headerSourceRows"]) or "なし"
    merge_ranges = ", ".join(block["mergedRanges"]) or "なし"
    photo = f"examples/excel-merge-matrix/screenshots/{case_id}.png"
    source_file = f"examples/excel-merge-matrix/pairs/{case_id}/document.md"
    return "\n".join((
        f'<div class="sample-case" id="excel-{case_id.lower()}">',
        f'<h3>{case_id}：{title}</h3>',
        f'<p>{note}</p>',
        '<div class="screenshot-grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:start">',
        f'<figure class="example-figure"><a href="{photo}"><img src="{photo}" alt="{case_id}の入力Excelにあるセル、直接塗り、結合範囲をブラウザーで可視化した画面" loading="lazy"></a><figcaption><strong>変換前：入力ブックのセル構造</strong>セル値・罫線・直接塗り・結合範囲を入力XLSXから抽出してブラウザーで表示したスクリーンショットです。Excelアプリの画面ではありません。</figcaption></figure>',
        '<div class="card" style="padding:16px">',
        f'<h4>変換後：document.md の {case_id} シート</h4>',
        f'<pre><code class="language-markdown">{escape(markdown.rstrip())}</code></pre>',
        f'<p style="margin:12px 0 0"><a href="{source_file}" download>このケースのMarkdownをダウンロード</a> · <a href="merge-cases.html#case-{case_id.lower()}">全データを見る</a></p>',
        '</div></div>',
        '<p class="sample-meta">'
        f'元の結合: <code>{escape(merge_ranges)}</code> · '
        f'ヘッダー判定: <code>{escape(block["header"])}</code> · '
        f'元行: <code>{header_rows}</code> · '
        f'出力列→元列: <code>{escape(spans(block))}</code></p>',
        '</div>',
    ))


def main() -> None:
    manifest = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    report = json.loads((HERE / "output/report.json").read_text(encoding="utf-8"))
    run = json.loads((HERE / "run.json").read_text(encoding="utf-8"))
    full_markdown = (HERE / "output/document.md").read_text(encoding="utf-8")
    verify_capture(manifest, report, run, full_markdown)
    assert run["response"]["status"] == 200
    assert run["sectionCount"] == run["tableCount"] == len(manifest["cases"]) == 24
    cases = {case["id"]: case for case in manifest["cases"]}
    blocks = {block["section"]: block for block in report["blocks"] if block["type"] == "table"}
    sections = exact_sections(full_markdown, manifest["cases"])
    pairs = json.loads((HERE / "pairs/manifest.json").read_text(encoding="utf-8"))
    records = {record["id"]: record for record in pairs["cases"]}
    cards = []
    for case_id in CASE_IDS:
        case = cases[case_id]
        block = blocks[case["sheet"]]
        screenshot = HERE / "screenshots" / f"{case_id}.png"
        excerpt = HERE / "pairs" / case_id / "document.md"
        record = records[case_id]
        if sha256(screenshot.read_bytes()).hexdigest() != record["screenshotSha256"]:
            raise ValueError(f"Screenshot differs from verified pair: {case_id}")
        if excerpt.read_bytes() != sections[case_id]:
            raise ValueError(f"Markdown differs from actual HTTP output: {case_id}")
        if sha256(excerpt.read_bytes()).hexdigest() != record["markdownSha256"]:
            raise ValueError(f"Markdown hash differs from verified pair: {case_id}")
        cards.append(highlight(case, block, excerpt.read_text(encoding="utf-8")))
    content = "\n".join((
        '<section class="sample-case" aria-labelledby="excel-matrix">',
        '<h2 id="excel-matrix">Excel：24シートで見出し・結合・罫線を比較</h2>',
        '<p>入力の直接塗り、横・縦結合、先頭だけ罫線のない表、多段見出しを変えた24シートを、現行の変換APIに送った結果です。代表10ケースを入力の画面表示と実際のMarkdownで並べました。残りも<a href="merge-cases.html">24ケースの比較ページ</a>で確認できます。</p>',
        f'<p class="sample-meta">HTTP {run["response"]["status"]} · {run["sectionCount"]} 出力シート · {run["tableCount"]} 検出表 · 警告 {run["warningCount"]} 件</p>',
        '<div class="sample-links"><a href="examples/excel-merge-matrix/input.xlsx" download>入力 XLSX</a><a href="examples/excel-merge-matrix/result.zip" download>実際の出力 ZIP</a><a href="examples/excel-merge-matrix/output/document.md" download>document.md 全文</a><a href="examples/excel-merge-matrix/output/report.json">report.json</a><a href="examples/excel-merge-matrix/pairs.zip" download>24ケースの画像＋Markdown ZIP</a><a href="examples/excel-merge-matrix/run.json">実行記録・SHA-256</a></div>',
        '<div class="callout"><p>通常の罫線表は、先頭から直接色付きの行をヘッダーにします。左上だけ罫線がないマトリックスでは、保存された行列の罫線構造から見出しを判定する <code>matrix-grid</code> もあります。無罫線の注記は表の前に文章として出し、元のセルや結合範囲にない見出しは補いません。図は入力セルのブラウザー表示を撮影したもので、右の文章は実際の <code>document.md</code> から一字も変更せずに掲載しています。</p></div>',
        *cards,
        '<p><a href="merge-cases.html">全24ケースの比較ページへ</a> · <a href="examples/excel-merge-matrix/README.md">入力生成・HTTP実行・検証方法</a></p>',
        '</section>',
    ))
    page = EXAMPLES.read_text(encoding="utf-8")
    if page.count(START) != 1 or page.count(END) != 1:
        raise ValueError("Missing unique Excel highlights markers")
    prefix, rest = page.split(START, 1)
    _, suffix = rest.split(END, 1)
    EXAMPLES.write_text(prefix + START + "\n" + content + "\n" + END + suffix, encoding="utf-8")
    print(f"Updated Excel examples with {len(cards)} verified before/after highlights")


if __name__ == "__main__":
    main()
