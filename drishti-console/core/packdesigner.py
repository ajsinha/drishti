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

"""Build -> New pack, the console's side (docs/guides/BUILD_GUIDE.md section 8): workspaces on disk and the jobs that run in them.

A workspace is a folder under the console's data folder (``<console data>/pack-designer/<server and user>/<id>/``): ``in/`` holds the uploaded JSON
Lines files, ``request.json`` the choices, ``status.json`` the running job's progress, ``analysis.json`` what the full pass found, ``dist/`` the
module. Everything a job knows is on disk, so any console worker answers any call. The jobs are ``tools/packgen/designer_job.py``, run as a process
of their own: the same code as ``drishti.py pack new`` (every record read by tools/packgen/packprofile.py, the Sutras drafted by
tools/sutragen.py, the module made by tools/packgen/module_from_jsonl.py); nothing is implemented twice."""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

ID_RE = re.compile(r"[0-9a-f]{16}")
NAME_RE = re.compile(r"[a-z0-9][a-z0-9-]*")
VERSION_RE = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?")
ACCEPT = (".jsonl", ".jsonl.gz", ".ndjson", ".ndjson.gz", ".json", ".json.gz")
# the settings of a job that become command-line options of `pack new`: option -> (request key, a pattern the value must match)
SAFE_VALUE = re.compile(r"[^\x00-\x1f]{0,2000}")


class DesignerError(Exception):
    def __init__(self, status: int, detail: str, code: str = "DRS-5001"):
        super().__init__(detail)
        self.status, self.detail, self.code = status, detail, code


@dataclass(frozen=True)
class DesignerLimits:
    max_mb: int = 4096              # uploaded bytes in one workspace
    max_files: int = 500            # files in one workspace
    ttl_hours: int = 72             # a workspace untouched for this long is removed
    jobs_per_user: int = 2          # running jobs
    workers: int = 0                # processes reading files (0: the tool's default)

    @classmethod
    def from_settings(cls, s) -> "DesignerLimits":
        g = lambda k, d: int(s.get(f"builder.{k}", d))     # noqa: E731
        return cls(g("pack_designer_max_mb", 4096), g("pack_designer_max_files", 500), g("pack_designer_ttl_hours", 72),
                   g("pack_designer_jobs_per_user", 2), g("pack_designer_workers", 0))

    def as_dict(self) -> dict:
        return {"maxMb": self.max_mb, "maxFiles": self.max_files, "accept": list(ACCEPT)}


def clean_path(p: str) -> str:
    """An uploaded file's path below the workspace's in/ folder: forward slashes, no dots, no drive, at most 6 levels; DesignerError otherwise."""
    parts = [x for x in str(p or "").replace("\\", "/").split("/") if x not in ("", ".")]
    if not parts or any(x == ".." or ":" in x or x.startswith("~") or len(x) > 200 for x in parts) or len(parts) > 6:
        raise DesignerError(400, f"{p!r} is not a file path the designer takes")
    if not parts[-1].lower().endswith(ACCEPT):
        raise DesignerError(415, f"{parts[-1]} is not one of {', '.join(ACCEPT)}", "DRS-5001")
    return "/".join(parts)


class Workspaces:
    def __init__(self, base: Path, tools: Path, limits: DesignerLimits, site: str = ""):
        self.base, self.tools, self.limits, self.site = base, tools, limits, site       # site: the config folder the bindings are written to ("": each workspace's own site/)

    # ---- folders ------------------------------------------------------------------------------------------------------
    def scope_dir(self, scope: str) -> Path:
        return self.base / re.sub(r"[^A-Za-z0-9._-]+", "_", scope)[:120]

    def create(self, scope: str) -> str:
        self.sweep(scope)
        wid = secrets.token_hex(8)
        (self.scope_dir(scope) / wid / "in").mkdir(parents=True)
        return wid

    def path(self, scope: str, wid: str) -> Path:
        if not ID_RE.fullmatch(wid or ""):
            raise DesignerError(404, "no such workspace", "DRS-5006")
        d = self.scope_dir(scope) / wid
        if not d.is_dir():
            raise DesignerError(404, "no such workspace (it may have been removed after being untouched)", "DRS-5006")
        return d

    def sweep(self, scope: str) -> None:
        """Removes this user's workspaces untouched for longer than the limit."""
        d = self.scope_dir(scope)
        if not d.is_dir():
            return
        old = time.time() - self.limits.ttl_hours * 3600
        for w in d.iterdir():
            try:
                if w.is_dir() and ID_RE.fullmatch(w.name) and w.stat().st_mtime < old and not self.running(w):
                    shutil.rmtree(w, ignore_errors=True)
            except OSError:
                continue

    def remove(self, scope: str, wid: str) -> None:
        d = self.path(scope, wid)
        if self.running(d):
            raise DesignerError(409, "a job is running in this workspace: wait for it, or cancel it")
        shutil.rmtree(d, ignore_errors=True)

    def touch(self, d: Path) -> None:
        os.utime(d, None)

    # ---- files --------------------------------------------------------------------------------------------------------
    def files(self, d: Path) -> list[dict]:
        root = d / "in"
        out = [{"path": str(f.relative_to(root)).replace(os.sep, "/"), "bytes": f.stat().st_size} for f in sorted(root.rglob("*")) if f.is_file()]
        return out

    def target(self, d: Path, rel: str) -> Path:
        rel = clean_path(rel)
        f = (d / "in" / rel).resolve()
        if (d / "in").resolve() not in f.parents:
            raise DesignerError(400, "that path leaves the workspace")
        files = self.files(d)
        if len(files) >= self.limits.max_files and not f.exists():
            raise DesignerError(413, f"at most {self.limits.max_files} files in one workspace (builder.pack_designer_max_files)", "DRS-5005")
        return f

    def used(self, d: Path) -> int:
        total = 0
        for x in self.files(d):
            total += x["bytes"]
        return total

    def remove_file(self, d: Path, rel: str) -> None:
        f = self.target(d, rel)
        if f.is_file():
            f.unlink()
            for p in f.parents:                     # empty folders go with the last file
                if p == d / "in" or any(p.iterdir()):
                    break
                p.rmdir()

    # ---- jobs ---------------------------------------------------------------------------------------------------------
    def running(self, d: Path) -> bool:
        pid = d / "job.pid"
        if not pid.is_file():
            return False
        try:
            p = int(pid.read_text().strip())
            os.kill(p, 0)
            with open(f"/proc/{p}/stat") as f:                                     # a zombie is not running
                return f.read().rsplit(")", 1)[1].split()[0] != "Z"
        except (ValueError, ProcessLookupError, PermissionError):
            return False
        except OSError:
            return True                                                              # no /proc (macOS, Windows): the pid answered

    def running_of(self, scope: str) -> int:
        d = self.scope_dir(scope)
        return sum(1 for w in d.iterdir() if w.is_dir() and ID_RE.fullmatch(w.name) and self.running(w)) if d.is_dir() else 0

    def request_for(self, body: dict) -> dict:
        """The job's request from the page's choices, each value checked."""
        r: dict = {}
        name = str(body.get("name") or "").strip()
        if not NAME_RE.fullmatch(name):
            raise DesignerError(400, "the pack name is lower-case letters, digits and '-'")
        r["name"] = name
        version = str(body.get("version") or "1.0.0")
        if not VERSION_RE.fullmatch(version):
            raise DesignerError(400, "the version must be X.Y.Z, such as 1.0.0 or 2.0.0-rc.1")
        r["version"] = version
        for k in ("title", "description", "kind", "kindPrefix", "key", "date", "match", "child", "mnemonic", "connector", "filesRoot", "publisher"):
            v = body.get(k)
            if v is None or v == "":
                continue
            if not isinstance(v, str) or not SAFE_VALUE.fullmatch(v):
                raise DesignerError(400, f"{k} is text")
            r[k] = v.strip()
        if "kind" in r and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", r["kind"]):
            raise DesignerError(400, "the kind is letters, digits, '_', '-' and '.'")
        if "child" in r and not re.fullmatch(r"[A-Za-z0-9_.,;=-]+", r["child"]):
            raise DesignerError(400, "child tables are fields: LEGS,CASHFLOWS or kind=LEGS;kind2=none")
        if "kindPrefix" in r and not re.fullmatch(r"[a-z0-9][a-z0-9-]*", r["kindPrefix"]):
            raise DesignerError(400, "the kind prefix is lower-case letters, digits and '-'")
        r["minDocs"] = max(1, min(int(body.get("minDocs") or 1), 1_000_000))
        if self.limits.workers:
            r["workers"] = self.limits.workers
        if isinstance(body.get("edits"), dict):
            r["edits"] = {str(k): str(v) for k, v in body["edits"].items() if isinstance(v, str) and len(v) < 400_000}
        site = os.environ.get("DRISHTI_PACK_DESIGNER_SITE") or self.site
        if site:
            r["site"] = str(site)
        r["load"] = bool(body.get("load", True))
        r["allowSharedKinds"] = bool(body.get("allowSharedKinds", False))
        return r

    def start(self, scope: str, d: Path, op: str, request: dict, env: dict | None = None) -> None:
        if self.running(d):
            raise DesignerError(409, "a job is already running in this workspace")
        if self.running_of(scope) >= self.limits.jobs_per_user:
            raise DesignerError(429, f"{self.limits.jobs_per_user} jobs are running for you: wait for one (builder.pack_designer_jobs_per_user)")
        if not self.files(d):
            raise DesignerError(400, "upload at least one .jsonl, .jsonl.gz or .ndjson file first")
        script = self.tools / "packgen" / "designer_job.py"
        if not script.is_file():
            raise DesignerError(501, "the console needs the repository's tools/ folder (builder.tools_dir) to design a pack")
        (d / "request.json").write_text(json.dumps(request), encoding="utf-8")
        for stale in ("status.json", "result.json", "analysis.json" if op == "analyse" else "result.json"):
            (d / stale).unlink(missing_ok=True)
        log = open(d / "job.log", "w", encoding="utf-8")
        proc = subprocess.Popen([sys.executable, str(script), op, str(d)], stdout=log, stderr=subprocess.STDOUT, cwd=str(self.tools.parent),
                                env={**os.environ, **(env or {}), "PYTHONUNBUFFERED": "1"}, start_new_session=True)
        log.close()
        (d / "job.pid").write_text(str(proc.pid), encoding="utf-8")
        (d / "status.json").write_text(json.dumps({"state": "running", "rows": 0, "seconds": 0, "lines": [], "error": None, "op": op}), encoding="utf-8")
        (d / "job.op").write_text(op, encoding="utf-8")
        self._reap(proc)

    @staticmethod
    def _reap(proc: subprocess.Popen) -> None:
        import threading
        threading.Thread(target=proc.wait, daemon=True).start()                      # no zombie while the console runs

    def cancel(self, d: Path) -> bool:
        try:
            pid = int((d / "job.pid").read_text().strip())
        except (OSError, ValueError):
            return False
        try:
            os.killpg(pid, 15)
        except (ProcessLookupError, PermissionError, OSError):
            return False
        (d / "status.json").write_text(json.dumps({"state": "failed", "error": "cancelled", "lines": [], "rows": 0, "seconds": 0}), encoding="utf-8")
        return True

    def status(self, d: Path) -> dict:
        try:
            s = json.loads((d / "status.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            s = {"state": "none", "lines": [], "rows": 0, "seconds": 0, "error": None}
        s.pop("t0", None)
        op = (d / "job.op").read_text().strip() if (d / "job.op").is_file() else None
        s["op"] = op
        if s.get("state") == "running" and not self.running(d):                  # the process ended without saying so
            try:
                s = json.loads((d / "status.json").read_text(encoding="utf-8"))
            except (OSError, ValueError):
                pass
            if s.get("state") == "running":
                s.update(state="failed", error="the job stopped without finishing: see the log")
            s["op"] = op
            s.pop("t0", None)
        tail = []
        try:
            tail = (d / "job.log").read_text(encoding="utf-8", errors="replace").splitlines()[-400:]
        except OSError:
            pass
        s["log"] = tail
        return s

    def analysis(self, d: Path) -> dict | None:
        f = d / "analysis.json"
        return json.loads(f.read_text(encoding="utf-8")) if f.is_file() else None

    def result(self, d: Path) -> dict | None:
        f = d / "result.json"
        return json.loads(f.read_text(encoding="utf-8")) if f.is_file() else None

    def dmod(self, d: Path) -> Path | None:
        r = self.result(d)
        f = d / "dist" / r["dmod"] if r else None
        return f if f is not None and f.is_file() else None
