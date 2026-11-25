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


"""Writes the navigation strip of the eleven BI pages (docs/guides/bi): one list of pages here, one generated block in each page.

A page's strip is the line `Business intelligence: [Index](README.md) . [Pivot](PIVOT.md) ...` with its own entry in bold, between
`<!-- nav:begin -->` and `<!-- nav:end -->`. A page that holds two parts (TABLES_AND_MODELS: the tables, then the models for reports) has
a strip at the head of each part; the second bolds the second entry.

    python3 tools/docs/bi_nav.py            rewrite the strips
    python3 tools/docs/bi_nav.py --check    fail when a strip is missing or stale
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BI = ROOT / "docs" / "guides" / "bi"

#: (file, anchor or "", title): the order of the index table; the title is the entry's text.
PAGES = [
    ("README.md", "", "Index"),
    ("PIVOT.md", "", "Pivot"),
    ("PIVOT_SCALE_AND_CUBES.md", "", "Pivot at scale and the risk cubes"),
    ("TABLES_AND_MODELS.md", "", "Tables and models"),
    ("REPORTS.md", "", "Reports and the designer"),
    ("REPORT_FILE_FORMAT_STATEMENTS.md", "", "The report file, formatting and statements"),
    ("TABLES_AND_MODELS.md", "#models-and-datasets-for-reports", "Models and datasets for reports"),
    ("LIVE_REPORTS_AND_BOARDS.md", "", "Live reports, boards and background runs"),
    ("DISTRIBUTION_AND_ALERTS.md", "", "Distribution, subscriptions and alerts"),
    ("GOVERNANCE_AND_AI.md", "", "Governing BI and AI in BI"),
    ("NOTEBOOK_AND_PYTHON.md", "", "The Notebook and Python"),
    ("GROUPINGS_AND_ANALYTICS.md", "", "Groupings and analytics"),
]
BLOCK = re.compile(r"<!-- nav:begin -->\n.*?<!-- nav:end -->", re.S)
OLD = re.compile(r"^Business intelligence: .*$", re.M)


def strip(current: int) -> str:
    """The strip with entry number `current` (an index into PAGES) in bold."""
    parts = []
    for i, (f, a, t) in enumerate(PAGES):
        parts.append(f"**{t}**" if i == current else f"[{t}]({f}{a})")
    return "<!-- nav:begin -->\nBusiness intelligence: " + " \u00b7 ".join(parts) + "\n<!-- nav:end -->"


def render(path: Path, text: str) -> str:
    """The page with each of its strips rewritten (the first strip bolds the page's first entry, a second one its second)."""
    mine = [i for i, (f, _a, _t) in enumerate(PAGES) if f == path.name]
    n = 0

    def one(_m):
        nonlocal n
        s = strip(mine[min(n, len(mine) - 1)])
        n += 1
        return s

    text = BLOCK.sub(one, text)
    if n == 0:
        text = OLD.sub(one, text)
    return text


def main(argv: list[str]) -> int:
    stale = []
    for f in sorted({p[0] for p in PAGES if p[0] != "README.md"}):
        path = BI / f
        old = path.read_text(encoding="utf-8")
        new = render(path, old)
        if new != old:
            stale.append(f)
            if "--check" not in argv:
                path.write_text(new, encoding="utf-8")
    if stale and "--check" in argv:
        print("stale BI navigation strips: " + ", ".join(stale) + "\n  fix with: python3 tools/docs/bi_nav.py")
        return 1
    print(("current" if "--check" in argv else "written") + f": {len(stale)} page(s) changed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
