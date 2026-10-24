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

"""Writes or checks a pack's README.md (the README standard: tools/packgen/common/readme.py).

A pack with a generator gets its README from that generator (tools/packgen/banking/make_docs.py, tools/packgen/world/make.py,
tools/packgen/us_equities/make.py); this script writes the README of a pack without one, and checks any pack. Both render the same
text: pack.yaml, the samples, the Sutras and config/about.yaml, plus the hand-written intro
(tools/packgen/<generator>/readme/<pack>.intro.md or tools/packgen/intros/<pack>.intro.md).

    python3 tools/packgen/readme.py news-and-markets          write config/packs/news-and-markets/README.md
    python3 tools/packgen/readme.py --all --check             fail when a pack's README is missing, stale or breaks the standard
    python3 tools/packgen/readme.py my-pack --packs DIR       a pack in another folder (its intro is looked up the same way)
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("drishti_pack_readme", ROOT / "tools" / "packgen" / "common" / "readme.py")
R = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(R)


def check(pack_dir: Path) -> list[str]:
    """What is wrong with one pack's README: missing, breaking the standard, or not what its generator writes."""
    kinds = list((yaml.safe_load((pack_dir / "pack.yaml").read_text(encoding="utf-8")) or {}).get("kinds") or [])
    probs = R.problems_pack(pack_dir / "README.md", kinds)
    if R.find_intro(pack_dir.name) is None:
        probs.append(f"no intro: write tools/packgen/<generator>/readme/{pack_dir.name}.intro.md (or tools/packgen/intros/)")
    elif (pack_dir / "README.md").is_file() and (pack_dir / "README.md").read_text(encoding="utf-8") != R.render_pack(pack_dir):
        probs.append("README.md is stale: run its generator (or tools/packgen/readme.py)")
    return probs


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Write or check pack READMEs.")
    p.add_argument("packs", nargs="*")
    p.add_argument("--all", action="store_true")
    p.add_argument("--check", action="store_true")
    p.add_argument("--packs", dest="packs_dir", type=Path, default=ROOT / "config" / "packs")
    a = p.parse_args(argv)
    names = sorted(d.name for d in a.packs_dir.iterdir() if (d / "pack.yaml").is_file()) if a.all else a.packs
    if not names:
        p.error("name a pack, or --all")
    bad = []
    for name in names:
        d = a.packs_dir / name
        if a.check:
            bad += [f"{name}: {x}" for x in check(d)]
            continue
        (d / "README.md").write_text(R.render_pack(d), encoding="utf-8")
        print(f"wrote {d / 'README.md'}")
    if bad:
        print("pack READMEs do not follow the standard:\n  " + "\n  ".join(bad))
        return 1
    if a.check:
        print(f"{len(names)} pack READMEs follow the standard")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
