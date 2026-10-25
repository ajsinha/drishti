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

"""A compute worker process (docs/architecture/PYTHON_COMPUTE.md section 7.2), started and watched by the server's supervisor.

Control is JSON lines: requests arrive on standard input, and progress, results and errors leave on the worker's protocol channel (the
original standard output; ``print`` in an engine goes to standard error, which the server logs, so it cannot corrupt the protocol).
Data travels as Arrow files in the request's scratch folder: inputs in ``in/``, large results written to ``out/``.

One request at a time. Two modes:

- **fork** (Linux, macOS): the engines are imported once here; each request runs in a forked child with a fresh copy of that memory, its
  own limits and the sandbox, and exits when done: nothing a request leaves behind reaches the next one.
- **warm** (Windows, where there is no fork): the request runs in this process; afterwards registered caches are cleared, the scratch
  folder released and the sandbox closed to writes. The supervisor replaces the worker after ``recycle-after`` requests or any error.

Messages out: ``ready`` (once), ``started`` (fork: the child's pid, so the supervisor can stop that request alone), ``progress``,
``result`` or ``error`` (once per request), ``done`` (the worker is free again; in fork mode with the child's exit status).
"""
from __future__ import annotations

import argparse
import gc
import importlib
import json
import math
import os
import signal
import sys
import time
import traceback

from . import __version__, arrow, engine as contract, guard

_OUT_NAME = "out"


class Channel:
    """The protocol channel: one JSON object per line, written whole."""

    def __init__(self, fd: int):
        self._f = os.fdopen(fd, "w", encoding="utf-8", buffering=1, newline="\n")

    def send(self, msg: dict) -> None:
        self._f.write(json.dumps(_clean(msg), separators=(",", ":"), allow_nan=False) + "\n")
        self._f.flush()


def _clean(v):
    """JSON values only: NaN and infinities become null, numpy scalars and memoryviews become numbers and lists."""
    if isinstance(v, float):
        return v if math.isfinite(v) else None
    if isinstance(v, dict):
        return {str(k): _clean(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_clean(x) for x in v]
    if isinstance(v, (str, int, bool)) or v is None:
        return v
    if isinstance(v, memoryview):
        return [_clean(x) for x in v.tolist()]
    item = getattr(v, "item", None)  # numpy scalar, without importing numpy
    if callable(item):
        try:
            return _clean(item())
        except (TypeError, ValueError):
            pass
    tolist = getattr(v, "tolist", None)
    if callable(tolist):
        return _clean(tolist())
    return str(v)


def load_engines(entries: list[str]) -> dict:
    engines = {}
    for entry in entries:
        module, _, cls = entry.partition(":")
        obj = getattr(importlib.import_module(module), cls)
        engines[entry] = obj() if isinstance(obj, type) else obj
    return engines


class Worker:
    def __init__(self, args, channel: Channel):
        self.args = args
        self.ch = channel
        self.engines = load_engines(args.engines.split(","))
        self.first = args.engines.split(",")[0]
        self.sandbox = guard.Sandbox()
        self.home = os.getcwd()

    # ------------------------------------------------------------------------------------------------------- one request
    def execute(self, msg: dict) -> None:
        """Runs one request and sends its result or error (in the fork child, or in this process when warm)."""
        rid = msg["id"]
        scratch = msg["scratch"]
        req = contract.Request(id=rid, kind=msg.get("kind", ""), params=msg.get("params") or {}, user=msg.get("user", ""),
                               roles=tuple(msg.get("roles") or ()), view_signature=msg.get("viewSignature", ""),
                               cls=msg.get("cls", "interactive"), seed=int(msg.get("seed") or 0), scratch=scratch,
                               chunk=msg.get("chunk"))
        inputs = contract.Inputs(scratch, msg.get("inputs") or {})
        progress = contract.Progress(lambda d: self.ch.send({"type": "progress", "id": rid, "data": d}))
        self.sandbox.refused = None
        try:
            entry = msg.get("engine") or self.first
            eng = self.engines.get(entry)
            if eng is None:
                raise contract.ComputeError(f"this pool has no engine {entry}")
            kinds = tuple(getattr(eng, "kinds", ()) or ())
            if kinds and req.kind not in kinds:
                raise contract.ComputeError(f"the engine computes {', '.join(kinds)}, not '{req.kind}'")
            result = eng.compute(req, inputs, progress)
            self.ch.send(self._answer(rid, scratch, result))
        except contract.ComputeError as e:
            self.ch.send({"type": "error", "id": rid, "code": "engine", "message": str(e)})
        except MemoryError:
            self.ch.send({"type": "error", "id": rid, "code": "limit", "message": "the computation passed its memory limit"})
        except Exception as e:  # noqa: BLE001 - every other failure is "failed", its traceback to the log only
            if self.sandbox.refused is not None:
                self.ch.send({"type": "error", "id": rid, "code": "limit", "message": f"the compute sandbox refused {self.sandbox.refused}"})
            else:
                sys.stderr.write(f"drishti-compute {rid}: the engine failed\n{traceback.format_exc()}")
                sys.stderr.flush()
                self.ch.send({"type": "error", "id": rid, "code": "failed", "message": f"the computation failed ({type(e).__name__})"})
        finally:
            inputs.close()

    def _answer(self, rid: str, scratch: str, result) -> dict:
        if isinstance(result, dict):
            result = contract.Result(**result)
        elif isinstance(result, list):
            result = contract.Result(rows=result)
        elif not isinstance(result, contract.Result):
            raise TypeError("an engine returns a Result")
        tables = {}
        if result.tables:
            out = os.path.join(scratch, _OUT_NAME)
            os.makedirs(out, exist_ok=True)
            for name, t in result.tables.items():
                if not isinstance(name, str) or not name.replace("-", "").replace("_", "").replace(".", "").isalnum():
                    raise contract.ComputeError(f"a result table's name is letters, digits, dots, hyphens and underscores, not '{name}'")
                rel = f"{_OUT_NAME}/{name}.arrows"
                path = os.path.join(scratch, _OUT_NAME, name + ".arrows")
                if isinstance(t, arrow.Table):
                    arrow.write_file(t, path)
                else:  # a pyarrow table
                    import pyarrow as pa  # noqa: WPS433
                    with pa.OSFile(path, "wb") as sink, pa.ipc.new_stream(sink, t.schema) as w:
                        w.write_table(t)
                tables[name] = rel
        meta = dict(result.meta or {})
        return {"type": "result", "id": rid, "rows": result.rows, "tables": tables, "meta": meta}

    # ------------------------------------------------------------------------------------------------------- the two modes
    def run_forked(self, msg: dict) -> None:
        rid = msg["id"]
        go_r, go_w = os.pipe()
        pid = os.fork()
        if pid == 0:
            code = 0
            try:
                os.close(go_w)
                os.read(go_r, 1)  # wait until the parent has said "started": lines never interleave
                os.close(go_r)
                signal.signal(signal.SIGTERM, signal.SIG_DFL)
                os.chdir(msg["scratch"])
                guard.set_limits(self.args.memory, int(msg.get("cpuSeconds") or self.args.cpu_seconds))
                self.sandbox.scratch = msg["scratch"]
                self.sandbox.install()
                self.execute(msg)
            except BaseException:  # noqa: BLE001 - the child must never return into the parent's loop
                code = 70
                try:
                    sys.stderr.write(traceback.format_exc())
                    sys.stderr.flush()
                except BaseException:  # noqa: BLE001
                    pass
            finally:
                os._exit(code)
        os.close(go_r)
        self.ch.send({"type": "started", "id": rid, "pid": pid})
        os.write(go_w, b"g")
        os.close(go_w)
        _, status = os.waitpid(pid, 0)
        done = {"type": "done", "id": rid}
        if os.WIFSIGNALED(status):
            done["signal"] = os.WTERMSIG(status)
            sig = done["signal"]
            done["signalName"] = signal.Signals(sig).name if sig in signal.Signals._value2member_map_ else str(sig)
        else:
            done["exit"] = os.WEXITSTATUS(status)
        self.ch.send(done)

    def run_warm(self, msg: dict) -> None:
        rid = msg["id"]
        self.ch.send({"type": "started", "id": rid, "pid": os.getpid()})
        try:
            os.chdir(msg["scratch"])
            self.sandbox.scratch = msg["scratch"]
            self.execute(msg)
        finally:
            self.sandbox.scratch = None
            os.chdir(self.home)
            contract.clear_caches()
            gc.collect()
        self.ch.send({"type": "done", "id": rid, "exit": 0})

    def serve(self) -> int:
        warm = self.args.mode == "warm"
        if warm:
            guard.start_watchdog(self.args.memory)
            self.sandbox.install()  # after the engines' imports, before any request
        self.ch.send({"type": "ready", "pid": os.getpid(), "mode": self.args.mode, "python": sys.version.split()[0], "sdk": __version__,
                      "engines": {k: list(getattr(v, "kinds", ()) or ()) for k, v in self.engines.items()},
                      "versions": {k: str(getattr(v, "version", "")) for k, v in self.engines.items()}})
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                sys.stderr.write("drishti-compute: a control line that is not JSON was ignored\n")
                continue
            t = msg.get("type")
            if t == "shutdown":
                return 0
            if t == "run":
                (self.run_warm if warm else self.run_forked)(msg)
        return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="drishti_compute.worker")
    p.add_argument("--engines", required=True, help="module:Class[,module:Class...]")
    p.add_argument("--mode", choices=("fork", "warm"), default="warm" if os.name == "nt" else "fork")
    p.add_argument("--memory", type=int, default=0, help="bytes per request (0: no limit)")
    p.add_argument("--cpu-seconds", type=int, default=0)
    p.add_argument("--worker", default="worker")
    args = p.parse_args(argv)
    if args.mode == "fork" and not hasattr(os, "fork"):
        args.mode = "warm"
    proto = os.dup(1)
    os.dup2(2, 1)  # print() in an engine goes to standard error, never into the protocol
    sys.stdout = sys.stderr
    ch = Channel(proto)
    started = time.monotonic()
    try:
        worker = Worker(args, ch)
    except BaseException as e:  # noqa: BLE001 - say why, then stop
        sys.stderr.write(traceback.format_exc())
        ch.send({"type": "fatal", "message": f"the engines could not be loaded: {type(e).__name__}: {e}"})
        return 3
    sys.stderr.write(f"drishti-compute {args.worker}: ready in {time.monotonic() - started:.3f}s ({args.mode})\n")
    return worker.serve()


if __name__ == "__main__":
    sys.exit(main())
