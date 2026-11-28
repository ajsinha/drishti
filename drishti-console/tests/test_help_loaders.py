# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The loaders' READMEs (tools/loaders/<package>/README.md) are in Help, as generated read-only copies under docs/loaders/ (group
"Loading data daily", Operate): every loader README has its page, every page renders, and the links inside a copy resolve."""
import re
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
LOADERS = ROOT / "tools" / "loaders"
HELP = yaml.safe_load((ROOT / "drishti-console" / "config" / "help.yaml").read_text(encoding="utf-8"))
GROUP = "Loading data daily"


def _entries():
    for section in HELP["sections"] if "sections" in HELP else []:
        for g in section.get("groups") or []:
            if g.get("title") == GROUP:
                return g["guides"]
    raise AssertionError(f"no Help group {GROUP!r}")


def _packages():
    return sorted(p.parent.name for p in LOADERS.glob("*/README.md"))


def test_every_loader_readme_has_its_help_page():
    docs = {e["doc"] for e in _entries()}
    assert "loaders/index.md" in docs
    for package in _packages():
        assert f"loaders/{package}.md" in docs, f"tools/loaders/{package}/README.md has no page in Help group {GROUP!r} (docs/loaders/{package}.md)"
    for d in docs:
        assert (ROOT / "docs" / d).is_file(), d


def test_each_page_is_the_readme_it_is_a_copy_of(tmp_path):
    import sys
    sys.path.insert(0, str(ROOT / "tools" / "docs"))
    import loader_docs
    for f, text in loader_docs.wanted().items():
        assert f.read_text(encoding="utf-8") == text, f"{f.relative_to(ROOT)}: python3 tools/docs/loader_docs.py"


def test_the_links_inside_the_pages_resolve_in_help():
    for page in sorted((ROOT / "docs" / "loaders").glob("*.md")):
        for link in re.findall(r"\]\(([^)\s#]+)(?:#[^)\s]*)?\)", page.read_text(encoding="utf-8")):
            if re.match(r"[a-z][a-z0-9+.-]*:", link):
                continue
            assert (page.parent / link).resolve().is_file(), f"{page.name}: {link}"


@pytest.fixture(scope="module")
def lib():
    from core.guides import Library
    console = ROOT / "drishti-console"
    return Library(console, console / "config" / "help.yaml", ROOT / "docs", [])


def test_the_pages_render_and_their_links_stay_in_help(lib):
    for e in _entries():
        page = lib.render(e["slug"])
        assert "<p>" in page["html"] and len(page["text"]) > 500, e["slug"]
        for href in re.findall(r'href="(/help/[^"#]*)', page["html"]):         # a link to another loader's page opens that page in Help
            assert href.removeprefix("/help/") in lib.guides or href.startswith("/help/docs-"), (e["slug"], href)
