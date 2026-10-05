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

"""`drishti.py data ingest --watch DIR`: a drop folder that feeds the lake.

Polls DIR for *.jsonl, waits until a file has stopped growing, ingests each file once with the same options as a plain
`data ingest`, and remembers it in a state file (path, size, mtime, sha256) that is written only after the ingest succeeded: a
crash re-ingests the file in flight, never skips it. A file that is later changed (another sha256) is ingested again.
`--done-dir` moves finished files away; `--once` does one pass and exits (for cron).
Mode: a watcher's default is `append` (files are batches that add rows); use `--mode overwrite-dates` when every file holds
complete days, so a changed file replaces its dates.
"""
from __future__ import annotations

import copy
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import time

STATE_NAME = ".drishti-ingest-state.json"


def log(level: str, msg: str, say=print) -> None:
    say(f"{datetime.datetime.now().isoformat(timespec='seconds')} {level:<5} {msg}")


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class State:
    """The processed files: {absolute path: {size, mtime, sha256, at, rows, bad}}; saved atomically."""

    def __init__(self, path: pathlib.Path | None):
        self.path, self.files = path, {}
        if path and path.is_file():
            try:
                self.files = json.loads(path.read_text(encoding="utf-8")).get("files", {})
            except (OSError, ValueError):
                raise SystemExit(f"ingest --watch: the state file {path} is unreadable; fix or delete it (files in the drop folder would be ingested again)")

    def save(self) -> None:
        if not self.path:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".tmp")
        tmp.write_text(json.dumps({"files": self.files}, indent=1, sort_keys=True), encoding="utf-8")
        os.replace(tmp, self.path)


def state_path(args) -> pathlib.Path | None:
    if getattr(args, "state", None):
        return pathlib.Path(args.state)
    if args.dry_run:
        return None
    target = args.lake if args.store == "delta" else args.root
    return pathlib.Path(target) / STATE_NAME if target else None


def candidates(root: pathlib.Path, recursive: bool) -> list[pathlib.Path]:
    found = root.rglob("*.jsonl") if recursive else root.glob("*.jsonl")
    return sorted(p for p in found if p.is_file() and not p.name.startswith("."))


def stat_of(p: pathlib.Path):
    try:
        s = p.stat()
        return s.st_size, s.st_mtime_ns
    except OSError:
        return None


def stable(paths: list[pathlib.Path], seconds: float, sleep=time.sleep) -> list[pathlib.Path]:
    """The files whose size and mtime did not change over `seconds`: nobody is still writing them."""
    before = {p: stat_of(p) for p in paths}
    if paths and seconds > 0:
        sleep(seconds)
    return [p for p in paths if before[p] is not None and stat_of(p) == before[p]]


def done_name(done: pathlib.Path, src: pathlib.Path) -> pathlib.Path:
    dest = done / src.name
    if dest.exists():
        dest = done / f"{src.stem}.{datetime.datetime.now().strftime('%Y%m%dT%H%M%S%f')}{src.suffix}"
    return dest


def one_pass(args, ingest, state: State, failed: dict, say=print, sleep=time.sleep) -> tuple[int, int]:
    """One scan: returns (files ingested, files that failed or had bad lines). `failed` remembers stat of failed files so they are not retried until they change."""
    root = pathlib.Path(args.watch)
    done = pathlib.Path(args.done_dir) if args.done_dir else None
    ok = bad = 0
    for path in stable(candidates(root, args.recursive), args.stable_seconds, sleep):
        key, st = str(path.resolve()), stat_of(path)
        if st is None:
            continue
        known = state.files.get(key)
        if known and (known["size"], known["mtime"]) == st:
            continue
        if failed.get(key) == st:
            continue
        digest = sha256(path)
        if known and known["sha256"] == digest:                       # touched, not changed
            known.update(size=st[0], mtime=st[1])
            state.save()
            continue
        log("INFO", f"{'changed' if known else 'new'} file {path.name} ({st[0]} bytes): ingesting", say)
        opts = copy.copy(args)
        opts.src, opts.recursive = [path], False
        try:
            report = ingest(opts, lambda *a, **k: None)
        except BaseException as e:                                    # noqa: BLE001 - SystemExit from a bad option is a failed file too
            if isinstance(e, KeyboardInterrupt):
                raise
            failed[key] = st
            bad += 1
            log("ERROR", f"{path.name}: ingest failed ({e}); it stays in place and is tried again when it changes or on the next run", say)
            continue
        rows = sum(report.rows.values())
        if report.bad:
            bad += 1
            log("WARN", f"{path.name}: {len(report.bad)} bad line(s), e.g. {report.bad[0]}", say)
        state.files[key] = {"size": st[0], "mtime": st[1], "sha256": digest, "at": datetime.datetime.now().isoformat(timespec="seconds"),
                            "rows": rows, "bad": len(report.bad)}
        if not args.dry_run:
            state.save()                                              # after success, never before
        failed.pop(key, None)
        ok += 1
        log("INFO", f"{path.name}: {rows} row(s) ingested" + (" (dry run)" if args.dry_run else ""), say)
        if done and not args.dry_run:
            done.mkdir(parents=True, exist_ok=True)
            dest = done_name(done, path)
            shutil.move(str(path), str(dest))
            state.files[key]["movedTo"] = str(dest)
            state.save()
            log("INFO", f"{path.name}: moved to {dest}", say)
    return ok, bad


def watch(args, ingest, say=print, sleep=time.sleep, max_passes: int | None = None) -> int:
    root = pathlib.Path(args.watch)
    if not root.is_dir():
        raise SystemExit(f"ingest --watch: {root} is not a folder")
    if args.mode is None:
        args.mode = "append"
    state, failed = State(state_path(args)), {}
    log("INFO", f"watching {root} for *.jsonl (mode {args.mode}, " + (f"state {state.path}" if state.path else "no state file") + ")", say)
    passes, total_bad = 0, 0
    try:
        while True:
            ok, bad = one_pass(args, ingest, state, failed, say, sleep)
            total_bad += bad
            passes += 1
            if args.once or (max_passes is not None and passes >= max_passes):
                log("INFO", f"pass finished: {ok} file(s) ingested, {bad} with problems", say)
                return 1 if total_bad else 0
            sleep(args.interval)
    except KeyboardInterrupt:
        log("INFO", "stopped", say)
        return 0
