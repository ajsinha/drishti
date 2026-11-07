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

"""`drishti.py dataload status|run|retry`: the data inbox from the command line (docs/guides/DATA_LAKE_GUIDE.md; docs/guides/DATA_GUIDE.md,
"The data inbox"; the API: /api/v1/admin/dataload, administrators, or a token with loads:write for run and retry).

    status [--json]          the files waiting and why, the last cycles, the failures with their reasons, the lock, the next cycle
    run                      Load now: a cycle at once; while a run still loads, none starts and it says so
    retry <file>             a failed file and its .ready back into the inbox; the next cycle (or run) loads it

Exit codes: 0 done, 1 the server said no, 2 usage, 3 the inbox is off on every server (DRS-1020)."""
from __future__ import annotations

EPILOG = ("examples:\n  drishti.py dataload status\n  drishti.py dataload run\n  drishti.py dataload retry trades-endur-2026-11-03.jsonl.gz\n"
          "  drishti.py dataload status --json | jq '.failed'")


def _size(n) -> str:
    if n is None:
        return "-"
    n = int(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return "-"


def render(st: dict) -> list[str]:
    """The status as a person reads it: the state line, the files waiting, the failures, the last cycles."""
    out = []
    state = "on" if st.get("on") else "off"
    if st.get("on") and not st.get("owner"):
        state += " (not the owner: cycles are skipped here)"
    out.append(f"data inbox {state}, node {st.get('node', '-')}, every {st.get('interval', '-')}, folder {st.get('dir', '-')}")
    run = st.get("running")
    if run:
        out.append(f"  a cycle is running since {run.get('startedAt')} ({run.get('trigger')})")
    elif st.get("nextCycleAt"):
        out.append(f"  next cycle at {st['nextCycleAt']}")
    lock = st.get("lock")
    if lock:
        out.append(f"  folder lock held by {lock.get('node')} (process {lock.get('pid')}), heartbeat {lock.get('heartbeat')}")
    if st.get("readyAll"):
        out.append("  ready.all is in the inbox: every file there loads at the next cycle")
    waiting = st.get("waiting") or []
    out.append(f"waiting ({len(waiting)}):" if waiting else "waiting: none")
    dates = {b.get("file"): (b.get("rule") or {}).get("date") for b in ((st.get("board") or {}).get("waiting") or [])}
    for w in waiting:
        out.append(f"  {w.get('file', ''):<44}{_size(w.get('size')):>10}  {w.get('state', '')}")
        if dates.get(w.get("file")):
            out.append(f"  {'':<44}{'':>10}  business date: {dates[w.get('file')]}")
    for m in st.get("markersWithoutFile") or []:
        out.append(f"  {m:<44}{'':>10}  a marker without its file")
    failed = st.get("failed") or []
    out.append(f"failed ({len(failed)}; drishti.py dataload retry <file>):" if failed else "failed: none")
    for f in failed:
        out.append(f"  {f.get('file', ''):<44}{f.get('day', '')}  {f.get('reason', '')}")
    cycles = st.get("cycles") or []
    out.append("last cycles:" if cycles else "last cycles: none since the server started")
    for c in cycles[:10]:
        files = c.get("files") or []
        counts: dict[str, int] = {}
        for f in files:
            counts[f.get("outcome", "")] = counts.get(f.get("outcome", ""), 0) + 1
        what = ", ".join(f"{n} {k}" for k, n in sorted(counts.items())) or (c.get("why") or "")
        out.append(f"  {str(c.get('started', ''))[:19].replace('T', ' ')}  {c.get('run', ''):<34} {c.get('state', ''):<9}{what}")
        for f in files:
            if f.get("dateNote"):
                out.append(f"    {f['dateNote']}")
        for n in c.get("notices") or []:
            out.append(f"    notice: {n}")
    if st.get("error"):
        out.append("error: " + st["error"])
    return out


def register(groups, srv, ctx: dict) -> None:
    """Adds ``dataload status|run|retry``."""
    import argparse

    Api, CliError, ApiError, say_json = ctx["Api"], ctx["CliError"], ctx["ApiError"], ctx["say_json"]

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        act = a.dataload_cmd
        try:
            if act == "run":
                res = api.json("POST", "/api/v1/admin/dataload/run", {})
                if a.json:
                    say_json(res)
                elif res.get("started"):
                    print("Load now: a cycle started (drishti.py dataload status shows it).")
                else:
                    print(f"Load now: {res.get('skipped')} (runs never overlap; the next cycle checks again).")
                return 0
            if act == "retry":
                res = api.json("POST", "/api/v1/admin/dataload/retry", {"file": a.file})
                if a.json:
                    say_json(res)
                else:
                    print(f"{res.get('file')}: back in the inbox (from {res.get('from')}); {res.get('next')}.")
                return 0
            st = api.json("GET", "/api/v1/admin/dataload")
            if a.json:
                say_json(st)
                return 0
            print("\n".join(render(st)))
            return 0
        except ApiError as e:
            if e.status == 404 and act == "retry":
                raise CliError(f"no failed file is named {a.file} (drishti.py dataload status lists them)", 1) from None
            raise

    p = groups.add_parser("dataload", help="dataload status|run|retry: the data inbox (files dropped in <data>/dataload/inbox, loaded into the lake)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="dataload_cmd", metavar="ACTION", required=True)
    for act, what in (("status", "the files waiting, the last cycles, the failures with their reasons"), ("run", "Load now: a cycle at once"),
                      ("retry", "a failed file and its .ready back into the inbox")):
        c = sub.add_parser(act, parents=[srv], help=what, description=what, epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
        c.set_defaults(func=cmd)
        if act == "retry":
            c.add_argument("file", help="the failed file's name as it was dropped (trades-2026-11-03.jsonl.gz)")
