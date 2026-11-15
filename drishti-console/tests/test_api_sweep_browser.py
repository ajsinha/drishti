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

"""The API sweep as a drill step (tools/sweep/api_sweep.py; TEST_HARNESS.md, "The API sweep"): every GET endpoint of the server and of the console,
called with real values of the saved test data, on a server of its own over the full demo lake the command sweep reads (built once and cached;
owner 2026-11-15: never the page walk's thin lake); any FAIL fails the drill. HTTP only, eight calls at a time. The report (Markdown and JSONL) is kept in DRISHTI_SWEEP_OUT (the drill's logs folder), else data/sweep/.

DRISHTI_PAGEWALK_BASE=<console URL> with DRISHTI_SWEEP_SERVER=<server URL> sweeps a stack that is already running; DRISHTI_SWEEP_ADMIN /
_ADMIN_PASSWORD sign in to its console. The drill's stack is open (no sign-in), so it runs as its one user."""
import importlib.util
import os
from pathlib import Path

import pytest

from test_command_sweep_browser import SWEEP_ENV, SWEEP_PACKS, sweep_lake
from test_page_walk_browser import _packs_ready
from var_stack import var_server

SWEEP = Path(__file__).resolve().parents[2] / "tools" / "sweep" / "api_sweep.py"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    """An open server and console (no sign-in) over the full demo lake; DRISHTI_PAGEWALK_BASE: one already running."""
    if os.environ.get("DRISHTI_PAGEWALK_BASE"):
        yield os.environ["DRISHTI_PAGEWALK_BASE"].rstrip("/")
        return
    for st in var_server(tmp_path_factory, sweep_lake(tmp_path_factory), DRISHTI_DATALOAD_ENABLED="true",
                         DRISHTI_DATALOAD_DIR=str(tmp_path_factory.mktemp("api-sweep-dataload")), DRISHTI_PACKS=SWEEP_PACKS,
                         DRISHTI_LENSES="risk,world,market,trading,markets", **SWEEP_ENV):
        _packs_ready(st.server, SWEEP_PACKS.split(","))
        yield st


def test_every_get_endpoint_answers_clean(stack, capsys):
    spec = importlib.util.spec_from_file_location("api_sweep", SWEEP)
    sweep = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sweep)
    server = os.environ.get("DRISHTI_SWEEP_SERVER") or getattr(stack, "server", "")
    assert server, "no server URL: the fixture has none and DRISHTI_SWEEP_SERVER is not set"
    args = ["--console", str(stack), "--server", str(server), "--out", os.environ.get("DRISHTI_SWEEP_OUT") or str(sweep.ROOT / "data" / "sweep")]
    for flag, env in (("--admin", "ADMIN"), ("--admin-password", "ADMIN_PASSWORD")):
        if os.environ.get("DRISHTI_SWEEP_" + env):
            args += [flag, os.environ["DRISHTI_SWEEP_" + env]]
    code = sweep.main(args)
    out = capsys.readouterr().out
    print(out)
    assert code == 0, out
