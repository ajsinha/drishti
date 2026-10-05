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

"""Calc shows a traceback of the user's own code, the runtime's frames behind a "details" toggle (QA 2026-10-01 UX-19).
Runs the module's own functions with the page bridge stubbed, as test_calc_browsers does."""
import importlib.util
import sys
import types
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "drishti-console" / "web" / "static" / "calc" / "drishti.py"
CALC_JS = (ROOT / "drishti-console" / "web" / "static" / "js" / "calc.js").read_text()
RUNTIME_FILE = "/lib/python314.zip/_pyodide/_base.py"


@pytest.fixture()
def d(monkeypatch):
    bridge = types.ModuleType("_drishti_bridge")
    bridge.request, bridge.emit = (lambda *a, **k: None), (lambda item: None)
    ffi = types.ModuleType("pyodide.ffi")
    ffi.can_run_sync, ffi.run_sync = (lambda: False), (lambda a: None)
    pyodide = types.ModuleType("pyodide")
    pyodide.ffi = ffi
    for name, mod in (("_drishti_bridge", bridge), ("pyodide", pyodide), ("pyodide.ffi", ffi)):
        monkeypatch.setitem(sys.modules, name, mod)
    spec = importlib.util.spec_from_file_location("drishti_calc_tb", MODULE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _raise_through_the_runtime(source):
    """What eval_code_async does: the compiler runs inside a frame of the runtime's file."""
    ns = {}
    exec(compile("def run(src):\n    return compile(src, '<calc>', 'exec')\n", RUNTIME_FILE, "exec"), ns)
    try:
        ns["run"](source)
    except SyntaxError as e:
        return e


def test_a_syntax_error_shows_no_runtime_frames(d):
    e = _raise_through_the_runtime("x = (1 +\n")
    assert RUNTIME_FILE not in d._traceback(e)
    assert "SyntaxError" in d._traceback(e)


def test_the_full_traceback_keeps_every_frame_for_the_details(d):
    e = _raise_through_the_runtime("x = (1 +\n")
    assert RUNTIME_FILE in d._traceback(e, full=True)


def test_a_runtime_error_keeps_the_users_frames(d):
    ns = {}
    try:
        exec(compile("def f():\n    return 1 / 0\nf()\n", "<calc>", "exec"), ns)
    except ZeroDivisionError as e:
        text = d._traceback(e)
    assert "<calc>" in text and "line 2" in text and "ZeroDivisionError" in text


def test_the_page_offers_the_details_behind_a_toggle():
    assert "m.details" in CALC_JS and "'details'" in CALC_JS
