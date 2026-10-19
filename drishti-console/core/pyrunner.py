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

"""The Python runner of BI, the console side (docs/guides/PYTHON_BI.md, RUPAKA.md section 13): runs a Python Measure or Visual on the rows a
person may see, in a short-lived worker process (``core/pyrunner_worker.py``) that has no network, a scratch folder only, and CPU, memory,
time and output limits. A pool (``bi.python.workers``) bounds how many run at once; a call that waits longer than ``bi.python.queue_seconds``
for a place is refused. The runner never sees a credential and never asks the server for anything: its input is the Arrow stream the server
built for this reader (masked and row-filtered), its output a number per group or a chart.

Wave B (docs/architecture/BI_DESIGN.md section 3.5): the worker processes come from a **fork-server** (``ForkServer``: one parent per console that has
imported pandas, numpy and pyarrow and forks one child per call, so a call no longer pays for an interpreter's start), answers are kept in the **L4
result cache** (``ResultCache``: by the item's code, its parameters and the exact input bytes, which are already masked for the reader), and the
interfaces are ``measure`` (``compute`` per group, or ``compute_groups`` over every group at once), ``visual``, ``transform``, ``column``
(``derive(frame, params)``, one value per row) and ``cell``.

Failures are DRS codes: 9070 (the item is not valid), 9071 (the code failed: the traceback is for authors), 9072 (a limit), 9073 (the sandbox
refused something), 9074 (no free worker, or the runner is off or not installed).
"""
from __future__ import annotations

import asyncio
import importlib.util
import json
import os
import shutil
import signal
import sys
from core import tmpdir
import time
from pathlib import Path

WORKER = Path(__file__).with_name("pyrunner_worker.py")
ENTRIES = {"measure": "compute", "transform": "run", "column": "derive", "visual": "render", "cell": "", "steps": "run", "profile": ""}
CODES = {"failed": ("DRS-9071", 422), "sandbox": ("DRS-9073", 403), "limit": ("DRS-9072", 413), "invalid": ("DRS-9070", 422)}
DEFAULT_PACKAGES = ["pandas", "numpy", "pyarrow", "scipy", "statsmodels"]


def _external_paths() -> list[str]:
    """The external library folder's Python paths (core/external_lib.py); the worker's import guard still applies the allow-list."""
    from core import external_lib

    return external_lib.paths()


class PyError(Exception):
    """A refusal or failure of a Python call, with the DRS code to answer with. ``traceback`` is for authors only."""

    def __init__(self, code: str, status: int, detail: str, traceback: str = ""):
        super().__init__(detail)
        self.code = code
        self.status = status
        self.detail = detail
        self.traceback = traceback


def check_source(m: dict, allowed: list[str]) -> list[dict]:
    """Static checks of a manifest's source that the server (which has no Python) cannot make: it parses, defines its entry function, and imports only
    what the manifest lists (and the console allows) or the safe standard library. Returns problems as ``{level, code, path, message}``."""
    import ast
    from core.pyrunner_worker import SAFE_STDLIB
    out = []

    def bad(path, message, level="error"):
        out.append({"level": level, "code": "DRS-9070", "path": path, "message": message})

    for p in m.get("packages") or []:
        if p not in allowed:
            bad("packages", f"'{p}' is not on the console's allow-list (bi.python.packages: {', '.join(allowed)})")
        elif importlib.util.find_spec(p) is None:
            bad("packages", f"'{p}' is allowed but not installed on this console, so the server target cannot run it", "warning")
    try:
        tree = ast.parse(m.get("source") or "", "<python-item>")
    except SyntaxError as e:
        bad("source", f"line {e.lineno}: {e.msg}")
        return out
    entry = m.get("entry") or ENTRIES.get(m.get("interface") or "", "render")
    has_function = any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name in ({entry, "compute_groups"} if m.get("interface") == "measure" else {entry})
                       for n in tree.body)
    has_method = m.get("interface") == "transform" and any(
        isinstance(n, ast.ClassDef) and n.name == "Transform" and any(isinstance(f, ast.FunctionDef) and f.name == entry for f in n.body) for n in tree.body)
    if not has_function and not has_method:
        bad("source", f"no top-level function '{entry}'" + (" (nor compute_groups)" if m.get("interface") == "measure" else "") + (" (nor a class Transform with a method of that name)" if m.get("interface") == "transform" else ""))
    ok = set(m.get("packages") or []) | SAFE_STDLIB
    for n in ast.walk(tree):
        names = [a.name for a in n.names] if isinstance(n, ast.Import) else [n.module or ""] if isinstance(n, ast.ImportFrom) and n.level == 0 else []
        for name in names:
            if name.split(".")[0] not in ok:
                bad("source", f"line {n.lineno}: import of '{name.split('.')[0]}' is not allowed (list it in packages, or it is not available to Python items)")
        if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load) and n.id in {"exec", "eval", "compile", "__import__"}:
            bad("source", f"line {n.lineno}: {n.id} is not available to Python items")
    return out


class ResultCache:
    """The L4 result cache (BI_DESIGN.md section 6.1): a Python node's answer by (the item's code and settings, its parameters, the groups asked and a hash
    of the exact input bytes). The inputs are the reader's masked rows, so two readers share an entry only when they handed Python the same bytes; an
    item's new version is new code, so a new key. In memory, least recently used out first, each entry kept ``seconds``; disposable."""

    def __init__(self, entries: int = 256, max_bytes: int = 64_000_000, seconds: float = 600.0):
        from collections import OrderedDict
        self.entries, self.max_bytes, self.seconds = max(0, int(entries)), int(max_bytes), float(seconds)
        self._data: "OrderedDict[str, tuple[float, int, str, bytes | None]]" = OrderedDict()
        self.bytes = 0
        self.hits = 0
        self.misses = 0

    @staticmethod
    def key(m: dict, params: dict, group_by, blobs: list[bytes]) -> str:
        import hashlib
        h = hashlib.sha256()
        what = {k: m.get(k) for k in ("name", "version", "interface", "entry", "source", "inputs", "packages", "output", "timeout", "memory")}
        h.update(json.dumps([what, params, group_by], sort_keys=True, default=str).encode())
        for b in blobs:
            h.update(len(b).to_bytes(8, "big"))
            h.update(hashlib.sha256(b).digest())
        return h.hexdigest()

    def get(self, key: str) -> tuple[dict, bytes | None] | None:
        hit = self._data.get(key)
        if hit is None or time.monotonic() - hit[0] > self.seconds:
            if hit is not None:
                self._drop(key)
            self.misses += 1
            return None
        self._data.move_to_end(key)
        self.hits += 1
        return json.loads(hit[2]), hit[3]

    def put(self, key: str, answer: dict, blob: bytes | None = None) -> None:
        if not self.entries:
            return
        text = json.dumps(answer)
        size = len(text) + (len(blob) if blob else 0)
        if size > self.max_bytes // 4:
            return                                                   # one answer may not take most of the cache
        self._drop(key)
        self._data[key] = (time.monotonic(), size, text, blob)
        self.bytes += size
        while self._data and (len(self._data) > self.entries or self.bytes > self.max_bytes):
            self._drop(next(iter(self._data)))

    def _drop(self, key: str) -> None:
        old = self._data.pop(key, None)
        if old is not None:
            self.bytes -= old[1]

    def status(self) -> dict:
        return {"entries": len(self._data), "bytes": self.bytes, "hits": self.hits, "misses": self.misses, "max": self.entries}


class ForkServer:
    """The fork-server (BI_DESIGN.md 3.5 invariant 3): one ``pyrunner_worker.py --serve`` parent, started on the first call (in a network namespace when
    the runner uses one), that forks a child per call. ``run(tmp, env, timeout)`` answers the child's exit code, as a process's would be (negative: the
    signal); a child past its time is killed by its process group. The parent ends when the console closes its standard input."""

    def __init__(self, cmd: list[str], env: dict):
        self.cmd, self.env = cmd, env
        self.proc = None
        self.loop = None
        self._next = 0
        self._wait: dict[int, dict] = {}
        self._reader = None
        self.started = 0
        self.forks = 0

    def alive(self) -> bool:
        return self.proc is not None and self.proc.returncode is None and self.loop is asyncio.get_running_loop()

    async def start(self) -> None:
        if self.alive():
            return
        await self.close()
        self.loop = asyncio.get_running_loop()
        self.proc = await asyncio.create_subprocess_exec(*self.cmd, env=self.env, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                                                         stderr=asyncio.subprocess.DEVNULL, start_new_session=True, limit=1 << 20)
        line = await asyncio.wait_for(self.proc.stdout.readline(), 30)
        if not line or not json.loads(line).get("ready"):
            raise OSError("the Python fork-server did not start")
        self.started += 1
        self._reader = asyncio.ensure_future(self._read())

    async def _read(self) -> None:
        proc = self.proc
        while True:
            line = await proc.stdout.readline()
            if not line:
                break
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            w = self._wait.get(msg.get("id"))
            if w is None:
                continue
            if "pid" in msg:
                w["pid"] = msg["pid"]
            if "rc" in msg:
                self._wait.pop(msg["id"], None)
                if not w["done"].done():
                    w["done"].set_result(int(msg["rc"]))
        for w in list(self._wait.values()):                        # the parent died: every call in flight fails as a dead worker
            if not w["done"].done():
                w["done"].set_result(-signal.SIGKILL)
        self._wait.clear()

    async def run(self, tmp: str, timeout: float) -> int:
        await self.start()
        self._next += 1
        rid = self._next
        w = {"pid": None, "done": self.loop.create_future()}
        self._wait[rid] = w
        self.proc.stdin.write((json.dumps({"id": rid, "tmp": tmp, "env": {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}}) + "\n").encode())
        await self.proc.stdin.drain()
        self.forks += 1
        try:
            return await asyncio.wait_for(asyncio.shield(w["done"]), timeout)
        except asyncio.TimeoutError:
            if w["pid"]:
                try:
                    os.killpg(w["pid"], signal.SIGKILL)
                except (ProcessLookupError, PermissionError, OSError):
                    pass
            try:
                await asyncio.wait_for(asyncio.shield(w["done"]), 3)
            except asyncio.TimeoutError:
                pass
            raise

    async def close(self) -> None:
        proc, self.proc = self.proc, None
        if proc is not None and proc.returncode is None:
            try:
                proc.stdin.close()
            except Exception:  # noqa: BLE001 - already closed
                pass
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError):
                pass


class PyRunner:
    def __init__(self, settings):
        g = lambda k, d: settings.get("bi.python." + k, d)  # noqa: E731
        self.enabled = bool(g("enabled", True))
        self.workers = max(1, int(g("workers", 2)))
        self.queue_seconds = float(g("queue_seconds", 5))
        packages = g("packages", DEFAULT_PACKAGES)
        self.packages = [str(p) for p in (packages if isinstance(packages, list) else str(packages).split(","))]
        self.max_timeout = int(g("max_timeout_seconds", 60))
        self.max_memory = int(g("max_memory_mb", 1024))
        self.max_input = int(g("max_input_bytes", 16_000_000))
        self.max_output = int(g("max_output_bytes", 1_000_000))
        self.max_groups = int(g("max_groups", 5000))
        self.netns = str(g("network_namespace", "auto")).lower()          # auto: use one when the machine allows; require; off
        self.scratch = str(g("scratch_dir", "")) or None
        self.use_fork = str(g("fork_server", True)).lower() not in ("false", "0", "no", "off")
        self.warm_on_start = str(g("warm", True)).lower() not in ("false", "0", "no", "off")
        self.warmed_ms: int | None = None
        self.cache = ResultCache(int(g("cache_entries", 256)), int(g("cache_max_bytes", 64_000_000)), float(g("cache_seconds", 600)))
        self.runner_token = str(g("runner_token", "") or "")
        self._fork: ForkServer | None = None
        self._fork_failed = ""
        self._sem: asyncio.Semaphore | None = None
        self._unshare: bool | None = None
        self.running = 0
        self.calls = 0
        self.failures = 0

    # ---- what is available -------------------------------------------------------------------------------------------------
    def installed(self) -> list[str]:
        return [p for p in self.packages if importlib.util.find_spec(p) is not None]

    def status(self) -> dict:
        need = [m for m in ("pandas", "pyarrow") if importlib.util.find_spec(m) is None]
        return {"enabled": self.enabled, "ready": self.enabled and not need, "missing": need, "workers": self.workers, "running": self.running,
                "packages": self.packages, "installed": self.installed(), "calls": self.calls, "failures": self.failures,
                "isolation": "network namespace + audit hook" if self._netns_ok() else "audit hook",
                "forkServer": {"on": self.use_fork, "running": bool(self._fork and self._fork.proc and self._fork.proc.returncode is None),
                               "forks": self._fork.forks if self._fork else 0, "failed": self._fork_failed, "warm": self.warm_on_start, "warmedMs": self.warmed_ms},
                "cache": self.cache.status(), "pyNode": bool(self.runner_token)}

    def _netns_ok(self) -> bool:
        if self.netns == "off" or not shutil.which("unshare"):
            return False
        if self._unshare is None:
            import subprocess
            try:
                self._unshare = subprocess.run(["unshare", "--user", "--map-root-user", "--net", "true"], capture_output=True, timeout=5).returncode == 0
            except (OSError, subprocess.SubprocessError):
                self._unshare = False
        return self._unshare

    # ---- a call ------------------------------------------------------------------------------------------------------------
    async def run(self, item: dict, arrow: bytes, *, group_by: list[str] | None = None, params: dict | None = None, extracts: dict[str, bytes] | None = None) -> dict:
        """Runs the item described by the server (``manifest`` with its source) on ``arrow`` (Arrow IPC stream bytes). Returns the worker's answer
        (``kind`` measure, echarts, svg or png) plus ``ms``; raises :class:`PyError`."""
        m = item["manifest"]
        self._check(m, arrow + b"".join({id(b): b for b in (extracts or {}).values()}.values()))
        merged = {**(m.get("params") or {}), **(params or {})}
        key = None
        if m.get("interface") != "cell":                         # a cell prints and may read quant: never cached
            key = ResultCache.key(m, merged, group_by, [arrow] + [extracts[k] for k in sorted(extracts or {})])
            hit = self.cache.get(key)
            if hit is not None:
                return {**hit[0], "cached": True}
        if self._sem is None:
            self._sem = asyncio.Semaphore(self.workers)
        try:
            await asyncio.wait_for(self._sem.acquire(), self.queue_seconds)
        except asyncio.TimeoutError:
            raise PyError("DRS-9074", 503, f"all {self.workers} Python workers are busy; try again in a moment") from None
        self.running += 1
        self.calls += 1
        try:
            answer = await self._spawn(m, arrow, group_by, merged, extracts=extracts)
            if key:
                self.cache.put(key, answer)
            return answer
        except PyError:
            self.failures += 1
            raise
        finally:
            self.running -= 1
            self._sem.release()

    async def run_transform(self, m: dict, arrows: list[bytes], *, params: dict | None = None, dest: str, max_rows: int = 100_000) -> dict:
        """Runs a Transform (``interface: transform``) on frames (Arrow IPC streams, one per input, already masked for the reader) and leaves the frame it returns,
        as Parquet, at ``dest``. Answers ``{columns: [{name, type}], rows, ms}``; raises :class:`PyError`."""
        self._check(m, b"".join(arrows))
        merged = {**(m.get("params") or {}), **(params or {})}
        key = ResultCache.key(m, {**merged, "__max_rows": max_rows}, None, list(arrows))
        hit = self.cache.get(key)
        if hit is not None and hit[1] is not None:
            Path(dest).write_bytes(hit[1])
            return {**hit[0], "cached": True}
        if self._sem is None:
            self._sem = asyncio.Semaphore(self.workers)
        try:
            await asyncio.wait_for(self._sem.acquire(), self.queue_seconds)
        except asyncio.TimeoutError:
            raise PyError("DRS-9074", 503, f"all {self.workers} Python workers are busy; try again in a moment") from None
        self.running += 1
        self.calls += 1
        try:
            answer = await self._spawn(m, arrows, None, merged, dest=dest, max_rows=max_rows)
            self.cache.put(key, answer, Path(dest).read_bytes())
            return answer
        except PyError:
            self.failures += 1
            raise
        finally:
            self.running -= 1
            self._sem.release()

    async def run_steps(self, m: dict, arrows: list[bytes], names: list[str], *, params: dict | None = None) -> dict:
        """The step-by-step run of a Transform (wave C, BI_PARITY_PLAN.md P1): ``run`` on frames (Arrow IPC streams, one per input, already masked for the
        reader), with the frame each step made described (rows, columns, first rows, profile) and the inputs profiled. Not kept in the result cache: an
        author runs it as they edit. Answers ``{kind: steps, inputs, keys, steps, planned, complete, failed?, ms}``; raises :class:`PyError` for what is not the
        code's own failure (a step's error is in ``failed``)."""
        self._check(m, b"".join(arrows))
        merged = {**(m.get("params") or {}), **(params or {})}
        if self._sem is None:
            self._sem = asyncio.Semaphore(self.workers)
        try:
            await asyncio.wait_for(self._sem.acquire(), self.queue_seconds)
        except asyncio.TimeoutError:
            raise PyError("DRS-9074", 503, f"all {self.workers} Python workers are busy; try again in a moment") from None
        self.running += 1
        self.calls += 1
        try:
            return await self._spawn({**m, "interface": "steps"}, list(arrows), None, merged, input_names=list(names))
        except PyError:
            self.failures += 1
            raise
        finally:
            self.running -= 1
            self._sem.release()

    async def profile(self, arrow: bytes) -> dict:
        """The profile of one frame (P3: nulls, distinct values, a key hint, min and max, top values, a histogram), worked out in the sandbox; no author code."""
        m = {"name": "profile", "interface": "profile", "source": "", "packages": [], "inputs": [], "timeout": 20, "memory": 512}
        return await self.run({"manifest": m}, arrow)

    def _check(self, m: dict, arrow: bytes) -> None:
        if not self.enabled:
            raise PyError("DRS-9074", 503, "the Python runner is switched off (bi.python.enabled)")
        need = [x for x in ("pandas", "pyarrow") if importlib.util.find_spec(x) is None]
        if need:
            raise PyError("DRS-9074", 503, f"the Python runner needs {', '.join(need)} in the console's environment (pip install -r drishti-console/requirements.txt)")
        if self.netns == "require" and not self._netns_ok():
            raise PyError("DRS-9074", 503, "bi.python.network_namespace is 'require' but this machine cannot create a network namespace (unshare --user --net)")
        for p in m.get("packages") or []:
            if p not in self.packages:
                raise PyError("DRS-9073", 403, f"package '{p}' is not on the allow-list (bi.python.packages: {', '.join(self.packages)})")
            if importlib.util.find_spec(p) is None:
                raise PyError("DRS-9074", 503, f"package '{p}' is allowed but not installed on the console")
        if len(arrow) > self.max_input:
            raise PyError("DRS-9072", 413, f"the rows handed to Python are {len(arrow):,} bytes; the limit is {self.max_input:,} (bi.python.max_input_bytes)")

    async def _spawn(self, m: dict, arrow, group_by, params: dict, *, dest: str | None = None, max_rows: int = 0, extracts: dict[str, bytes] | None = None,
                     input_names: list[str] | None = None) -> dict:
        timeout = min(int(m.get("timeout") or 10), self.max_timeout)
        memory = min(int(m.get("memory") or 256), self.max_memory)
        tmp = tmpdir.mkdtemp("drishti-py-", dir=self.scratch)
        t0 = time.monotonic()
        proc = None
        try:
            files: list[str] = []
            if isinstance(arrow, list):                                  # a transform: one stream per input dataset
                for i, blob in enumerate(arrow):
                    (Path(tmp) / f"in_{i}.arrow").write_bytes(blob)
                    files.append(f"in_{i}.arrow")
            else:
                (Path(tmp) / "in.arrow").write_bytes(arrow)
            more: dict[str, str] = {}                                    # a notebook cell's other extracts: name -> file (two names of one extract share it)
            written: dict[int, str] = {}
            for ename, blob in (extracts or {}).items():
                if id(blob) not in written:
                    written[id(blob)] = f"ex_{len(written)}.arrow"
                    (Path(tmp) / written[id(blob)]).write_bytes(blob)
                more[ename] = written[id(blob)]
            req = {"extracts": more, "interface": m["interface"], "entry": m.get("entry") or ENTRIES.get(m["interface"], "render"),
                   "source": m["source"], "inputs_files": files, "max_rows": max_rows, "input_names": list(input_names or []),
                   "inputs": [] if m["interface"] in ("transform", "steps") else list(m.get("inputs") or []), "group_by": group_by, "params": params, "packages": list(m.get("packages") or []),
                   "output": m.get("output") or "echarts", "quant_root": m.get("quant_root"), "quant_engine": m.get("quant_engine"),
                   "table_rows": m.get("table_rows"), "extra_paths": _external_paths(), "timeout": timeout, "memory": memory, "max_output_bytes": self.max_output, "max_groups": self.max_groups}
            (Path(tmp) / "req.json").write_text(json.dumps(req), encoding="utf-8")
            rc = await self._forked(tmp, timeout, m) if self.use_fork else None
            if rc is not None:
                out = Path(tmp) / "out.json"
                if not out.exists():
                    raise self._died(rc, Path(tmp) / "err.txt", m, timeout, memory)
                return self._collect(tmp, m, t0, dest)
            cmd = [sys.executable, "-I", str(WORKER), tmp]
            if self._netns_ok():
                cmd = ["unshare", "--user", "--map-root-user", "--net", "--"] + cmd
            env = {**self._env(), "HOME": tmp, "TMPDIR": tmp, "MPLCONFIGDIR": tmp}
            with open(Path(tmp) / "err.txt", "wb") as err:
                proc = await asyncio.create_subprocess_exec(*cmd, cwd=tmp, env=env, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=err,
                                                            start_new_session=True)
                try:
                    await asyncio.wait_for(proc.wait(), timeout + 3)
                except asyncio.TimeoutError:
                    self._kill(proc)
                    raise PyError("DRS-9072", 504, f"'{m.get('name')}' ran longer than its {timeout} s limit and was stopped") from None
            out = Path(tmp) / "out.json"
            if not out.exists():
                raise self._died(proc.returncode, Path(tmp) / "err.txt", m, timeout, memory)
            return self._collect(tmp, m, t0, dest)
        finally:
            if proc is not None and proc.returncode is None:
                self._kill(proc)
            shutil.rmtree(tmp, ignore_errors=True)

    @staticmethod
    def _env() -> dict:
        return {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "OMP_NUM_THREADS": "1", "OPENBLAS_NUM_THREADS": "1", "MKL_NUM_THREADS": "1",
                "ARROW_DEFAULT_MEMORY_POOL": "system", "PYTHONDONTWRITEBYTECODE": "1"}

    async def _forked(self, tmp: str, timeout: int, m: dict) -> int | None:
        """Runs the call in a child of the fork-server; None when the fork-server cannot run here (the call is then a process of its own)."""
        if self._fork_failed:
            return None
        try:
            if self._fork is None:
                cmd = [sys.executable, "-I", str(WORKER), "--serve"]
                if self._netns_ok():
                    cmd = ["unshare", "--user", "--map-root-user", "--net", "--"] + cmd
                self._fork = ForkServer(cmd, self._env())
            await self._fork.start()
        except (OSError, ValueError, asyncio.TimeoutError) as e:
            self._fork_failed = f"{type(e).__name__}: {e}"[:200]
            return None
        try:
            return await self._fork.run(tmp, timeout + 3)
        except asyncio.TimeoutError:
            raise PyError("DRS-9072", 504, f"'{m.get('name')}' ran longer than its {timeout} s limit and was stopped") from None

    async def warm(self) -> bool:
        """Starts the fork-server now (wave C, BI_PARITY_PLAN.md P6: warm workers), so the first call of the day (a report, a Transform's step run) does not pay
        for the interpreter and the pandas, numpy and pyarrow imports. Answers whether it is running; the console starts it at start-up (``bi.python.warm``)."""
        if not (self.enabled and self.use_fork) or self._fork_failed or any(importlib.util.find_spec(x) is None for x in ("pandas", "pyarrow")):
            return False
        t0 = time.monotonic()
        try:
            if self._fork is None:
                cmd = [sys.executable, "-I", str(WORKER), "--serve"]
                if self._netns_ok():
                    cmd = ["unshare", "--user", "--map-root-user", "--net", "--"] + cmd
                self._fork = ForkServer(cmd, self._env())
            await self._fork.start()
        except (OSError, ValueError, asyncio.TimeoutError) as e:
            self._fork_failed = f"{type(e).__name__}: {e}"[:200]
            return False
        self.warmed_ms = round((time.monotonic() - t0) * 1000)
        return True

    async def close(self) -> None:
        if self._fork is not None:
            await self._fork.close()

    def _collect(self, tmp: str, m: dict, t0: float, dest: str | None) -> dict:
        """The worker's answer from its scratch folder (both ways of running end here)."""
        out = Path(tmp) / "out.json"
        if out.stat().st_size > self.max_output + 1024:
            raise PyError("DRS-9072", 413, f"the result is over {self.max_output:,} bytes (bi.python.max_output_bytes)")
        answer = json.loads(out.read_text(encoding="utf-8"))
        if not answer.get("ok"):
            code, status = CODES.get(answer.get("kind"), CODES["failed"])
            if answer.get("kind") == "limit" and "time" in answer.get("error", "").lower():
                status = 504
            raise PyError(code, status, answer.get("error") or "the Python item failed", answer.get("traceback") or "")
        answer.pop("ok", None)
        answer["ms"] = round((time.monotonic() - t0) * 1000)
        if answer.get("kind") == "transform":
            produced = Path(tmp) / "out.parquet"
            if not produced.exists():
                raise PyError("DRS-9071", 422, "the Transform returned no frame")
            if produced.stat().st_size > self.max_output:
                raise PyError("DRS-9072", 413, f"the frame is {produced.stat().st_size:,} bytes; the limit is {self.max_output:,} (drishti.bi.transform.max-bytes)")
            shutil.move(str(produced), dest)
        return answer

    @staticmethod
    def _kill(proc) -> None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError, OSError):
            pass

    @staticmethod
    def _died(rc, err: Path, m: dict, timeout: int, memory: int) -> PyError:
        tail = ""
        try:
            tail = err.read_text(encoding="utf-8", errors="replace")[-1500:]
        except OSError:
            pass
        if rc == -signal.SIGXCPU or rc == -signal.SIGKILL:
            return PyError("DRS-9072", 504, f"'{m.get('name')}' used more than its {timeout} s of CPU time and was stopped", tail)
        if rc in (-signal.SIGSEGV, -signal.SIGABRT, -signal.SIGBUS):
            return PyError("DRS-9072", 413, f"'{m.get('name')}' ran out of its {memory} MB of memory and was stopped", tail)
        if rc == -signal.SIGXFSZ:
            return PyError("DRS-9072", 413, f"'{m.get('name')}' wrote more than its file-size limit and was stopped", tail)
        return PyError("DRS-9071", 422, f"the Python worker ended without an answer (exit {rc})", tail)
