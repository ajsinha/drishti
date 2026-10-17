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

"""Wave B, console lane task 2 (docs/architecture/BI_DESIGN.md section 3.5): the runner. Python calls run in children of one fork-server (no interpreter start
per call, one process per call still); a stuck child is killed and the fork-server goes on; answers are kept in the L4 result cache by the item's code, its
parameters and the exact input bytes; the `column` interface and `compute_groups`; the Py-node endpoint the Java planner calls (/internal/py/*), behind
its token."""
import base64
import io

import pytest
from fastapi.testclient import TestClient

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402
from core.pyrunner import ResultCache  # noqa: E402

TOKEN = "sekret-runner-token-1"
SUM = {"name": "desk-sum", "interface": "measure", "entry": "compute", "inputs": ["pnl"], "packages": ["pandas"], "timeout": 10, "memory": 512,
       "source": "def compute(table, params):\n    return float(table['pnl'].sum()) * params.get('k', 1)\n"}
COLUMNS, ROWS = ["desk", "pnl"], [["A", 1.0], ["A", 2.0], ["B", 5.0], ["B", None]]


def make(**python):
    s = load_settings(CONSOLE / "config")
    s.as_dict().setdefault("bi", {}).setdefault("python", {}).update({"runner_token": TOKEN, **python})
    app = create_app(s)
    app.state.backend = BiBackend(("bi",))
    return app


def call(c, path, body, token=TOKEN):
    return c.post("/internal/py/" + path, json=body, headers={"X-Drishti-Runner-Token": token} if token is not None else {})


def test_a_measure_runs_in_a_child_of_the_fork_server_and_a_second_ask_is_cached():
    app = make()
    with TestClient(app) as c:
        r = call(c, "measure", {"manifest": SUM, "params": {"k": 2}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS})
        assert r.status_code == 200, r.text
        assert r.json()["ok"] is True and r.json()["by"] == ["desk"] and r.json()["rows"] == [["A", 6.0], ["B", 10.0]] and r.json()["cached"] is False
        st = app.state.pyrunner.status()
        assert st["forkServer"]["running"] is True and st["forkServer"]["forks"] == 1 and st["pyNode"] is True
        again = call(c, "measure", {"manifest": SUM, "params": {"k": 2}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS}).json()
        assert again["cached"] is True and again["rows"] == [["A", 6.0], ["B", 10.0]] and app.state.pyrunner.status()["forkServer"]["forks"] == 1
        other = call(c, "measure", {"manifest": SUM, "params": {"k": 3}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS}).json()
        assert other["cached"] is False and other["rows"] == [["A", 9.0], ["B", 15.0]]               # other parameters: another entry
        masked = call(c, "measure", {"manifest": SUM, "params": {"k": 3}, "group_by": ["desk"], "columns": COLUMNS, "rows": [["A", None], ["B", None]]}).json()
        assert masked["cached"] is False and masked["rows"] == [["A", 0.0], ["B", 0.0]]              # other input bytes (a masked reader): never another reader's answer


def test_a_child_past_its_time_is_killed_and_the_fork_server_goes_on():
    app = make()
    slow = {**SUM, "name": "slow", "timeout": 1, "source": "def compute(table, params):\n    while True:\n        pass\n"}
    with TestClient(app) as c:
        r = call(c, "measure", {"manifest": slow, "params": {}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS})
        assert r.status_code in (413, 504) and r.json()["code"] == "DRS-9072", r.text
        sandbox = {**SUM, "name": "net", "source": "import socket\ndef compute(table, params):\n    return 1\n"}
        assert call(c, "measure", {"manifest": sandbox, "params": {}, "group_by": [], "columns": COLUMNS, "rows": ROWS}).json()["code"] == "DRS-9073"
        ok = call(c, "measure", {"manifest": SUM, "params": {}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS})
        assert ok.status_code == 200 and ok.json()["rows"] == [["A", 3.0], ["B", 5.0]]
        assert app.state.pyrunner.status()["forkServer"]["running"] is True


def test_compute_groups_is_one_vectorised_call_over_every_group():
    src = ("def compute_groups(frame, by, params):\n    out = frame.groupby(by, dropna=False)['pnl'].sum(min_count=1).reset_index()\n"
           "    return out.rename(columns={'pnl': 'value'})\n")
    app = make()
    with TestClient(app) as c:
        r = call(c, "measure", {"manifest": {**SUM, "name": "vec", "source": src}, "params": {}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS}).json()
        assert r["ok"] is True and r["rows"] == [["A", 3.0], ["B", 5.0]]


def test_the_column_interface_answers_one_value_per_row():
    src = "def derive(frame, params):\n    return frame['pnl'].fillna(0) * params['k']\n"
    app = make()
    with TestClient(app) as c:
        r = call(c, "column", {"manifest": {"name": "scaled", "interface": "column", "inputs": ["pnl"], "packages": ["pandas"], "source": src}, "params": {"k": 10},
                               "columns": COLUMNS, "rows": ROWS}).json()
        assert r["ok"] is True and r["values"] == [10.0, 20.0, 50.0, 0.0] and r["type"] == "decimal"
        short = "def derive(frame, params):\n    return [1]\n"
        bad = call(c, "column", {"manifest": {"name": "short", "interface": "column", "inputs": ["pnl"], "source": short}, "params": {}, "columns": COLUMNS, "rows": ROWS}).json()
        assert bad["ok"] is False and bad["code"] == "DRS-9071" and "one value per row" in bad["detail"]


def test_a_transform_answers_parquet_with_its_columns():
    def ipc(rows):
        t = pa.table({"desk": [r[0] for r in rows], "pnl": [r[1] for r in rows]})
        sink = io.BytesIO()
        with pa.ipc.new_stream(sink, t.schema) as w:
            w.write_table(t)
        return base64.b64encode(sink.getvalue()).decode()
    src = "def run(trades, params):\n    return trades.groupby('desk', as_index=False)['pnl'].sum()\n"
    app = make()
    with TestClient(app) as c:
        r = call(c, "transform", {"manifest": {"name": "by-desk", "interface": "transform", "packages": ["pandas"], "source": src}, "params": {},
                                  "inputs": [ipc(ROWS)], "max_rows": 100, "max_bytes": 1_000_000})
        assert r.status_code == 200 and r.headers["content-type"] == "application/vnd.apache.parquet", r.text
        assert r.headers["x-drishti-rows"] == "2" and '"name": "pnl"' in r.headers["x-drishti-columns"]
        import pyarrow.parquet as pq
        assert pq.read_table(io.BytesIO(r.content)).to_pydict() == {"desk": ["A", "B"], "pnl": [3.0, 5.0]}


def test_the_endpoint_needs_its_token_and_is_off_without_one():
    app = make()
    with TestClient(app) as c:
        body = {"manifest": SUM, "params": {}, "group_by": [], "columns": COLUMNS, "rows": ROWS}
        assert call(c, "measure", body, token=None).json()["code"] == "DRS-9073"
        wrong = call(c, "measure", body, token="nope")
        assert wrong.status_code == 403 and wrong.json() == {"ok": False, "code": "DRS-9073", "status": 403, "detail": wrong.json()["detail"]}
        assert call(c, "measure", {"params": {}}).json()["code"] == "DRS-9070"
    off = make(runner_token="")
    with TestClient(off) as c:
        r = call(c, "measure", {"manifest": SUM}, token="")
        assert r.status_code == 503 and r.json()["code"] == "DRS-9074"


def test_without_the_fork_server_a_call_is_a_process_of_its_own():
    app = make(fork_server=False, cache_entries=0)
    with TestClient(app) as c:
        r = call(c, "measure", {"manifest": SUM, "params": {}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS}).json()
        assert r["rows"] == [["A", 3.0], ["B", 5.0]] and app.state.pyrunner.status()["forkServer"]["forks"] == 0
        assert call(c, "measure", {"manifest": SUM, "params": {}, "group_by": ["desk"], "columns": COLUMNS, "rows": ROWS}).json()["cached"] is False


def test_the_cache_drops_the_oldest_past_its_size():
    cache = ResultCache(entries=2, max_bytes=10_000, seconds=60)
    keys = [ResultCache.key({"source": "x"}, {"k": i}, None, [b"rows"]) for i in range(3)]
    assert len(set(keys)) == 3
    for i, k in enumerate(keys):
        cache.put(k, {"rows": [i]})
    assert cache.get(keys[0]) is None and cache.get(keys[2])[0] == {"rows": [2]} and cache.status()["entries"] == 2
    assert ResultCache.key({"source": "x"}, {}, None, [b"a"]) != ResultCache.key({"source": "x"}, {}, None, [b"b"])
