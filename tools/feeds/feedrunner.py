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

"""One feed run from start to end, and the run ledger (docs/guides/EXTERNAL_DATA.md).

    run_feed(spec, opts)    lock -> pull (Feed.pull) -> write the dated files and manifests -> the feed's check -> load (loaders.py)
                            -> save the state (only after success) -> one ledger line -> one log line; returns (exit code, entry)
    Ledger                  <data>/datafeeds/ledger.jsonl: one JSON line per run, kept for ever (Admin → Feeds and `feed status` read it)

The one log line of a run (what a scheduler's mail or journal shows):

    2026-11-07T06:30:12-05:00 feed ok exit=0 feed=gleif date=2026-11-07 files=3 rows=1,204 load=java loaded=1,204 seconds=41.2
"""
from __future__ import annotations

import json
import os
import time
import traceback
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import feedbase as FB
import feedload as L


@dataclass
class RunOptions:
    business_date: date | None = None
    since: date | None = None
    load: str | None = None              # java | python | inbox | none; None: the feed file's
    dry_run: bool = False
    snapshot: bool = False
    internal_ok: bool = False
    offline: bool = False
    sets: dict = field(default_factory=dict)
    extra_args: list = field(default_factory=list)
    lake: str | None = None              # over the feed file's
    load_opts: dict = field(default_factory=dict)   # inbox, lake_root, jar, java, signal, token_file
    trigger: str = "cli"                 # cli | schedule | admin


class Ledger:
    """<data>/datafeeds/ledger.jsonl: one line per run (never rewritten; appended under a lock file so two feeds finishing at once do not
    interleave)."""

    def __init__(self, root: Path | None = None):
        self.path = Path(root or FB.feeds_dir()) / "ledger.jsonl"

    def append(self, entry: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(entry, sort_keys=True, ensure_ascii=False) + "\n"
        with open(self.path, "a", encoding="utf-8") as fh:
            if os.name != "nt":
                import fcntl
                fcntl.flock(fh, fcntl.LOCK_EX)
            fh.write(line)
            fh.flush()

    def entries(self, feed: str | None = None, limit: int | None = None) -> list[dict]:
        if not self.path.is_file():
            return []
        out = []
        with open(self.path, encoding="utf-8") as fh:
            for line in fh:
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                if feed is None or e.get("feed") == feed:
                    out.append(e)
        return out[-limit:] if limit else out

    def summary(self, specs: list) -> list[dict]:
        """Per feed: the last run, the last success, its rows and duration, the failures since the last success."""
        by: dict[str, list[dict]] = {}
        for e in self.entries():
            by.setdefault(e.get("feed"), []).append(e)
        rows = []
        for s in specs:
            runs = [e for e in by.get(s.name, []) if e.get("status") != "dry-run"]
            last = runs[-1] if runs else None
            ok = [e for e in runs if e.get("status") == "ok"]
            since_ok = runs[runs.index(ok[-1]) + 1:] if ok else runs
            rows.append({"feed": s.name, "title": s.title, "lake": s.lake, "load": s.load, "enabled": s.enabled,
                         "schedule": f"{s.schedule_days} {s.schedule_at}", "internalOnly": s.internal_only, "licence": s.licence.name,
                         "lastRun": last and last.get("at"), "lastStatus": last and last.get("status"), "lastExit": last and last.get("exit"),
                         "lastSuccess": ok[-1].get("at") if ok else None, "rows": ok[-1].get("rows") if ok else None,
                         "seconds": last and last.get("seconds"), "businessDate": ok[-1].get("businessDate") if ok else None,
                         "failuresSinceSuccess": len([e for e in since_ok if e.get("status") in ("failed", "partial")]),
                         "lastError": next((e.get("error") for e in reversed(runs) if e.get("error")), None) if last and last.get("status") != "ok" else None})
        return rows


def log_line(entry: dict) -> str:
    parts = [FB.now_iso(), "feed", entry["status"], f"exit={entry['exit']}", f"feed={entry['feed']}", f"date={entry.get('businessDate')}"]
    if entry.get("since"):
        parts.append(f"since={entry['since']}")
    parts += [f"files={entry.get('files', 0)}", f"rows={entry.get('rows', 0):,}"]
    ld = entry.get("load") or {}
    if ld:
        parts.append(f"load={ld.get('route')}")
        if ld.get("route") not in (None, "none"):
            parts.append(f"loaded={ld.get('rows', 0):,}")
    parts.append(f"seconds={entry.get('seconds', 0)}")
    if entry.get("error"):
        parts.append('error="' + str(entry["error"]).replace('"', "'")[:300] + '"')
    return " ".join(parts)


def run_feed(spec: FB.FeedSpec, o: RunOptions, log=print, root: Path | None = None, opener=None, sleep=time.sleep,
             ledger: Ledger | None = None) -> tuple[int, dict]:
    """Runs one feed; (exit code, its ledger entry). Never raises for a feed's own failure: it is in the entry and the exit code."""
    t0 = time.monotonic()
    ledger = ledger or Ledger(root)
    route = o.load or spec.load
    lake = o.lake or spec.lake
    entry = {"at": FB.now_iso(), "feed": spec.name, "trigger": o.trigger, "businessDate": (o.business_date or date.today()).isoformat(),
             "since": o.since.isoformat() if o.since else None, "lake": lake, "internalOnly": spec.internal_only,
             "licence": spec.licence.name, "status": "failed", "exit": FB.FAILED, "files": 0, "rows": 0, "datasets": {}}
    run = None
    try:
        if route not in FB.ROUTES:
            raise FB.FeedRefused(f"--load {route}: one of {', '.join(FB.ROUTES)}")
        cls = FB.feed_class(spec)
        feed = cls(spec)
        run = FB.FeedRun(spec, o.business_date, o.since, o.dry_run, o.sets, log, root, opener, sleep, o.offline, o.extra_args,
                         o.internal_ok)
        entry["run"] = run.run_id
        if o.dry_run:
            planned = feed.plan(run)
            log(f"dry run: feed {spec.name} ({spec.title}), business date {run.business_date}, window {run.window()[0]}..{run.window()[1]}"
                f"{' (--since)' if o.since else ''}, datasets {', '.join(d.table for d in spec.datasets.values())} -> lake {lake} by {route}"
                f"{' [snapshot]' if o.snapshot else ''}")
            for p in planned[:50]:
                log(f"  {p}")
            if len(planned) > 50:
                log(f"  ... and {len(planned) - 50} more")
            if spec.internal_only:
                log(f"  internal only: {spec.licence.name} ({spec.licence.url})")
            entry.update(status="dry-run", exit=FB.OK, planned=len(planned))
            return _done(entry, t0, ledger, log, record=False)
        with FB.Lock(run.folder):
            if o.snapshot:
                rc = feed.snapshot(run)
                entry.update(status="ok" if rc == 0 else "failed", exit=FB.OK if rc == 0 else FB.FAILED, snapshot=True,
                             load={"route": "none", "status": "ok", "rows": 0})
                if rc != 0:
                    entry["error"] = f"the snapshot refresh exited {rc}"
                return _done(entry, t0, ledger, log)
            why = None
            try:
                feed.pull(run)
            except FB.FeedRefused:
                run.abandon()
                raise
            except Exception as e:  # noqa: BLE001
                run.abandon()
                if not run.http.failed:
                    raise
                why = f" ({e})"
            failed = list(run.http.failed)
            if failed:
                if why is None:
                    run.abandon()
                raise FB.FeedError(f"{len(failed)} request(s) failed after retries, first {failed[0]} (the downloads so far are cached: "
                                   f"run again to resume){why or ''}")
            files = run.finish()
            entry["files"] = len(files)
            entry["rows"] = sum(f["rows"] for f in files)
            entry["dates"] = run.dates
            for f in files:
                d = entry["datasets"].setdefault(f["dataset"], {"rows": 0, "table": f["table"]})
                d["rows"] += f["rows"]
            entry["folder"] = str(run.folder)
            problems = feed.check(run)
            if problems:
                raise FB.FeedError("check: " + "; ".join(problems[:5]))
            res = L.load(route, files, lake, spec.name, run.run_id, run.folder / "loads" / run.run_id, o.load_opts, log)
            entry["load"] = {k: v for k, v in res.items() if k != "loads"} | {"count": len(res.get("loads", []))}
            if res["status"] == "failed":
                entry.update(status="failed", exit=FB.FAILED, error="load: " + str(res.get("error")))
                return _done(entry, t0, ledger, log)
            run.save_state()
            entry.update(status="ok" if res["status"] == "ok" else "partial", exit=FB.OK if res["status"] == "ok" else FB.PARTIAL)
            if res["status"] == "rejects":
                entry["error"] = f"{res.get('rejected')} row(s) rejected by the load"
            return _done(entry, t0, ledger, log)
    except FB.FeedLocked as e:
        entry.update(status="locked", exit=FB.LOCKED, error=str(e))
    except FB.FeedRefused as e:
        entry.update(status="refused", exit=FB.REFUSED, error=str(e))
    except FB.FeedError as e:
        entry.update(status="failed", exit=FB.FAILED, error=str(e))
    except Exception as e:  # noqa: BLE001  (a feed's own bug: recorded, never a stack trace in a scheduler's mail)
        entry.update(status="failed", exit=FB.FAILED, error=f"{type(e).__name__}: {e}")
        if os.environ.get("DRISHTI_FEEDS_DEBUG"):
            traceback.print_exc()
    return _done(entry, t0, ledger, log)


def _done(entry: dict, t0: float, ledger: Ledger, log, record: bool = True) -> tuple[int, dict]:
    entry["seconds"] = round(time.monotonic() - t0, 1)
    if record:
        ledger.append(entry)
    log(log_line(entry))
    return entry["exit"], entry
