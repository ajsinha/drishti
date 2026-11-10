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

"""The VaR browser files' stack: the market-risk pack's committed VaR data built into a scratch lake ONCE PER TEST RUN for every worker
(browser_harness.shared_once; the build takes minutes), and a real server over it per file (wb_live's pool), the demo connector off
(docs/architecture/TEST_HARNESS.md section 3.12). Files asking the same data and business date read the same lake; the servers only
read it (each has its own VaR hot folder and its own folder for the official results it publishes). A file split in two asks the same
and pays only its server's start again."""
from __future__ import annotations

import datetime
import hashlib
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
RISK = ("scenario-pv", "fx-scenario", "scenario-set")   # the system baskets are no data: seeded (DRISHTI_VAR_SYSTEM_BASKETS_SEED)


#: the trades every VaR browser file's lake holds, so one lake is built per run and shared by all of them
VAR_TRADES = ("trades/murex", "trades/endur")


#: the heap of a VaR file's server: both trade sets' P&L vectors, the official capture of the newest date it runs at start (every node,
#: written to its lake) and a page's bank-wide by-trade contributions at once pass 768 MB (heap at 95-99 % after collections, then
#: OutOfMemoryError: requests time out and the console's pages hang, as test_arrow_browser and test_var_explorer_browser saw)
VAR_HEAP = "1536m"


def var_data(*more: str, limits: bool = True) -> str:
    """The ``--only`` list: the market-risk VaR tables (and the limits, when ``limits``), then ``more`` (trades/murex, trades/endur ...)."""
    return ",".join([f"market-risk/{k}" for k in RISK + (("var-limit",) if limits else ())] + ["core/desk", "core/book", "core/legal-entity"] + list(more))


def last_weekday() -> str:
    """Today, or the Friday before on a weekend: the business date the lakes end on (yyyy-MM-dd)."""
    d = datetime.date.today()
    while d.weekday() >= 5:
        d -= datetime.timedelta(days=1)
    return d.isoformat()


def var_lake(tmp_path_factory, only: str, as_of: str | None = None) -> Path:
    """The lake of ``only`` ending ``as_of`` (default: :func:`last_weekday`), built by the first worker of the run that asks; skips when
    it cannot be built."""
    import browser_harness

    as_of = as_of or last_weekday()

    run = browser_harness.run_dir(tmp_path_factory)
    key = "var-lake-" + hashlib.sha1(f"{only}|{as_of}".encode()).hexdigest()[:12]

    def make():
        sys.path.insert(0, str(ROOT / "tools" / "drillkit"))
        import lake_cache   # built once per hash of the test data, generators and lake code, across runs (TEST_HARNESS.md, "The cached demo lake")

        try:
            lake = lake_cache.demo_lake(only, as_of) / "delta"   # the Python writer: auto starts a JVM for every table and business day
        except RuntimeError as e:
            return {"lake": "", "error": "could not build the VaR demo lake: " + str(e)}
        return {"lake": str(lake), "error": None if (lake / "risk" / "scenario-pv").is_dir() else f"no scenario-pv in the cached lake {lake}"}

    got = browser_harness.shared_once(run, key, make)
    if got["error"]:
        pytest.skip(got["error"])
    return Path(got["lake"])


def trade_grain(lake: Path, budget: int = 40) -> None:
    """A small trade-grain morning on the lake's newest date, replacing its position records (the demo is position grain; the engine's trade
    paths stay covered): each trade its own record, Endur as scenario PVs, Calypso and Murex as P&L vectors, at most ``budget`` trades a book."""
    day = max(p.name.split("=")[1] for p in (lake / "risk" / "scenario-pv").iterdir() if p.name.startswith("business_date="))
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "pvgen" / "morning.py"), "--grain", "trade", "--pv-systems", "ENDUR", "--budget", str(budget), "--date", day,
                        "--out", str(lake.parent / "morning"), "--lake", str(lake)], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0:
        pytest.skip("could not write the trade-grain morning: " + (r.stderr or r.stdout)[-400:])


def trade_grain_copy(lake: Path, dest: Path, budget: int = 40) -> Path:
    """A copy of a (cached, shared) lake with :func:`trade_grain` on its newest date; the original is never written. Data files are hard
    links (Delta writes new files and never changes one), each _delta_log a real copy (a log file may be rewritten in place)."""
    import os
    import shutil

    def copy(src: Path, dst: Path) -> None:
        shutil.copytree(src, dst, copy_function=os.link, ignore=shutil.ignore_patterns("_delta_log"))
        for log in src.rglob("_delta_log"):
            shutil.copytree(log, dst / log.relative_to(src))

    out = dest / "delta"
    copy(lake, out)
    trade_grain(out, budget)
    return out


def var_server(tmp_path_factory, lake: Path, **env: str):
    """A real server and console over ``lake``, its VaR engine ready; ``env``: more DRISHTI_* settings. Its VaR hot folder and the root
    it publishes the official results to (``<root>/risk-results``, VAR_GUIDE section 19) are its own: the lake is read, never written."""
    from test_var_explorer_browser import _engine_ready
    from wb_live import _stack

    hot = tmp_path_factory.mktemp("var-hot")
    official = tmp_path_factory.mktemp("var-official")
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_VAR_SYSTEM_BASKETS_SEED": "true",
                                        "DRISHTI_VAR_HOT_DIR": str(hot), "DRISHTI_VAR_OFFICIAL_ROOT": str(official), **env}, heap=VAR_HEAP):
        _engine_ready(str(st))
        yield st
