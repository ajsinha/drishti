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

"""Wave C (docs/guides/BI_GUIDE.md "Tests shipped with an item"): tests a Python item carries are run in the sandbox by Check, and a failing one refuses a publish
made through the console (a bi-admin may force it)."""
import asyncio

import pytest
from fastapi.testclient import TestClient

pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402
from core.pyrunner import PyRunner  # noqa: E402
from core.pytests import run_tests  # noqa: E402

SUM = "def compute(table, params):\n    return float(table['pnl1d'].sum()) * params['k']\n"
MEASURE_M = {"name": "sum", "interface": "measure", "entry": "compute", "inputs": ["pnl1d"], "packages": ["pandas"], "target": "server", "timeout": 10, "memory": 256,
             "output": "number", "params": {"k": 1}, "source": SUM}


def _run(manifest, sample=None):
    return asyncio.run(run_tests(PyRunner(load_settings(CONSOLE / "config")), manifest, sample))


def test_a_measure_test_passes_within_its_tolerance_and_fails_outside_it():
    t = [{"name": "sums", "input": {"columns": ["pnl1d"], "rows": [[1.0], [2.0]]}, "params": {"k": 2}, "expect": 6.0},
         {"name": "close", "input": {"columns": ["pnl1d"], "rows": [[1.0], [2.0]]}, "expect": 3.001, "tolerance": 0.01},
         {"name": "wrong", "input": {"columns": ["pnl1d"], "rows": [[1.0], [2.0]]}, "expect": 4.0}]
    r = _run({**MEASURE_M, "tests": t})
    assert [x["status"] for x in r["results"]] == ["pass", "pass", "fail"] and r["passed"] == 2 and r["failed"] == 1 and r["ok"] is False
    assert "expected 4.0, got 3.0" in r["results"][2]["message"]


def test_a_grouped_measure_test_compares_each_group_and_refuses_a_group_it_did_not_expect():
    frame = {"columns": ["desk", "pnl1d"], "rows": [["A", 1.0], ["A", 2.0], ["B", 5.0]]}
    ok = {"name": "g", "input": frame, "by": ["desk"], "expect": {"A": 3.0, "B": 5.0}}
    extra = {"name": "e", "input": frame, "by": ["desk"], "expect": {"A": 3.0}}
    r = _run({**MEASURE_M, "tests": [ok, extra]})
    assert [x["status"] for x in r["results"]] == ["pass", "fail"] and "B: a group the test does not expect" in r["results"][1]["message"]


def test_a_test_that_cannot_run_is_an_error_not_a_failure():
    boom = {"name": "boom", "input": {"columns": ["pnl1d"], "rows": [[1.0]]}, "expect": 1.0}
    r = _run({**MEASURE_M, "source": "def compute(table, params):\n    return 1 / 0\n", "tests": [boom]})
    assert r["results"][0]["status"] == "error" and "DRS-9071" in r["results"][0]["message"] and r["errors"] == 1
    mixed = {"name": "mixed", "input": {"columns": ["pnl1d"], "rows": [[1.0], ["x"]]}, "expect": 1.0}
    assert _run({**MEASURE_M, "tests": [mixed]})["results"][0]["status"] == "error"


def test_a_visual_test_checks_the_kind_and_dotted_paths_of_the_result():
    src = "def render(table, params):\n    return {'mark': 'bar', 'data': {'values': [{'n': len(table)}]}}\n"
    m = {**MEASURE_M, "interface": "visual", "entry": "render", "output": "vega-lite", "source": src}
    frame = {"columns": ["pnl1d"], "rows": [[1.0], [2.0]]}
    r = _run({**m, "tests": [{"name": "k", "input": frame, "expect": {"kind": "vega-lite", "has": {"mark": "bar", "data.values.0.n": 2}}},
                              {"name": "kind", "input": frame, "expect": {"kind": "echarts"}},
                              {"name": "path", "input": frame, "expect": {"kind": "vega-lite", "has": {"mark": "line"}}}]})
    assert [x["status"] for x in r["results"]] == ["pass", "fail", "fail"]


def test_a_test_may_read_rows_of_a_dataset_through_the_sample_loader():
    import io

    import pyarrow as pa

    async def sample(spec):
        sink = io.BytesIO()
        t = pa.table({"pnl1d": [10.0, 20.0]})
        with pa.ipc.new_stream(sink, t.schema) as w:
            w.write_table(t)
        assert spec == {"dataset": "trading-risk", "fields": ["trades.pnl1d"], "limit": 50}
        return sink.getvalue()
    r = _run({**MEASURE_M, "tests": [{"name": "s", "sample": {"dataset": "trading-risk", "fields": ["trades.pnl1d"], "limit": 50}, "expect": 30.0}]}, sample)
    assert r["ok"] is True


class TestsBackend(BiBackend):
    __test__ = False

    def __init__(self, tests, powers=("bi", "bi-author")):
        super().__init__(powers)
        self.tests, self.calls = tests, []

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/python/validate":
            return {"valid": True, "problems": [], "manifest": {**MEASURE_M, "tests": self.tests}, "packages": ["pandas"]}
        if method == "GET" and path.startswith("/items/file:python:vol"):
            return {"id": "file:python:vol", "kind": "python", "title": "Vol", "body": "tests:\n  - x\n"}
        if method == "POST" and path.endswith(("/publish", "/approve")):
            self.calls.append((path, body))
            return {"id": "file:python:vol", "status": "published"}
        return await super().bi(method, path, ident, body, **params)


def _client(tests, powers=("bi", "bi-author")):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TestsBackend(tests, powers)
    return TestClient(app), app.state.backend


PASSING = [{"name": "ok", "input": {"columns": ["pnl1d"], "rows": [[1.0]]}, "expect": 1.0}]
FAILING = [{"name": "bad", "input": {"columns": ["pnl1d"], "rows": [[1.0]]}, "expect": 9.0}]


def test_check_runs_the_tests_and_reports_each():
    client, _ = _client(PASSING + FAILING)
    d = client.post("/bi/python/check", json={"yaml": SUM}).json()
    assert d["valid"] is True and d["tests"]["total"] == 2 and d["tests"]["passed"] == 1 and d["tests"]["failed"] == 1
    assert client.post("/bi/python/check", json={"yaml": SUM, "tests": False}).json().get("tests") is None


def test_publishing_an_item_with_a_failing_test_is_refused_and_passing_tests_publish():
    client, backend = _client(FAILING)
    r = client.post("/bi/api/items/file:python:vol/publish", json={"note": "x"})
    assert r.status_code == 422 and r.json()["code"] == "DRS-9076" and "bad: expected 9.0, got 1.0" in r.json()["detail"] and backend.calls == []
    assert client.post("/bi/api/items/file:python:vol/approve", json={}).status_code == 422
    ok, backend = _client(PASSING)
    assert ok.post("/bi/api/items/file:python:vol/publish", json={}).status_code == 200 and len(backend.calls) == 1


def test_only_a_bi_admin_may_publish_over_a_failing_test_with_force():
    author, backend = _client(FAILING, powers=("bi", "bi-author"))
    assert author.post("/bi/api/items/file:python:vol/publish", json={"force": True}).status_code == 422
    admin, backend = _client(FAILING, powers=("bi", "bi-author", "bi-admin"))
    assert admin.post("/bi/api/items/file:python:vol/publish", json={"force": True}).status_code == 200 and backend.calls
