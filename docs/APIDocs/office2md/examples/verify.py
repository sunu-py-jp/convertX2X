#!/usr/bin/env python3
"""Validate the published evidence without starting a server or recalculating Office files."""
from pathlib import Path
import hashlib
import json
import re
import zipfile

HERE = Path(__file__).resolve().parent
EXPECTED = {
    "excel-complex": (8, 34, 17),
    "word-complex": (1, 2, 1),
    "powerpoint-complex": (4, 2, 2),
    "powerpoint-rag-flow": (3, 3, 1),
}


def main():
    for name, expected in EXPECTED.items():
        directory = HERE / name
        run = json.loads((directory / "run.json").read_text())
        report = json.loads((directory / "output/report.json").read_text())
        markdown = (directory / "output/document.md").read_text()
        assert run["response"]["status"] == 200
        assert not run["browserErrors"] and not run["externalBrowserRequests"]
        assert (report["sectionCount"], len(report["assets"]), len(report["warnings"])) == expected
        assert report["source"]["sha256"] == run["input"]["sha256"]
        for record in [run["input"], *run["files"]]:
            data = (directory / record["path"]).read_bytes()
            assert len(data) == record["sizeBytes"], record["path"]
            assert hashlib.sha256(data).hexdigest() == record["sha256"], record["path"]
        with zipfile.ZipFile(directory / "result.zip") as archive:
            for member in archive.namelist():
                assert member in ("document.md", "report.json") or re.fullmatch(
                    r"images/[a-z]+-[0-9]+\.[a-z0-9]{1,8}", member), member
                assert archive.read(member) == (directory / "output" / member).read_bytes()
        for asset in report["assets"]:
            assert (directory / "output" / asset["path"]).is_file()
            assert asset["path"] in markdown
        assert "SECRET_REMOVED" not in markdown
        if name == "excel-complex":
            assert "DELETE_" not in markdown and "HIDDEN_" not in markdown
            assert "キャッシュ999" in markdown and "　999" in markdown
            assert any(item["code"] == "GRAPHIC_FRAME_UNSUPPORTED" for item in report["warnings"])
        if name.startswith("powerpoint-"):
            assert "取消線の秘密" not in markdown and "非表示の秘密" not in markdown
            assert "非表示のスライドは出力しない" not in markdown
            metadata = [json.loads(label) for label in re.findall(r'^!\[(.+)\]\(images/[^)]+\)$', markdown, re.M)]
            assert metadata == [block["metadata"] for block in report["blocks"] if "metadata" in block]
            assert len(metadata) == expected[1] and all(set(item) == {"type", "text", "x", "y", "width", "height"} for item in metadata)
            assert "図中の項目：" in markdown
        if name == "powerpoint-rag-flow":
            diagrams = [block for block in report["blocks"] if block["type"] == "diagram"]
            edges = [edge for block in diagrams for edge in block["edges"]]
            assert len(edges) == 14 and sum(edge["status"] == "resolved" for edge in edges) == 13
            assert "接続関係不明" in markdown and "↔" in markdown
            assert "STRIKE_SECRET_RAG_DEMO" not in markdown and "HIDDEN_SECRET_RAG_DEMO" not in markdown
            assert "STRIKE_SECRET_RAG_DEMO" not in json.dumps(report) and "HIDDEN_SECRET_RAG_DEMO" not in json.dumps(report)
        print(f"PASS {name}: input/output/screenshot hashes, ZIP contents, expected actual result")


if __name__ == "__main__":
    main()
