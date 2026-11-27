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

"""The genomics module's showcase: its two packs and their data, from one seed.

    python3 tools/packgen/genomics_showcase/make.py                write data/testdata/genomics-showcase/ and protein-engineering/ (gzipped
                                                                   JSON Lines, one folder a kind, listed in testdata.yaml) and the packs'
                                                                   contracts, Sutras, samples, README and guide in modules/genomics/packs/
    python3 tools/packgen/genomics_showcase/make.py --check        fail if what would be written differs from what is committed
    python3 tools/packgen/genomics_showcase/make.py --seed 7 --testdata DIR   another seed or another folder (a trial run)

Standard library and PyYAML only; no network. The same seed gives byte-identical files (the gzip carries no name and no time). 200,000
documents: 100,000 in the genomics-showcase pack and 100,000 in the protein-engineering pack.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import files as FI  # noqa: E402
import model as M  # noqa: E402
import spec as S  # noqa: E402
import publicsnap as SNAP  # noqa: E402  (tools/testdata/publicsnap.py: the snapshots and their testdata.yaml entries)


def run(seed: int, check: bool, testdata: Path) -> int:
    docs, story = M.build(seed)
    bad = 0
    for pack in (S.GENOMICS, S.PROTEIN):
        pack_dir = FI.MODULE_DIR / "packs" / pack
        if not check:                       # the README reads the contracts from the disk: write the other files first, then the README
            for p, t in FI.pack_files(pack, docs[pack], story, readme=False).items():
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(t, encoding="utf-8")
        bad += SNAP.generate(pack, pack_dir, docs[pack], FI.pack_files(pack, docs[pack], story), check, testdata)
    return bad


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="Write or check the genomics showcase's packs and data.")
    ap.add_argument("--seed", type=int, default=M.SEED)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--testdata", type=Path, default=SNAP.TESTDATA, help="the test data folder (default data/testdata)")
    a = ap.parse_args(argv)
    return 1 if run(a.seed, a.check, a.testdata.resolve()) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
