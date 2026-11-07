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

"""Python items in the console (docs/guides/BI_GUIDE.md › Python in BI): every run is the server's (its compute pool python, ARCHITECTURE.md section 24.19).
The console's routes pass the page's calls to the server as the signed-in person and pass the answer back: a python visual's run, the check, the status;
the server's traceback reaches the page only when the server gave one (for authors). The report page carries no browser runtime; CSRF holds. What the
code computes and what the sandbox refuses is tested where it runs: drishti-compute/src/test/python/test_items.py."""
import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

MEASURE = {"id": "file:python:desk-vol", "title": "Desk volatility", "version": 1, "status": "published", "draft": False,
           "manifest": {"name": "desk-vol", "interface": "measure", "entry": "compute", "inputs": ["pnl1d"], "packages": ["pandas"], "target": "server", "timeout": 10, "memory": 512,
                        "output": "number", "params": {"k": 1}, "source": "def compute(table, params):\n    return float(table['pnl1d'].sum()) * params['k']\n"},
           "measure": {"name": "Desk P&L volatility", "inputs": ["trade.pnl1d"], "by": ["trade.desk"]}, "params": {"k": 1}, "run": "auto", "visual": "vol", "title": "Vol"}
ANSWER = {"kind": "measure", "by": ["desk"], "rows": [["A", 3.0], ["B", 5.0]], "ms": 4, "measure": "Desk P&L volatility", "item": "file:python:desk-vol",
          "version": 1, "target": "server", "inputRows": 3, "masked": ["mtm"]}


class PyBackend(BiBackend):
    """Plays the server's Python routes: what it was asked, as whom, and its answer or refusal."""

    def __init__(self, powers=("bi", "bi-author"), refuse=None):
        super().__init__(powers)
        self.item, self.refuse_python = MEASURE, refuse

    async def bi(self, method, path, ident, body=None, **params):
        if path.startswith("/python/"):
            self.bi_calls.append((method, path, body, params, ident.user))
            if self.refuse_python:
                status, code, detail, tb = self.refuse_python
                e = BackendError(status, code, detail)
                e.extra = {"code": code, "detail": detail, **({"traceback": tb} if tb else {})}
                raise e
            if path == "/python/visual":
                return ANSWER
            if path == "/python/check":
                return {"valid": True, "problems": [], "manifest": MEASURE["manifest"], "tests": {"total": 0, "ok": True, "results": []}}
            if path == "/python/runtime":
                return {"runs": True, "why": None, "where": "server", "pool": "python", "packages": ["pandas", "numpy"], "calls": 0, "failures": 0}
        if path.endswith("/python") and method == "POST":
            return self.item
        return await super().bi(method, path, ident, body, **params)


def make(powers=("bi", "bi-author"), refuse=None):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PyBackend(powers, refuse)
    return TestClient(app), app.state.backend


def run(client, **extra):
    return client.post("/bi/python/run", json={"report": "file:report:python-desk-risk", "page": "risk", "visual": "vol", "state": {"slicers": {}}, **extra})


def test_a_python_visual_runs_on_the_server_as_the_person():
    client, backend = make()
    r = run(client)
    assert r.status_code == 200 and r.json() == ANSWER
    method, path, body, _, _ = [c for c in backend.bi_calls if c[1].startswith("/python/")][0]
    assert (method, path) == ("POST", "/python/visual") and body == {"report": "file:report:python-desk-risk", "page": "risk", "visual": "vol", "state": {"slicers": {}}}
    assert client.post("/bi/python/run", json={"report": "r"}).status_code == 422


def test_a_failure_carries_the_servers_code_and_its_traceback_only_when_the_server_gave_one():
    client, _ = make(refuse=(422, "DRS-9071", "ZeroDivisionError: division by zero", '  File "<python-item>", line 2'))
    r = run(client)
    assert r.status_code == 422 and r.json()["code"] == "DRS-9071" and "<python-item>" in r.json()["traceback"]
    reader, _ = make(powers=("bi",), refuse=(422, "DRS-9071", "ZeroDivisionError: division by zero", None))
    assert "traceback" not in run(reader).json()
    off, _ = make(refuse=(503, "DRS-9074", "Python runs only on a Linux server", None))
    r = run(off)
    assert r.status_code == 502 or r.json()["code"] == "DRS-9074"


def test_check_and_status_are_the_servers():
    client, backend = make()
    c = client.post("/bi/python/check", json={"yaml": "def compute(t, p):\n    return 1\n"}).json()
    assert c["valid"] is True and "tests" in c
    st = client.get("/bi/python/status").json()
    assert st["runs"] is True and st["where"] == "server" and st["pool"] == "python"
    assert [c[1] for c in backend.bi_calls if c[1].startswith("/python/")] == ["/python/check", "/python/runtime"]


def test_the_python_paths_of_the_server_are_reachable_from_the_page_script():
    client, backend = make()
    r = client.post("/bi/api/reports/file:report:python-desk-risk/python", json={"page": "risk", "visual": "vol"})
    assert r.status_code == 200 and r.json()["manifest"]["name"] == "desk-vol"
    assert client.post("/bi/api/python/validate", json={"yaml": "x"}).status_code == 200


def test_the_report_page_wires_python_without_inline_script_and_without_a_browser_runtime():
    client, _ = make()
    page = client.get("/bi/r/file:report:python-desk-risk").text
    assert "/static/js/drishti_bi-python.js" in page and 'data-author="true"' in page
    assert "data-py-runtime" not in page and "data-py-driver" not in page and "drishti_calc-worker.js" not in page and "pyodide" not in page.lower()
    assert "<script>" not in page.replace("<script src", "") and " onclick=" not in page
    reader, _ = make(powers=("bi",))
    assert 'data-author="false"' in reader.get("/bi/r/file:report:python-desk-risk").text
    assert client.get("/bi/r/file:report:python-desk-risk").headers["content-security-policy"].count("wasm-unsafe-eval") == 0
    assert client.get("/static/calc/bi_driver.py").status_code == 404
    assert client.get("/static/js/drishti_bi-python.js").status_code == 200


def test_the_run_route_takes_json_from_this_console_only():
    client, _ = make()
    other = client.post("/bi/python/run", json={"report": "r", "page": "p", "visual": "v"}, headers={"Origin": "https://evil.example"})
    assert other.status_code == 403 and other.json()["code"] == "DRS-5002"
    plain = client.post("/bi/python/run", content='{"report":"r"}', headers={"Content-Type": "text/plain"})
    assert plain.status_code in (400, 415) and plain.json()["code"] == "DRS-5001"
    assert client.post("/bi/python/check", content="x", headers={"Content-Type": "text/plain"}).status_code in (400, 415)
