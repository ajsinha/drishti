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
import tempfile
import time
from pathlib import Path

WORKER = Path(__file__).with_name("pyrunner_worker.py")
CODES = {"failed": ("DRS-9071", 422), "sandbox": ("DRS-9073", 403), "limit": ("DRS-9072", 413), "invalid": ("DRS-9070", 422)}
DEFAULT_PACKAGES = ["pandas", "numpy", "pyarrow", "scipy", "statsmodels"]


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
    entry = m.get("entry") or ("compute" if m.get("interface") == "measure" else "render")
    if not any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == entry for n in tree.body):
        bad("source", f"no top-level function '{entry}'")
    ok = set(m.get("packages") or []) | SAFE_STDLIB
    for n in ast.walk(tree):
        names = [a.name for a in n.names] if isinstance(n, ast.Import) else [n.module or ""] if isinstance(n, ast.ImportFrom) and n.level == 0 else []
        for name in names:
            if name.split(".")[0] not in ok:
                bad("source", f"line {n.lineno}: import of '{name.split('.')[0]}' is not allowed (list it in packages, or it is not available to Python items)")
        if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load) and n.id in {"exec", "eval", "compile", "__import__"}:
            bad("source", f"line {n.lineno}: {n.id} is not available to Python items")
    return out


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
                "isolation": "network namespace + audit hook" if self._netns_ok() else "audit hook"}

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
    async def run(self, item: dict, arrow: bytes, *, group_by: list[str] | None = None, params: dict | None = None) -> dict:
        """Runs the item described by the server (``manifest`` with its source) on ``arrow`` (Arrow IPC stream bytes). Returns the worker's answer
        (``kind`` measure, echarts, svg or png) plus ``ms``; raises :class:`PyError`."""
        m = item["manifest"]
        self._check(m, arrow)
        if self._sem is None:
            self._sem = asyncio.Semaphore(self.workers)
        try:
            await asyncio.wait_for(self._sem.acquire(), self.queue_seconds)
        except asyncio.TimeoutError:
            raise PyError("DRS-9074", 503, f"all {self.workers} Python workers are busy; try again in a moment") from None
        self.running += 1
        self.calls += 1
        try:
            return await self._spawn(m, arrow, group_by, {**(m.get("params") or {}), **(params or {})})
        except PyError:
            self.failures += 1
            raise
        finally:
            self.running -= 1
            self._sem.release()

    def _check(self, m: dict, arrow: bytes) -> None:
        if not self.enabled:
            raise PyError("DRS-9074", 503, "the Python runner is switched off (bi.python.enabled)")
        need = [x for x in ("pandas", "pyarrow") if importlib.util.find_spec(x) is None]
        if need:
            raise PyError("DRS-9074", 503, f"the Python runner needs {', '.join(need)} in the console's environment (pip install -r drishti-console/requirements.txt)")
        if self.netns == "require" and not self._netns_ok():
            raise PyError("DRS-9074", 503, "bi.python.network_namespace is 'require' but this machine cannot create a network namespace (unshare --user --net)")
        if m.get("target") == "browser":
            raise PyError("DRS-9070", 422, f"'{m.get('name')}' runs in the browser only (target: browser)")
        for p in m.get("packages") or []:
            if p not in self.packages:
                raise PyError("DRS-9073", 403, f"package '{p}' is not on the allow-list (bi.python.packages: {', '.join(self.packages)})")
            if importlib.util.find_spec(p) is None:
                raise PyError("DRS-9074", 503, f"package '{p}' is allowed but not installed on the console")
        if len(arrow) > self.max_input:
            raise PyError("DRS-9072", 413, f"the rows handed to Python are {len(arrow):,} bytes; the limit is {self.max_input:,} (bi.python.max_input_bytes)")

    async def _spawn(self, m: dict, arrow: bytes, group_by, params: dict) -> dict:
        timeout = min(int(m.get("timeout") or 10), self.max_timeout)
        memory = min(int(m.get("memory") or 256), self.max_memory)
        tmp = tempfile.mkdtemp(prefix="drishti-py-", dir=self.scratch)
        t0 = time.monotonic()
        proc = None
        try:
            (Path(tmp) / "in.arrow").write_bytes(arrow)
            req = {"interface": m["interface"], "entry": m.get("entry") or ("compute" if m["interface"] == "measure" else "render"), "source": m["source"],
                   "inputs": list(m.get("inputs") or []), "group_by": group_by, "params": params, "packages": list(m.get("packages") or []),
                   "output": m.get("output") or "echarts", "quant_root": m.get("quant_root"), "quant_engine": m.get("quant_engine"),
                   "table_rows": m.get("table_rows"), "timeout": timeout, "memory": memory, "max_output_bytes": self.max_output, "max_groups": self.max_groups}
            (Path(tmp) / "req.json").write_text(json.dumps(req), encoding="utf-8")
            cmd = [sys.executable, "-I", str(WORKER), tmp]
            if self._netns_ok():
                cmd = ["unshare", "--user", "--map-root-user", "--net", "--"] + cmd
            env = {"PATH": "/usr/bin:/bin", "HOME": tmp, "TMPDIR": tmp, "LANG": "C.UTF-8", "OMP_NUM_THREADS": "1", "OPENBLAS_NUM_THREADS": "1",
                   "MKL_NUM_THREADS": "1", "ARROW_DEFAULT_MEMORY_POOL": "system", "PYTHONDONTWRITEBYTECODE": "1", "MPLCONFIGDIR": tmp}
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
            return answer
        finally:
            if proc is not None and proc.returncode is None:
                self._kill(proc)
            shutil.rmtree(tmp, ignore_errors=True)

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
