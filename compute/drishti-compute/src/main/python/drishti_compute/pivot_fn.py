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

"""Python custom aggregation functions for the pivot engine (docs/architecture/ARCHITECTURE.md section 26.5).

A function is a class with four steps, ``start(params)``, ``add(state, batch)`` (a columnar batch of the cell's rows: the declared inputs
only, already ruled and masked), ``merge(a, b)`` and ``finish(state)``, decorated with :func:`function`::

    from drishti_pivot import function, Mergeable
    import pyarrow.compute as pc

    @function(name="wavg_return", kind=Mergeable, inputs={"r": "float64", "w": "float64"})
    class WeightedReturn:
        def start(self, params):          return {"swr": 0.0, "sw": 0.0}
        def add(self, s, batch):
            swr = pc.sum(pc.multiply(batch["r"], batch["w"])).as_py() or 0.0
            sw = pc.sum(batch["w"]).as_py() or 0.0
            return {"swr": s["swr"] + swr, "sw": s["sw"] + sw}
        def merge(self, a, b):            return {"swr": a["swr"] + b["swr"], "sw": a["sw"] + b["sw"]}
        def finish(self, s):              return None if s["sw"] == 0 else s["swr"] / s["sw"]

``batch[name]`` is a pyarrow array when pyarrow is installed (else a numpy array, else a list); ``batch.numpy(name)`` and
``batch.list(name)`` give the other forms. A state is numbers, texts, lists, dicts and numpy arrays; it is serialised by the worker
(JSON, exact for floats) and travels to the server, across nodes and into the cache.

The worker (``python -I -m drishti_compute.pivot_fn``) is one process of the server's ``pivot-fn`` pool. It reads length-prefixed frames
on standard input and writes them on its protocol channel (the original standard output): a JSON header, then a body (Arrow IPC stream
bytes for a batch, state bytes). Batches arrive in memory, never as files. The sandbox: no network, no processes, no writes at all, no
reads outside the Python installation and the SDK, imports from an allow-list only; ``exec``, ``eval``, ``compile`` and ``open`` are not
given to the function's code; memory is limited for the process and the server limits each call's time.
"""
from __future__ import annotations

import builtins
import importlib
import json
import math
import os
import struct
import sys
import types

from . import arrow

Mergeable = "mergeable"
Sketch = "sketch"
Holistic = "holistic"

#: Modules a function may import (the top-level name), besides the safe standard-library ones below.
PACKAGES = ("numpy", "pyarrow", "scipy", "pandas", "polars")
STDLIB = ("math", "statistics", "cmath", "decimal", "fractions", "functools", "itertools", "operator", "collections", "heapq",
          "bisect", "array", "random", "re", "string", "datetime", "dataclasses", "typing", "enum", "numbers", "copy", "json")

_REFUSED_BUILTINS = ("open", "exec", "eval", "compile", "input", "breakpoint", "__import__", "globals", "vars", "memoryview",
                     "help", "exit", "quit")


class FunctionError(Exception):
    """A function's own failure, said to the server in words."""


def function(name: str, kind: str = Mergeable, inputs: dict | None = None, state: dict | None = None):
    """Marks a class as a pivot function: its name, kind (``Mergeable``, ``Sketch`` or ``Holistic``), inputs and state description."""
    if kind not in (Mergeable, Sketch, Holistic):
        raise FunctionError(f"kind is Mergeable, Sketch or Holistic, not {kind!r}")

    def mark(cls):
        cls.__pivot_function__ = {"name": name, "kind": kind, "inputs": dict(inputs or {}), "state": dict(state or {})}
        return cls

    return mark


# --------------------------------------------------------------------------------------------------------------- the batch

class Batch:
    """The declared input columns of a cell's rows, by name."""

    def __init__(self, table: arrow.Table, names: list[str]):
        self._t = table
        self.names = list(names)
        self.rows = len(table)
        self._cache: dict = {}

    def __len__(self) -> int:
        return self.rows

    def list(self, name: str) -> list:
        return self._t.column(name).to_list()

    def numpy(self, name: str):
        import numpy as np  # noqa: WPS433
        col = self._t.column(name)
        values = col.to_list()
        if col.type == "float64" or col.type == "int64":
            return np.array([math.nan if v is None else float(v) for v in values], dtype="float64")
        return np.array(values, dtype=object)

    def __getitem__(self, name: str):
        if name not in self.names:
            raise KeyError(f"{name} is not an input of the function: {self.names}")
        if name not in self._cache:
            try:
                import pyarrow as pa  # noqa: WPS433
                self._cache[name] = pa.array(self.list(name))
            except ImportError:
                try:
                    self._cache[name] = self.numpy(name)
                except ImportError:
                    self._cache[name] = self.list(name)
        return self._cache[name]

    def __contains__(self, name) -> bool:
        return name in self.names


# --------------------------------------------------------------------------------------------------------------- states

def _plain(v):
    """A state as JSON values: numpy arrays and scalars become tagged lists and numbers; floats keep every bit."""
    if isinstance(v, dict):
        return {str(k): _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    if isinstance(v, (str, bool, int, float)) or v is None:
        return v
    tolist = getattr(v, "tolist", None)
    if callable(tolist) and hasattr(v, "dtype") and getattr(v, "ndim", 0) > 0:
        return {"__ndarray__": tolist(), "dtype": str(v.dtype)}
    item = getattr(v, "item", None)
    if callable(item):
        return item()
    raise FunctionError(f"a state holds numbers, texts, lists, dicts and numpy arrays, not {type(v).__name__}")


def _back(v):
    if isinstance(v, dict):
        if "__ndarray__" in v:
            import numpy as np  # noqa: WPS433
            return np.array(v["__ndarray__"], dtype=v.get("dtype", "float64"))
        return {k: _back(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_back(x) for x in v]
    return v


def encode_state(s) -> bytes:
    return json.dumps(_plain(s), sort_keys=True, separators=(",", ":"), allow_nan=True).encode("utf-8")


def decode_state(b: bytes):
    return _back(json.loads(b.decode("utf-8")))


# --------------------------------------------------------------------------------------------------------------- loading code

def _importer(allowed: set):
    real = builtins.__import__

    def guarded(name, globals=None, locals=None, fromlist=(), level=0):  # noqa: A002 - the import protocol's names
        top = name.split(".")[0]
        if level != 0 or (top not in allowed and top != "drishti_pivot"):
            raise ImportError(f"a pivot function may import {sorted(allowed)}, not {name}")
        return real(name, globals, locals, fromlist, level)

    return guarded


def load(code: str, entry: str, params: dict | None = None, allowed=PACKAGES + STDLIB):
    """The function object of ``code``'s class ``entry``, its code run with restricted built-ins."""
    safe = {k: getattr(builtins, k) for k in dir(builtins) if not k.startswith("_") and k not in _REFUSED_BUILTINS}
    safe["__build_class__"] = builtins.__build_class__
    safe["__name__"] = "pivot_function"
    safe["__import__"] = _importer(set(allowed))
    module = types.ModuleType("pivot_function")
    module.__dict__["__builtins__"] = safe
    try:
        compiled = compile(code, "<pivot function>", "exec")
    except SyntaxError as e:
        raise FunctionError(f"the code does not compile: {e.msg} (line {e.lineno})") from None
    exec(compiled, module.__dict__)  # noqa: S102 - the function's own code, sandboxed: restricted built-ins, the audit hook, limits
    cls = module.__dict__.get(entry)
    if cls is None or not hasattr(cls, "__pivot_function__"):
        raise FunctionError(f"no class {entry} decorated with @function in the code")
    obj = cls()
    meta = cls.__pivot_function__
    for step in ("start", "add", "finish") + (() if meta["kind"] == Holistic else ("merge",)):
        if not callable(getattr(obj, step, None)):
            raise FunctionError(f"{entry} has no {step}(...)")
    return Loaded(obj, meta, dict(params or {}))


class Loaded:
    """A loaded function: its object, what @function said, its parameters."""

    def __init__(self, obj, meta: dict, params: dict):
        self.obj = obj
        self.meta = meta
        self.params = params

    def start(self) -> bytes:
        return encode_state(self.obj.start(self.params))

    def add(self, state: bytes, batch: Batch) -> bytes:
        return encode_state(self.obj.add(decode_state(state), batch))

    def merge(self, a: bytes, b: bytes) -> bytes:
        if self.meta["kind"] == Holistic:
            raise FunctionError("a holistic function is never merged")
        return encode_state(self.obj.merge(decode_state(a), decode_state(b)))

    def finish(self, state: bytes):
        v = self.obj.finish(decode_state(state))
        if v is None:
            return None
        if isinstance(v, dict):
            v = v.get("value")
        try:
            f = float(v)
        except (TypeError, ValueError):
            raise FunctionError(f"finish returns a number or None, not {type(v).__name__}") from None
        return f if math.isfinite(f) else None


# --------------------------------------------------------------------------------------------------------------- the sandbox

class Sandbox:
    """The audit hook: no network, no processes, no writes at all, reads only under ``roots``."""

    def __init__(self, roots):
        self.roots = [os.path.realpath(r) for r in roots if r]
        self.refused = None

    def _refuse(self, what):
        self.refused = what
        raise PermissionError(f"the pivot function sandbox refuses {what}")

    def readable(self, path) -> bool:
        if isinstance(path, int):
            return True
        try:
            p = os.path.realpath(os.fsdecode(path))
        except (TypeError, ValueError):
            return False
        return any(p == r or p.startswith(r + os.sep) for r in self.roots)

    def hook(self, event, args):
        if event.startswith("socket.") and event != "socket.gethostname":
            self._refuse("network access")
        if event.startswith(("subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn", "os.fork", "os.forkpty",
                             "pty.spawn", "os.startfile", "ctypes.dlopen", "ctypes.cdata")):
            self._refuse("starting processes or loading native code (" + event + ")")
        if event == "open":
            path, mode, flags = (tuple(args) + (None, None, None))[:3]
            writes = (isinstance(mode, str) and any(c in mode for c in "wax+")) or (isinstance(flags, int) and flags & (
                os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_APPEND | os.O_TRUNC))
            if writes and path != os.devnull:
                self._refuse(f"writing ({path})")
            if not writes and not self.readable(path):
                self._refuse(f"reading outside the Python installation ({path})")
        if event in ("os.remove", "os.rename", "os.replace", "os.mkdir", "os.rmdir", "os.truncate", "os.chmod", "shutil.rmtree"):
            self._refuse("changing files")

    def install(self):
        sys.addaudithook(self.hook)


def roots() -> list[str]:
    """The folders a function may read: the Python installation, its packages and the SDK."""
    out = {sys.prefix, sys.base_prefix, sys.exec_prefix, os.path.dirname(os.path.dirname(os.path.abspath(__file__)))}
    for p in sys.path:
        if p and os.path.isdir(p):
            out.add(p)
    return sorted(out)


# --------------------------------------------------------------------------------------------------------------- the protocol

def _read_exact(f, n: int) -> bytes | None:
    buf = b""
    while len(buf) < n:
        chunk = f.read(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def read_frame(f):
    """A frame: a JSON header and a body (both length-prefixed, 4 bytes big-endian); None at the end."""
    h = _read_exact(f, 4)
    if h is None:
        return None
    header = json.loads(_read_exact(f, struct.unpack(">I", h)[0]).decode("utf-8"))
    n = struct.unpack(">I", _read_exact(f, 4))[0]
    body = _read_exact(f, n) if n else b""
    return header, body


def write_frame(f, header: dict, body: bytes = b"") -> None:
    h = json.dumps(header, separators=(",", ":"), allow_nan=False).encode("utf-8")
    f.write(struct.pack(">I", len(h)) + h + struct.pack(">I", len(body)) + body)
    f.flush()


def _split(body: bytes):
    n = struct.unpack(">I", body[:4])[0]
    return body[4:4 + n], body[4 + n:]


class Worker:
    """Serves frames: ``load``, ``start``, ``add``, ``merge``, ``finish``; one at a time."""

    def __init__(self, sandbox: Sandbox | None = None):
        self.loaded: dict[str, Loaded] = {}
        self.sandbox = sandbox

    def handle(self, header: dict, body: bytes):
        op = header.get("op")
        fn = header.get("fn", "")
        try:
            if op == "load":
                self.loaded[fn] = load(header["code"], header["entry"], header.get("params"))
                return {"ok": True, "kind": self.loaded[fn].meta["kind"]}, b""
            if op == "ping":
                return {"ok": True}, b""
            f = self.loaded.get(fn)
            if f is None:
                return {"ok": False, "code": "unloaded", "message": "the function is not loaded in this worker"}, b""
            if op == "start":
                return {"ok": True}, f.start()
            if op == "add":
                state, batch = _split(body)
                table = arrow.read(batch)
                return {"ok": True}, f.add(state, Batch(table, header.get("inputs") or table.names))
            if op == "merge":
                a, b = _split(body)
                return {"ok": True}, f.merge(a, b)
            if op == "finish":
                return {"ok": True, "value": f.finish(body)}, b""
            return {"ok": False, "code": "protocol", "message": f"no operation {op!r}"}, b""
        except FunctionError as e:
            return {"ok": False, "code": "function", "message": str(e)}, b""
        except MemoryError:
            return {"ok": False, "code": "limit", "message": "the function passed its memory limit"}, b""
        except Exception as e:  # noqa: BLE001 - a function's failure is said in words, its traceback to the log
            refused = self.sandbox.refused if self.sandbox else None
            if refused:
                self.sandbox.refused = None
                return {"ok": False, "code": "sandbox", "message": f"the sandbox refused {refused}"}, b""
            sys.stderr.write(f"pivot function {fn[:12]} {op} failed: {type(e).__name__}: {e}\n")
            return {"ok": False, "code": "function", "message": f"{op} failed: {type(e).__name__}: {e}"}, b""

    def serve(self, inp, out) -> int:
        write_frame(out, {"ok": True, "ready": True, "pid": os.getpid(), "python": sys.version.split()[0]})
        while True:
            frame = read_frame(inp)
            if frame is None:
                return 0
            header, body = frame
            if header.get("op") == "shutdown":
                return 0
            h, b = self.handle(header, body)
            write_frame(out, h, b)


def _alias():
    """``from drishti_pivot import function, Mergeable`` works inside a function's code."""
    sys.modules.setdefault("drishti_pivot", sys.modules[__name__])


def main(argv=None) -> int:
    import argparse  # noqa: WPS433
    p = argparse.ArgumentParser(prog="drishti_compute.pivot_fn")
    p.add_argument("--memory", type=int, default=0, help="bytes for the process (0: no limit)")
    args = p.parse_args(argv)
    _alias()
    for name in PACKAGES:  # imported before the sandbox closes, so a function's import finds them loaded
        try:
            importlib.import_module(name)
        except ImportError:
            pass
    proto_out = os.fdopen(os.dup(1), "wb")
    os.dup2(2, 1)  # print() in a function goes to standard error, never into the protocol
    sys.stdout = sys.stderr
    inp = os.fdopen(os.dup(0), "rb")
    from . import guard  # noqa: WPS433
    guard.set_limits(args.memory, 0)
    box = Sandbox(roots())
    box.install()
    return Worker(box).serve(inp, proto_out)


if __name__ == "__main__":
    sys.exit(main())
