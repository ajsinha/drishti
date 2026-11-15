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


def var_lake(tmp_path_factory, only: str, as_of: str | None = None, extra: tuple[str, ...] = (), needs: str = "risk/scenario-pv") -> Path:
    """The cached demo lake of ``only`` ending ``as_of`` (default: :func:`last_weekday`; ``data``: the test data's own dates): built ONCE per
    hash of the test data, generators and lake code, across runs and drills, by the parallel Java loader (tools/drillkit/lake_cache.py), and
    looked up once per run (the first worker that asks). Read-only: a file that writes gets :func:`lake_copy` of it. Skips when it cannot be
    built or lacks ``needs`` (a table folder under the lake). ``extra``: more ``lake demo`` options (``--exposure committed``)."""
    import browser_harness

    as_of = as_of or last_weekday()

    run = browser_harness.run_dir(tmp_path_factory)
    key = "var-lake-" + hashlib.sha1("|".join((only, as_of, *extra, needs)).encode()).hexdigest()[:12]

    def make():
        lake_cache = _lake_cache()
        try:
            lake = lake_cache.demo_lake(only, as_of, extra) / "delta"   # lake demo's default writer: the parallel Java loader of the exec jar
        except RuntimeError as e:
            return {"lake": "", "error": "could not build the demo lake: " + str(e)}
        return {"lake": str(lake), "error": None if (lake / needs).is_dir() else f"no {needs} in the cached lake {lake}"}

    got = browser_harness.shared_once(run, key, make)
    if got["error"]:
        pytest.skip(got["error"])
    return Path(got["lake"])


def _lake_cache():
    if str(ROOT / "tools" / "drillkit") not in sys.path:
        sys.path.insert(0, str(ROOT / "tools" / "drillkit"))
    import lake_cache   # TEST_HARNESS.md, "The cached demo lake"
    return lake_cache


def lake_copy(lake: Path, dest: Path) -> Path:
    """A copy of a (cached, shared) lake at ``dest/delta``: data files are hard links where both sit on one file system (Delta writes new
    files and never changes one; else copied), each _delta_log a real copy (a log file may be rewritten in place). The original is never written."""
    import os
    import shutil

    def link(src, dst):
        try:
            os.link(src, dst)
        except OSError:                                  # another file system (a tmpfs /tmp): a plain copy
            shutil.copy2(src, dst)

    out = dest / "delta"
    shutil.copytree(lake, out, copy_function=link, ignore=shutil.ignore_patterns("_delta_log"))
    for log in lake.rglob("_delta_log"):
        shutil.copytree(log, out / log.relative_to(lake))
    return out


def demo_lake_copy(only: str, as_of: str, dest: Path, connectors: Path | None = None) -> Path:
    """The cached demo lake of ``only`` (built once per source hash by the Java loader) copied to ``dest/delta`` (:func:`lake_copy`), for a
    stack whose server writes beside its lake (live connectors); ``connectors``: where to put the connector files the build wrote beside it."""
    import shutil

    try:
        folder = _lake_cache().demo_lake(only, as_of)
    except RuntimeError as e:
        pytest.skip(str(e))
    if connectors is not None and (folder / "connectors").is_dir():
        shutil.copytree(folder / "connectors", connectors, dirs_exist_ok=True)
    return lake_copy(folder / "delta", dest)


def trade_grain(lake: Path, budget: int = 40) -> None:
    """A small trade-grain morning on the lake's newest date, replacing its position records (the demo is position grain; the engine's trade
    paths stay covered): each trade its own record, Endur as scenario PVs, Calypso and Murex as P&L vectors, at most ``budget`` trades a book."""
    day = max(p.name.split("=")[1] for p in (lake / "risk" / "scenario-pv").iterdir() if p.name.startswith("business_date="))
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "pvgen" / "morning.py"), "--grain", "trade", "--pv-systems", "ENDUR", "--budget", str(budget), "--date", day,
                        "--out", str(lake.parent / "morning"), "--lake", str(lake)], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0:
        raise RuntimeError("could not write the trade-grain morning: " + (r.stderr or r.stdout)[-400:])


def trade_grain_lake(lake: Path, budget: int = 40) -> Path:
    """The cached lake ``lake`` with :func:`trade_grain` on its newest date, itself cached: built once per source hash (a copy of ``lake``
    beside it in the cache, hard links, then the morning written into the copy), read-only like every cached lake. ``lake`` is never written."""
    lake_cache = _lake_cache()

    def build(work: Path) -> None:
        trade_grain(lake_copy(lake, work), budget)

    try:
        return lake_cache.cached("trade-grain|" + str(lake) + "|" + str(budget), build) / "delta"
    except RuntimeError as e:
        pytest.skip(str(e))


def own_folders(tmp_path_factory, shared: bool = False) -> dict[str, str]:
    """The settings that keep a server's writes off its (cached, read-only) lake: its VaR hot folder and the roots it publishes the official
    VaR and exposure results to. ``shared``: inside the server's scratch folder (the same settings for every file that shares it)."""
    if shared:
        return {"DRISHTI_VAR_HOT_DIR": "data/var/hot", "DRISHTI_VAR_OFFICIAL_ROOT": "data/var/official", "DRISHTI_CCR_EXPOSURE_OFFICIAL_ROOT": "data/ccr/official"}
    return {"DRISHTI_VAR_HOT_DIR": str(tmp_path_factory.mktemp("var-hot")), "DRISHTI_VAR_OFFICIAL_ROOT": str(tmp_path_factory.mktemp("var-official")),
            "DRISHTI_CCR_EXPOSURE_OFFICIAL_ROOT": str(tmp_path_factory.mktemp("ccr-official"))}


def var_server(tmp_path_factory, lake: Path, shared: bool = False, auth: bool = False, heap: str = VAR_HEAP, **env: str):
    """A real server and console over ``lake``, its VaR engine ready; ``env``: more DRISHTI_* settings. Its VaR hot folder and the roots
    it publishes the official VaR and exposure results to (``<root>/risk-results``, VAR_GUIDE section 19, EXPOSURE_GUIDE section 20; the
    exposure root defaults to the lake's own) are its own: the lake is read, never written.

    ``auth``: sign-in on (the development administrator: drishti-dev-admin / drishti-dev-admin123).

    ``shared``: the file only reads (TEST_HARNESS.md, "Shared read-only servers"): the next file on this worker asking for the same lake
    and settings gets this server without a start. Its own folders are then inside the server's scratch folder (relative to it), so the
    settings are the same for every file that asks, and the pool's guard fails a file that writes there."""
    from test_var_explorer_browser import _engine_ready
    from wb_live import _stack

    own = own_folders(tmp_path_factory, shared)
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_VAR_SYSTEM_BASKETS_SEED": "true",
                                        **own, **env}, auth=auth, heap=heap, shared=shared):
        _engine_ready(str(st), opener=signed_in(str(st)) if auth else None)
        yield st


def signed_in(console: str, user: str = "drishti-dev-admin", password: str = "drishti-dev-admin123"):
    """A urllib opener signed in to a console with sign-in on (the development administrator by default): its session cookie on every ask.
    The console signs in through its server, which may still be coming up: tried until it answers, for up to two minutes."""
    import http.cookiejar
    import time
    import urllib.error
    import urllib.parse
    import urllib.request

    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    form = urllib.parse.urlencode({"user": user, "password": password}).encode()
    deadline = time.monotonic() + 120
    while True:
        try:
            op.open(urllib.request.Request(console + "/login", data=form), timeout=60).read()
            return op
        except (urllib.error.URLError, OSError):
            if time.monotonic() > deadline:
                raise
            time.sleep(1)   # wait-ok: the server behind the console is still coming up
