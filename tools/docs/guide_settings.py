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


"""Writes the settings tables of the guides from the settings catalogue (docs/admin/CONFIGURATION.md's source), so a default and a meaning have one home.

A guide that wants a table of keys marks a place, `<!-- settings:begin <id> -->` ... `<!-- settings:end -->`, and tools/docs/guide_settings.yaml
names the keys of that block (an exact key, or a prefix ending in `.*`). The block is `| Setting | Default | Meaning |` read from the catalogue
(tools/settings_catalogue.py), in the order the spec gives; a key the catalogue does not know fails the check.

    python3 tools/docs/guide_settings.py            rewrite the blocks
    python3 tools/docs/guide_settings.py --check    fail when a block is stale or a key is unknown
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import settings_catalogue as S  # noqa: E402

SPEC = Path(__file__).resolve().with_name("guide_settings.yaml")


def block(keys: list[str], rows: dict[str, "S.Row"], problems: list[str], where: str) -> str:
    out = ["| Setting | Default | Meaning |", "|---|---|---|"]
    for k in keys:
        if k.endswith(".*"):
            found = [r for name, r in rows.items() if name.startswith(k[:-1])]
        else:
            found = [rows[k]] if k in rows else []
        if not found:
            problems.append(f"{where}: no setting {k!r} in the catalogue")
        for r in found:
            out.append(f"| `{r.key}` | {S.default_cell(r)} | {S._text(r.meaning)} |")
    return "\n".join(out)


def run(check: bool) -> int:
    rows = {r.key: r for r in S.all_rows()[0]}
    problems: list[str] = []
    stale: list[str] = []
    for spec in (yaml.safe_load(SPEC.read_text(encoding="utf-8")) or {}).get("blocks") or []:
        path = ROOT / spec["file"]
        text = path.read_text(encoding="utf-8")
        pat = re.compile(rf"(<!-- settings:begin {re.escape(spec['id'])} -->\n)(?:.*?\n)?(<!-- settings:end -->)", re.S)
        if not pat.search(text):
            problems.append(f"{spec['file']}: no settings block {spec['id']!r}")
            continue
        new = pat.sub(lambda m: m.group(1) + block(spec["keys"], rows, problems, spec["file"]) + "\n" + m.group(2), text, count=1)
        if new != text:
            stale.append(spec["file"])
            if not check:
                path.write_text(new, encoding="utf-8")
    for p in problems:
        print(p)
    if stale and check:
        print("stale settings blocks: " + ", ".join(stale) + "\n  fix with: python3 tools/docs/guide_settings.py")
    print(("current" if check and not stale else "written" if not check else "stale") + f": {len(stale)} guide(s)")
    return 1 if problems or (check and stale) else 0


if __name__ == "__main__":
    sys.exit(run("--check" in sys.argv[1:]))
