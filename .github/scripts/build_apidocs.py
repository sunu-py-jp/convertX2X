#!/usr/bin/env python3
"""Stage the standalone API documentation for GitHub Pages.

Links that leave docs/APIDocs point to the matching file in the deployed
repository revision. The source documentation keeps its local relative links.
"""

from __future__ import annotations

import argparse
from html import unescape
from pathlib import Path
import re
import shutil
from urllib.parse import quote, unquote, urlsplit


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SOURCE = REPOSITORY_ROOT / "docs" / "APIDocs"
ATTRIBUTE = re.compile(r"(?P<prefix>\b(?:href|src)\s*=\s*)(?P<quote>['\"])(?P<url>.*?)(?P=quote)")


def github_file(repository: str, ref: str, path: Path, fragment: str = "") -> str:
    relative = path.relative_to(REPOSITORY_ROOT).as_posix()
    url = f"https://github.com/{repository}/blob/{ref}/{quote(relative, safe='/')}"
    return url + (f"#{quote(fragment, safe='')}" if fragment else "")


def rewrite_page(source: Path, destination: Path, repository: str, ref: str) -> None:
    def replace(match: re.Match[str]) -> str:
        raw = match.group("url")
        parsed = urlsplit(unescape(raw))
        if parsed.scheme or parsed.netloc or not parsed.path or parsed.path.startswith("/"):
            return match.group(0)
        target = (source.parent / unquote(parsed.path)).resolve()
        if target.is_relative_to(SOURCE):
            if not target.is_file() and not target.is_dir():
                raise ValueError(f"Broken API Docs link: {source}: {raw}")
            return match.group(0)
        if not target.is_relative_to(REPOSITORY_ROOT) or not target.is_file():
            raise ValueError(f"Unresolved repository link: {source}: {raw}")
        url = github_file(repository, ref, target, parsed.fragment)
        return f"{match.group('prefix')}{match.group('quote')}{url}{match.group('quote')}"

    destination.write_text(ATTRIBUTE.sub(replace, source.read_text(encoding="utf-8")), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="GitHub OWNER/REPO")
    parser.add_argument("--ref", required=True, help="Commit SHA or branch")
    parser.add_argument("--output", type=Path, required=True, help="Staging directory")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("--repository must be OWNER/REPO")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.ref):
        parser.error("--ref must be a branch or commit SHA")
    output = args.output.resolve()
    if output == SOURCE or output.is_relative_to(SOURCE) or output == REPOSITORY_ROOT:
        parser.error("--output must not replace the source or repository root")
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(SOURCE, output)

    pages = list(SOURCE.rglob("*.html"))
    for page in pages:
        rewrite_page(page, output / page.relative_to(SOURCE), args.repository, args.ref)

    sidebar = output / "assets" / "app.js"
    old = "${url('../README.md')}"
    if sidebar.read_text(encoding="utf-8").count(old) != 1:
        raise ValueError("The developer manual link in the sidebar changed")
    new = github_file(args.repository, args.ref, REPOSITORY_ROOT / "docs" / "README.md")
    sidebar.write_text(sidebar.read_text(encoding="utf-8").replace(old, new), encoding="utf-8")
    (output / ".nojekyll").touch()
    if not (output / "index.html").is_file():
        raise ValueError("API Docs index.html is missing")
    print(f"Staged {len(pages)} API pages at {output}")


if __name__ == "__main__":
    main()
