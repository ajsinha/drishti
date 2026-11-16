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

"""The command sweep as a drill step (tools/sweep/command_sweep.py; TEST_HARNESS.md, "The command sweep"): every function of the FUNC directory,
bare, with its example and with real ids of the saved test data, on a server of its own (sign-in on) over the full demo lake (every dataset of the
saved test data, built once and cached: :func:`sweep_lake`; owner 2026-11-15: never the page walk's thin lake); any FAIL fails the drill.
The report (Markdown and JSONL) is kept in DRISHTI_SWEEP_OUT (the drill's logs folder), else in the test's own scratch folder (never
under data/: the drill's re-run sets no DRISHTI_SWEEP_OUT, drill of 2026-11-16).

DRISHTI_SWEEP_BASE=<console URL> sweeps a console that is already running; DRISHTI_SWEEP_ADMIN / _ADMIN_PASSWORD / _ROLE / _ROLE_PASSWORD sign in to
it. Otherwise the test starts its own server with sign-in on over the full demo lake (the walk's own stack is open, with no sign-in, so it
could never show a role's refusals): the sweep signs in as the administrator (blocking); with DRISHTI_SWEEP_ROLE set it also signs in as a role user the test creates
(a trader), report-only."""
import importlib.util
import json
import os
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from test_page_walk_browser import SNAPSHOT_DATE, WALK_PACKS, _packs_ready
from var_stack import shared_lake, var_server

SWEEP = Path(__file__).resolve().parents[2] / "tools" / "sweep" / "command_sweep.py"
ADMIN, ADMIN_PASSWORD = "drishti-dev-admin", "drishti-dev-admin123"
ROLE, ROLE_PASSWORD = "sweep-trader", "long-enough-pass-1"
#: the packs the sweeps' server serves: the walk's and GLEIF's (every pack the full demo lake has data for)
SWEEP_PACKS = WALK_PACKS + ",gleif"
#: the sweeps' servers start without the official exposure capture (CCR is parked for i4, owner 2026-11-15: it took minutes at every start)
SWEEP_ENV = {"DRISHTI_EXPOSURE_OFFICIAL": "false"}
#: the sweeps' server heap: the full demo lake's dates open beside each other (Why it moved opens the previous one); at 1536m the server
#: spent the sweep over 85% of its heap and refused or timed out (TEST_HARNESS.md, "The command sweep")
SWEEP_HEAP = "6g"


def sweep_lake(tmp_path_factory):
    """The full demo lake the sweeps run on: every dataset of the saved test data (data/testdata) through the lake builder, ending on the
    walk's fixed date, the committed exposure run; built once per hash of the test data and the builder (tools/drillkit/lake_cache.py),
    reused while they are unchanged."""
    return shared_lake(tmp_path_factory)


@pytest.fixture(scope="module")
def signed_in_stack(tmp_path_factory):
    """The walk's server and lake with sign-in on: the base URL, and the people to sign in as."""
    if os.environ.get("DRISHTI_SWEEP_BASE"):
        yield os.environ["DRISHTI_SWEEP_BASE"].rstrip("/"), os.environ.get("DRISHTI_SWEEP_ADMIN", ""), os.environ.get("DRISHTI_SWEEP_ADMIN_PASSWORD", ""), \
            os.environ.get("DRISHTI_SWEEP_ROLE", ""), os.environ.get("DRISHTI_SWEEP_ROLE_PASSWORD", "")
        return
    from wb_live import TOKEN_SECRET
    lake = sweep_lake(tmp_path_factory)
    for st in var_server(tmp_path_factory, lake, auth=True, heap=SWEEP_HEAP, DRISHTI_SECURITY_ENABLED="true", DRISHTI_TOKEN_SECRET=TOKEN_SECRET,
                         DRISHTI_DATALOAD_ENABLED="true", DRISHTI_DATALOAD_DIR=str(tmp_path_factory.mktemp("sweep-dataload")),
                         DRISHTI_PACKS=SWEEP_PACKS, DRISHTI_LENSES="risk,world,market,trading,markets", **SWEEP_ENV):
        _packs_ready(st.server, SWEEP_PACKS.split(","), token_secret=TOKEN_SECRET)
        role = os.environ.get("DRISHTI_SWEEP_ROLE", "")        # a role user is swept only when asked for: report-only
        if role:
            _make_role_user(str(st))
        yield str(st), ADMIN, ADMIN_PASSWORD, ROLE if role else "", ROLE_PASSWORD


def _make_role_user(base: str) -> None:
    """The administrator makes the role user the sweep signs in as: a trader of the trading, counterparty-risk and market-risk packs."""
    import http.cookiejar
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    op.open(urllib.request.Request(base + "/login", data=urllib.parse.urlencode({"user": ADMIN, "password": ADMIN_PASSWORD}).encode()), timeout=60).read()
    body = {"username": ROLE, "displayName": "Sweep Trader", "roles": ["trader"], "packs": ["trading", "counterparty-risk", "market-risk"], "enabled": True,
            "password": ROLE_PASSWORD, "mustChangePassword": False}
    req = urllib.request.Request(base + "/admin/api/users", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    assert op.open(req, timeout=60).status == 201


def test_every_function_runs_clean(signed_in_stack, capsys, tmp_path):
    """Signed in as the administrator: any FAIL fails the drill. A role user (DRISHTI_SWEEP_ROLE set) is swept too, report-only: its FAILs are
    printed and kept in the report, never blocking (owner 2026-11-14: testing is done as the administrator)."""
    base, admin, admin_password, role, role_password = signed_in_stack
    spec = importlib.util.spec_from_file_location("command_sweep", SWEEP)
    sweep = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sweep)
    args = ["--base", base, "--out", os.environ.get("DRISHTI_SWEEP_OUT") or str(tmp_path / "sweep-out"),
            "--as-of", os.environ.get("DRISHTI_SWEEP_AS_OF", "" if os.environ.get("DRISHTI_SWEEP_BASE") else SNAPSHOT_DATE)]   # the lake's last date
    for flag, value in (("--admin", admin), ("--admin-password", admin_password), ("--role", role), ("--role-password", role_password)):
        if value:
            args += [flag, value]
    if role:
        args += ["--report-only", role]
    code = sweep.main(args)
    out = capsys.readouterr().out
    print(out)
    assert code == 0, out
