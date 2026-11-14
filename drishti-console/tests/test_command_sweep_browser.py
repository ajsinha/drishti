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
bare, with its example and with real ids of the saved test data, on the page walk's server over its cached lake; any FAIL fails the drill.
The report (Markdown and JSONL) is kept in DRISHTI_SWEEP_OUT (the drill's logs folder), else data/sweep/.

DRISHTI_PAGEWALK_BASE=<console URL> sweeps a console that is already running (the walk's fixture); DRISHTI_SWEEP_ADMIN / _ADMIN_PASSWORD /
_ROLE / _ROLE_PASSWORD sign in to it. The drill's stack is open (no sign-in), so it runs as its one user."""
import importlib.util
import os
from pathlib import Path

from test_page_walk_browser import stack  # noqa: F401 - the fixture: the walk's server

SWEEP = Path(__file__).resolve().parents[2] / "tools" / "sweep" / "command_sweep.py"


def test_every_function_runs_clean(stack, capsys):
    spec = importlib.util.spec_from_file_location("command_sweep", SWEEP)
    sweep = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sweep)
    args = ["--base", str(stack), "--out", os.environ.get("DRISHTI_SWEEP_OUT") or str(sweep.ROOT / "data" / "sweep")]
    for flag, env in (("--admin", "ADMIN"), ("--admin-password", "ADMIN_PASSWORD"), ("--role", "ROLE"), ("--role-password", "ROLE_PASSWORD")):
        if os.environ.get("DRISHTI_SWEEP_" + env):
            args += [flag, os.environ["DRISHTI_SWEEP_" + env]]
    code = sweep.main(args)
    out = capsys.readouterr().out
    print(out)
    assert code == 0, out
