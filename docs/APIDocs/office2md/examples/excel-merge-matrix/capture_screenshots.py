#!/usr/bin/env python3
"""Render selected input Excel case grids in a real browser for paired evidence."""

from __future__ import annotations

import argparse
from html import escape
import json
import os
from pathlib import Path
import subprocess
import tempfile

from render import HERE, source_grid


DEFAULT_CHROME = Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
SCREENSHOTS = HERE / "screenshots"


def case_page(case: dict) -> str:
    return f"""<!doctype html><html lang="ja"><head><meta charset="utf-8">
<style>
*{{box-sizing:border-box}}html,body{{margin:0;background:#fff;color:#26372c;font-family:-apple-system,BlinkMacSystemFont,'Noto Sans CJK JP',sans-serif}}
.card{{width:640px;min-height:700px;border:1px solid #dce5de;border-radius:18px;overflow:hidden}}
h1{{margin:0;padding:26px 30px;background:#f7faf7;border-bottom:1px solid #dce5de;font-size:24px;line-height:1.35}}
.body{{padding:25px 30px}}.legend{{display:flex;gap:13px;align-items:flex-start;margin:0 0 22px;color:#5c7162;font-size:16px;line-height:1.7}}
.swatch{{display:inline-block;flex:none;width:22px;height:22px;background:#dcefdc;border:1px solid #bad7bd;border-radius:5px;margin-top:3px}}
.sheet-grid{{width:100%;border-spacing:0;border:1px solid #d9e3da;border-radius:10px;overflow:hidden;table-layout:fixed;font-size:17px}}
.sheet-grid th,.sheet-grid td{{border:1px solid #d9e3da;padding:12px 6px;text-align:center;overflow-wrap:anywhere;height:64px;vertical-align:middle}}
.sheet-grid .sheet-axis{{background:#f3f6f4;color:#67786a;font-size:14px;font-weight:700;width:52px}}
.sheet-grid .sheet-filled{{background:#dcefdc}}.sheet-grid .sheet-hidden{{opacity:.58}}
.sr-only{{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}}
</style></head><body><div class="card"><h1>入力Excelのセル・結合・色</h1><div class="body">
<p class="legend"><span class="swatch" aria-hidden="true"></span><span>緑のセル＝直接塗り。枠線は各セルの直接罫線。結合セルは実際の範囲で表示。</span></p>
{source_grid(case)}
</div></div></body></html>"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case_ids", nargs="*", default=["C23", "C24"])
    parser.add_argument("--chrome", type=Path, default=Path(os.environ.get("CHROME_PATH", DEFAULT_CHROME)))
    args = parser.parse_args()
    if not args.chrome.is_file():
        parser.error(f"Chrome executable not found: {args.chrome}")
    manifest = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    cases = {case["id"]: case for case in manifest["cases"]}
    SCREENSHOTS.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="office2md-excel-grids-") as work:
        for case_id in args.case_ids:
            if case_id not in cases:
                parser.error(f"Unknown case ID: {case_id}")
            source = Path(work) / f"{case_id}.html"
            source.write_text(case_page(cases[case_id]), encoding="utf-8")
            target = SCREENSHOTS / f"{case_id}.png"
            subprocess.run([
                str(args.chrome), "--headless=new", "--no-sandbox", "--disable-gpu",
                "--hide-scrollbars", "--force-device-scale-factor=1", "--window-size=640,700",
                f"--screenshot={target}", source.as_uri(),
            ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print(f"Captured {case_id}: {target.relative_to(HERE)}")


if __name__ == "__main__":
    main()
