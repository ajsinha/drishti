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
called with real values of the saved test data, on the page walk's server over its cached lake; any FAIL fails the drill. HTTP only, eight calls
at a time. The report (Markdown and JSONL) is kept in DRISHTI_SWEEP_OUT (the drill's logs folder), else data/sweep/.

DRISHTI_PAGEWALK_BASE=<console URL> with DRISHTI_SWEEP_SERVER=<server URL> sweeps a stack that is already running; DRISHTI_SWEEP_ADMIN /
_ADMIN_PASSWORD sign in to its console. The drill's stack is open (no sign-in), so it runs as its one user."""
import importlib.util
import os
from pathlib import Path

from test_page_walk_browser import stack  # noqa: F401 - the fixture: the walk's server

SWEEP = Path(__file__).resolve().parents[2] / "tools" / "sweep" / "api_sweep.py"


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
