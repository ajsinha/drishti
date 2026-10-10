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

"""Real public data for the economics and politics-society packs.

    python3 tools/realdata/realdata.py fetch     [--cache DIR] [--refresh] [--pause S]   download the raw data (network)
    python3 tools/realdata/realdata.py normalise [--cache DIR]                           raw cache -> tools/realdata/dataset/*.json
    python3 tools/realdata/realdata.py write                                              dataset -> the two packs (runs both generators)
    python3 tools/realdata/realdata.py all       [--cache DIR] [--refresh]                fetch, normalise, write
    python3 tools/realdata/realdata.py check                                              fail if the packs differ from the dataset

The raw cache lives outside the repository (default ~/.cache/drishti-realdata) and is never committed; the normalised
dataset (tools/realdata/dataset/) and the packs generated from it are. See docs/guides/REAL_DATA.md.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import fetch as F  # noqa: E402
import normalise as N  # noqa: E402

GENERATORS = ["tools/packgen/economics/make.py", "tools/packgen/politics/make.py"]


def write(check: bool = False) -> int:
    rc = 0
    for g in GENERATORS:
        rc |= subprocess.call([sys.executable, str(ROOT / g)] + (["--check"] if check else []), cwd=ROOT)
    return rc


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["fetch", "normalise", "write", "all", "check"])
    ap.add_argument("--cache", default=str(Path.home() / ".cache" / "drishti-realdata"))
    ap.add_argument("--refresh", action="store_true", help="download again even when the file is cached")
    ap.add_argument("--pause", type=float, default=0.4, help="seconds between requests")
    a = ap.parse_args(argv)
    cache = Path(a.cache).expanduser()
    cfg = N.load_config()
    if a.command in ("fetch", "all"):
        F.Fetcher(cache, cfg, a.pause, a.refresh).all()
    if a.command in ("normalise", "all"):
        n = N.run(cache, cfg)
        print(f"dataset: {n['countries']} countries, {n['series']} series, {n['factbook']} factbook profiles -> {N.DATASET}")
    if a.command in ("write", "all"):
        return write()
    if a.command == "check":
        return write(check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
