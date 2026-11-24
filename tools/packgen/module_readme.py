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

"""Writes or checks a module's docs/README.md: generated whole from modules/<module>/module.yaml (the module, its title and its domain)
(`module:` there only where the folder name differs from the manifest id) and the module's own text in modules/<module>/docs/about.yaml (`front`: the other front matter lines, `body`: the page after them).

    python3 tools/packgen/module_readme.py --all            write every module's docs/README.md
    python3 tools/packgen/module_readme.py --all --check    fail when one is missing or differs from what is written here
    python3 tools/packgen/module_readme.py banking          one module (a folder name under modules/)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MODULES = ROOT / "modules"
LICENCE = ("# Project Drishti · Any data. Any domain. One grammar.\n"
           "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.\n"
           "# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.\n")


def module_dirs(modules: Path = MODULES) -> list[Path]:
    return sorted(d for d in modules.iterdir() if (d / "docs" / "about.yaml").is_file() and (d / "module.yaml").is_file())


def render(mod: Path) -> str:
    manifest = yaml.safe_load((mod / "module.yaml").read_text(encoding="utf-8")) or {}
    about = yaml.safe_load((mod / "docs" / "about.yaml").read_text(encoding="utf-8")) or {}
    head = f"module: {about.get('module') or manifest['id']}\ntitle: {manifest['title']}\ndomain: {manifest['domain']}\n"
    return "---\n" + LICENCE + head + about.get("front", "") + "---\n" + about.get("body", "")


def check(mod: Path) -> list[str]:
    f = mod / "docs" / "README.md"
    if not f.is_file():
        return [f"{mod.name}: docs/README.md is missing: run tools/packgen/module_readme.py {mod.name}"]
    if f.read_text(encoding="utf-8") != render(mod):
        return [f"{mod.name}: docs/README.md is stale: run tools/packgen/module_readme.py {mod.name} (edit docs/about.yaml, not the README)"]
    return []


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Write or check module READMEs.")
    p.add_argument("modules", nargs="*")
    p.add_argument("--all", action="store_true")
    p.add_argument("--check", action="store_true")
    p.add_argument("--modules-dir", type=Path, default=MODULES)
    a = p.parse_args(argv)
    mods = module_dirs(a.modules_dir) if a.all else [a.modules_dir / n for n in a.modules]
    if not mods:
        p.error("name a module, or --all")
    bad = []
    for m in mods:
        if a.check:
            bad += check(m)
        else:
            (m / "docs" / "README.md").write_text(render(m), encoding="utf-8")
            print(f"wrote {m / 'docs' / 'README.md'}")
    if bad:
        print("module READMEs are not what their sources write:\n  " + "\n  ".join(bad))
        return 1
    if a.check:
        print(f"{len(mods)} module READMEs are up to date")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
