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
bare, with its example and with real ids of the saved test data, on a server of its own (sign-in on) over the walk's cached lake; any FAIL fails the drill.
The report (Markdown and JSONL) is kept in DRISHTI_SWEEP_OUT (the drill's logs folder), else data/sweep/.

DRISHTI_SWEEP_BASE=<console URL> sweeps a console that is already running; DRISHTI_SWEEP_ADMIN / _ADMIN_PASSWORD / _ROLE / _ROLE_PASSWORD sign in to
it. Otherwise the test starts its own server with sign-in on over the walk's cached lake (the walk's own stack is open, with no sign-in, so it
could never show a role's refusals): the sweep signs in as the administrator (blocking); with DRISHTI_SWEEP_ROLE set it also signs in as a role user the test creates
(a trader), report-only."""
import importlib.util
import json
import os
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from test_page_walk_browser import SNAPSHOT_DATE, WALK_DATA, WALK_PACKS, _packs_ready
from var_stack import var_data, var_lake, var_server

SWEEP = Path(__file__).resolve().parents[2] / "tools" / "sweep" / "command_sweep.py"
ADMIN, ADMIN_PASSWORD = "drishti-dev-admin", "drishti-dev-admin123"
ROLE, ROLE_PASSWORD = "sweep-trader", "long-enough-pass-1"


@pytest.fixture(scope="module")
def signed_in_stack(tmp_path_factory):
    """The walk's server and lake with sign-in on: the base URL, and the people to sign in as."""
    if os.environ.get("DRISHTI_SWEEP_BASE"):
        yield os.environ["DRISHTI_SWEEP_BASE"].rstrip("/"), os.environ.get("DRISHTI_SWEEP_ADMIN", ""), os.environ.get("DRISHTI_SWEEP_ADMIN_PASSWORD", ""), \
            os.environ.get("DRISHTI_SWEEP_ROLE", ""), os.environ.get("DRISHTI_SWEEP_ROLE_PASSWORD", "")
        return
    from wb_live import TOKEN_SECRET
    lake = var_lake(tmp_path_factory, var_data(*WALK_DATA), as_of=SNAPSHOT_DATE, extra=("--exposure", "committed"))
    for st in var_server(tmp_path_factory, lake, auth=True, DRISHTI_SECURITY_ENABLED="true", DRISHTI_TOKEN_SECRET=TOKEN_SECRET,
                         DRISHTI_DATALOAD_ENABLED="true", DRISHTI_DATALOAD_DIR=str(tmp_path_factory.mktemp("sweep-dataload")),
                         DRISHTI_PACKS=WALK_PACKS, DRISHTI_LENSES="risk,world,market,trading,markets"):
        _packs_ready(st.server, WALK_PACKS.split(","))
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


def test_every_function_runs_clean(signed_in_stack, capsys):
    """Signed in as the administrator: any FAIL fails the drill. A role user (DRISHTI_SWEEP_ROLE set) is swept too, report-only: its FAILs are
    printed and kept in the report, never blocking (owner 2026-11-14: testing is done as the administrator)."""
    base, admin, admin_password, role, role_password = signed_in_stack
    spec = importlib.util.spec_from_file_location("command_sweep", SWEEP)
    sweep = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sweep)
    args = ["--base", base, "--out", os.environ.get("DRISHTI_SWEEP_OUT") or str(sweep.ROOT / "data" / "sweep")]
    for flag, value in (("--admin", admin), ("--admin-password", admin_password), ("--role", role), ("--role-password", role_password)):
        if value:
            args += [flag, value]
    if role:
        args += ["--report-only", role]
    code = sweep.main(args)
    out = capsys.readouterr().out
    print(out)
    assert code == 0, out
