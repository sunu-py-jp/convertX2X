#!/usr/bin/env python3
"""Refresh only the Word and PowerPoint sections from captured real outputs."""

from __future__ import annotations

from html import escape
import json
from pathlib import Path
import re


HERE = Path(__file__).resolve().parent
PAGE = HERE.parent / "examples.html"


def load(name: str) -> tuple[dict, dict, str]:
    case = HERE / name
    run = json.loads((case / "run.json").read_text(encoding="utf-8"))
    report = json.loads((case / "output/report.json").read_text(encoding="utf-8"))
    markdown = (case / "output/document.md").read_text(encoding="utf-8")
    if run["response"]["status"] != 200 or report["sectionCount"] != run["sectionCount"]:
        raise ValueError(f"Invalid captured conversion: {name}")
    return run, report, markdown


def code(value: str, language: str = "language-markdown") -> str:
    return f'<pre><code class="{language}">{escape(value)}</code></pre>'


def details(label: str, value: str, language: str) -> str:
    return f'<details class="sample-details"><summary>{escape(label)}</summary>{code(value, language)}</details>'


def links(name: str, suffix: str) -> str:
    prefix = f"examples/{name}/"
    return "\n".join([
        '<div class="sample-links">',
        f'<a href="{prefix}input.{suffix}" download>入力 {suffix.upper()}</a>',
        f'<a href="{prefix}result.zip" download>実際の出力 ZIP</a>',
        f'<a href="{prefix}output/document.md" download>document.md</a>',
        f'<a href="{prefix}output/report.json">report.json</a>',
        f'<a href="{prefix}run.json">実行記録・SHA-256</a>',
        '</div>',
    ])


def figure(name: str, filename: str, alt: str, caption: str) -> str:
    href = f"examples/{name}/screenshots/{filename}"
    if not (HERE / name / "screenshots" / filename).is_file():
        raise FileNotFoundError(href)
    return (f'<figure class="example-figure"><a href="{href}"><img src="{href}" '
            f'alt="{escape(alt, quote=True)}" loading="lazy"></a>'
            f'<figcaption>{escape(caption)} 画像をクリックすると原寸で表示します。</figcaption></figure>')


def meta(name: str, run: dict, kind: str) -> str:
    case = HERE / name
    archive_size = (case / "result.zip").stat().st_size
    unit = "表示スライド" if kind == "slides" else "出力セクション"
    return (f'<p class="sample-meta">HTTP {run["response"]["status"]} · '
            f'入力 {run["input"]["sizeBytes"]:,} bytes · ZIP {archive_size:,} bytes · '
            f'{run["sectionCount"]} {unit} · {run["assetCount"]} アセット · '
            f'警告 {run["warningCount"]} 件</p>')


def excerpt(markdown: str, begin: str, end: str | None) -> str:
    start = markdown.index(begin)
    stop = markdown.index(end, start + len(begin)) if end else len(markdown)
    return markdown[start:stop].strip("\n")


def word_section() -> str:
    name = "word-complex"
    run, report, markdown = load(name)
    if "## **判定条件と複数段落の表**" not in markdown or "[^footnote-2]" not in markdown:
        raise ValueError("Expanded Word output was not captured")
    table = excerpt(markdown, "## **判定条件と複数段落の表**", "## **番号と脚注**")
    parts = [
        '<section class="sample-case" aria-labelledby="word">',
        '<h2 id="word">Word：2種類の結合表・脚注・図形を実変換</h2>',
        '<p>編集可能なDOCXに、罫線なしの結合表と3列の判定表、セル内の複数段落、太字とリンク、変更履歴、3から始まる番号、2件の脚注を収録しています。回転した角丸長方形、楕円、埋め込みPNGも同じ文書に入れ、図中の文字をMarkdown本文へ残します。</p>',
        meta(name, run, "sections"),
        links(name, "docx"),
        '<div class="screenshot-grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:start">',
        figure(name, "section-01.png", "Word変換結果のPlayground表示。2つの表と脚注、2つの図形、画像を含む。", "実際のPlaygroundプレビュー。新しい3列表、脚注、図形の出力を確認できます。"),
        '<div class="card" style="padding:16px"><h3>変換後：3列の判定表</h3>',
        code(table),
        '<p><a href="examples/word-complex/output/document.md" download>Markdown全文をダウンロード</a></p></div>',
        '</div>',
        '<p>2つのWord表はMarkdown表として出力され、結合セルは開始セルに値を残します。追加したセル内の2段落は <code>&lt;br&gt;</code> でつながります。図形の文字は通常の本文にあり、参考画像3点で形を確認できます。簡易描画に関する2件の警告は下のJSONと実際のレポートに記録しています。</p>',
        details("実際のMarkdown全文", markdown, "language-markdown"),
        details("実際のreport.json：警告", json.dumps(report["warnings"], ensure_ascii=False, indent=2), "language-json"),
        '<p><a href="examples/word-complex/screenshots/overview.png">Playground全体</a> · <a href="examples/word-complex/screenshots/markdown-source.png">Markdownタブ</a> · <a href="examples/word-complex/screenshots/warnings.png">警告の画面</a></p>',
        '</section>',
    ]
    return "\n".join(parts) + "\n"


def word_rag_section() -> str:
    name = "word-rag-flow"
    run, report, markdown = load(name)
    if "| 記録 | 保存される内容 |" in markdown or "図の後の説明" not in markdown:
        raise ValueError("The old Word sample table is still in the captured output")
    flow = excerpt(markdown, "図中の項目：", "![")
    parts = [
        '<section class="sample-case" aria-labelledby="word-rag">',
        '<h2 id="word-rag">Word：RAG向けの購入申請フロー</h2>',
        '<p>編集可能なDrawingMLグループに、申請・確認・完了・修正再申請を配置しました。図の前後には通常の段落を置き、図形の文字と4件の確定した接続、1件の接続不明を本文の位置関係とともに確認できます。</p>',
        meta(name, run, "sections"),
        links(name, "docx"),
        '<div class="screenshot-grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:start">',
        figure(name, "section-01.png", "Word購入申請フローの実変換結果。4つの図形と接続関係を表示。", "実際のPlaygroundプレビュー。図の前後の段落と接続関係が本文に並びます。"),
        '<div class="card" style="padding:16px"><h3>変換後：図形と接続の本文</h3>',
        code(flow),
        '<p><a href="examples/word-rag-flow/output/document.md" download>Markdown全文をダウンロード</a></p></div>',
        '</div>',
        '<p>非表示図形と取消線の検証用文字列は、本文・JSON・画像から除外されています。最下段の独立した矢印は接続先の保存情報がなく、近くの図形に推測で接続しません。</p>',
        details("実際のMarkdown全文", markdown, "language-markdown"),
        details("実際のreport.json：近似・不明な接続の警告", json.dumps(report["warnings"], ensure_ascii=False, indent=2), "language-json"),
        '<p><a href="examples/word-rag-flow/screenshots/overview.png">Playground全体</a> · <a href="examples/word-rag-flow/screenshots/markdown-source.png">Markdownタブ</a> · <a href="examples/word-rag-flow/screenshots/warnings.png">警告の画面</a></p>',
        '</section>',
    ]
    return "\n".join(parts) + "\n"


def powerpoint_section() -> str:
    name = "powerpoint-complex"
    run, report, markdown = load(name)
    if run["sectionCount"] != 6 or "# レビュー経路の図形と補足" not in markdown:
        raise ValueError("Expanded PowerPoint output was not captured")
    table = excerpt(markdown, "[page 5]", "[page 6]")
    diagram = excerpt(markdown, "[page 6]", None)
    parts = [
        '<section class="sample-case" aria-labelledby="powerpoint">',
        '<h2 id="powerpoint">PowerPoint：6スライドの表・図形・画像を実変換</h2>',
        '<p>6表示＋1非表示スライドの編集可能なPPTXです。本文、番号、リンク、罫線なし結合表に加え、4列の条件別判定表、3つの図形を結ぶレビュー経路、埋め込みPNGを含みます。図形の文字と接続関係をMarkdown本文に、図形・線・位置をreport.jsonに残します。</p>',
        meta(name, run, "slides"),
        links(name, "pptx"),
        '<div class="screenshot-grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:start">',
        figure(name, "section-05.png", "PowerPointの5枚目にある条件別判定表の実変換プレビュー", "スライド5の4列表。結合行の値は開始セルに残ります。"),
        '<div class="card" style="padding:16px"><h3>変換後：スライド5の表</h3>',
        code(table),
        '</div></div>',
        '<div class="screenshot-grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:start">',
        figure(name, "section-06.png", "PowerPointの6枚目にあるレビュー経路図と接続関係の実変換プレビュー", "スライド6の図形と接続。3つのラベルと2本の関係が本文に残ります。"),
        '<div class="card" style="padding:16px"><h3>変換後：スライド6の図形</h3>',
        code(diagram),
        '</div></div>',
        '<p>従来の罫線なし結合表と、接続先IDを持たない線の例も残しています。スライド3の線は接続関係不明、スライド6の2本は変換結果で接続先を確定しています。非表示スライド、図形の取消線、非表示図形の文字は本文へ出しません。</p>',
        '<div class="screenshot-grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:start">',
        figure(name, "section-02.png", "PowerPointの2枚目にある罫線なし結合表の実変換プレビュー", "スライド2の罫線なし結合表。"),
        figure(name, "section-03.png", "PowerPointの3枚目にある図形と未解決接続の実変換プレビュー", "スライド3の図形と接続先不明の線。"),
        '</div>',
        details("実際のMarkdown全文：6枚の表示スライド", markdown, "language-markdown"),
        details("実際のreport.json：警告", json.dumps(report["warnings"], ensure_ascii=False, indent=2), "language-json"),
        '<p><a href="examples/powerpoint-complex/output/images/diagram-0003.png">レビュー経路の参考画像</a> · <a href="examples/powerpoint-complex/screenshots/overview.png">Playground全体</a> · <a href="examples/powerpoint-complex/screenshots/markdown-source.png">Markdownタブ</a> · <a href="examples/powerpoint-complex/screenshots/warnings.png">警告の画面</a></p>',
        '</section>',
    ]
    return "\n".join(parts) + "\n"


def main() -> None:
    page = PAGE.read_text(encoding="utf-8")
    cards = {
        "word": '<a class="card" href="#word"><h3>Word：2種類の表と脚注</h3><p>変更履歴、3列表、2件の脚注、2つの図形、画像を実変換。</p></a>',
        "powerpoint": '<a class="card" href="#powerpoint"><h3>PowerPoint：6スライドの表と図</h3><p>2種類の表、レビュー経路、接続線、埋め込み画像を抽出。</p></a>',
    }
    for anchor, replacement in cards.items():
        page, count = re.subn(r'<a class="card" href="#' + anchor + r'">.*?</a>',
                              lambda _: replacement, page, count=1)
        if count != 1:
            raise ValueError(f"Missing index card: {anchor}")
    sections = [
        ("word", "word-rag", word_section()),
        ("word-rag", "powerpoint", word_rag_section()),
        ("powerpoint", "powerpoint-rag", powerpoint_section()),
    ]
    # Each replacement re-reads the current page contents held in memory and
    # addresses only its own section, leaving Excel and other content unchanged.
    for current, following, replacement in sections:
        start = f'<section class="sample-case" aria-labelledby="{current}">'
        stop = f'<section class="sample-case" aria-labelledby="{following}">'
        if page.count(start) != 1 or page.count(stop) != 1:
            raise ValueError(f"Missing unique section boundaries: {current}")
        left, rest = page.split(start, 1)
        _, right = rest.split(stop, 1)
        page = left + replacement + stop + right
    PAGE.write_text(page, encoding="utf-8")
    print("Rendered captured Word, Word flow, and PowerPoint sections into examples.html")


if __name__ == "__main__":
    main()
