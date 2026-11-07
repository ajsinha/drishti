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

"""feed: pull external data and load it into the lakes (drishti-feeds; docs/guides/FEEDS_GUIDE.md).

    python3 tools/drishti.py feed list                                    every feed: lake, route, schedule, licence
    python3 tools/drishti.py feed run gleif                               pull today, write data/feeds/gleif/<date>/, load (java)
    python3 tools/drishti.py feed run --all                               every enabled feed, one after another
    python3 tools/drishti.py feed run us-equities --date 2026-11-06 --load inbox
    python3 tools/drishti.py feed run world-economy --since 2026-10-01 --load python
    python3 tools/drishti.py feed run gleif --dry-run                     what would be fetched and loaded; nothing changes
    python3 tools/drishti.py feed run gleif --snapshot                    refresh the committed demo snapshot (data/testdata), no load
    python3 tools/drishti.py feed run gleif -- --scope GROUP:784F5XWPLTWKTBV3E584    arguments after -- go to the source
    python3 tools/drishti.py feed status                                  last success, rows, duration, failures per feed
    python3 tools/drishti.py feed schedule --cron                         daily crontab lines (--systemd, --windows)

    tools/feeds/feed.sh <the same arguments>                              Linux and macOS (the console's Python)
    .\\tools\\windows\\feed.ps1 <the same arguments>                         Windows (PowerShell)

Exit codes (for schedulers): 0 ok, 1 failed, 2 refused or a usage error (a bad feed file, a key not set, an unknown feed), 3 another
run of the feed holds its lock, 4 partial (some feeds of --all failed, or the load rejected rows). Every run prints one log line and
appends one line to <data>/feeds/ledger.jsonl (Admin → Feeds)."""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import feedbase as FB  # noqa: E402
import feedrunner as R  # noqa: E402
import feedschedule as S  # noqa: E402


def _date(text: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"{text}: give a date as YYYY-MM-DD")


def _sets(items: list[str] | None) -> dict:
    out = {}
    for kv in items or []:
        if "=" not in kv:
            raise FB.FeedRefused(f"--set {kv}: give NAME=VALUE")
        k, v = kv.split("=", 1)
        try:
            out[k.strip()] = json.loads(v)
        except ValueError:
            out[k.strip()] = v
    return out


def cmd_list(a, extra=None) -> int:
    specs = FB.all_specs(a.config)
    if a.json:
        print(json.dumps([{"feed": s.name, "title": s.title, "lake": s.lake, "load": s.load, "enabled": s.enabled,
                           "schedule": {"at": s.schedule_at, "days": s.schedule_days}, "licence": FB.licence_doc(s),
                           "datasets": {d.name: {"pack": d.pack, "kind": d.kind, "table": d.table} for d in s.datasets.values()},
                           "keys": s.keys, "file": str(s.path)} for s in specs], indent=1))
        return 0
    rows = [("FEED", "LAKE", "LOAD", "SCHEDULE", "LICENCE", "USE", "DATASETS")]
    for s in specs:
        rows.append((s.name + ("" if s.enabled else " (off)"), s.lake, s.load, f"{s.schedule_days} {s.schedule_at}", s.licence.name[:34],
                     "internal only" if s.internal_only else s.licence.redistribution, ", ".join(d.table for d in s.datasets.values())))
    _table(rows)
    return 0


def _table(rows: list[tuple]) -> None:
    w = [max(len(str(r[i])) for r in rows) for i in range(len(rows[0]))]
    for r in rows:
        print("  ".join(str(c).ljust(w[i]) for i, c in enumerate(r)).rstrip())


def options(a) -> R.RunOptions:
    if a.date and a.since and a.since > a.date:
        raise FB.FeedRefused("--since is after --date")
    return R.RunOptions(business_date=a.date, since=a.since, load=a.load, dry_run=a.dry_run, snapshot=a.snapshot,
                        internal_ok=a.internal_ok, offline=a.offline, sets=_sets(a.set), extra_args=list(a.rest or []), lake=a.lake,
                        load_opts={k: getattr(a, k) for k in ("inbox", "lake_root", "jar", "java", "signal", "token_file") if getattr(a, k)},
                        trigger=a.trigger)


def cmd_run(a, extra=None) -> int:
    rest = list(a.rest or []) + list(extra or [])
    if a.all and a.feed:
        rest = [a.feed] + rest
        a.feed = None
    a.rest = rest
    if not a.all and not a.feed:
        print("feed run: name a feed, or --all (drishti.py feed list shows them)", file=sys.stderr)
        return FB.REFUSED
    try:
        o = options(a)
        specs = [s for s in FB.all_specs(a.config) if s.enabled] if a.all else [FB.load_spec(a.feed, a.config)]
    except FB.FeedRefused as e:
        print(f"feed run: {e}", file=sys.stderr)
        return FB.REFUSED
    if a.all and o.extra_args:
        print("feed run --all: arguments for a source need one feed", file=sys.stderr)
        return FB.REFUSED
    codes, entries = [], []
    for s in specs:
        rc, entry = R.run_feed(s, o, log=print)
        codes.append(rc)
        entries.append(entry)
    if a.json:
        print(json.dumps(entries if a.all else entries[0], indent=1, default=str))
    if not codes:
        return FB.OK
    if all(c == codes[0] for c in codes):
        return codes[0]
    return FB.PARTIAL


def cmd_status(a, extra=None) -> int:
    specs = FB.all_specs(a.config)
    if a.feed:
        specs = [s for s in specs if s.name == a.feed]
        if not specs:
            print(f"feed status: no feed '{a.feed}'", file=sys.stderr)
            return FB.REFUSED
    led = R.Ledger()
    rows = led.summary(specs)
    if a.json:
        print(json.dumps({"feeds": rows, "ledger": str(led.path), "runs": led.entries(a.feed, a.runs) if a.runs else []}, indent=1, default=str))
        return 0
    out = [("FEED", "LAST RUN", "STATUS", "LAST SUCCESS", "DATE", "ROWS", "SECONDS", "FAILURES")]
    for r in rows:
        out.append((r["feed"], (r["lastRun"] or "never")[:19], r["lastStatus"] or "-", (r["lastSuccess"] or "never")[:19],
                    r["businessDate"] or "-", f"{r['rows']:,}" if r["rows"] is not None else "-", r["seconds"] if r["seconds"] is not None else "-",
                    r["failuresSinceSuccess"]))
    _table(out)
    for r in rows:
        if r["lastError"]:
            print(f"{r['feed']}: {r['lastError']}")
    print(f"ledger: {led.path}")
    if a.runs:
        for e in led.entries(a.feed, a.runs):
            print(f"{e.get('at')} {e.get('feed')} {e.get('status')} exit={e.get('exit')} date={e.get('businessDate')} rows={e.get('rows')} "
                  f"seconds={e.get('seconds')}" + (f" error={e['error']}" if e.get("error") else ""))
    return 1 if any(r["lastStatus"] in ("failed", "refused") for r in rows) else 0


def cmd_schedule(a, extra=None) -> int:
    specs = FB.all_specs(a.config)
    if a.feeds:
        unknown = sorted(set(a.feeds) - {s.name for s in specs})
        if unknown:
            print(f"feed schedule: no feed {', '.join(unknown)}", file=sys.stderr)
            return FB.REFUSED
        specs = [s for s in specs if s.name in a.feeds]
    if a.windows:
        sys.stdout.write(S.windows(specs))
    elif a.systemd:
        sys.stdout.write(S.systemd(specs))
    else:
        sys.stdout.write(S.cron(specs, data=a.log_dir))
    return 0


def register(groups, add=None) -> argparse.ArgumentParser:
    """Adds ``feed list|run|status|schedule`` to the drishti.py command line."""
    epilog = __doc__.split("\n\n", 1)[1]
    g = groups.add_parser("feed", help="feed list|run|status|schedule: pull external data (GLEIF, SEC, IMF, ...) and load it into the lakes",
                          description=__doc__.split("\n\n")[0], epilog=epilog, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = g.add_subparsers(dest="cmd", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--config", help="the feed files' folder (default config/feeds)")

    p = sub.add_parser("list", help="every feed: lake, load route, schedule, licence, datasets", parents=[common])
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("run", help="pull a feed (or --all), write its dated JSONL files, load them into its lake", parents=[common],
                       epilog=epilog, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("feed", nargs="?", help="the feed (drishti.py feed list)")
    p.add_argument("rest", nargs="*", help=argparse.SUPPRESS)
    p.add_argument("--all", action="store_true", help="every enabled feed, one after another")
    p.add_argument("--date", type=_date, metavar="YYYY-MM-DD", help="the business date (default today)")
    p.add_argument("--since", type=_date, metavar="YYYY-MM-DD", help="pull history from this date (default: the day after the state's last)")
    p.add_argument("--load", choices=FB.ROUTES, help="java (the parallel loader, the default), python (lake build), inbox, or none")
    p.add_argument("--lake", help="load into this lake instead of the feed file's")
    p.add_argument("--dry-run", action="store_true", help="say what would be fetched and loaded; change nothing")
    p.add_argument("--snapshot", action="store_true", help="refresh the committed demo snapshot (data/testdata) as the source always did; no load")
    p.add_argument("--internal-ok", action="store_true", help="with --snapshot: allow internal-only data into the committed folder (internal demos)")
    p.add_argument("--offline", action="store_true", help="use the download cache only; fetch nothing")
    p.add_argument("--set", action="append", metavar="NAME=VALUE", help="override an option of the feed file (JSON values allowed)")
    p.add_argument("--inbox", metavar="DIR", help="--load inbox: the inbox folder (default <data>/dataload/inbox)")
    p.add_argument("--lake-root", metavar="DIR", help="java/python: write the lake's layout under this folder instead (a scratch copy)")
    p.add_argument("--jar", help="the server's exec jar (default DRISHTI_JAR, then drishti-server/target)")
    p.add_argument("--java", help="the java binary (default JAVA_HOME, then java on the PATH)")
    p.add_argument("--signal", metavar="URL", help="java/python: tell this server the data landed (a token with loads:write)")
    p.add_argument("--token-file", help="the API token for --signal (default DRISHTI_TOKEN)")
    p.add_argument("--trigger", default="cli", choices=("cli", "schedule", "admin"), help=argparse.SUPPRESS)
    p.add_argument("--json", action="store_true", help="print the ledger entry (entries with --all) as JSON")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("status", help="per feed: last run, last success, rows, duration, failures (from the run ledger)", parents=[common])
    p.add_argument("feed", nargs="?")
    p.add_argument("--runs", type=int, default=0, metavar="N", help="also the last N runs")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("schedule", help="print ready-to-use daily entries: --cron, --systemd or --windows", parents=[common])
    p.add_argument("feeds", nargs="*", help="only these feeds (default every one)")
    m = p.add_mutually_exclusive_group(required=True)
    m.add_argument("--cron", action="store_true", help="crontab lines (Linux, macOS)")
    m.add_argument("--systemd", action="store_true", help="a service and a timer per feed")
    m.add_argument("--windows", action="store_true", help="schtasks commands (Windows Task Scheduler)")
    p.add_argument("--log-dir", help="--cron: the folder of the feeds' log files (default $HOME/drishti-feeds-logs)")
    p.set_defaults(func=cmd_schedule)
    return g


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="feed")
    register(ap.add_subparsers(dest="group", required=True))
    a, extra = ap.parse_known_args(["feed"] + list(sys.argv[1:] if argv is None else argv))
    if extra and extra[0] == "--":
        extra = extra[1:]
    if extra and a.cmd != "run":
        ap.error("unrecognized arguments: " + " ".join(extra))
    return a.func(a, extra)


if __name__ == "__main__":
    sys.exit(main())
