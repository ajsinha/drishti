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
"""Calc in a browser without JavaScript Promise Integration (Safari; Firefox with it switched off): the plain reads say
exactly how to write them with await, the starter snippets read only with the _async forms (so they run in every
browser), and the panel says so before a run fails. The module runs in Pyodide; here it is imported with the two
Pyodide modules it needs stood in, as a browser without JSPI would answer."""
import asyncio
import importlib.util
import re
import sys
import types
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "console" / "web" / "static" / "calc" / "drishti.py"


@pytest.fixture()
def drishti_without_jspi(monkeypatch):
    requests = []

    async def request(op, args):
        requests.append((op, args))
        return '{"ok": true, "data": {"tradeId": "MX-1", "mtm": 5}}'

    bridge = types.ModuleType("_drishti_bridge")
    bridge.request, bridge.emit = request, lambda item: None
    ffi = types.ModuleType("pyodide.ffi")
    ffi.can_run_sync = lambda: False                      # no JSPI: Python cannot pause for the page
    ffi.run_sync = lambda awaitable: pytest.fail("run_sync must not be called without JSPI")
    pyodide = types.ModuleType("pyodide")
    pyodide.ffi = ffi
    monkeypatch.setitem(sys.modules, "_drishti_bridge", bridge)
    monkeypatch.setitem(sys.modules, "pyodide", pyodide)
    monkeypatch.setitem(sys.modules, "pyodide.ffi", ffi)
    spec = importlib.util.spec_from_file_location("drishti_calc_under_test", MODULE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.requests = requests
    return module


def test_a_plain_read_says_exactly_how_to_write_it_with_await(drishti_without_jspi):
    d = drishti_without_jspi
    with pytest.raises(RuntimeError) as e:
        d.get("trade", "MX-1")
    assert "needs JavaScript Promise Integration" in str(e.value)
    assert "write `await drishti.get_async('trade', 'MX-1')` instead" in str(e.value)
    with pytest.raises(RuntimeError, match=re.escape("`await drishti.search_async('TRD where mtm > 0', as_of='2026-09-25')`")):
        d.search("TRD where mtm > 0", as_of="2026-09-25")
    with pytest.raises(RuntimeError, match=re.escape("`await drishti.history_async('trade', 'MX-1', 'mtm', 60)`")):
        d.history("trade", "MX-1", "mtm", 60)
    with pytest.raises(RuntimeError, match=re.escape("`await drishti.columns_async('TRD', ['mtm'])`")):
        d.columns("TRD", ["mtm"])
    assert d.requests == []                               # nothing was asked of the page


def test_the_async_forms_read_without_jspi(drishti_without_jspi):
    d = drishti_without_jspi
    assert asyncio.run(d.get_async("trade", "MX-1")) == {"tradeId": "MX-1", "mtm": 5}
    assert d.requests and d.requests[0][0] == "get"


def _snippets():
    """Every starter snippet the packs ship: in pack.yaml (python.snippets) and in python/*.py."""
    out = []
    for manifest in sorted(ROOT.glob("packs/*/pack.yaml")):
        py = (yaml.safe_load(manifest.read_text()) or {}).get("python") or {}
        out += [(f"{manifest.parent.name}: {s.get('title')}", s.get("code") or "") for s in py.get("snippets") or []]
        out += [(f"{manifest.parent.name}/{f.name}", f.read_text()) for f in sorted((manifest.parent / "python").glob("*.py"))]
    return out


def test_every_starter_snippet_reads_with_await_so_it_runs_in_every_browser():
    snippets = _snippets()
    assert len(snippets) >= 7
    plain = re.compile(r"\bdrishti\.(get|search|columns|history)\(")
    unawaited = re.compile(r"(?<!await )\bdrishti\.(get|search|columns|history)_async\(")
    for name, code in snippets:
        body = "\n".join(line for line in code.splitlines() if not line.lstrip().startswith("#"))
        assert not plain.search(body), f"{name} reads with a plain call, which needs JSPI: use await drishti.<op>_async(...)"
        assert not unawaited.search(body), f"{name} calls an _async read without await"


def test_the_panel_says_how_to_read_before_a_run_fails(client):
    js = client.get("/static/js/calc.js").text
    assert "data-calc-nosync" in js and "write await drishti.get_async" in js
    template = (ROOT / "console" / "web" / "templates" / "terminal" / "_calc.html").read_text()
    assert "<span data-calc-nosync hidden>" in template and "await drishti.search_async(…)" in template
