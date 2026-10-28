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

"""docs/README.md's map of the help centre matches config/help.yaml, the file the help centre is built from.

The map drifted once (2.9.6 audit, C11): it said "about forty pages in six sections", listed pages the help centre no longer had and
missed ones it had. Each row of the table "The help centre, section by section" is one help.yaml category, in order, naming every page of
it (a linked document, or the page's title for a guide that has no document), and the intro sentence gives the counts."""
import re

import yaml

from conftest import CONSOLE

ROOT = CONSOLE.parent
README = ROOT / "docs" / "README.md"
HELP = CONSOLE / "config" / "help.yaml"
LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")


def _categories() -> list:
    return yaml.safe_load(HELP.read_text(encoding="utf-8"))["categories"]


def _map_rows() -> list:
    text = README.read_text(encoding="utf-8")
    section = text.split("## The help centre, section by section", 1)[1].split("\n## ", 1)[0]
    rows = []
    for line in section.splitlines():
        m = re.match(r"^\|\s*\*\*([^*]+)\*\*\s*\|(.*)\|\s*$", line)
        if m:
            rows.append((m.group(1).strip(), [i.strip() for i in m.group(2).split(" · ")]))
    return rows


def test_every_help_entry_has_a_title_that_parsed_whole():
    for c in _categories():
        for g in c["guides"]:
            assert isinstance(g.get("title"), str) and g["title"], g
            assert set(g) <= {"slug", "doc", "title", "icon", "kind", "summary"}, f"{g.get('slug')}: unexpected keys {sorted(set(g))} (an unquoted comma in a title?)"


def test_the_docs_readme_names_the_help_sections_in_order():
    assert [name for name, _ in _map_rows()] == [c["name"] for c in _categories()]


def test_each_section_row_lists_exactly_that_sections_pages():
    for (name, items), c in zip(_map_rows(), _categories()):
        assert len(items) == len(c["guides"]), f"{name}: {len(items)} items in docs/README.md, {len(c['guides'])} pages in help.yaml"
        for item, g in zip(items, c["guides"]):
            if g.get("doc"):
                links = LINK.findall(item)
                assert links and links[0][1] == g["doc"], f"{name}: '{item}' should link {g['doc']} ({g['title']})"
            else:
                assert g["title"] in item, f"{name}: '{item}' should name {g['title']}"


def test_the_intro_gives_the_help_centres_counts():
    cats = _categories()
    pages = sum(len(c["guides"]) for c in cats)
    assert f"**{pages} pages in {len(cats)} sections**" in README.read_text(encoding="utf-8")
