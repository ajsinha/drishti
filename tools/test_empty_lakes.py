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

"""The lakes shipped empty (risklake, newslake, worldlake, regulators, capitalmarket, referencelake) are definitions only: nothing shipped points at them.
Every shipped connector, routing rule, engine setting and pack reads the demo lake; a site points at another lake itself (the product
owner, 2026-11-07: "do not point anything to any of these lakes except the demo lake"). The data feeds (config/feeds/*.yaml) are the
one exception by design: a feed names the lake it loads, and the owner placed them (GLEIF in referencelake, 2026-11-07)."""
import pathlib
import re

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
EMPTY = ("risklake", "newslake", "worldlake", "regulators", "capitalmarket", "referencelake")
NAMED = re.compile(r"\b(" + "|".join(EMPTY) + r")\b")


def test_the_empty_lakes_ship_as_definitions_with_no_tables():
    for name in EMPTY:
        f = ROOT / "config" / "lakes" / f"{name}.yaml"
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        assert doc["lake"] == name and not doc.get("tables"), f
        assert (ROOT / "data" / "delta" / name / "README.md").is_file(), name


def test_nothing_shipped_points_at_them():
    places = [*ROOT.glob("config/connectors/*.yaml"), *ROOT.glob("config/engines/*.yaml"), ROOT / "config" / "dataload.yaml",
              *ROOT.glob("config/packs/**/*.yaml"), *ROOT.glob("drishti-server/src/main/resources/**/*.yaml"),
              *ROOT.glob("drishti-stack/*.yaml")]
    hits = []
    for f in places:
        if not f.is_file():
            continue
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            code = line.split("#", 1)[0]
            if NAMED.search(code) and re.search(r"lake\s*[:=]|DATALOAD_LAKE", code):
                hits.append(f"{f.relative_to(ROOT)}:{n}: {line.strip()}")
    assert not hits, "a shipped file points at a lake that ships empty:\n" + "\n".join(hits)
