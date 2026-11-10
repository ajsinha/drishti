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

"""Rule 4 of the domain boundaries (docs/architecture/DOMAIN_MODULES.md section 4): strings count too. The core's code names no banking
command word, engine id, connector, API path or console path. Today's names are counted per file in an allow list
(tools/domain_strings_allowed.yaml) that only shrinks: a file not on the list, or one whose count grew, fails; a file whose count fell
asks for the list to be lowered (``--write`` rewrites it from the tree).

    python3 tools/domain_strings.py            # check
    python3 tools/domain_strings.py --write    # write the allow list from the tree (only after moving names out of the core)

The core is every source file outside the domain modules: Java under drishti-*/src/main (the domain modules live under modules/, outside it),
the console's core/, routes/, web/static/js, web/static/css and web/templates (not drishti-console/plugins). Comments count: a core
file that explains a banking screen names it. A line that only looks like banking (the statistics function var, Excel's EXP) carries the
comment ``domain-strings: generic`` and is not counted."""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
ALLOWED = ROOT / "tools" / "domain_strings_allowed.yaml"

#: the banking names: its command words (quoted), its engine ids and connectors, its API and console paths, its packages
NAMES = re.compile(
    r"""(?x)
    ["'](?:VAR|PVAR|SMAT|SWIN|EXP|PREDEAL|FRSA|SIMM|MCUBE|CCUBE|VRES|VOFF|SENS|RFAC)["']      # a command word as a string
    | ["'](?:var|exposure|frtb-sa|simm|mr-cube|sensitivities)["']                                  # an engine id
    | \b(?:var-engine|var-positions|var-pv-stream|exposure-engine)\b                               # its connectors
    | /api/v1/risk\b | /api/v1/(?:var|exposure|frtb|simm|sensitivities|risk-factors|pivot/mr)\b   # its API, new and old
    | ["'`]/(?:var|exposure|frtb|simm|smat|play|mcube)(?:[/?"'`]|$)                                # its console pages
    | /api/(?:var|exposure|frtb|simm|smat|play|pivot/mr|home/trading|home/market)/                 # its console APIs
    | com\.ash\.drishti\.banking\b                                                                 # its packages
    """)

#: a line that is not about banking, marked so: the VAR of statistics, Excel's EXP (name the reason after the mark)
MARK = "domain-strings: generic"
DOMAIN_MODULES = ("banking-service", "banking-compute")   # under modules/banking/, outside the glob below


def core_files() -> list[Path]:
    out = []
    for m in sorted(ROOT.glob("drishti-*/src/main")):
        if m.parts[-3] in DOMAIN_MODULES:
            continue
        out += [p for p in m.rglob("*") if p.suffix in (".java", ".py") and p.is_file()]
    console = ROOT / "drishti-console"
    for d, pattern in (("core", "*.py"), ("routes", "*.py"), ("web/static/js", "*.js"), ("web/static/css", "*.css"), ("web/templates", "*.html")):
        out += sorted((console / d).rglob(pattern))
    return out


def counts() -> dict[str, int]:
    out = {}
    for p in core_files():
        n = sum(len(NAMES.findall(l)) for l in p.read_text(encoding="utf-8", errors="ignore").splitlines() if MARK not in l)
        if n:
            out[str(p.relative_to(ROOT))] = n
    return out


def problems(now: dict[str, int], allowed: dict[str, int]) -> list[str]:
    bad = []
    for f, n in sorted(now.items()):
        if f not in allowed:
            bad.append(f"{f}: names banking {n} times and is not on the allow list (move the names into the module, or behind a hook)")
        elif n > allowed[f]:
            bad.append(f"{f}: names banking {n} times, the allow list says {allowed[f]} (it only shrinks)")
    for f, n in sorted(allowed.items()):
        if now.get(f, 0) < n:
            bad.append(f"{f}: names banking {now.get(f, 0)} times now, fewer than the {n} allowed: lower the list (python3 tools/domain_strings.py --write)")
    return bad


def main(argv: list[str]) -> int:
    now = counts()
    if "--write" in argv:
        header = ("# Rule 4 of the domain boundaries (docs/architecture/DOMAIN_MODULES.md section 4): the core files that still name banking, and how\n"
                  "# many times. Written by tools/domain_strings.py --write; it only shrinks.\n")
        ALLOWED.write_text((ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().replace("\n", "\n# ").join(["# ", "\n\n"])
                           .replace("# \n", "#\n") + header + yaml.safe_dump({"allowed": now}, sort_keys=True), encoding="utf-8")
        print(f"wrote {ALLOWED.relative_to(ROOT)}: {len(now)} files, {sum(now.values())} names")
        return 0
    allowed = (yaml.safe_load(ALLOWED.read_text(encoding="utf-8")) or {}).get("allowed") or {}
    bad = problems(now, allowed)
    for b in bad:
        print(b)
    print(f"{len(now)} core files name banking {sum(now.values())} times (allowed {sum(allowed.values())})" if not bad else f"{len(bad)} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
