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

"""The browser files' lake and VaR stack (docs/architecture/TEST_HARNESS.md, "The shared test lake"): every browser test reads ONE lake, the
full demo lake of the saved test data (every dataset, ending SHARED_AS_OF, the committed exposure run), built once per hash of the test
data, generators and loaders by the parallel Java loader (tools/drillkit/lake_cache.py) and reused across runs and drills. It is read
only: no test builds a lake of its own, takes a subset of it or copies it; a server over it writes to its own folders (own_folders). A
test that has to write to a lake is parked (PARKED_I6). A real server over it per file (wb_live's pool), the demo connector off.

PARKED_I6 lists the tests parked to i6 because they write to a lake (owner 2026-11-15); TEST_HARNESS lists them too."""
from __future__ import annotations

import datetime
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
    """The VaR datasets a file reads (the market-risk VaR tables, the limits when ``limits``, then ``more``): a statement of what it needs;
    the shared lake holds them all."""
    return ",".join([f"market-risk/{k}" for k in RISK + (("var-limit",) if limits else ())] + ["core/desk", "core/book", "core/legal-entity"] + list(more))


def last_weekday() -> str:
    """Today, or the Friday before on a weekend (yyyy-MM-dd)."""
    d = datetime.date.today()
    while d.weekday() >= 5:
        d -= datetime.timedelta(days=1)
    return d.isoformat()


#: the shared lake's last business date (fixed, so the visual snapshots and the walk's pages are the same every day) and its options
SHARED_AS_OF = "2026-11-06"
SHARED_EXTRA = ("--exposure", "committed")

#: the tests parked to i6 because they write to a lake (owner 2026-11-15): shown as skipped with this reason in every run and drill report
PARKED_I6_REASON = "parked to i6: writes to a lake (owner 2026-11-15)"
PARKED_I6 = pytest.mark.skip(reason=PARKED_I6_REASON)


def _lake_cache():
    if str(ROOT / "tools" / "drillkit") not in sys.path:
        sys.path.insert(0, str(ROOT / "tools" / "drillkit"))
    import lake_cache   # TEST_HARNESS.md, "The cached demo lake"
    return lake_cache


def shared_cache_folder() -> Path:
    """The shared lake's folder in the lake cache (built under the cache's lock when missing; a lookup takes milliseconds when it is there).
    RuntimeError when it cannot be built. For a file without pytest's tmp_path_factory; the others ask :func:`shared_folder`."""
    return _lake_cache().demo_lake("", SHARED_AS_OF, SHARED_EXTRA)


def shared_folder(tmp_path_factory) -> Path:
    """The shared lake's cache folder (``delta/`` the lake, ``connectors/`` the connector files the build wrote beside it): looked up once per
    run by the first worker that asks; skips when it cannot be built."""
    import browser_harness

    def make():
        try:
            return {"folder": str(shared_cache_folder()), "error": None}
        except RuntimeError as e:
            return {"folder": "", "error": "could not build the shared demo lake: " + str(e)}

    got = browser_harness.shared_once(browser_harness.run_dir(tmp_path_factory), "shared-lake", make)
    if got["error"]:
        pytest.skip(got["error"])
    return Path(got["folder"])


def shared_lake(tmp_path_factory, needs: str = "risk/scenario-pv") -> Path:
    """The ONE shared lake (``delta/``), read only; fails when it lacks ``needs`` (a table folder a file reads)."""
    lake = shared_folder(tmp_path_factory) / "delta"
    assert (lake / needs).is_dir(), f"the shared lake {lake} has no {needs}"
    return lake


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
