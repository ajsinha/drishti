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

"""The three load routes of drishti-feeds (docs/guides/EXTERNAL_DATA.md section "Loading"). None of them writes a lake itself: each hands
the feed's files to what Drishti already has (docs/guides/DATA_LAKE_GUIDE.md section 3), and every one replaces the business date it
loads (a re-run of the same date leaves the same rows: idempotent).

    java    (the default) ``drishti.py lake build --engine java``: the parallel loader, ``com.ash.drishti.lakeload.LakeLoad`` started from
            the server's exec jar with ``-Dloader.main``, under the lake's lock, one commit per business date; Java 21 and the jar
    python  ``drishti.py lake build`` with its default writer (the same rules; for a machine where the parallel loader is not wanted)
    inbox   the files dropped into the data inbox (``<data>/dataload/inbox``) with a ``.ready`` file naming the lake, pack, kind, table,
            mode replace, the SHA-256 and the rows; a feed of several datasets drops ONE tar of ``<table>/<YYYY-MM-DD>/`` folders, which
            the inbox loads completely or not at all. The server loads it at its next cycle (or Admin → Data loads → Load now).

Each route takes the files of one run (feedbase.FeedRun.files) and returns {route, status ok|failed, loads: [...], rows, error}."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DRISHTI = ROOT / "tools" / "drishti.py"


def packs_dir() -> Path:
    """The packs' folder whose pack.yaml gives a table's kind, key and recommended layout."""
    return ROOT / "config" / "packs"


def inbox_dir(given: str | None = None) -> Path:
    """The data inbox's ``inbox/`` folder: --inbox, else DRISHTI_DATALOAD_DIR/inbox, else <data>/dataload/inbox (the inbox's
    default ``dir``; config/engines/dataload.yaml)."""
    if given:
        return Path(given)
    base = os.environ.get("DRISHTI_DATALOAD_DIR")
    if base:
        return Path(base) / "inbox"
    import pullkit
    return pullkit.data_dir() / "dataload" / "inbox"


def lake_build_args(f: dict, lake: str, engine: str | None, summary: Path, opts: dict) -> list[str]:
    args = [sys.executable, str(DRISHTI), "lake", "build", "--from", f["path"], "--lake", lake, "--pack", str(packs_dir() / f["pack"]),
            "--kind", f["kind"], "--mode", "overwrite-dates", "--summary", str(summary), "-q"]
    if f["table"] != f["kind"]:
        args += ["--table", f["table"]]
    if f.get("key"):                                   # the dataset's id field (a lake entry without one takes it; one that has one must agree)
        args += ["--key", f["key"]]
    if engine:
        args += ["--engine", engine]
    for k, flag in (("lake_root", "--lake-root"), ("jar", "--jar"), ("java", "--java"), ("signal", "--signal"), ("token_file", "--token-file")):
        if opts.get(k):
            args += [flag, str(opts[k])]
    return args


def ensure_entries(files: list[dict], lake: str, opts: dict, log=print) -> list[str]:
    """A table the lake file does not describe yet gets its pack's recommended entry first (``LakesCli add-entry``, the same the data
    inbox does for a new lake): the lake file is the truth from then on, and an entry already there is left as it is. The tables added."""
    if opts.get("lake_root") is None and not _is_lake_name(lake):
        return []
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import lakeconfig
    added = []
    keys = {(f["pack"], f["kind"]): f.get("key") or "" for f in files if f["rows"]}
    for pack, kind in sorted(keys):
        args = ["add-entry", "--lake", lake, "--pack", str(packs_dir() / pack), "--kind", kind]
        res = lakeconfig.run(args, jar=opts.get("jar"), java=opts.get("java"))
        if res.get("added"):
            added.append(str(res.get("table")))
            log(f"  {res.get('message')}")
            if keys[(pack, kind)]:
                give_id(lakeconfig.lakes_dir() / f"{lake}.yaml", str(res.get("table")), keys[(pack, kind)], log)
    return added


def give_id(lake_file: Path, entry: str, key: str, log=print) -> bool:
    """An entry just copied from a pack that names no id field gets the dataset's key (``id: <key>``): the parallel loader takes a table's
    id from its lake entry only. An entry with an id, or one not written as a block, is left as it is."""
    import yaml
    text = lake_file.read_text(encoding="utf-8")
    doc = yaml.safe_load(text) or {}
    have = ((doc.get("tables") or {}).get(entry))
    if have is None or not isinstance(have, dict) or have.get("id"):
        return False
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.rstrip() == f"  {entry}:":
            lines.insert(i + 1, f"    id: {key}                    # the feed's dataset key (drishti-feeds)\n")
            lake_file.write_text("".join(lines), encoding="utf-8")
            log(f"  {entry}: id {key} added to {lake_file} (the feed's dataset key)")
            return True
    return False


def _is_lake_name(lake: str) -> bool:
    sys.path.insert(0, str(ROOT / "tools" / "lake"))
    import lakeconfig
    return lakeconfig.is_lake_name(lake)


def via_lake_build(files: list[dict], lake: str, route: str, work: Path, opts: dict, log=print) -> dict:
    """java and python: one ``lake build`` per file (a file is one dataset of one business date), in date order; a table new to the lake
    file gets its pack's recommended entry first."""
    engine = "java" if route == "java" else None
    out = {"route": route, "status": "ok", "loads": [], "rows": 0, "rejected": 0}
    work.mkdir(parents=True, exist_ok=True)
    try:
        out["entriesAdded"] = ensure_entries(files, lake, opts, log)
    except Exception as e:  # noqa: BLE001  (the lake reader's refusal, in its words)
        return {**out, "status": "failed", "error": f"lake {lake}: {e}"}
    for n, f in enumerate(sorted(files, key=lambda x: (x["businessDate"], x["table"]))):
        if not f["rows"]:
            out["loads"].append({"table": f["table"], "businessDate": f["businessDate"], "skipped": "no rows"})
            continue
        summary = work / f"load-{n:03d}.json"
        cmd = lake_build_args(f, lake, engine, summary, opts)
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                              cwd=str(ROOT), env=dict(os.environ))
        said = [s for s in proc.stdout.splitlines() if s.strip()]
        try:
            s = json.loads(summary.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            s = {}
        entry = {"table": f["table"], "businessDate": f["businessDate"], "exit": proc.returncode, "rows": _count(s.get("rows")),
                 "rejected": _count(s.get("rejected")), "engine": s.get("engine"), "line": said[-1] if said else ""}
        out["loads"].append(entry)
        if proc.returncode not in (0, 2):
            out["status"] = "failed"
            out["error"] = f"{f['table']} {f['businessDate']}: " + (said[-1] if said else f"lake build exit {proc.returncode}")
            log(f"  load failed: {out['error']}")
            break
        out["rows"] += _count(s.get("rows"))
        out["rejected"] += _count(s.get("rejected"))
        log(f"  loaded {f['table']} {f['businessDate']}: {_count(s.get('rows'))} rows into lake {lake} ({route})")
    if out["status"] == "ok" and out["rejected"]:
        out["status"] = "rejects"
    return out


def _count(v) -> int:
    """A summary's count: a number, or rows by business date ({date: rows})."""
    if isinstance(v, dict):
        return sum(_count(x) for x in v.values())
    try:
        return int(v or 0)
    except (TypeError, ValueError):
        return 0


def _sha(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for b in iter(lambda: fh.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def _ready(path: Path, doc: dict) -> None:
    """The .ready file last, written whole (a .part renamed), so the inbox never sees half of it."""
    import yaml
    part = path.with_name(path.name + ".part")
    part.write_text(yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")
    part.replace(path)


def via_inbox(files: list[dict], lake: str, feed: str, run_id: str, opts: dict, log=print) -> dict:
    """Drops the run's files into the data inbox: one dataset, one date: the file and its .ready; anything more: one tar of
    <table>/<YYYY-MM-DD>/<file> folders and its .ready (folders: {table: pack})."""
    inbox = inbox_dir(opts.get("inbox"))
    inbox.mkdir(parents=True, exist_ok=True)
    files = [f for f in files if f["rows"]]
    out = {"route": "inbox", "status": "ok", "loads": [], "rows": sum(f["rows"] for f in files), "inbox": str(inbox)}
    if not files:
        out["loads"].append({"skipped": "no rows"})
        return out
    if len(files) == 1:
        f = files[0]
        dest = inbox / Path(f["path"]).name
        part = dest.with_name(dest.name + ".part")              # a name the inbox does not read, renamed when complete
        shutil.copyfile(f["path"], part)
        part.replace(dest)
        _ready(dest.with_name(dest.name + ".ready"), {"lake": lake, "pack": f["pack"], "kind": f["kind"], "table": f["table"],
                                                      "business-date": f["businessDate"], "mode": "replace",
                                                      "sha256": f["sha256"], "rows": f["rows"]})
        out["loads"].append({"file": dest.name, "rows": f["rows"]})
        log(f"  dropped {dest.name} ({f['rows']} rows) into {inbox} for lake {lake}")
        return out
    days = sorted({f["businessDate"] for f in files})
    name = f"{feed}-{days[0]}" + (f"-to-{days[-1]}" if len(days) > 1 else "") + ".tar"
    dest = inbox / name
    part = dest.with_name(dest.name + ".part")
    with tarfile.open(part, "w", format=tarfile.PAX_FORMAT) as tar:
        for f in sorted(files, key=lambda x: (x["table"], x["businessDate"])):
            info = tar.gettarinfo(f["path"], arcname=f"{f['table']}/{f['businessDate']}/{Path(f['path']).name}")
            info.mtime, info.uid, info.gid, info.uname, info.gname, info.mode = 0, 0, 0, "", "", 0o644
            with open(f["path"], "rb") as fh:
                tar.addfile(info, fh)
    part.replace(dest)
    folders = {}
    for f in files:
        folders[f["table"]] = f["pack"]
    _ready(dest.with_name(dest.name + ".ready"), {"lake": lake, "folders": folders, "mode": "replace", "sha256": _sha(dest),
                                                  "rows": out["rows"]})
    out["loads"].append({"file": name, "rows": out["rows"], "tables": sorted(folders), "dates": days})
    log(f"  dropped {name} ({len(files)} files, {out['rows']} rows) into {inbox} for lake {lake}")
    return out


def load(route: str, files: list[dict], lake: str, feed: str, run_id: str, work: Path, opts: dict | None = None, log=print) -> dict:
    opts = opts or {}
    if route == "none":
        return {"route": "none", "status": "ok", "loads": [], "rows": 0}
    if route == "inbox":
        return via_inbox(files, lake, feed, run_id, opts, log)
    if route in ("java", "python"):
        return via_lake_build(files, lake, route, work, opts, log)
    raise ValueError(f"load route {route}: java, python, inbox or none")
