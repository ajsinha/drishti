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

"""Python custom pivot functions (ARCHITECTURE.md sections 23.8.1 and 23.8.5): the four steps over Arrow batches, split-and-merge equal to one
pass, states that round-trip, restricted imports and built-ins, and the worker process's frames and sandbox."""
import io
import math
import os
import struct
import subprocess
import sys

import pytest

from drishti_compute import arrow, pivot_fn

WRET = '''
from drishti_pivot import function, Mergeable
import math

@function(name="wavg_return", kind=Mergeable, inputs={"r": "float64", "w": "float64"})
class WeightedReturn:
    def start(self, params):
        return {"swr": 0.0, "sw": 0.0}
    def add(self, s, batch):
        r, w = batch.list("r"), batch.list("w")
        swr = math.fsum(a * b for a, b in zip(r, w) if a is not None and b is not None)
        sw = math.fsum(b for a, b in zip(r, w) if a is not None and b is not None)
        return {"swr": s["swr"] + swr, "sw": s["sw"] + sw}
    def merge(self, a, b):
        return {"swr": a["swr"] + b["swr"], "sw": a["sw"] + b["sw"]}
    def finish(self, s):
        return None if s["sw"] == 0 else s["swr"] / s["sw"]
'''

MEDIAN = '''
from drishti_pivot import function, Holistic
import statistics

@function(name="med", kind=Holistic, inputs={"x": "float64"})
class Med:
    def start(self, params):
        return []
    def add(self, s, batch):
        return s + [v for v in batch.list("x") if v is not None]
    def finish(self, s):
        return statistics.median(s) if s else None
'''


def batch(r, w):
    t = arrow.Table([arrow.float64("r", r), arrow.float64("w", w)])
    return arrow.to_bytes(t)


def setup_module():
    pivot_fn._alias()


def test_four_steps_and_split_and_merge_equal_one_pass():
    f = pivot_fn.load(WRET, "WeightedReturn")
    r = [0.01 * (i % 17 - 8) for i in range(1000)]
    w = [1 + i % 13 for i in range(1000)]
    t = arrow.read(batch(r, w))
    one = f.finish(f.add(f.start(), pivot_fn.Batch(t, ["r", "w"])))
    a = f.add(f.start(), pivot_fn.Batch(arrow.read(batch(r[:400], w[:400])), ["r", "w"]))
    b = f.add(f.start(), pivot_fn.Batch(arrow.read(batch(r[400:], w[400:])), ["r", "w"]))
    merged = f.finish(f.merge(a, b))
    assert math.isclose(one, merged, rel_tol=1e-12)
    assert math.isclose(one, math.fsum(x * y for x, y in zip(r, w)) / sum(w), rel_tol=1e-12)


def test_a_holistic_function_is_never_merged():
    f = pivot_fn.load(MEDIAN, "Med")
    s = f.add(f.start(), pivot_fn.Batch(arrow.read(arrow.to_bytes(arrow.Table([arrow.float64("x", [3.0, 1.0, None, 2.0])]))), ["x"]))
    assert f.finish(s) == 2.0
    with pytest.raises(pivot_fn.FunctionError):
        f.merge(s, s)


def test_states_round_trip_exactly():
    s = {"a": 0.1 + 0.2, "n": 3, "inf": math.inf, "xs": [1.5, None], "t": "x"}
    back = pivot_fn.decode_state(pivot_fn.encode_state(s))
    assert back == s
    np = pytest.importorskip("numpy")
    v = pivot_fn.decode_state(pivot_fn.encode_state({"v": np.array([1.0, 2.5])}))
    assert list(v["v"]) == [1.0, 2.5]


@pytest.mark.parametrize("code,why", [
    ("import os", "may import"),
    ("import socket", "may import"),
    ("import subprocess", "may import"),
    ("import ctypes", "may import"),
    ("x = open('/etc/passwd')", "open"),
    ("x = eval('1')", "eval"),
    ("x = __import__('os')", "may import"),
])
def test_restricted_imports_and_builtins(code, why):
    with pytest.raises((ImportError, NameError, pivot_fn.FunctionError)) as e:
        pivot_fn.load(code + "\n" + WRET, "WeightedReturn")
    assert why in str(e.value)


def test_a_class_without_the_decorator_or_a_step_is_refused():
    with pytest.raises(pivot_fn.FunctionError):
        pivot_fn.load("class X:\n    pass\n", "X")
    with pytest.raises(pivot_fn.FunctionError):
        pivot_fn.load(WRET.replace("def merge", "def merged"), "WeightedReturn")


def test_the_worker_handles_frames_in_process():
    w = pivot_fn.Worker()
    h, _ = w.handle({"op": "load", "fn": "abc", "code": WRET, "entry": "WeightedReturn"}, b"")
    assert h["ok"] and h["kind"] == "mergeable"
    _, s = w.handle({"op": "start", "fn": "abc"}, b"")
    body = struct.pack(">I", len(s)) + s + batch([0.1, 0.2], [1.0, 3.0])
    h, s2 = w.handle({"op": "add", "fn": "abc", "inputs": ["r", "w"]}, body)
    assert h["ok"]
    h, _ = w.handle({"op": "finish", "fn": "abc"}, s2)
    assert math.isclose(h["value"], (0.1 + 0.6) / 4)
    h, _ = w.handle({"op": "finish", "fn": "nope"}, s2)
    assert not h["ok"] and h["code"] == "unloaded"


def _frame(header, body=b""):
    out = io.BytesIO()
    pivot_fn.write_frame(out, header, body)
    return out.getvalue()


@pytest.mark.skipif(os.name == "nt", reason="the sandboxed worker's limits are POSIX")
def test_the_worker_process_and_its_sandbox():
    sdk = os.path.dirname(os.path.dirname(os.path.abspath(pivot_fn.__file__)))
    env = {"PYTHONPATH": sdk}
    p = subprocess.Popen([sys.executable, "-I", "-c", "import sys; sys.path.insert(0, %r); from drishti_compute import pivot_fn; "
                          "sys.exit(pivot_fn.main())" % sdk], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        ready = pivot_fn.read_frame(p.stdout)
        assert ready[0]["ready"]
        p.stdin.write(_frame({"op": "load", "fn": "f1", "code": WRET, "entry": "WeightedReturn"}))
        p.stdin.write(_frame({"op": "start", "fn": "f1"}))
        p.stdin.flush()
        assert pivot_fn.read_frame(p.stdout)[0]["ok"]
        h, s = pivot_fn.read_frame(p.stdout)
        assert h["ok"]
        p.stdin.write(_frame({"op": "add", "fn": "f1", "inputs": ["r", "w"]}, struct.pack(">I", len(s)) + s + batch([1.0, 3.0], [1.0, 1.0])))
        p.stdin.flush()
        h, s = pivot_fn.read_frame(p.stdout)
        p.stdin.write(_frame({"op": "finish", "fn": "f1"}, s))
        p.stdin.write(_frame({"op": "load", "fn": "f2", "code": "import os\n" + WRET, "entry": "WeightedReturn"}))
        p.stdin.write(_frame({"op": "shutdown"}))
        p.stdin.flush()
        assert pivot_fn.read_frame(p.stdout)[0]["value"] == 2.0
        h, _ = pivot_fn.read_frame(p.stdout)
        assert not h["ok"] and "may import" in h["message"]
        p.wait(timeout=20)
    finally:
        if p.poll() is None:
            p.kill()
        p.stdout.close()
        p.stderr.close()
        p.stdin.close()
