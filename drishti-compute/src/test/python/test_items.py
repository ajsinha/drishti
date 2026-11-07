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

"""The one Python runtime (docs/architecture/PYTHON_COMPUTE.md section 19): ``drishti_compute.items`` in a real worker of the strict sandbox (a
network namespace, Landlock per request), driven over its protocol as the server's pool drives it. Every interface answers; a failure of the
author's code is an answer with its own traceback; the sandbox refuses the network, files outside the request's folder (also from C++ code), other
packages and processes; a limit stops a request alone. Linux with Landlock only (the strict sandbox's platform); elsewhere the tests skip."""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys

import pytest

from conftest import HERE, SDK

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from drishti_compute import guard, items  # noqa: E402

STRICT = guard.strict_unavailable("fork") is None
pytestmark = pytest.mark.skipif(not STRICT, reason="the strict sandbox needs Linux with Landlock: " + str(guard.strict_unavailable("fork")))
LIMITS = {"timeout": 10, "memory": 512, "max_output_bytes": 1_000_000}


def stream(table) -> bytes:
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


FRAME = stream(pa.table({"desk": ["A", "A", "B"], "pnl1d": [1.0, 2.0, 5.0], "id": ["1", "2", "3"]}))


class Strict:
    """A worker of the pool ``python``: the items engine, fork mode, strict isolation."""

    def __init__(self, tmp_path):
        env = {"PYTHONPATH": os.pathsep.join([SDK, HERE]), "PATH": os.environ.get("PATH", ""), "PYTHONHASHSEED": "0", "ARROW_DEFAULT_MEMORY_POOL": "system"}
        self.tmp = tmp_path
        self.p = subprocess.Popen([sys.executable, "-s", "-B", "-W", "ignore", "-m", "drishti_compute.worker", "--engines", "drishti_compute.items:ItemsEngine",
                                   "--mode", "fork", "--memory", str(8 << 30), "--cpu-seconds", "60", "--isolate", "strict"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env, cwd=str(tmp_path))
        self.ready = json.loads(self.p.stdout.readline())
        self.n = 0

    def call(self, kind="run", files=None, **params):
        self.n += 1
        rid = f"r{self.n}"
        scratch = self.tmp / rid
        os.makedirs(scratch / "in")
        rel = {}
        for name, blob in (files or {}).items():
            (scratch / "in" / name).write_bytes(blob)
            rel[name] = "in/" + name
        self.p.stdin.write(json.dumps({"type": "run", "id": rid, "kind": kind, "params": params, "scratch": str(scratch), "inputs": rel}) + "\n")
        self.p.stdin.flush()
        out = {}
        while True:
            m = json.loads(self.p.stdout.readline())
            out[m["type"]] = m
            if m["type"] == "done":
                out["scratch"] = scratch
                return out

    def item(self, interface, source, files=None, packages=("pandas",), limits=None, **how):
        out = self.call("run", files if files is not None else {"f.arrow": FRAME}, interface=interface,
                        manifest={"name": "t", "source": source, "packages": list(packages), "inputs": how.pop("inputs", []), "output": how.pop("output", "echarts"),
                                  **({"entry": how.pop("entry")} if "entry" in how else {})},
                        limits={**LIMITS, **(limits or {})}, **({"frame": "f.arrow"} if files is None and "frames" not in how else {}), **how)
        return out.get("result", {}).get("rows", [{}])[0], out

    def close(self):
        self.p.stdin.write('{"type":"shutdown"}\n')
        self.p.stdin.flush()
        self.p.wait(timeout=10)


@pytest.fixture
def w(tmp_path):
    worker = Strict(tmp_path)
    yield worker
    worker.close()


def test_the_worker_has_no_network_and_landlock(w):
    assert w.ready["isolation"]["level"] == "strict" and w.ready["isolation"]["network"] == "namespace"
    assert w.ready["isolation"]["files"].startswith("landlock ")
    about = w.call("about", packages=["pandas", "nothing-here"])["result"]["rows"][0]
    assert about["packages"]["pandas"] and about["packages"]["nothing-here"] is None


def test_a_measure_is_one_number_per_group(w):
    a, _ = w.item("measure", "def compute(table, params):\n    return float(table['pnl1d'].sum()) * params.get('k', 1)\n", inputs=["pnl1d"],
                  group_by=["desk"], params={"k": 2})
    assert a["ok"] and a["kind"] == "measure" and a["by"] == ["desk"] and a["rows"] == [["A", 6.0], ["B", 10.0]]
    v, _ = w.item("measure", "def compute_groups(frame, by, params):\n    return frame.groupby(by, as_index=False)['pnl1d'].sum().rename(columns={'pnl1d': 'value'})\n",
                  inputs=["pnl1d"], group_by=["desk"])
    assert v["rows"] == [["A", 3.0], ["B", 5.0]] and v["vectorised"] is True


def test_a_visual_returns_an_option_without_formatters_and_vega_lite_loads_nothing(w):
    a, _ = w.item("visual", "def render(table, params):\n    return {'title': {'text': str(len(table))}, 'tooltip': {'formatter': 'x'}}\n")
    assert a["kind"] == "echarts" and a["option"]["title"]["text"] == "3" and "formatter" not in a["option"]["tooltip"]
    v, _ = w.item("visual", "def render(table, params):\n    return {'mark': 'bar', 'data': {'url': 'http://example.org/x.json'}}\n", output="vega-lite")
    assert v["ok"] is False and v["kind"] == "sandbox" and "URL" in v["error"]


def test_a_column_and_a_cell(w):
    c, _ = w.item("column", "def derive(rows, params):\n    return rows['pnl1d'] * 2\n", inputs=["pnl1d"])
    assert c["kind"] == "column" and c["type"] == "decimal" and c["values"] == [2.0, 4.0, 10.0]
    other = stream(pa.table({"desk": ["A"], "limit": [9.0]}))
    cell, _ = w.item("cell", "print(len(df))\nresult = df.merge(extract('limits'), on='desk')\n", files={"f.arrow": FRAME, "x.arrow": other}, frame="f.arrow",
                     extracts={"limits": "x.arrow"})
    assert cell["stdout"] == "3\n" and cell["table"]["columns"] == ["desk", "pnl1d", "limit"] and cell["table"]["rowCount"] == 2


def test_a_transform_and_a_tables_build_function_write_parquet(w):
    a, out = w.item("transform", "def run(t, params):\n    return t.groupby('desk', as_index=False)['pnl1d'].sum()\n", files={"in_0.arrow": FRAME}, frames=["in_0.arrow"])
    assert a["kind"] == "transform" and a["rows"] == 2 and a["columns"] == [{"name": "desk", "type": "string"}, {"name": "pnl1d", "type": "decimal"}]
    assert out["result"]["files"] == {"result.parquet": "out/result.parquet"} and a["head"] == [["A", 3.0], ["B", 5.0]]
    b, _ = w.item("transform", "def build(inputs, params):\n    return inputs['legs'][['desk']].drop_duplicates()\n", files={"in_0.arrow": FRAME},
                  frames=["in_0.arrow"], names=["legs"], entry="build")
    assert b["ok"], b
    assert b["rows"] == 2 and b["columns"] == [{"name": "desk", "type": "string"}]


def test_text_the_contract_declares_a_date_is_read_as_one(w):
    dated = stream(pa.table({"desk": ["A", "B"], "tradeDate": ["2026-10-01", "2026-10-02"], "note": ["x", "not a date"]}))
    a, _ = w.item("transform", "def run(t, params):\n    return t\n", files={"in_0.arrow": dated}, frames=["in_0.arrow"],
                  contract={"tradeDate": "date", "note": "date", "desk": "string"})
    assert a["ok"], a
    assert a["columns"] == [{"name": "desk", "type": "string"}, {"name": "tradeDate", "type": "date"}, {"name": "note", "type": "string"}]


def test_steps_and_a_profile(w):
    s, _ = w.item("steps", "def run(t, params):\n    # step: keep A\n    t = t[t['desk'] == 'A']\n    # step: total\n    return t.groupby('desk', as_index=False)['pnl1d'].sum()\n",
                  files={"in_0.arrow": FRAME}, frames=["in_0.arrow"], names=["trades"])
    assert s["complete"] and [x["label"] for x in s["steps"]] == ["keep A", "total"] and s["steps"][0]["rows"] == 2 and s["inputs"][0]["name"] == "trades"
    p, _ = w.item("profile", "")
    assert p["kind"] == "profile" and p["rows"] == 3 and [c["name"] for c in p["columns"]] == ["desk", "pnl1d"]


def test_a_failure_is_an_answer_with_the_authors_own_traceback(w):
    a, out = w.item("visual", "def render(table, params):\n    return 1 / 0\n")
    assert a["ok"] is False and a["kind"] == "failed" and "ZeroDivisionError" in a["error"]
    assert "<python-item>" in a["traceback"] and "items.py" not in a["traceback"] and out["done"]["exit"] == 0


@pytest.mark.parametrize("source, words", [
    ("import os\ndef render(t, p):\n    return {}\n", "import of 'os'"),
    ("import socket\ndef render(t, p):\n    return {}\n", "import of 'socket'"),
    ("def render(t, p):\n    return {'x': open('/etc/hostname').read()}\n", "reading /etc/hostname"),
    ("def render(t, p):\n    open('/tmp/drishti-escape.txt', 'w')\n    return {}\n", "/tmp/drishti-escape.txt"),
    ("def render(t, p):\n    return eval('1')\n", "eval"),
])
def test_the_sandbox_refuses_in_words(w, source, words):
    a, _ = w.item("visual", source, packages=["pandas"])
    assert a["ok"] is False and words in a["error"] + a["traceback"], a
    assert not os.path.exists("/tmp/drishti-escape.txt")


def test_the_kernel_holds_code_the_audit_hook_cannot_see(w):
    """pyarrow opens files and sockets in C++, out of Python's audit hooks: Landlock and the namespace refuse them all the same."""
    home = os.path.expanduser("~/.bashrc") if os.path.exists(os.path.expanduser("~/.bashrc")) else "/etc/passwd"
    a, _ = w.item("visual", f"import pyarrow.fs as fs\ndef render(t, p):\n    return {{'x': str(fs.LocalFileSystem().open_input_stream({home!r}).read(5))}}\n",
                  packages=["pyarrow"])
    assert a["ok"] is False and ("Permission denied" in a["error"] or "denied" in a["error"].lower())


def test_a_limit_stops_the_request_alone(w):
    _, out = w.item("visual", "def render(t, p):\n    while True:\n        pass\n", limits={"timeout": 1})
    assert "result" not in out and out["done"].get("signal")                                 # CPU seconds: the child is killed, the worker stays
    a, _ = w.item("visual", "def render(t, p):\n    return {'ok': 1}\n")
    assert a["ok"] is True


def test_check_source_without_running_anything():
    m = {"interface": "measure", "packages": ["pandas"]}
    assert items.check_source({**m, "source": "def compute(t, p):\n    return 1\n"}, ["pandas"]) == []
    bad = items.check_source({**m, "source": "import os\ndef compute(t, p):\n    return eval('1')\n"}, ["pandas"])
    assert any("import of 'os'" in p["message"] for p in bad) and any("eval" in p["message"] for p in bad)
    assert "line 1" in items.check_source({**m, "source": "def compute(:\n"}, ["pandas"])[0]["message"]
    assert any("no top-level function 'compute'" in p["message"] for p in items.check_source({**m, "source": "x = 1\n"}, ["pandas"]))
    assert any("allow-list" in p["message"] for p in items.check_source({**m, "packages": ["requests"], "source": "def compute(t, p):\n    return 1\n"}, ["pandas"]))
