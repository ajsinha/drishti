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

"""The trade-grain lake of the VaR browser tests: a COPY of the shared lake with a small trade-grain morning on its newest date, built once per
source hash in the lake cache's own folder (never in the shared lake). Kept out of var_stack, which makes the shared lake only."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

from var_stack import ROOT, _lake_cache


def lake_copy(lake: Path, dest: Path) -> Path:
    """A copy of a (cached, shared) lake at ``dest/delta``: data files are hard links where both sit on one file system (Delta writes new
    files and never changes one; else copied), each _delta_log a real copy. The original is never written."""
    import os
    import shutil

    def link(src, dst):
        try:
            os.link(src, dst)
        except OSError:
            shutil.copy2(src, dst)

    out = dest / "delta"
    shutil.copytree(lake, out, copy_function=link, ignore=shutil.ignore_patterns("_delta_log"))
    for log in lake.rglob("_delta_log"):
        shutil.copytree(log, out / log.relative_to(lake))
    return out


def trade_grain(lake: Path, budget: int = 40) -> None:
    """A small trade-grain morning on the lake's newest date, replacing its position records: each trade its own record, Endur as
    scenario PVs, Calypso and Murex as P&L vectors, at most ``budget`` trades a book. Writes into ``lake`` (a copy), nowhere else."""
    import subprocess
    day = max(p.name.split("=")[1] for p in (lake / "risk" / "scenario-pv").iterdir() if p.name.startswith("business_date="))
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "pvgen" / "morning.py"), "--grain", "trade", "--pv-systems", "ENDUR", "--budget", str(budget), "--date", day,
                        "--out", str(lake.parent / "morning"), "--lake", str(lake)], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0:
        raise RuntimeError("could not write the trade-grain morning: " + (r.stderr or r.stdout)[-400:])


def trade_grain_lake(lake: Path, budget: int = 40) -> Path:
    """``lake`` with :func:`trade_grain` on its newest date: a copy built once per source hash in the lake cache's own folder (the shared
    lake is never written), read only after that."""
    lake_cache = _lake_cache()

    def build(work: Path) -> None:
        trade_grain(lake_copy(lake, work), budget)

    try:
        return lake_cache.cached("trade-grain|" + str(lake) + "|" + str(budget), build) / "delta"
    except RuntimeError as e:
        pytest.skip(str(e))
