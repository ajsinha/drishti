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

"""The compute worker SDK (docs/architecture/PYTHON_COMPUTE.md section 8): the pure-Python Arrow codec (and its agreement with pyarrow when
pyarrow is installed), the sample engine, and the worker's protocol, sandbox and isolation in both modes. Warm mode runs everywhere,
Windows included; fork mode skips on Windows (there is no fork). Nothing here needs a native binary."""
from __future__ import annotations

import array
import json
import os
import subprocess
import sys

import pytest

from drishti_compute import arrow as A
from drishti_compute.engine import Inputs, Progress, Request
from drishti_compute.samples.vectors import VectorEngine

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.normpath(os.path.join(HERE, "..", "..", "main", "python"))
ENGINES = "drishti_compute.samples.vectors:VectorEngine,compute_test_engines.faults:FaultEngine"
FAULTS = "compute_test_engines.faults:FaultEngine"


def sample_table() -> A.Table:
    return A.Table([A.int64("I", [1, None, 3]), A.float64("F", [1.5, 2.5, None]), A.boolean("B", [True, False, None]),
                    A.utf8("S", ["a", None, "héllo"]), A.vectors("V", 2, [1, 2, 3, 4, 5, 6])])


def test_the_codec_round_trips_every_column_type_with_nulls():
    back = A.read(A.to_bytes(sample_table()))
    assert back.to_rows() == [{"I": 1, "F": 1.5, "B": True, "S": "a", "V": [1.0, 2.0]},
                              {"I": None, "F": 2.5, "B": False, "S": None, "V": [3.0, 4.0]},
                              {"I": 3, "F": None, "B": None, "S": "héllo", "V": [5.0, 6.0]}]


def test_a_million_doubles_are_read_without_copying(tmp_path):
    n = 1_000_000
    p = tmp_path / "x.arrows"
    A.write_file(A.Table([A.float64("X", array.array("d", range(n)))]), p)
    t = A.read(str(p))
    col = t.column("X")
    assert isinstance(col.data, memoryview) and len(col) == n
    assert sum(col.data) == n * (n - 1) / 2
    del col
    t.close()


def test_the_codec_agrees_with_pyarrow_both_ways():
    pa = pytest.importorskip("pyarrow")
    pt = pa.ipc.open_stream(A.to_bytes(sample_table())).read_all()
    assert pt.column("S").to_pylist() == ["a", None, "héllo"]
    assert str(pt.schema.field("V").type) == "fixed_size_list<item: double>[2]"
    src = pa.table({"x": pa.array([1, None, 3], pa.int32()), "s": pa.array(["q", None, "r"], pa.large_string()),
                    "v": pa.FixedSizeListArray.from_arrays(pa.array([1.0, 2, 3, 4, 5, 6]), 2)})
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, src.schema) as w:
        w.write_table(src, max_chunksize=2)
    back = A.read(sink.getvalue().to_pybytes())
    assert back.to_rows() == [{"x": 1, "s": "q", "v": [1.0, 2.0]}, {"x": None, "s": None, "v": [3.0, 4.0]},
                              {"x": 3, "s": "r", "v": [5.0, 6.0]}]


def test_unsupported_columns_are_refused_in_words():
    pa = pytest.importorskip("pyarrow")
    sink = pa.BufferOutputStream()
    t = pa.table({"d": pa.array([1, 2], pa.date32())})
    with pa.ipc.new_stream(sink, t.schema) as w:
        w.write_table(t)
    with pytest.raises(A.ArrowError, match="column d has Arrow type"):
        A.read(sink.getvalue().to_pybytes())


def _req(kind, scratch, **params):
    return Request(id="r1", kind=kind, params=params, user="asha", roles=(), view_signature="", cls="interactive", seed=0, scratch=str(scratch))


def test_the_sample_engine_sums_columns_and_vectors(tmp_path):
    os.makedirs(tmp_path / "in")
    A.write_file(A.Table([A.float64("X", [1.5, 2.5]), A.int64("K", [2, 3]), A.vectors("PNL", 3, [1, 2, 3, 10, 20, 30])]),
                 tmp_path / "in" / "values.arrows")
    seen = []
    eng = VectorEngine()
    r = eng.compute(_req("sum", tmp_path), Inputs(str(tmp_path), {"values": "in/values.arrows"}), Progress(seen.append))
    assert r.rows == [{"column": "X", "sum": 4.0, "count": 2}, {"column": "K", "sum": 5, "count": 2}]
    assert seen[-1] == {"done": 2, "of": 2}
    r = eng.compute(_req("sum-vectors", tmp_path), Inputs(str(tmp_path), {"values": "in/values.arrows"}), Progress(seen.append))
    assert list(r.tables["total"].column("PNL").vector(0)) == [11.0, 22.0, 33.0]
    assert r.rows[0]["sum"] == 66.0


def test_the_path_sum_engine_adds_paths_day_by_day_in_the_shape_of_its_java_twin(tmp_path):
    from drishti_compute.engine import ComputeError
    from drishti_compute.samples.paths import PathSumEngine
    os.makedirs(tmp_path / "in")
    A.write_file(A.Table([A.utf8("BOOK", ["B1", "B1"]), A.vectors("PNL", 3, [1, -2, 3, 10, 20, -30])]), tmp_path / "in" / "values.arrows")
    seen = []
    r = PathSumEngine().compute(_req("sum-vectors", tmp_path), Inputs(str(tmp_path), {"values": "in/values.arrows"}), Progress(seen.append))
    assert r.rows == [{"ROWS": 2, "WIDTH": 3, "SUM": 2.0, "WORST": -27.0, "BEST": 18.0}]
    assert r.tables["total"].column("DAY").to_list() == [1, 2, 3] and r.tables["total"].column("PNL").to_list() == [11.0, 18.0, -27.0]
    assert seen and seen[-1]["done"] == 2
    A.write_file(A.Table([A.utf8("BOOK", ["B1"])]), tmp_path / "in" / "none.arrows")
    with pytest.raises(ComputeError, match="no record of values has a PNL path"):
        PathSumEngine().compute(_req("sum-vectors", tmp_path), Inputs(str(tmp_path), {"values": "in/none.arrows"}), Progress(seen.append))


class Worker:
    """A worker process driven over its JSON-lines protocol, as the server's supervisor drives it."""

    def __init__(self, mode, tmp_path):
        env = {"PYTHONPATH": os.pathsep.join([SDK, HERE]), "PATH": os.environ.get("PATH", ""), "PYTHONHASHSEED": "0",
               "SYSTEMROOT": os.environ.get("SYSTEMROOT", "")}
        self.tmp = tmp_path
        self.p = subprocess.Popen([sys.executable, "-s", "-B", "-m", "drishti_compute.worker", "--engines", ENGINES, "--mode", mode],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env,
                                  cwd=str(tmp_path))
        self.ready = json.loads(self.p.stdout.readline())
        self.n = 0

    def run(self, kind, engine=FAULTS, **params):
        self.n += 1
        rid = f"r{self.n}"
        scratch = self.tmp / rid
        os.makedirs(scratch / "in")
        A.write_file(A.Table([A.float64("X", [1.0, 2.0])]), scratch / "in" / "values.arrows")
        self.p.stdin.write(json.dumps({"type": "run", "id": rid, "engine": engine, "kind": kind, "params": params, "user": "asha",
                                       "scratch": str(scratch), "inputs": {"values": "in/values.arrows"}}) + "\n")
        self.p.stdin.flush()
        out = []
        while True:
            line = self.p.stdout.readline()
            if not line:
                return out
            m = json.loads(line)
            out.append(m)
            if m["type"] == "done":
                return out

    def close(self):
        self.p.stdin.write('{"type":"shutdown"}\n')
        self.p.stdin.flush()
        self.p.wait(timeout=10)


MODES = ["warm"] + ([] if os.name == "nt" else ["fork"])


@pytest.mark.parametrize("mode", MODES)
def test_the_worker_answers_isolates_and_refuses(mode, tmp_path):
    w = Worker(mode, tmp_path)
    try:
        assert w.ready["type"] == "ready" and w.ready["mode"] == mode
        assert "sum-vectors" in w.ready["engines"]["drishti_compute.samples.vectors:VectorEngine"]
        msgs = w.run("echo", engine="drishti_compute.samples.vectors:VectorEngine", a=1)
        result = [m for m in msgs if m["type"] == "result"][0]
        assert result["rows"][0]["params"] == {"a": 1}
        assert A.read(str(tmp_path / "r1" / result["tables"]["values"])).to_rows() == [{"X": 1.0}, {"X": 2.0}]
        w.run("leak", value="secret")
        who = [m for m in w.run("who") if m["type"] == "result"][0]["rows"][0]
        assert who["leak"] is None                                       # fork: a fresh child; warm: the registered cache cleared
        err = [m for m in w.run("socket") if m["type"] == "error"][0]
        assert err["code"] == "limit" and "network" in err["message"]
        outside = tmp_path / "escaped.txt"
        err = [m for m in w.run("write-outside", path=str(outside)) if m["type"] == "error"][0]
        assert err["code"] == "limit" and not outside.exists()
        err = [m for m in w.run("boom") if m["type"] == "error"][0]
        assert err["code"] == "failed" and "secret" not in err["message"]
        progress = [m for m in w.run("progress") if m["type"] == "progress"]
        assert [p["data"]["done"] for p in progress] == [1, 2, 3]
    finally:
        w.close()


@pytest.mark.skipif(os.name == "nt", reason="fork mode: there is no fork on Windows")
def test_a_crash_in_fork_mode_kills_only_the_request(tmp_path):
    w = Worker("fork", tmp_path)
    try:
        done = w.run("crash")[-1]
        assert done["type"] == "done" and done["exit"] == 9
        assert [m for m in w.run("who") if m["type"] == "result"]          # the worker carries on
    finally:
        w.close()
