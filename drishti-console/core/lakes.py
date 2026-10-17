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

"""Admin → Data loads → Lakes: load a large JSON or JSON Lines file into a Delta lake from the browser, with the same engine as
``drishti.py lake build`` (docs/guides/TOOLS.md).

The file is streamed to the console's temporary folder as it arrives (never held in memory) and scanned as a stream for its fields
and their values (the suggested id, business date and classifiers). The build is the CLI itself, ``tools/lake/build.py``, run as a
separate process on the console's machine: the same lock, exit codes, summary and run log as a scheduled run, and memory flat in
that process. Afterwards, as the administrator who started it, the console binds the lake to the chosen pack's Delta connector
(the connector API: validated, written atomically, audited, the old text kept) and announces the load (``POST
/api/v1/packs/{pack}/loads``), so the server refreshes and people are told. No token is handed to the build process.

Only lake roots listed in ``lakes.roots`` can be written: a lake is a folder under one of them (``<root>/<domain>/<kind>``).
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote

import yaml

from core import tmpdir

NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
PATH = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")
MODES = ("overwrite-dates", "overwrite-partitions", "append")


class LakeError(Exception):
    def __init__(self, status: int, detail: str, code: str = "DRS-5001"):
        super().__init__(detail)
        self.status, self.detail, self.code = status, detail, code


@dataclass
class LakeSettings:
    roots: list[Path]
    tools_dir: Path
    max_upload_mb: int = 4096
    scan_docs: int = 20000
    engine: str = "auto"
    jar: str = ""
    python: str = ""
    keep_min: int = 240
    jobs: int = 2

    @classmethod
    def from_settings(cls, s, console_dir: Path) -> "LakeSettings":
        def path(p):
            d = Path(str(p)).expanduser()
            return d if d.is_absolute() else (console_dir / d).resolve()
        roots = s.get("lakes.roots", None) or ["../data/delta"]
        if isinstance(roots, str):
            roots = [r for r in roots.split(",") if r.strip()]
        return cls(roots=[path(r) for r in roots], tools_dir=path(s.get("builder.tools_dir", "../tools")),
                   max_upload_mb=int(s.get("lakes.max_upload_mb", 4096)), scan_docs=int(s.get("lakes.scan_docs", 20000)),
                   engine=str(s.get("lakes.engine", "auto") or "auto"), jar=str(s.get("lakes.jar", "") or ""),
                   python=str(s.get("lakes.python", "") or ""), keep_min=int(s.get("lakes.keep_min", 240)), jobs=int(s.get("lakes.jobs", 2)))

    def as_dict(self) -> dict:
        return {"roots": [str(r) for r in self.roots], "maxUploadMb": self.max_upload_mb, "scanDocs": self.scan_docs, "engine": self.engine}


def _tool(tools_dir: Path, name: str):
    """tools/lake/<name>.py, imported by path (the console needs the repository's tools/ folder: builder.tools_dir)."""
    folder = tools_dir / "lake"
    if not (folder / f"{name}.py").is_file():
        raise LakeError(501, f"tools/lake/{name}.py is not here: the console needs the repository's tools/ folder (builder.tools_dir)")
    if str(folder) not in sys.path:
        sys.path.insert(0, str(folder))
    return __import__(name)


# ------------------------------------------------------------------------------------------------ uploads

@dataclass
class Upload:
    id: str
    name: str
    path: Path
    owner: str
    size: int = 0
    created: float = field(default_factory=time.time)
    scan: dict | None = None

    def view(self) -> dict:
        return {"id": self.id, "name": self.name, "size": self.size, "scanned": self.scan is not None}


@dataclass
class Job:
    id: str
    owner: str
    params: dict
    folder: Path
    started: float = field(default_factory=time.time)
    state: str = "running"                       # running, built (bind and announce to do), done (see summary.status), failed
    exit: int | None = None
    error: str | None = None
    summary: dict | None = None
    bind: dict | None = None
    signal: list | None = None
    stderr: str = ""
    after_started: bool = False

    def view(self) -> dict:
        prog = {}
        pf = self.folder / "progress.json"
        if pf.is_file():
            try:
                prog = json.loads(pf.read_text(encoding="utf-8"))
            except ValueError:
                prog = {}
        return {"id": self.id, "state": "running" if self.state == "built" else self.state, "exit": self.exit, "error": self.error, "progress": prog, "summary": self.summary,
                "bind": self.bind, "signal": self.signal, "params": {k: v for k, v in self.params.items() if k != "upload"},
                "seconds": round(time.time() - self.started, 1)}


class Lakes:
    """The uploads and build jobs of this console process."""

    def __init__(self, cfg: LakeSettings):
        self.cfg = cfg
        self.uploads: dict[str, Upload] = {}
        self.jobs: dict[str, Job] = {}

    # -------------------------------------------------------------------------------------------- housekeeping

    def _expire(self) -> None:
        cut = time.time() - self.cfg.keep_min * 60
        for k, u in list(self.uploads.items()):
            if u.created < cut and not any(j.state in ("running", "built") and j.params.get("upload") == k for j in self.jobs.values()):
                u.path.unlink(missing_ok=True)
                del self.uploads[k]
        for k, j in list(self.jobs.items()):
            if j.state in ("done", "failed") and j.started < cut:
                del self.jobs[k]

    def upload(self, id_: str, owner: str) -> Upload:
        u = self.uploads.get(id_)
        if u is None or u.owner != owner:
            raise LakeError(404, "no such upload (it expires after lakes.keep_min minutes): upload the file again", "DRS-5033")
        return u

    # -------------------------------------------------------------------------------------------- upload and scan

    async def receive(self, name: str, owner: str, chunks, declared: int | None) -> Upload:
        self._expire()
        cap = self.cfg.max_upload_mb * 1048576
        if declared and declared > cap:
            raise LakeError(413, f"the file is over {self.cfg.max_upload_mb} MB (lakes.max_upload_mb)", "DRS-5005")
        safe = re.sub(r"[^A-Za-z0-9_.-]", "_", Path(name or "upload.json").name)[:80] or "upload.json"
        if not safe.lower().endswith((".json", ".jsonl", ".ndjson", ".gz")):
            raise LakeError(415, "send a .json (array), .jsonl, .ndjson or .gz file")
        folder = tmpdir.sub_dir("lake-uploads")
        uid = secrets.token_hex(8)
        path = folder / f"{uid}-{safe}"
        size = 0
        try:
            with open(path, "wb") as fh:
                async for c in chunks:
                    size += len(c)
                    if size > cap:
                        raise LakeError(413, f"the file is over {self.cfg.max_upload_mb} MB (lakes.max_upload_mb)", "DRS-5005")
                    fh.write(c)
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        if size == 0:
            path.unlink(missing_ok=True)
            raise LakeError(400, "the file is empty")
        u = self.uploads[uid] = Upload(uid, safe, path, owner, size)
        return u

    async def scan(self, u: Upload) -> dict:
        if u.scan is None:
            mod = _tool(self.cfg.tools_dir, "scan")
            u.scan = await asyncio.to_thread(mod.scan, [str(u.path)], self.cfg.scan_docs)
        return u.scan

    # -------------------------------------------------------------------------------------------- targets

    def tables(self) -> list[dict]:
        """The tables already under the roots: <root>/<domain>/<kind> with a _delta_log, their partitioning and the last run."""
        writers = _tool(self.cfg.tools_dir, "writers")
        out = []
        for root in self.cfg.roots:
            if not root.is_dir():
                continue
            for log in sorted(root.glob("*/*/_delta_log")):
                t = log.parent
                try:
                    have = writers.existing_layout(str(t))
                except Exception:              # noqa: BLE001 - a table we cannot read is listed without its layout
                    have = None
                parts, types = have if have else ([], {})
                out.append({"root": str(root), "domain": t.parent.name, "kind": t.name, "partitionBy": [p for p in parts if p != "business_date"],
                            "promoted": [c for c in types if c not in ("id", "doc", "business_date") and c not in parts]})
        return out

    def runs(self, limit: int = 20) -> list[dict]:
        out = []
        for root in self.cfg.roots:
            log = root / "_drishti-lake" / "runs.log"
            if not log.is_file():
                log = root / ".drishti-lake" / "runs.log"      # an earlier build's name
            if log.is_file():
                for line in log.read_text(encoding="utf-8").splitlines()[-limit:]:
                    out.append({"root": str(root), "line": line})
        return out[-limit:]

    # -------------------------------------------------------------------------------------------- the build

    def check(self, p: dict) -> dict:
        root = Path(str(p.get("root") or ""))
        if root not in self.cfg.roots:
            raise LakeError(400, "choose one of the lake roots this console may write (lakes.roots)")
        for k in ("domain", "kind"):
            if not NAME.match(str(p.get(k) or "")):
                raise LakeError(400, f"'{k}' must be a short name (letters, digits, - _ .)")
        if not PATH.match(str(p.get("key") or "")):
            raise LakeError(400, "choose the id field (key)")
        if not p.get("asOf") and not PATH.match(str(p.get("date") or "")):
            raise LakeError(400, "choose the business-date field, or one date for the whole file (as of)")
        if p.get("asOf") and not re.match(r"^\d{4}-\d{2}-\d{2}$", str(p["asOf"])):
            raise LakeError(400, "as of is a date, yyyy-MM-dd")
        for k in ("partitionBy", "promote"):
            v = p.get(k) or []
            if not isinstance(v, list) or not all(isinstance(x, str) and PATH.match(x) for x in v):
                raise LakeError(400, f"'{k}' is a list of field paths")
        if len(p.get("partitionBy") or []) > 4:
            raise LakeError(400, "at most 4 classifiers (each multiplies the folders of a date)")
        if p.get("mode", "overwrite-dates") not in MODES:
            raise LakeError(400, f"mode is one of {', '.join(MODES)}")
        if (p.get("bind") or p.get("signal")) and not p.get("pack"):
            raise LakeError(400, "choose the pack to bind and announce to")
        if p.get("bind") and not NAME.match(str(p.get("connector") or "")):
            raise LakeError(400, "choose the pack's Delta connector to bind")
        return p

    def argv(self, p: dict, u: Upload, folder: Path) -> list[str]:
        py = self.cfg.python or sys.executable
        a = [py, str(self.cfg.tools_dir / "lake" / "build.py"), "--from", str(u.path), "--lake", str(p["root"]), "--domain", p["domain"],
             "--kind", p["kind"], "--key", p["key"], "--mode", p.get("mode") or "overwrite-dates", "--engine", self.cfg.engine,
             "--progress", str(folder / "progress.json"), "--summary", str(folder / "summary.json"), "--quiet"]
        a += ["--as-of", p["asOf"]] if p.get("asOf") else ["--date", p["date"]]
        if p.get("partitionBy"):
            a += ["--partition-by", ",".join(p["partitionBy"])]
        a += ["--promote", ",".join(p.get("promote") or [])]
        if p.get("pack"):
            a += ["--pack-name", p["pack"]]
        if self.cfg.jar:
            a += ["--jar", self.cfg.jar]
        return a

    def start(self, p: dict, me) -> Job:
        """Starts the build as its own process; a thread waits for it. Binding and announcing happen on the next poll
        (:meth:`after`), as the administrator who polls, so no request has to stay open for the build."""
        self._expire()
        owner = getattr(me, "user", None) or "admin"
        u = self.upload(str(p.get("upload") or ""), owner)
        self.check(p)
        if sum(1 for j in self.jobs.values() if j.state in ("running", "built")) >= self.cfg.jobs:
            raise LakeError(429, f"{self.cfg.jobs} lake builds are running already (lakes.jobs): wait for one to finish", "DRS-5005")
        jid = secrets.token_hex(6)
        folder = Path(tmpdir.mkdtemp("lake-job-"))
        job = self.jobs[jid] = Job(jid, owner, {**p, "upload": u.id, "file": u.name}, folder)
        env = {k: v for k, v in os.environ.items() if k != "DRISHTI_TOKEN"}       # the build never holds a token
        err = open(folder / "stderr.txt", "wb")
        try:
            proc = subprocess.Popen(self.argv(p, u, folder), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err, env=env)
        except OSError as e:
            err.close()
            job.state, job.error = "failed", f"the build could not start: {e}"
            return job
        threading.Thread(target=self._wait, args=(job, proc, err), name=f"lake-build-{jid}", daemon=True).start()
        return job

    @staticmethod
    def _wait(job: Job, proc, err) -> None:
        job.exit = proc.wait()
        err.close()
        job.stderr = (job.folder / "stderr.txt").read_text(encoding="utf-8", errors="replace")[-2000:]
        sf = job.folder / "summary.json"
        if sf.is_file():
            try:
                job.summary = json.loads(sf.read_text(encoding="utf-8"))
            except ValueError:
                job.summary = None
        if job.exit not in (0, 2):
            job.error = (job.summary or {}).get("error") or (job.stderr.strip().splitlines() or [f"exit {job.exit}"])[-1]
            job.state = "failed"
            return
        job.state = "built" if job.params.get("bind") or job.params.get("signal") else "done"

    async def after(self, job: Job, me, backend) -> None:
        """Once built: bind the lake to the pack's connector and announce the load (once, as ``me``)."""
        if job.state != "built" or job.after_started:
            return
        job.after_started = True
        p = job.params
        try:
            if p.get("bind"):
                job.bind = await bind(backend, me, p["connector"], Path(p["root"]), p["domain"], p["kind"], (job.summary or {}).get("columns", {}))
            if p.get("signal"):
                job.signal = await announce(backend, me, p["pack"], p["kind"], job.summary or {})
        finally:
            job.state = "done"


# ------------------------------------------------------------------------------------------------ bind and announce

def connector_text(current: str | None, root: Path, domain: str, kind: str, columns: dict) -> str:
    """The connector file with this lake bound: plugin delta, the kind among its kinds, root and domain set, and the kind's promoted
    columns as its layout (the rest of the file is kept)."""
    doc = (yaml.safe_load(current) if current else None) or {}
    if not isinstance(doc, dict):
        doc = {}
    doc["plugin"] = "delta"
    kinds = [k for k in (doc.get("kinds") or []) if isinstance(k, str)]
    if kind not in kinds:
        kinds.append(kind)
    doc["kinds"] = kinds
    st = doc.get("settings") if isinstance(doc.get("settings"), dict) else {}
    st["root"] = str(root)
    st["domain"] = domain
    promoted = list((columns or {}).get("promoted") or {})
    if promoted:
        lay = st.get("layout") if isinstance(st.get("layout"), dict) else {}
        k = lay.get(kind) if isinstance(lay.get(kind), dict) else {}
        k["columns"] = [c.replace("__", ".") for c in promoted]
        lay[kind] = k
        st["layout"] = lay
    doc["settings"] = st
    return yaml.safe_dump(doc, sort_keys=False, default_flow_style=False)


async def bind(backend, me, connector: str, root: Path, domain: str, kind: str, columns: dict) -> dict:
    from core.backend import BackendError
    try:
        cur = await backend.admin("GET", f"/connectors/{quote(connector, safe='')}", me)
    except BackendError as e:
        if e.status != 404:
            return {"ok": False, "connector": connector, "error": e.detail}
        cur = {}
    before = cur.get("text") or cur.get("packDefault")
    text = connector_text(before, root, domain, kind, columns)
    headers = {"If-Match": cur["etag"]} if cur.get("etag") else None
    try:
        await backend.admin("PUT", f"/connectors/{quote(connector, safe='')}", me, {"text": text}, headers=headers, timeout=60.0, confirm="true")
    except BackendError as e:
        return {"ok": False, "connector": connector, "error": e.detail, "code": e.code}
    return {"ok": True, "connector": connector, "created": not cur.get("text"), "text": text}


async def announce(backend, me, pack: str, kind: str, summary: dict) -> list:
    from core.backend import BackendError
    out = []
    for day, rows in (summary.get("rows") or {}).items():
        body = {"kind": kind, "businessDate": day, "status": "ready", "rows": rows, "rejected": summary.get("rejected", 0),
                "batchId": summary.get("run"), "source": "console lake build"}
        try:
            r = await backend.announce_load(pack, body, me)
            out.append({"date": day, "ok": True, "verified": (r or {}).get("verified")})
        except BackendError as e:
            out.append({"date": day, "ok": False, "error": e.detail})
    return out
