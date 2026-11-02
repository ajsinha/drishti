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


"""`drishti.py lake memory status|jobs|refill`: the in-memory lake over REST (administrators; docs/guides/IN_MEMORY_LAKE_GUIDE.md).

    status   every memory store (cap, use, contract, refusals) and table (state, bytes, rows, version, refills, evictions, ratio), health
    jobs     the replenish jobs' runs, newest first (rows, bytes, version, time taken, why a run failed or was refused); --table NAME
    refill   queue a run of a table's job now (audited as lake.memory.refill)

The data itself never comes through these commands: ids and counts only. Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

EPILOG = ("examples:\n  drishti.py lake memory status\n  drishti.py lake memory jobs --table news --limit 20\n"
          "  drishti.py lake memory refill news\n  drishti.py lake memory status --json")


def mb(n) -> str:
    return f"{(n or 0) / 1048576:,.1f} MB"


def status_text(s: dict, say) -> None:
    stores = s.get("stores") or []
    say(f"MEMORY STORES ({len(stores)})")
    for st in stores:
        cap = st.get("capBytes") or 0
        used = st.get("usedBytes") or 0
        share = f"{100 * used / cap:.0f}%" if cap else "-"
        say(f"  {st['name']:<16} {'owner' if st.get('owner') else 'remote':<7} {mb(used):>12} of {mb(cap):>12} ({share})  "
            f"files {st.get('files', 0)}  refusals {st.get('refusals', 0)}" + (f"  contract {st['contract']}" if st.get("contract") else ""))
        if st.get("lastRefusal"):
            say(f"    last refusal: {st['lastRefusal']}")
    tables = s.get("tables") or []
    say("")
    say(f"TABLES ({len(tables)})")
    for t in tables:
        ratio = f"{t['ratio']}x" if t.get("ratio") else "-"
        say(f"  {t['name']:<20} {t.get('state', ''):<10} {mb(t.get('bytes')):>12}  rows {t.get('rows', 0):>9,}  v{t.get('version')}  "
            f"ratio {ratio:<6} {'pinned ' if t.get('pinned') else ''}evictions {t.get('evictions', 0)}  refusals {t.get('refusals', 0)}  "
            f"compactions {t.get('compactions', 0)}")
        say(f"    {t.get('path')}  ←  {t.get('source')}  ({t.get('mode')})  last {t.get('lastRefill') or 'never'}  next {t.get('nextRefill') or '-'}")
        if t.get("words"):
            say(f"    {t['words']}")
    h = s.get("health") or {}
    say("")
    say(f"HEALTH  {h.get('state', 'green')}")
    for p in h.get("problems") or []:
        say(f"  - {p}")
    c = s.get("compaction") or {}
    if c:
        say(f"COMPACTION  {'on' if c.get('enabled') else 'off'}{' (idle only)' if c.get('idleOnly') else ''}: {c.get('rewrites', 0)} rewrites, "
            f"{mb(c.get('savedTotalBytes'))} saved")
    g = s.get("guard") or {}
    if g:
        say(f"GUARD  {g.get('refusals', 0)} writes refused, {g.get('downloads', 0)} downloads of memory-only data")


def jobs_text(runs: list, say) -> None:
    say(f"RUNS ({len(runs)})")
    for r in runs:
        say(f"  {r.get('at', '')[:19]}  {r.get('table', ''):<20} {r.get('trigger', ''):<16} {r.get('outcome', ''):<8} rows {r.get('rows', 0):>9,}  "
            f"{mb(r.get('bytes')):>10}  v{r.get('version')}  {r.get('millis', 0):,} ms" + (f"  {r['message']}" if r.get("message") else ""))


def register(sub, add, srv, ctx: dict) -> None:
    """Adds ``lake memory`` to the lake group (``sub`` its subparsers)."""
    import argparse
    import urllib.parse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        if a.action == "status":
            s = api.json("GET", "/api/v1/admin/lakes/memory")
            if a.json:
                say_json(s)
            else:
                status_text(s, print)
            return 0
        if a.action == "jobs":
            q = {"limit": a.limit}
            if a.table:
                q["table"] = a.table
            runs = api.json("GET", "/api/v1/admin/lakes/memory/jobs?" + urllib.parse.urlencode(q)).get("runs", [])
            if a.json:
                say_json(runs)
            else:
                jobs_text(runs, print)
            return 0
        if not a.table:
            raise CliError("lake memory refill: name the table (drishti.py lake memory refill news)", 2)
        out = api.json("POST", "/api/v1/admin/lakes/memory/refill/" + urllib.parse.quote(a.table, safe=""))
        print(f"{out.get('table')}: a refill is queued (drishti.py lake memory jobs --table {out.get('table')} shows its run)")
        return 0

    p = sub.add_parser("memory", parents=[srv], help="lake memory status|jobs|refill: the in-memory lake's stores, tables and replenish jobs",
                       description=__doc__.split("\n\n")[1] if "\n\n" in __doc__ else __doc__, epilog=EPILOG,
                       formatter_class=argparse.RawDescriptionHelpFormatter)
    p.set_defaults(func=cmd)
    p.add_argument("action", choices=("status", "jobs", "refill"))
    p.add_argument("table", nargs="?", help="refill: the table; jobs: only this table's runs")
    p.add_argument("--table", dest="table_opt", help=argparse.SUPPRESS)
    p.add_argument("--limit", type=int, default=50, help="jobs: the runs shown (default 50)")

    def normalise(a, extra):
        if getattr(a, "table_opt", None) and not a.table:
            a.table = a.table_opt
        return cmd(a, extra)

    p.set_defaults(func=normalise)
