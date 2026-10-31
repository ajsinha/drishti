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

"""The Transform interface of the Python runner (docs/guides/BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 13): frames in, one frame out, in the same sandbox as every Python item.
Real worker processes on small Arrow tables, and the command-line entry the server starts (core/transform_cli.py)."""
import asyncio
import io
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")
pq = pytest.importorskip("pyarrow.parquet")

from core.config import Settings  # noqa: E402
from core.pyrunner import PyError, PyRunner, check_source  # noqa: E402

CLI = Path(__file__).resolve().parent.parent / "core" / "transform_cli.py"


def arrow(**cols) -> bytes:
    table = pa.table(cols)
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


def runner(**over) -> PyRunner:
    cfg = {"workers": 2, "queue_seconds": 2, "packages": ["pandas", "numpy", "pyarrow"], "max_output_bytes": 2_000_000, **over}
    return PyRunner(Settings({"bi": {"python": cfg}}))


def transform(source: str, **over) -> dict:
    m = {"name": "t", "interface": "transform", "entry": "run", "inputs": ["a", "b"], "packages": ["pandas"], "target": "server", "timeout": 10, "memory": 512,
         "output": "frame", "params": {}, "source": source}
    m.update(over)
    return m


PNL = arrow(cp=["CP-0", "CP-1", "CP-2"], pnl=[100.0, -50.0, 30.0])
EXPOSURE = arrow(cp=["CP-0", "CP-1", "CP-3"], ee=[1000.0, 500.0, 70.0])


def run(tmp_path, source, frames=(PNL, EXPOSURE), r=None, max_rows=1000, **over):
    out = tmp_path / "result.parquet"
    answer = asyncio.run((r or runner()).run_transform(transform(source, **over), list(frames), params={"scale": 2}, dest=str(out), max_rows=max_rows))
    return answer, out


JOIN = """
import pandas as pd

def run(pnl, exposure):
    m = pnl.merge(exposure, on="cp", how="inner")
    m["ratio"] = m["pnl"] / m["ee"]
    return m
"""


def test_a_function_gets_the_frames_in_order_and_its_frame_comes_back_typed(tmp_path):
    answer, out = run(tmp_path, JOIN)
    assert answer["kind"] == "transform" and answer["rows"] == 2
    assert answer["columns"] == [{"name": "cp", "type": "string"}, {"name": "pnl", "type": "decimal"}, {"name": "ee", "type": "decimal"}, {"name": "ratio", "type": "decimal"}]
    table = pq.read_table(out).to_pandas()
    assert list(table["cp"]) == ["CP-0", "CP-1"]
    assert list(table["ratio"]) == [0.1, -0.1]


def test_a_class_named_transform_gets_the_parameters_on_self(tmp_path):
    source = """
class Transform:
    def run(self, pnl, exposure):
        out = pnl.copy()
        out["scaled"] = out["pnl"] * self.params["scale"]
        out["when"] = __import__("datetime").date(2026, 10, 9)
        out["ok"] = out["pnl"] > 0
        out["n"] = range(len(out))
        return out
"""
    answer, out = run(tmp_path, source, packages=["pandas"])
    types = {c["name"]: c["type"] for c in answer["columns"]}
    assert types == {"cp": "string", "pnl": "decimal", "scaled": "decimal", "when": "date", "ok": "boolean", "n": "integer"}
    assert list(pq.read_table(out).to_pandas()["scaled"]) == [200.0, -100.0, 60.0]


def test_what_is_not_a_frame_or_has_unusable_columns_is_refused_as_a_failure(tmp_path):
    with pytest.raises(PyError) as e:
        run(tmp_path, "def run(a, b):\n    return [1, 2]\n")
    assert e.value.code == "DRS-9071" and "pandas DataFrame" in e.value.detail
    with pytest.raises(PyError) as e:
        run(tmp_path, "def run(a, b):\n    a.columns = ['x y', 'z']\n    return a\n")
    assert e.value.code == "DRS-9071" and "x y" in e.value.detail
    with pytest.raises(PyError) as e:
        run(tmp_path, "def run(a, b):\n    return a\n", **{"entry": "missing"})
    assert e.value.code == "DRS-9071" and "missing" in e.value.detail


def test_the_row_limit_stops_a_frame_that_is_too_long(tmp_path):
    with pytest.raises(PyError) as e:
        run(tmp_path, "def run(a, b):\n    return a.loc[a.index.repeat(5)]\n", max_rows=10)
    assert e.value.code == "DRS-9072" and "15 rows" in e.value.detail


def test_the_sandbox_is_the_one_every_python_item_runs_in(tmp_path):
    with pytest.raises(PyError) as e:
        run(tmp_path, "import socket\n\ndef run(a, b):\n    return a\n")
    assert e.value.code == "DRS-9073"
    with pytest.raises(PyError) as e:
        run(tmp_path, "def run(a, b):\n    open('/etc/passwd').read()\n    return a\n")
    assert e.value.code == "DRS-9073"
    with pytest.raises(PyError) as e:
        run(tmp_path, "def run(a, b):\n    while True:\n        pass\n", timeout=1)
    assert e.value.code == "DRS-9072"


def test_the_input_size_limit_applies_to_all_the_frames_together(tmp_path):
    with pytest.raises(PyError) as e:
        run(tmp_path, JOIN, r=runner(max_input_bytes=100))
    assert e.value.code == "DRS-9072"


def test_the_source_check_accepts_a_function_or_the_class_and_names_the_missing_entry():
    ok_fn = {"interface": "transform", "source": "def run(*frames):\n    return frames[0]\n", "packages": []}
    ok_cls = {"interface": "transform", "source": "class Transform:\n    def run(self, *frames):\n        return frames[0]\n", "packages": []}
    none = {"interface": "transform", "source": "def other():\n    pass\n", "packages": []}
    assert not [p for p in check_source(ok_fn, ["pandas"]) if p["level"] == "error"]
    assert not [p for p in check_source(ok_cls, ["pandas"]) if p["level"] == "error"]
    assert "class Transform" in check_source(none, ["pandas"])[0]["message"]


def test_the_command_line_entry_the_server_starts_writes_result_json(tmp_path):
    (tmp_path / "in_0.arrow").write_bytes(PNL)
    (tmp_path / "in_1.arrow").write_bytes(EXPOSURE)
    out = tmp_path / "frame.parquet"
    req = {"manifest": transform(JOIN), "params": {}, "inputs": ["in_0.arrow", "in_1.arrow"], "output": str(out), "max_rows": 1000,
           "settings": {"bi.python.packages": ["pandas", "numpy", "pyarrow"], "bi.python.max_timeout_seconds": 20, "bi.python.max_memory_mb": 1024,
                        "bi.python.max_output_bytes": 2_000_000, "bi.python.workers": 1, "bi.python.queue_seconds": 1}}
    (tmp_path / "req.json").write_text(json.dumps(req))
    done = subprocess.run([sys.executable, str(CLI), str(tmp_path)], capture_output=True, timeout=60)
    assert done.returncode == 0, done.stderr
    result = json.loads((tmp_path / "result.json").read_text())
    assert result["ok"] and result["rows"] == 2 and [c["name"] for c in result["columns"]] == ["cp", "pnl", "ee", "ratio"]
    assert out.exists()
    # a failure is an answer, not a crash
    req["manifest"] = transform("def run(a, b):\n    raise ZeroDivisionError('boom')\n")
    (tmp_path / "req.json").write_text(json.dumps(req))
    subprocess.run([sys.executable, str(CLI), str(tmp_path)], capture_output=True, timeout=60, check=True)
    failed = json.loads((tmp_path / "result.json").read_text())
    assert failed == {"ok": False, "code": "DRS-9071", "status": 422, "detail": "ZeroDivisionError: boom"}
