#!/usr/bin/env python3
"""Copy original repo fixtures, correcting three stale descriptive Excel labels only."""
from pathlib import Path
import shutil
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SOURCE = ROOT / "functions/office2md/samples"
REPLACEMENTS = {
    "GRP01  明示グループ：図形・線・画像を1枚に合成":
        "GRP01  明示グループ：図形・線・画像を個別に出力",
    "GRP04  未グループ：描画領域の重なりで1枚にまとめる":
        "GRP04  未グループ：重なった図形も個別に出力",
    "GRP05  背景図形＋PNG：合成画像として一度だけ出力":
        "GRP05  背景図形＋PNG：図形と画像を別々に出力",
}


def main():
    destination = HERE / "excel-complex/input.xlsx"
    destination.parent.mkdir(parents=True, exist_ok=True)
    replaced = 0
    with zipfile.ZipFile(SOURCE / "full-feature.xlsx") as source:
        with zipfile.ZipFile(destination, "w") as target:
            for info in source.infolist():
                data = source.read(info.filename)
                if info.filename == "xl/sharedStrings.xml":
                    text = data.decode("utf-8")
                    for old, new in REPLACEMENTS.items():
                        assert text.count(old) == 1, old
                        text = text.replace(old, new)
                        replaced += 1
                    data = text.encode("utf-8")
                target.writestr(info, data)
    assert replaced == 3
    for case, name in (("word-complex", "office-sample.docx"),
                       ("powerpoint-complex", "office-sample.pptx")):
        target = HERE / case / ("input" + Path(name).suffix)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE / name, target)
    print("Prepared 3 original Office examples; only 3 Excel descriptive labels adjusted.")


if __name__ == "__main__":
    main()
