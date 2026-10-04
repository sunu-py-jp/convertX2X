#!/usr/bin/env python3
"""Validate the published evidence without starting a server or recalculating Office files."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
EXPECTED = {
    "excel-complex": (8, 33, 20),
    "word-complex": (1, 2, 1),
    "word-rag-flow": (1, 1, 5),
    "powerpoint-complex": (4, 2, 2),
    "powerpoint-rag-flow": (3, 3, 1),
}
SLIDE_POSITIONS = {
    f"{horizontal}-{vertical}"
    for horizontal in ("left", "center", "right")
    for vertical in ("top", "middle", "bottom")
}
ARROW_BEARINGS = SLIDE_POSITIONS - {"center-middle"}
EDGE_KEYS = {
    "arrowheadDirectionAlongLine",
    "connectionResolutionStatus",
    "connectionResolutionReason",
    "connectionResolutionExplanation",
}


def check_diagram_fields(report, name):
    diagrams = [block for block in report["blocks"] if "nodes" in block and "edges" in block]
    edges = [edge for block in diagrams for edge in block["edges"]]
    for block in diagrams:
        for node in block["nodes"]:
            if name.startswith("powerpoint-"):
                assert "positionOnSlide" in node
                assert node["positionOnSlide"] in SLIDE_POSITIONS | {None}
            else:
                assert "positionOnSlide" not in node
        for edge in block["edges"]:
            assert EDGE_KEYS <= edge.keys(), edge
            assert not {"direction", "status", "reason"} & edge.keys(), edge
            assert edge["arrowheadDirectionAlongLine"] in {
                "start-to-end", "end-to-start", "bidirectional", "undirected", "unknown"
            }
            status = edge["connectionResolutionStatus"]
            assert status in {"resolved", "unresolved"}
            if status == "resolved":
                assert edge["connectionResolutionReason"] == ""
                assert edge["connectionResolutionExplanation"] == ""
            else:
                assert edge["connectionResolutionReason"] in {
                    "MISSING_ENDPOINT", "TARGET_UNAVAILABLE", "AMBIGUOUS_TARGET", "UNKNOWN_ARROWHEAD"
                }
                assert edge["connectionResolutionExplanation"]
            if name.startswith("powerpoint-"):
                assert "positionOnSlide" in edge
                assert edge["positionOnSlide"] in SLIDE_POSITIONS | {None}
                if name == "powerpoint-rag-flow" and block["section"] == "スライド3" and edge["id"] == "shape-9":
                    # The original PPTX stores no endpoint IDs and draws a straight rightward arrow.
                    assert edge["positionOnSlide"] == "left-middle"
                    assert edge["arrowheadPointsToward"] == "right-middle"
                    assert edge["arrowheadPointsToward"] in ARROW_BEARINGS
                else:
                    assert "arrowheadPointsToward" not in edge
            else:
                assert "positionOnSlide" not in edge
                assert "arrowheadPointsToward" not in edge
    return edges


def main():
    for name, expected in EXPECTED.items():
        directory = HERE / name
        run = json.loads((directory / "run.json").read_text())
        report = json.loads((directory / "output/report.json").read_text())
        markdown = (directory / "output/document.md").read_text()
        assert report["specVersion"] == 1
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
            assert set(archive.namelist()) == {str(p.relative_to(directory / "output")) for p in (directory / "output").rglob("*") if p.is_file()}
        for asset in report["assets"]:
            assert (directory / "output" / asset["path"]).is_file()
            assert asset["path"] in markdown
        assert "SECRET_REMOVED" not in markdown
        if name == "excel-complex":
            assert "DELETE_" not in markdown and "HIDDEN_" not in markdown
            assert "キャッシュ999" in markdown and "　999" in markdown
            assert any(item["code"] == "GRAPHIC_FRAME_UNSUPPORTED" for item in report["warnings"])
            inferred = [block for block in report["blocks"]
                        if {node.get("text") for node in block.get("nodes", [])} == {"申請", "確認", "保管"}]
            assert len(inferred) == 1 and inferred[0]["range"] == "A79:X96"
            assert len(inferred[0]["edges"]) == 2
            assert all(edge["connectionResolutionStatus"] == "resolved" for edge in inferred[0]["edges"])
        metadata = [json.loads(label) for label in re.findall(r'^!\[(.+)\]\(images/[^)]+\)$', markdown, re.M)]
        assert metadata == [block["metadata"] for block in report["blocks"] if "metadata" in block]
        assert metadata and all(set(item) == {"type", "text", "x", "y", "width", "height"} for item in metadata)
        assert "図中の項目：" in markdown
        edges = check_diagram_fields(report, name)
        if name.startswith("powerpoint-"):
            assert "取消線の秘密" not in markdown and "非表示の秘密" not in markdown
            assert "非表示のスライドは出力しない" not in markdown
            assert len(metadata) == expected[1]
        if name == "powerpoint-rag-flow":
            assert len(edges) == 14 and sum(edge["connectionResolutionStatus"] == "resolved" for edge in edges) == 13
            assert "接続関係不明" in markdown and "↔" in markdown
            assert "スライド左中央" in markdown and "矢印の先端は右を向く" in markdown
            assert "STRIKE_SECRET_RAG_DEMO" not in markdown and "HIDDEN_SECRET_RAG_DEMO" not in markdown
            assert "STRIKE_SECRET_RAG_DEMO" not in json.dumps(report) and "HIDDEN_SECRET_RAG_DEMO" not in json.dumps(report)
        if name in ("excel-complex", "word-rag-flow"):
            assert (len(edges), sum(edge["connectionResolutionStatus"] == "resolved" for edge in edges)) == ((6, 3) if name == "excel-complex" else (5, 4))
            assert "接続関係不明" in markdown
        if name == "word-rag-flow":
            assert "STRIKE_SECRET_RAG_DEMO" not in markdown and "HIDDEN_SECRET_RAG_DEMO" not in markdown
            assert "STRIKE_SECRET_RAG_DEMO" not in json.dumps(report) and "HIDDEN_SECRET_RAG_DEMO" not in json.dumps(report)
            assert markdown.index("本文の前後関係") < markdown.index("図中の項目") < markdown.index("図の後の説明")
        print(f"PASS {name}: input/output/screenshot hashes, ZIP contents, expected actual result")
    subprocess.run([sys.executable, str(HERE / "excel-merge-matrix/verify.py")], check=True)


if __name__ == "__main__":
    main()
