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

"""`drishti.py history KIND ID`: one entity over business days, as the console's history page shows it (HISTORY_QUERIES.md
section 15.3). The server's own answer (GET /api/v1/history/{kind}/{id}) for the caller's token, so masks apply as in the browser.

Text is a table, newest first, a changed cell marked `*`; --json prints the answer; --csv writes the table as CSV (UTF-8 with a BOM,
CRLF, text a spreadsheet would run as a formula kept as text). Exit codes: 0, 1 (the server refused or some days could not be read),
2 (usage)."""
from __future__ import annotations

import csv
import io
import sys
import urllib.parse

EPILOG = ("examples:\n  drishti.py history trade 1000015 --days 30\n  drishti.py history equity EQ-CSCA --range 3M --fields PRICE,CHANGE_1D\n"
          "  drishti.py history trade 1000015 --from 2026-09-01 --to 2026-09-30 --diff\n  drishti.py history trade 1000015 --days 30 --changes-only --json\n"
          "  drishti.py history trade 1000015 --days 30 --csv > trade-1000015.csv")


def path_for(kind: str, id_: str, a) -> str:
    """The server's path and query for the arguments; an id a path cannot carry goes in the query (``~``)."""
    q = lambda s: urllib.parse.quote(s, safe="")   # noqa: E731
    params = []
    for key, value in (("days", a.days), ("range", a.range), ("from", a.from_), ("to", a.to), ("fields", a.fields), ("knownAt", a.known_at)):
        if value not in (None, ""):
            params.append((key, str(value)))
    if a.diff:
        params.append(("diff", "true"))
    if not any(k in ("days", "range", "from") for k, _ in params):
        params.insert(0, ("days", "30"))
    where = f"{q(kind)}/{q(id_)}"
    if "/" in id_ or "\\" in id_ or id_ in (".", "..", "~"):
        where = f"{q(kind)}/~"
        params.append(("id", id_))
    return f"/api/v1/history/{where}?" + urllib.parse.urlencode(params)


def rows_of(res: dict, changes_only: bool) -> tuple[list[str], list[list[str]]]:
    """The table, newest first: Date, each column (its shown text, ``*`` after a changed one), each part's count."""
    cols = res.get("columns") or []
    parts = res.get("parts") or []
    header = ["Date"] + [c.get("label") or c["path"] for c in cols] + [p.get("label") or p["path"] for p in parts]
    out = []
    for r in reversed(res.get("rows") or []):
        changed = set(r.get("changed") or [])
        if changes_only and not changed:
            continue
        disp, vals = r.get("display") or {}, r.get("values") or {}
        line = [r["date"] + (f" ⟲{r['versions']}" if r.get("restated") else "")]
        for c in cols:
            text = disp.get(c["path"])
            if text is None:
                v = vals.get(c["path"])
                text = "" if v is None else str(v)
            line.append(text + ("*" if c["path"] in changed else ""))
        for p in parts:
            n = (r.get("parts") or {}).get(p["path"])
            line.append(("" if n is None else str(n)) + ("*" if "parts." + p["path"] in changed else ""))
        out.append(line)
    return header, out


def render(res: dict, say, changes_only: bool = False) -> None:
    w = res.get("window") or {}
    ref = res.get("ref") or {}
    total = len(res.get("rows") or [])
    say(f"{res.get('mnemonic') or ref.get('kind')} {ref.get('id')}  HISTORY  {w.get('from')} -> {w.get('to')}  "
        f"{total} days with data, {res.get('changedDays', 0)} changed" + (f", no data on {', '.join(w['missing'])}" if w.get("missing") else ""))
    if res.get("title"):
        say(res["title"])
    s = res.get("summary") or {}
    if s.get("field"):
        say(f"{s['field']}: last {s.get('last')}  chg {s.get('change')} ({s.get('changePct')}%)  high {s.get('high')} ({s.get('highDate')})  "
            f"low {s.get('low')} ({s.get('lowDate')})  range {s.get('range')}  avg {s.get('average')}")
    header, rows = rows_of(res, changes_only)
    widths = [max([len(h)] + [len(r[i]) for r in rows]) for i, h in enumerate(header)]
    say("  ".join(h.ljust(widths[i]) for i, h in enumerate(header)).rstrip())
    say("  ".join("-" * x for x in widths))
    for r in rows:
        say("  ".join(c.ljust(widths[i]) for i, c in enumerate(r)).rstrip())
    if changes_only:
        say(f"{len(rows)} of {total} days changed")
    if any(r.get("changes") for r in res.get("rows") or []):
        for r in reversed(res.get("rows") or []):
            if r.get("changes"):
                say(f"{r['date']}:")
                for c in r["changes"]:
                    say(f"  {c.get('label') or c.get('path')}  {c.get('before')} -> {c.get('after')}" + (f"  {c['delta']:+}" if isinstance(c.get("delta"), (int, float)) else ""))
    for p in res.get("problems") or []:
        say(f"problem {p.get('code')}: {p.get('message')} {', '.join(p.get('days') or [])}".rstrip())
    if res.get("truncated"):
        say("only the newest rows are shown: narrow the range with --from/--to")


def to_csv(res: dict, changes_only: bool = False) -> str:
    cols = res.get("columns") or []
    parts = res.get("parts") or []
    header = ["Date"] + [c.get("label") or c["path"] for c in cols] + [p.get("label") or p["path"] for p in parts] + ["Changed"]
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    w.writerow(header)
    for r in reversed(res.get("rows") or []):
        if changes_only and not r.get("changed"):
            continue
        vals, disp = r.get("values") or {}, r.get("display") or {}
        line = [r["date"]]
        for c in cols:
            v = vals.get(c["path"])
            line.append(v if isinstance(v, (int, float)) and not isinstance(v, bool) and not c.get("masked") else disp.get(c["path"], "" if v is None else v))
        line += [(r.get("parts") or {}).get(p["path"]) for p in parts]
        line.append(" ".join(r.get("changed") or []))
        w.writerow([("'" + x) if isinstance(x, str) and x[:1] in ("=", "+", "-", "@") else x for x in line])
    return "﻿" + buf.getvalue()


def register(groups, srv, ctx: dict) -> None:
    """Adds ``history KIND ID`` (no sub-command) to the CLI."""
    import argparse

    def cmd_history(a, extra) -> int:
        if a.csv and a.json:
            raise ctx["CliError"]("--csv and --json cannot be used together", 2)
        if a.days is not None and not 1 <= a.days <= 9999:
            raise ctx["CliError"]("--days must be 1 to 9999", 2)
        res = ctx["Api"].from_args(a).json("GET", path_for(a.kind, a.id, a))
        if a.json:
            if a.changes_only:
                res = dict(res, rows=[r for r in res.get("rows") or [] if r.get("changed")])
            ctx["say_json"](res)
        elif a.csv:
            sys.stdout.write(to_csv(res, a.changes_only))
        else:
            render(res, print, a.changes_only)
        return 1 if res.get("partial") else 0

    p = groups.add_parser("history", help="history: one entity over business days (a table newest first, changed cells marked *)",
                          description=__doc__.split("\n\n")[0], parents=[srv], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.set_defaults(func=cmd_history, cmd=None)
    p.add_argument("kind", help="the kind, for example trade or equity")
    p.add_argument("id", help="the entity's id, for example 1000015")
    r = p.add_mutually_exclusive_group()
    r.add_argument("--days", type=int, help="the last N business days on which the entity has data (default 30)")
    r.add_argument("--range", help="a calendar range: 30D, 2W, 3M, 1Y, YTD, MTD, QTD")
    r.add_argument("--from", dest="from_", metavar="YYYY-MM-DD", help="the first business date (with --to, or to the latest)")
    p.add_argument("--to", metavar="YYYY-MM-DD", help="the last business date (default the latest)")
    p.add_argument("--fields", help="only these top-level fields, comma-separated (PRICE,CHANGE_1D)")
    p.add_argument("--known-at", dest="known_at", metavar="ISO-8601", help="the history as it was known at this instant (Delta, Iceberg)")
    p.add_argument("--diff", action="store_true", help="each day's changes against the day before")
    p.add_argument("--changes-only", dest="changes_only", action="store_true", help="only the days on which something changed")
    p.add_argument("--csv", action="store_true", help="write the table as CSV to standard output")
