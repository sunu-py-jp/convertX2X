#!/usr/bin/env python3
"""Convert the 120-column / 300-row fixture through the real HTTP API."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import os
from pathlib import Path
import urllib.parse
from datetime import datetime, timezone

from run_additional_examples import extract_result, request, summary, write_json


ROOT = Path(__file__).resolve().parents[3]
EXAMPLE = ROOT / "docs/APIDocs/excel2img/examples/large-pagination"


def column(number: int) -> str:
    label = ""
    while number:
        number, digit = divmod(number - 1, 26)
        label = chr(65 + digit) + label
    return label


def address(page: dict) -> str:
    return (f"{column(page['firstColumn'])}{page['firstRow']}:"
            f"{column(page['lastColumn'])}{page['lastRow']}")


def verify_coverage(report: dict) -> None:
    """Every source cell must occur on exactly one page, in reading order."""
    coverage = bytearray(120 * 300)
    windows = []
    for page in report["pages"]:
        first_row, last_row = page["firstRow"], page["lastRow"]
        first_col, last_col = page["firstColumn"], page["lastColumn"]
        assert 1 <= first_row <= last_row <= 300
        assert 1 <= first_col <= last_col <= 120
        windows.append((first_row, first_col))
        for row in range(first_row - 1, last_row):
            for col in range(first_col - 1, last_col):
                index = row * 120 + col
                assert coverage[index] == 0, "A source cell appears on multiple pages"
                coverage[index] = 1
    assert all(coverage), "Some source cells are missing"
    assert windows == sorted(windows), "Pages must proceed across, then down"
    assert len({row for row, _ in windows}) > 1, "Vertical pagination is missing"
    assert len({col for _, col in windows}) > 1, "Horizontal pagination is missing"
    assert all(item["status"] == "rendered" for item in report["ranges"])
    source = report["ranges"][0]["sourceBoundsPoints"]
    bands = {}
    for page in report["pages"]:
        bands.setdefault(page["firstRow"], []).append(page["sourceBoundsPoints"])
    bottom = source["y"]
    for band in bands.values():
        top, height = band[0]["y"], band[0]["height"]
        assert math.isclose(top, bottom, abs_tol=1e-6), "Vertical windows have a gap or overlap"
        right = source["x"]
        for window in band:
            assert math.isclose(window["y"], top, abs_tol=1e-6)
            assert math.isclose(window["height"], height, abs_tol=1e-6)
            assert math.isclose(window["x"], right, abs_tol=1e-6), "Horizontal windows have a gap or overlap"
            right += window["width"]
        assert math.isclose(right, source["x"] + source["width"], abs_tol=1e-6)
        bottom = top + height
    assert math.isclose(bottom, source["y"] + source["height"], abs_tol=1e-6)


def gallery(run: dict, report: dict) -> str:
    pages = report["pages"]
    columns = sorted({p["firstColumn"] for p in pages})
    rows = sorted({p["firstRow"] for p in pages})
    scale = report["ranges"][0]["pixelsPerPoint"]
    png_run, pdf_run = run["requests"]
    cards, table_rows = [], []
    for page in pages:
        number = page["page"]
        path = "png/" + page["path"]
        source = address(page)
        cards.append(
            f'<a class="tile" href="{path}" target="_blank" rel="noopener">'
            f'<span class="tile-head"><b>{number:02d}</b><span>{source}</span></span>'
            f'<img src="{path}" alt="ページ{number}・{source}の実変換画像">'
            f'<span class="tile-foot">{page["widthPixels"]} × {page["heightPixels"]} px</span></a>')
        table_rows.append(
            f'<tr><td><a href="{path}">{number:02d}</a></td><td>{source}</td>'
            f'<td>{page["firstRow"]}〜{page["lastRow"]}行</td>'
            f'<td>{column(page["firstColumn"])}〜{column(page["lastColumn"])}</td>'
            f'<td>{page["widthPixels"]} × {page["heightPixels"]}</td></tr>')
    warnings = "".join(
        f'<li><code>{html.escape(w["code"])}</code>：{html.escape(w["message"])}</li>'
        for w in report["warnings"])
    return f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>120列 × 300行 — Excelの縦横ページ分割</title>
<style>
:root{{font-family:-apple-system,BlinkMacSystemFont,"Noto Sans JP",sans-serif;color:#183a44;background:#f1f5f4}}
*{{box-sizing:border-box}}body{{margin:0}}main{{max-width:1500px;margin:auto;padding:28px}}
a{{color:#09695c}}.eyebrow{{font-size:12px;letter-spacing:.14em;color:#53726d;font-weight:700}}
h1{{font-size:30px;margin:10px 0}}p{{line-height:1.7}}.lead{{margin:0;color:#526764}}
.metrics{{display:flex;gap:12px;flex-wrap:wrap;margin:22px 0 16px}}
.metric{{background:white;border:1px solid #d2dfdb;border-radius:10px;padding:13px 20px;flex:1;min-width:180px}}
.metric b{{display:block;font-size:26px;margin-bottom:5px}}.metric span{{font-size:13px;color:#526764}}
.downloads{{display:flex;gap:10px;flex-wrap:wrap;margin:16px 0}}
.downloads a{{background:#116859;color:white;padding:10px 14px;border-radius:7px;text-decoration:none;font-size:14px}}
.order{{background:#e0eeea;border-left:4px solid #168571;padding:12px 16px;font-size:14px}}
.grid{{display:grid;grid-template-columns:repeat({len(columns)},minmax(0,1fr));gap:12px;margin:20px 0 30px}}
.tile{{display:flex;flex-direction:column;min-width:0;background:white;border:1px solid #ccdcd6;border-radius:8px;overflow:hidden;text-decoration:none;box-shadow:0 2px 5px #183a440a}}
.tile:hover{{border-color:#168571}}.tile-head{{display:flex;align-items:center;gap:9px;padding:9px;font-size:12px;white-space:nowrap}}
.tile-head b{{background:#116859;color:white;padding:5px;border-radius:4px}}
.tile img{{display:block;object-fit:contain;object-position:top;width:100%;height:190px;background:#fafcfb}}
.tile-foot{{font-size:11px;padding:7px 10px;color:#61746f}}
details{{background:white;padding:18px;border-radius:9px;margin:18px 0}}summary{{cursor:pointer;font-weight:700}}
table{{border-collapse:collapse;width:100%;margin-top:15px;font-size:14px}}th,td{{padding:8px;border-bottom:1px solid #dbe5e1;text-align:left}}
li{{line-height:1.7}}footer{{font-size:12px;color:#61746f;line-height:1.8}}
@media(max-width:800px){{main{{padding:16px}}h1{{font-size:25px}}.metrics{{display:grid;grid-template-columns:1fr 1fr}}.metric{{min-width:0;padding:10px}}.metric b{{font-size:22px}}.grid{{gap:5px}}.tile-head{{display:block;font-size:9px;padding:5px;white-space:normal;overflow-wrap:anywhere}}.tile-head b{{display:inline-block;margin-bottom:4px}}.tile-head span{{display:block}}.tile img{{height:120px}}.tile-foot{{font-size:9px;padding:5px}}}}
</style></head><body><main>
<div class="eyebrow">EXCEL2IMG / 実API変換サンプル</div>
<h1>120列 × 300行を、縦横に分割</h1>
<p class="lead">A1:DP300の架空データ36,000セルを変換。下の各画像をクリックすると、出力PNGを原寸で開けます。</p>
<div class="metrics">
<div class="metric"><b>横 {len(columns)} × 縦 {len(rows)}</b><span>合計 {len(pages)}ページ / 全範囲を出力</span></div>
<div class="metric"><b>{scale / 2 * 100:.1f}%</b><span>既定倍率から縮小 / 11pt文字 → {11 * scale:.0f}px</span></div>
<div class="metric"><b>{png_run["elapsedMs"] / 1000:.1f}秒</b><span>PNG変換のローカルHTTP往復時間</span></div>
<div class="metric"><b>{pdf_run["elapsedMs"] / 1000:.1f}秒</b><span>PDF変換のローカルHTTP往復時間</span></div>
</div>
<div class="downloads"><a href="input/large-120-columns-300-rows.xlsx" download>元のExcel</a>
<a href="png.zip" download>全PNGをZIPで保存</a><a href="pdf/document.pdf">全ページのPDF</a>
<a href="png/report.json">変換レポート</a></div>
<div class="order">ページ順：同じ高さを左 → 右に進み、その次の段へ。下の一覧も元シートと同じ配置です。
ヘッダの自動再掲は行わず、各ページに元のセル範囲をそのまま描画します。</div>
<div class="map"><div class="grid">{''.join(cards)}</div></div>
<details><summary>各ページのセル範囲</summary><table><thead><tr><th>ページ</th><th>範囲</th><th>行</th><th>列</th><th>出力px</th></tr></thead>
<tbody>{''.join(table_rows)}</tbody></table></details>
<details><summary>変換時の注意事項（{len(report["warnings"])}件）</summary><ul>{warnings}</ul></details>
<footer>全36,000セルがページ群に重複・欠落なく含まれること、PNGとPDFのページ範囲が一致すること、
PNG寸法と成果物ハッシュがreportと一致することを検証済み。<br>
使用設定：最大横幅2048px、最大高さ2800px、最小文字サイズ12px。所要時間はローカル実行時の値です。
<a href="run.json">実行記録</a> · <a href="../../examples.html#large-pagination">変換例一覧へ</a></footer>
</main></body></html>"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:7076/api")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.query or parsed.fragment:
        parser.error("Use an HTTP(S) API URL without credentials, query or fragment")
    source = EXAMPLE / "input/large-120-columns-300-rows.xlsx"
    data = source.read_bytes()
    key = os.environ.get("EXCEL2IMG_FUNCTION_KEY", "")
    status, _, content, _ = request(base + "/capabilities", None, 30, 65536, key)
    assert status == 200
    capabilities = json.loads(content)
    maximum = int(capabilities["maxOutputBytes"])
    run = {"startedAt": datetime.now(timezone.utc).isoformat(), "transport": "real HTTP",
           "input": {"path": str(source.relative_to(EXAMPLE)), "bytes": len(data),
                     "sha256": hashlib.sha256(data).hexdigest(), "rows": 300, "columns": 120},
           "capabilities": capabilities, "requests": []}
    reports = {}
    for fmt in ("png", "pdf"):
        params = {"filename": source.name, "format": fmt}
        status, headers, response, elapsed = request(
            base + "/convert?" + urllib.parse.urlencode(params), data, 180, maximum, key)
        if status != 200:
            raise RuntimeError(f"{fmt}: HTTP {status}: {response.decode('utf-8', 'replace')[:1000]}")
        assert "application/zip" in headers.get("Content-Type", "")
        report = extract_result(response, EXAMPLE / fmt, fmt, maximum)
        assert report["source"]["sha256"] == run["input"]["sha256"]
        verify_coverage(report)
        (EXAMPLE / f"{fmt}.zip").write_bytes(response)
        reports[fmt] = report
        run["requests"].append({"format": fmt, "httpStatus": status, "elapsedMs": elapsed,
                                "responseBytes": len(response), "parameters": params,
                                **summary(report)})
        write_json(EXAMPLE / "run.json", run)
        print(f"{fmt}: HTTP {status}, {report['pageCount']} pages, {report['outcome']}, {elapsed}ms", flush=True)
    fields = ("firstRow", "lastRow", "firstColumn", "lastColumn", "widthPixels", "heightPixels")
    assert [[p[f] for f in fields] for p in reports["png"]["pages"]] == [
        [p[f] for f in fields] for p in reports["pdf"]["pages"]]
    assert [p["sourceBoundsPoints"] for p in reports["png"]["pages"]] == [
        p["sourceBoundsPoints"] for p in reports["pdf"]["pages"]]
    run["verification"] = {"cellCoverageExactlyOnce": 36000, "pngPdfPageBoundsEqual": True}
    run["completedAt"] = datetime.now(timezone.utc).isoformat()
    write_json(EXAMPLE / "run.json", run)
    (EXAMPLE / "index.html").write_text(gallery(run, reports["png"]), encoding="utf-8")


if __name__ == "__main__":
    main()
