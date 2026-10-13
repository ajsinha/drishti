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

"""Wave C, Calc's BI client (docs/guides/PYTHON_CALC.md "The BI client"): ``drishti_bi.query`` asks the page for the BI engine's answer as the person and returns a
DataFrame; ``datasets`` and ``describe`` read the catalogue; ``walk`` hands a frame to the Graphic Walker frame, which is sandboxed under its own narrow policy.
The module runs in Pyodide; here it is imported with the two Pyodide modules it needs stood in."""
import asyncio
import importlib.util
import json
import sys
import types
from pathlib import Path

import pytest

pytest.importorskip("pandas")

ROOT = Path(__file__).resolve().parents[2]
CALC = ROOT / "drishti-console" / "web" / "static" / "calc"

ANSWER = {"columns": [{"name": "trade.desk", "type": "string", "masked": False}, {"name": "MTM", "type": "decimal", "masked": False},
                      {"name": "trade.book", "type": "string", "masked": True}],
          "rows": [["FX", 10.5, "(masked)"], ["RATES", -3.0, "(masked)"]], "rowCount": 2,
          "meta": {"layer": "columns", "millis": 3.2, "dates": ["2026-10-09"], "masked": ["trade.book"]}}


@pytest.fixture()
def bi(monkeypatch):
    sent, emitted = [], []

    async def request(op, args):
        sent.append((op, json.loads(args)))
        if op == "bi.query":
            return json.dumps({"ok": True, "data": ANSWER})
        if op == "bi.datasets":
            return json.dumps({"ok": True, "data": [{"id": "file:dataset:trades-basic", "title": "Trades", "tables": 1, "measures": 4, "description": "x"}]})
        if op == "bi.model":
            return json.dumps({"ok": True, "data": {"tables": [{"name": "trade", "columns": [{"name": "desk", "type": "string", "masked": False}]}],
                                                    "measures": [{"name": "MTM", "python": None}, {"name": "Vol", "python": "vol"}]}})
        return json.dumps({"ok": False, "code": "DRS-5001", "detail": "unknown read"})

    bridge = types.ModuleType("_drishti_bridge")
    bridge.request, bridge.emit = request, lambda item: emitted.append(json.loads(item))
    ffi = types.ModuleType("pyodide.ffi")
    ffi.can_run_sync = lambda: False
    ffi.run_sync = lambda awaitable: pytest.fail("run_sync must not be called without JSPI")
    pyodide = types.ModuleType("pyodide")
    pyodide.ffi = ffi
    for name, mod in (("_drishti_bridge", bridge), ("pyodide", pyodide), ("pyodide.ffi", ffi)):
        monkeypatch.setitem(sys.modules, name, mod)
    for name, file in (("drishti", "drishti.py"), ("drishti_bi", "drishti_bi.py")):
        spec = importlib.util.spec_from_file_location(name, CALC / file)
        module = importlib.util.module_from_spec(spec)
        monkeypatch.setitem(sys.modules, name, module)
        spec.loader.exec_module(module)
    module.sent, module.emitted = sent, emitted
    return module


def test_a_query_is_the_engines_answer_as_a_dataframe_with_what_is_masked_said(bi):
    df = asyncio.run(bi.query_async("trades-basic", measures=["MTM"], by=["trade.desk", "trade.book"], filters={"trade.desk": ["FX", "RATES"]}, limit=50))
    assert list(df.columns) == ["trade.desk", "MTM", "trade.book"] and len(df) == 2
    assert df["MTM"].dtype.kind == "f" and df.attrs["masked"] == ["trade.book"] and df.attrs["dataset"] == "trades-basic" and df.attrs["asOf"] == "2026-10-09"
    op, args = bi.sent[0]
    assert op == "bi.query" and args["dataset"] == "trades-basic"
    assert args["body"] == {"mq": 1, "select": {"fields": ["trade.desk", "trade.book"], "measures": ["MTM"]},
                            "where": [{"field": "trade.desk", "op": "in", "values": ["FX", "RATES"]}], "limit": 50, "model": "trades-basic@1"}   # one model query on the converter's model


def test_filters_may_be_written_as_the_engine_takes_them_and_dates_are_one_or_a_range(bi):
    asyncio.run(bi.query_async("d", measures="MTM", filters=[{"field": "trade.desk", "op": "ne", "values": ["FX"]}], dates=("2026-10-01", "2026-10-09")))
    body = bi.sent[0][1]["body"]
    assert body["select"]["measures"] == ["MTM"] and body["where"][0]["op"] == "ne" and body["time"] == {"window": {"from": "2026-10-01", "to": "2026-10-09"}}
    asyncio.run(bi.query_async("d", by="trade.desk", dates="2026-10-09"))
    assert bi.sent[1][1]["body"]["time"] == {"window": {"from": "2026-10-09", "to": "2026-10-09"}}
    assert bi.model_ref("trading-risk@1") == "trading-risk@1" and bi.model_ref("dataset:pack.trading.trade") == "dataset:pack.trading.trade"
    assert bi.model_ref("file:dataset:trades-basic") == "trades-basic@1"
    with pytest.raises(ValueError, match="at least one measure"):
        asyncio.run(bi.query_async("d"))


def test_the_plain_forms_say_how_to_write_them_with_await_where_the_browser_cannot_pause(bi):
    with pytest.raises(RuntimeError, match=r"await drishti\.bi\.query_async|needs JavaScript Promise Integration"):
        bi.query("trades-basic", measures=["MTM"])
    assert bi.sent == []


def test_datasets_and_describe_are_frames(bi):
    d = asyncio.run(bi.datasets_async())
    assert list(d["id"]) == ["file:dataset:trades-basic"] and d.loc[0, "measures"] == 4
    m = asyncio.run(bi.describe_async("trades-basic"))
    assert list(m["name"]) == ["trade.desk", "MTM", "Vol"] and list(m["kind"]) == ["field", "measure", "measure"] and m.loc[2, "type"] == "python"


def test_walk_hands_the_frame_to_graphic_walker_with_field_types(bi):
    import pandas as pd
    df = pd.DataFrame({"desk": ["FX", "RATES"], "mtm": [1.5, 2.5], "day": pd.to_datetime(["2026-10-08", "2026-10-09"]), "live": [True, False]})
    bi.walk(df, title="Desks")
    item = bi.emitted[0]
    assert item["kind"] == "walker" and item["title"] == "Desks" and item["total"] == 2
    kinds = {f["name"]: (f["semanticType"], f["analyticType"]) for f in item["fields"]}
    assert kinds == {"desk": ("nominal", "dimension"), "mtm": ("quantitative", "measure"), "day": ("temporal", "dimension"), "live": ("nominal", "dimension")}
    assert item["rows"][0] == {"desk": "FX", "mtm": 1.5, "day": "2026-10-08", "live": True}
    bi.walk(df, limit=1)
    assert any(i.get("kind") == "note" and "first 1 of 2 rows" in i["text"] for i in bi.emitted)


def test_the_page_asks_the_bi_proxy_as_the_person_and_the_worker_loads_the_module_on_demand():
    js = (ROOT / "drishti-console/web/static/js/calc.js").read_text()
    assert "'/bi/api/datasets'" in js and "'/bi/api/mq'" in js and "m.op === 'bi.query'" in js and "walker.html" in js and "'allow-scripts'" in js
    assert "allow-same-origin" not in js
    worker = (ROOT / "drishti-console/web/static/js/calc-worker.js").read_text()
    assert "drishti_bi.py" in worker and "drishti_bi" in worker.split("const DATA")[1].split("\n")[0]


def test_the_walker_page_has_its_own_narrow_policy_and_everything_else_keeps_the_strict_one(client):
    r = client.get("/static/calc/walker.html")
    csp = r.headers["content-security-policy"]
    assert r.status_code == 200 and "sandbox allow-scripts" in csp and "connect-src 'none'" in csp and "script-src 'self' 'unsafe-eval'" in csp
    assert "form-action 'none'" in csp and "default-src 'none'" in csp
    strict = client.get("/static/calc/drishti_bi.py").headers["content-security-policy"]
    assert "unsafe-eval" not in strict and "unsafe-inline" not in strict
    assert client.get("/static/vendor/graphic-walker/0.5.3/walker.js").status_code == 200
    assert "<script>" not in r.text.replace("<script src", "")


def test_the_vendored_walker_has_no_react_leaflet_code_and_carries_its_licences():
    walker = ROOT / "drishti-console/web/static/vendor/graphic-walker/0.5.3"
    text = (walker / "walker.js").read_text(encoding="utf-8")
    assert "useLeafletContext" not in text and "Hippocratic" not in (walker / "LICENSES.txt").read_text(encoding="utf-8")
    assert "Apache License" in (walker / "LICENSE").read_text()
    assert not any("http" in line for line in (CALC / "walker-frame.js").read_text().splitlines() if "Copyright" not in line and "docs/" not in line)
