#!/usr/bin/env python3
"""Copy the reproducible original Office fixtures into the documentation examples."""
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SOURCE = ROOT / "functions/office2md/samples"
def main():
    destination = HERE / "excel-complex/input.xlsx"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCE / "full-feature.xlsx", destination)
    for case, name in (("word-complex", "office-sample.docx"),
                       ("powerpoint-complex", "office-sample.pptx")):
        target = HERE / case / ("input" + Path(name).suffix)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE / name, target)
    print("Prepared 3 reproducible original Office examples.")


if __name__ == "__main__":
    main()
