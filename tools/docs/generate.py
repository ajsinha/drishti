#!/usr/bin/env python3
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

"""Every documentation generator, in the right order, behind one command (i7k).

    python3 tools/drishti.py docs generate            write every generated reference
    python3 tools/drishti.py docs generate --check    write nothing; fail naming every stale reference and the one command that fixes it

``GENERATORS`` is the single list: ``drishti.py docs generate`` and ``tools/test_docs_generated.py`` both read it, so a new generator is
added here once. The order matters: a page that lists other pages (the Help map, the corpus) comes after the pages it lists.
The generators read the built server jar (``python3 tools/drishti.py build jar``), as the settings and the command catalogue come from it.
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PY = "python3"

#: (what it writes, the write command, the check command), each from the repository root. The Java function catalogue page is checked
#: by FunctionCataloguePageTest, and the screenshots (``docs shots``) need a browser, so neither is here.
GENERATORS: list[tuple[str, list[str], list[str]]] = [
    ("docs/admin/CONFIGURATION.md", ["tools/settings_catalogue.py"], ["tools/settings_catalogue.py", "--check"]),
    ("docs/admin/UNDER_CONSTRUCTION.md", ["tools/under_construction.py"], ["tools/under_construction.py", "--check"]),
    ("docs/guides/COMMAND_CATALOG.md", ["tools/command_catalog.py"], ["tools/command_catalog.py", "--check"]),
    ("docs/contracts/packs/*.md", ["tools/contracts.py", "docs"], ["tools/contracts.py", "docs", "--check"]),
    ("docs/contracts/CSV_FORMATS.md", ["tools/mrloader/kind_docs.py"], ["tools/mrloader/kind_docs.py", "--check"]),
    ("config/feeds/*.md", ["tools/feeds/feedreadme.py"], ["tools/feeds/feedreadme.py", "--check"]),
    ("docs/knowledge/*.md (the glossaries)", ["tools/knowledge.py"], ["tools/knowledge.py", "--check"]),
    ("pack READMEs (config/packs, modules/*/packs)", ["tools/packgen/readme.py", "--all"], ["tools/packgen/readme.py", "--all", "--check"]),
    ("module READMEs (modules/*/docs/README.md)", ["tools/packgen/module_readme.py", "--all"], ["tools/packgen/module_readme.py", "--all", "--check"]),
    ("connector type and template READMEs", ["tools/connector_readmes.py"], ["tools/connector_readmes.py", "--check"]),
    ("the IDE launchers and IDE_GUIDE section 4", ["tools/drishti.py", "launchers"], ["tools/drishti.py", "launchers", "--check"]),
    ("the guides' data sections", ["tools/docs/guide_data.py"], ["tools/docs/guide_data.py", "--check"]),
    ("docs/README.md (the Help map)", ["tools/docs/help_map.py"], ["tools/docs/help_map.py", "--check"]),
    ("the docs corpus tables", ["tools/docs/corpus.py", "--write"], ["tools/docs/corpus.py", "--check"]),
]


def fix_command(what_cmd: list[str]) -> str:
    """The command a person types to rewrite one reference (its write command, from the repository root)."""
    return f"{PY} " + " ".join(what_cmd)


def run(check: bool, only: list[str] | None = None, out=sys.stdout) -> int:
    """Runs every generator (or those whose name contains one of ``only``); returns 0, or 1 when any is stale (check) or fails (write)."""
    failed: list[tuple[str, list[str], str]] = []
    todo = [g for g in GENERATORS if not only or any(o in g[0] for o in only)]
    for what, write, chk in todo:
        t = time.time()
        r = subprocess.run([sys.executable, *(chk if check else write)], cwd=ROOT, capture_output=True, text=True)
        text = (r.stdout + r.stderr).strip()
        state = "current" if r.returncode == 0 and check else ("written" if r.returncode == 0 else ("STALE" if check else "FAILED"))
        print(f"  {state:8} {what} ({time.time() - t:.1f}s)", file=out)
        if r.returncode != 0:
            failed.append((what, write, text))
    for what, write, text in failed:
        print(f"\n{what}: {'stale or invalid' if check else 'failed'}\n  fix with: {fix_command(write)}\n  " + "\n  ".join(text[-1500:].splitlines()), file=out)
    if failed:
        print(f"\n{len(failed)} of {len(todo)} generated references " + ("are stale: run  python3 tools/drishti.py docs generate" if check else "failed"), file=out)
        return 1
    print(f"{len(todo)} generated references " + ("are current" if check else "written"), file=out)
    return 0


def main(argv: list[str] | None = None) -> int:
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="write nothing; fail naming every stale reference")
    ap.add_argument("--only", action="append", help="only the generators whose name contains this text (repeatable)")
    a = ap.parse_args(argv)
    return run(a.check, a.only)


if __name__ == "__main__":
    sys.exit(main())
