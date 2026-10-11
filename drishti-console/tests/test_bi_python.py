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

"""Rupaka phase 6 (docs/guides/PYTHON_BI.md), the console's routes for Python in BI: the run route hands the Python runner the masked rows the server built and answers a
number per group or a chart; failures are DRS codes and the traceback is for authors; the check route; the report page carries the browser target's wiring; CSRF."""
import io

import pytest
from fastapi.testclient import TestClient

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

MEASURE = {"id": "file:python:desk-vol", "title": "Desk volatility", "version": 1, "status": "published", "draft": False,
           "manifest": {"name": "desk-vol", "interface": "measure", "entry": "compute", "inputs": ["pnl1d"], "packages": ["pandas"], "target": "both", "timeout": 10, "memory": 512,
                        "output": "number", "params": {"k": 1}, "source": "def compute(table, params):\n    return float(table['pnl1d'].sum()) * params['k']\n"},
           "measure": {"name": "Desk P&L volatility", "inputs": ["trade.pnl1d"], "by": ["trade.desk"]}, "params": {"k": 1}, "run": "auto", "visual": "vol", "title": "Vol"}
VISUAL = {**MEASURE, "id": "file:python:hist", "measure": None, "params": {"bins": 2},
          "manifest": {**MEASURE["manifest"], "name": "hist", "interface": "visual", "entry": "render", "output": "echarts", "inputs": ["pnl1d"],
                       "source": "def render(table, params):\n    return {'title': {'text': str(len(table))}, 'tooltip': {'formatter': 'x'}, 'series': [{'type': 'bar', 'data': list(table['pnl1d'])}]}\n"}}


def arrow() -> bytes:
    t = pa.table({"desk": ["A", "A", "B"], "pnl1d": [1.0, 2.0, 5.0], "id": ["1", "2", "3"]})
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, t.schema) as w:
        w.write_table(t)
    return sink.getvalue()


class PyBackend(BiBackend):
    def __init__(self, item, powers=("bi", "bi-author"), refuse_item=None):
        super().__init__(powers)
        self.item, self.refuse_item, self.arrow_calls = item, refuse_item, []

    async def bi(self, method, path, ident, body=None, **params):
        if path.endswith("/python") and method == "POST":
            self.bi_calls.append((method, path, body, params))
            if self.refuse_item:
                raise BackendError(*self.refuse_item)
            return self.item
        if path == "/python/validate":
            self.bi_calls.append((method, path, body, params))
            return {"valid": True, "problems": [], "manifest": {**self.item["manifest"], "source": body["yaml"]}, "packages": ["pandas"]}
        return await super().bi(method, path, ident, body, **params)

    async def bi_arrow(self, path, ident, body, **params):
        self.arrow_calls.append((path, body, params))
        return arrow(), {"X-Bi-Rows": "3", "X-Bi-Masked": "mtm"}


def make(item, powers=("bi", "bi-author"), settings=None, refuse_item=None):
    s = load_settings(CONSOLE / "config")
    for k, v in (settings or {}).items():
        s.as_dict().setdefault("bi", {}).setdefault("python", {})[k] = v
    app = create_app(s)
    app.state.backend = PyBackend(item, powers, refuse_item)
    return TestClient(app), app.state.backend


def run(client, **extra):
    return client.post("/bi/python/run", json={"report": "file:report:python-desk-risk", "page": "risk", "visual": "vol", "state": {"slicers": {}}, **extra})


def test_a_python_measure_is_one_number_per_group_on_the_rows_the_server_built():
    client, backend = make(MEASURE)
    r = run(client)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["kind"] == "measure" and body["by"] == ["desk"]
    assert body["rows"] == [["A", 3.0], ["B", 5.0]]
    assert body["measure"] == "Desk P&L volatility" and body["item"] == "file:python:desk-vol" and body["target"] == "server" and body["inputRows"] == 3
    assert body["masked"] == ["mtm"] and body["ms"] >= 0
    # the console asked the server for what to run, then for the rows of the same visual as the same person: nothing else
    assert [c[:2] for c in backend.bi_calls if c[1].endswith("/python")] == [("POST", "/reports/file:report:python-desk-risk/python")]
    path, query, params = backend.arrow_calls[0]
    assert path == "/reports/file:report:python-desk-risk/query" and params == {"format": "arrow"} and query["visual"] == "vol" and query["state"] == {"slicers": {}}


def test_a_python_visual_returns_a_chart_option_with_params_and_formatters_removed():
    client, _ = make(VISUAL)
    body = run(client).json()
    assert body["kind"] == "echarts" and body["target"] == "server" and body["measure"] is None
    assert body["option"]["title"]["text"] == "3" and "formatter" not in body["option"]["tooltip"]


def test_a_failure_is_a_drs_code_and_only_an_author_sees_the_traceback():
    broken = {**VISUAL, "manifest": {**VISUAL["manifest"], "source": "def render(table, params):\n    return 1 / 0\n"}}
    client, _ = make(broken)
    r = run(client)
    assert r.status_code == 422 and r.json()["code"] == "DRS-9071" and "ZeroDivisionError" in r.json()["detail"]
    assert "<python-item>" in r.json()["traceback"]
    reader, _ = make(broken, powers=("bi",))
    r = run(reader)
    assert r.status_code == 422 and r.json()["code"] == "DRS-9071" and "traceback" not in r.json()


def test_a_sandbox_refusal_and_a_limit_are_their_own_codes():
    nosock = {**VISUAL, "manifest": {**VISUAL["manifest"], "source": "import os\ndef render(table, params):\n    return {}\n"}}
    r = run(make(nosock)[0])
    assert r.status_code == 403 and r.json()["code"] == "DRS-9073"
    slow = {**VISUAL, "manifest": {**VISUAL["manifest"], "timeout": 1, "source": "def render(table, params):\n    while True:\n        pass\n"}}
    r = run(make(slow)[0])
    assert r.status_code == 504 and r.json()["code"] == "DRS-9072"


def test_a_switched_off_runner_answers_9074_and_status_says_so():
    client, _ = make(MEASURE, settings={"enabled": False})
    r = run(client)
    assert r.status_code == 503 and r.json()["code"] == "DRS-9074"
    st = client.get("/bi/python/status").json()
    assert st["enabled"] is False and st["ready"] is False and "browser" in st
    ok = make(MEASURE)[0].get("/bi/python/status").json()
    assert ok["ready"] is True and "pandas" in ok["packages"] and ok["workers"] == 2


def test_the_servers_refusals_are_passed_on_and_a_malformed_call_is_refused():
    client, _ = make(MEASURE, refuse_item=(403, "DRS-9002", "no BI power"))
    r = run(client)
    assert r.status_code == 403 and r.json()["code"] == "DRS-9002"
    assert client.post("/bi/python/run", json={"report": "r"}).status_code == 422


def test_a_browser_only_item_is_not_run_on_the_server():
    only = {**MEASURE, "manifest": {**MEASURE["manifest"], "target": "browser"}}
    r = run(make(only)[0])
    assert r.status_code == 422 and r.json()["code"] == "DRS-9070" and "browser only" in r.json()["detail"]


def test_check_adds_the_consoles_source_checks_to_the_servers():
    client, _ = make(MEASURE)
    good = client.post("/bi/python/check", json={"yaml": "def compute(table, params):\n    return 1\n"}).json()
    assert good["valid"] is True and good["problems"] == []
    bad = client.post("/bi/python/check", json={"yaml": "import os\ndef compute(table, params):\n    return eval('1')\n"}).json()
    paths = [(p["path"], p["message"]) for p in bad["problems"]]
    assert bad["valid"] is False and any("import of 'os'" in m for _, m in paths) and any("eval" in m for _, m in paths)
    syntax = client.post("/bi/python/check", json={"yaml": "def compute(:\n"}).json()
    assert syntax["valid"] is False and "line 1" in syntax["problems"][0]["message"]
    missing = client.post("/bi/python/check", json={"yaml": "x = 1\n"}).json()
    assert any("no top-level function 'compute'" in p["message"] for p in missing["problems"])
    offlist = {**MEASURE, "manifest": {**MEASURE["manifest"], "packages": ["requests"]}}
    c2 = make(offlist)[0].post("/bi/python/check", json={"yaml": "def compute(t, p):\n    return 1\n"}).json()
    assert any("allow-list" in p["message"] for p in c2["problems"])


def test_the_python_paths_of_the_server_are_reachable_from_the_page_script():
    client, backend = make(MEASURE)
    r = client.post("/bi/api/reports/file:report:python-desk-risk/python", json={"page": "risk", "visual": "vol"})
    assert r.status_code == 200 and r.json()["manifest"]["name"] == "desk-vol"
    assert client.post("/bi/api/python/validate", json={"yaml": "x"}).status_code == 200


def test_the_report_page_wires_the_browser_target_without_inline_script():
    client, _ = make(MEASURE)
    page = client.get("/bi/r/file:report:python-desk-risk").text
    assert "/static/js/bi-python.js" in page and 'data-py-driver="/static/calc/bi_driver.py' in page and 'data-author="true"' in page
    assert "<script>" not in page.replace("<script src", "") and " onclick=" not in page
    reader, _ = make(MEASURE, powers=("bi",))
    assert 'data-author="false"' in reader.get("/bi/r/file:report:python-desk-risk").text
    assert client.get("/bi/r/file:report:python-desk-risk").headers["content-security-policy"].count("wasm-unsafe-eval") == 0
    assert "/static/calc/bi_driver.py" and client.get("/static/calc/bi_driver.py").status_code == 200
    assert client.get("/static/js/bi-python.js").status_code == 200


def test_the_run_route_takes_json_from_this_console_only():
    client, _ = make(MEASURE)
    other = client.post("/bi/python/run", json={"report": "r", "page": "p", "visual": "v"}, headers={"Origin": "https://evil.example"})
    assert other.status_code == 403 and other.json()["code"] == "DRS-5002"
    plain = client.post("/bi/python/run", content='{"report":"r"}', headers={"Content-Type": "text/plain"})
    assert plain.status_code in (400, 415) and plain.json()["code"] == "DRS-5001"
    assert client.post("/bi/python/check", content="x", headers={"Content-Type": "text/plain"}).status_code in (400, 415)


def _browser(capsys, payload):
    """The browser target's driver (static/calc/bi_driver.py), run here in CPython: the same code Pyodide runs, on the JSON rows the page received."""
    import base64
    import json
    ns: dict = {}
    exec(compile((CONSOLE / "web/static/calc/bi_driver.py").read_text(encoding="utf-8"), "bi_driver.py", "exec"), ns)
    ns["_bi_main"](base64.b64encode(json.dumps(payload).encode()).decode())
    line = [x for x in capsys.readouterr().out.splitlines() if x.startswith("\x01BIRESULT")][0]
    return json.loads(line[len("\x01BIRESULT"):])


def test_the_browser_driver_answers_as_the_server_worker_does(capsys):
    rows = {"columns": ["desk", "pnl1d", "id"], "rows": [["A", 1.0, "1"], ["A", 2.0, "2"], ["B", 5.0, "3"]]}
    m = MEASURE["manifest"]
    out = _browser(capsys, {**rows, "source": m["source"], "entry": "compute", "interface": "measure", "inputs": ["pnl1d"], "by": ["desk"], "params": {"k": 1}, "output": "number"})
    assert out == {"kind": "measure", "by": ["desk"], "rows": [["A", 3.0], ["B", 5.0]]}
    v = VISUAL["manifest"]
    out = _browser(capsys, {**rows, "source": v["source"], "entry": "render", "interface": "visual", "inputs": ["pnl1d"], "by": None, "params": {}, "output": "echarts"})
    assert out["kind"] == "echarts" and out["option"]["title"]["text"] == "3" and "formatter" not in out["option"]["tooltip"]
    with pytest.raises(TypeError, match="one number per group"):
        _browser(capsys, {**rows, "source": "def compute(table, params):\n    return 'x'\n", "entry": "compute", "interface": "measure", "inputs": ["pnl1d"], "by": ["desk"], "params": {}, "output": "number"})
