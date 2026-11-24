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

"""Writes the banking packs' READMEs (modules/<module>/packs/<pack>/README.md) from pack.yaml, the samples, the Sutras and the
contracts, so they cannot drift from what the packs contain. Each pack's story (an overview, the product catalogue, the market-data
catalogue, the cross-pack data model) is a section of a feature guide since i7k (docs/guides/BANK_DATA_GUIDE.md, VAR_GUIDE.md,
EXPOSURE_GUIDE.md).

    python3 tools/packgen/banking/make_docs.py            write the READMEs
    python3 tools/packgen/banking/make_docs.py --check    fail if they differ from what would be written
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
import readme as README  # noqa: E402  (the pack README standard: each pack's README.md, from the `intro:` of its config/about.yaml)

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT / "tools") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools"))
from resolved import pack_dir  # noqa: E402  (where a pack is: the Java resolver's answer, tools/resolved.py)

PACKS = ["core", "market-data", "trading", "market-risk", "counterparty-risk"]


def files() -> dict[Path, str]:
    """Each banking pack's README.md (the README standard, tools/packgen/common/readme.py). The packs' guides were merged into the
    feature guides in i7k (BANK_DATA_GUIDE, VAR_GUIDE, EXPOSURE_GUIDE): a pack's facts are its README and contract."""
    return {pack_dir(p) / "README.md": README.render_pack(pack_dir(p)) for p in PACKS}


def main() -> None:
    want = files()
    if "--check" in sys.argv:
        stale = [str(f) for f, t in want.items() if not f.exists() or f.read_text(encoding="utf-8") != t]
        if stale:
            raise SystemExit(f"banking pack docs out of date; run make_docs.py: {stale[:3]}")
        print(f"{len(want)} banking doc files up to date")
        return
    for f, t in want.items():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(t, encoding="utf-8")
    print(f"wrote {len(want)} pack READMEs")


if __name__ == "__main__":
    main()
