#!/usr/bin/env python3
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

"""Runs the public-sector feeds (docs/guides/PUBLIC_SECTOR_GUIDE.md section 5): pull what is new from the source, write one JSONL.gz
per kind with its .ready file, and load it into the feed's lake (worldlake or newslake).

    python3 tools/feeds/publicsector/run.py list                          the feeds, their lake, schedule, licences and keys
    python3 tools/feeds/publicsector/run.py run world-development         pull and write under <data>/feeds/world-development/out/<date>/
    python3 tools/feeds/publicsector/run.py run --all --inbox             write into the data inbox (<data>/dataload/inbox): the server loads
    python3 tools/feeds/publicsector/run.py run central-banks --load      write, then load into the lake now (drishti.py lake build)
    python3 tools/feeds/publicsector/run.py run oecd --dry-run            what it would ask, writing nothing
    python3 tools/feeds/publicsector/run.py run gdelt-events --fixtures DIR   answer from recorded responses (tests; never the network)
    python3 tools/feeds/publicsector/run.py run oecd --record DIR         call the network and keep every response as a fixture

Exit codes (scheduler friendly): 0 written or nothing new, 2 the pull failed (said in words), 3 a key it needs is not set.
State (what the last pull saw) is <data>/feeds/<feed>/state.json; --full forgets it once.
"""
from __future__ import annotations

import argparse
import importlib
import json
import os
import subprocess
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from base import ROOT, SPECS, FeedError, Fetch, Fixtures, Recorder, data_dir, feed_names, load_spec, now, today, write_outputs  # noqa: E402

MODULES = {"world-development": ("world_development", "WorldDevelopment"), "central-banks": ("central_banks", "CentralBanks"),
           "national-statistics": ("national_statistics", "NationalStatistics"), "oecd": ("oecd", "Oecd"),
           "official-news": ("official_news", "OfficialNews"), "gdelt-events": ("gdelt_events", "GdeltEvents")}


def feed_class(name: str):
    mod, cls = MODULES[name]
    return getattr(importlib.import_module(mod), cls)


def missing_keys(spec: dict) -> list[str]:
    """The environment variables the feed needs and does not have (a key is never written in the YAML)."""
    return [k["env"] for k in spec.get("keys") or [] if k.get("required", True) and not os.environ.get(k["env"])]


def run_one(name: str, a, log=print) -> int:
    spec = load_spec(name, Path(a.specs))
    if a.selection:
        narrow = yaml.safe_load(Path(a.selection).read_text(encoding="utf-8")) or {}
        spec["selection"] = {**(spec.get("selection") or {}), **(narrow.get(name) or {})}
    as_of = a.as_of or today()
    if a.fixtures:
        opener = Fixtures(_per_feed(Path(a.fixtures), name))
    elif a.record:
        opener = Recorder(Path(a.record) / name)
    else:
        opener = None
    lacking = missing_keys(spec)
    if lacking and not a.fixtures:
        log(f"{name}: needs {', '.join(lacking)} (see config/feeds/{name}.yaml keys:): not run")
        return 3
    limits = spec.get("limits") or {}
    fetch = Fetch(**({"opener": opener} if opener else {}), pause=float(limits.get("pause-seconds", 0.5)),
                  timeout=float(limits.get("timeout-seconds", 90)), log=log)
    home = Path(a.state_dir) if a.state_dir else data_dir() / "feeds" / name
    state_file = home / "state.json"
    state = {} if a.full or not state_file.is_file() else json.loads(state_file.read_text(encoding="utf-8"))
    if a.full:
        state["force"] = True
    state.setdefault("dir", str(home))
    feed = feed_class(name)(spec, fetch, as_of, log)
    if a.dry_run:
        log(f"{name}: would pull {spec.get('title')} into lake {spec['lake']} for {as_of} (state {state_file})")
        return 0
    try:
        docs = feed.pull(state)
    except FeedError as e:
        log(f"{name}: FAILED: {e}")
        return 2
    if not any(docs.values()):
        log(f"{name}: nothing new for {as_of}")
        _save(state_file, state, as_of, [])
        return 0
    out = Path(a.out) if a.out else (data_dir() / "dataload" / "inbox" if a.inbox else home / "out" / as_of)
    written = write_outputs(spec, docs, out, as_of)
    for w in written:
        log(f"{name}: {w['kind']}: {w['rows']} rows -> {w['file']}")
    _save(state_file, state, as_of, written)
    if a.load:
        for w in written:
            r = w["ready"]
            cmd = [sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "build", "--from", w["file"], "--pack",
                   str(ROOT / "config" / "packs" / r["pack"]), "--kind", r["kind"], "--connector", r["connector"], "--lake", r["lake"],
                   "--as-of", as_of, "--key", r["key"]]
            if a.lake_root:
                cmd += ["--lake-root", a.lake_root]
            p = subprocess.run(cmd, capture_output=True, text=True)
            if p.returncode != 0:
                log(f"{name}: load of {w['kind']} FAILED:\n{p.stdout[-1500:]}{p.stderr[-1500:]}")
                return 2
            log(f"{name}: loaded {w['kind']} into {r['lake']}")
    return 0


def _per_feed(folder: Path, name: str) -> Path:
    """A fixtures folder holds one feed's recordings, or one sub-folder a feed (<folder>/<feed>/index.json)."""
    return folder / name if (folder / name / "index.json").is_file() else folder


def _save(state_file: Path, state: dict, as_of: str, written: list) -> None:
    state.pop("dir", None)
    state["lastRun"], state["lastRunAt"] = as_of, now()
    state["lastWritten"] = [{k: w[k] for k in ("kind", "file", "rows", "sha256")} for w in written] or state.get("lastWritten", [])
    state_file.parent.mkdir(parents=True, exist_ok=True)
    tmp = state_file.with_name("state.json.part")
    tmp.write_text(json.dumps(state, indent=1, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(state_file)


def list_feeds(specs: Path, log=print) -> int:
    for name in feed_names(specs):
        s = load_spec(name, specs, env={})
        lic = "; ".join(f"{x.get('name')}: {x.get('licence')}{' (internal only)' if x.get('internal-only') else ''}" for x in s.get("sources") or [])
        keys = ", ".join(k["env"] for k in s.get("keys") or []) or "none"
        log(f"{name:20} lake {s['lake']:10} {s.get('schedule', {}).get('cron', ''):12} keys {keys}\n{'':20} {lic}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    lp = sub.add_parser("list", help="the feeds, their lakes, schedules, licences and keys")
    lp.add_argument("--specs", default=str(SPECS))
    r = sub.add_parser("run", help="pull, write and (with --load or --inbox) load")
    r.add_argument("feeds", nargs="*", help="one or more of: " + ", ".join(MODULES))
    r.add_argument("--all", action="store_true", help="every public-sector feed")
    r.add_argument("--as-of", help="the business date written (default today)")
    r.add_argument("--out", help="where the JSONL.gz and .ready files go (default <data>/feeds/<feed>/out/<date>)")
    r.add_argument("--inbox", action="store_true", help="write into the data inbox (<data>/dataload/inbox): the server loads it")
    r.add_argument("--load", action="store_true", help="load into the lake now (drishti.py lake build)")
    r.add_argument("--lake-root", help="with --load: write the lake under this folder (a scratch lake)")
    r.add_argument("--state-dir", help="the feed's state folder (default <data>/feeds/<feed>)")
    r.add_argument("--full", action="store_true", help="forget the last pull's state: pull the whole selection again")
    r.add_argument("--fixtures", help="answer from recorded responses in this folder (tests): never the network")
    r.add_argument("--record", help="call the network and keep every response in this folder as a fixture")
    r.add_argument("--selection", help="a YAML {feed: {selection keys}} merged over each feed's selection (a narrower pull)")
    r.add_argument("--dry-run", action="store_true")
    r.add_argument("--specs", default=str(SPECS))
    a = ap.parse_args(argv)
    if a.cmd == "list":
        return list_feeds(Path(a.specs))
    names = feed_names(Path(a.specs)) if a.all else a.feeds
    if not names:
        ap.error("name a feed, or --all")
    unknown = [n for n in names if n not in MODULES]
    if unknown:
        ap.error(f"unknown feed {', '.join(unknown)}; the public-sector feeds are {', '.join(MODULES)}")
    worst = 0
    for n in names:
        worst = max(worst, run_one(n, a))
    return worst


if __name__ == "__main__":
    sys.exit(main())
