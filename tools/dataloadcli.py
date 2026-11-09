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

"""`drishti.py dataload status|run|retry|undo`: the data inbox from the command line (docs/guides/DATA_LAKE_GUIDE.md; docs/guides/DATA_GUIDE.md,
"The data inbox"; the API: /api/v1/admin/dataload, administrators, or a token with loads:write for run and retry).

    status [--json]          the files waiting and why, the last cycles, the failures with their reasons, the lock, the next cycle
    run                      Load now: a cycle at once; while a run still loads, none starts and it says so
    retry <file>             a failed file and its .ready back into the inbox; the next cycle (or run) loads it
    ready --file F [--table T --lake L --date D --mode append|replace --key K --hold] [--check] [--push] [--yes-replace]
                             the file uploaded to the server, its exact .ready printed, its checks with --check, pushed with --push (or a file
                             already in the inbox folder: --inbox NAME); the same endpoints as Admin, Data loads, Upload
    undo <load-id> [--yes]   what undoing a load would remove (a dry run), or with --yes the undo, in the lake the load wrote into
                             (the server finds it in the ledger; --lake NAME to say it)

Exit codes: 0 done, 1 the server said no, 2 usage, 3 the inbox is off on every server (DRS-1020)."""
from __future__ import annotations

EPILOG = ("examples:\n  drishti.py dataload status\n  drishti.py dataload run\n  drishti.py dataload retry trades-endur-2026-11-03.jsonl.gz\n"
          "  drishti.py dataload undo 20261104T093000.123-4242.1\n  drishti.py dataload undo 20261104T093000.123-4242.1 --yes\n"
          "  drishti.py dataload status --json | jq '.failed'\n"
          "  drishti.py dataload ready --file trade_endur.2026-11-06.jsonl.gz --table trade_endur --check\n"
          "  drishti.py dataload ready --file eod-2026-11-06.tar.gz --mode append --push")


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


def ready_form(a, item: str | None, choices: dict) -> dict:
    """The form's fields from the command line: the table named resolves to its pack, kind and connector by the same list the page offers."""
    f = {"item": item, "lake": a.lake, "mode": a.mode or "append", "hold": a.hold, "ackReplace": a.yes_replace, "key": a.key, "businessDate": a.date}
    if a.new_table:
        f.update({"newTable": True, "table": a.table, "pack": a.pack, "kind": a.kind or a.table})
    elif a.table:
        hits = [t for t in choices.get("tables") or [] if t.get("table") == a.table]
        if len(hits) > 1 and a.pack:
            hits = [t for t in hits if t.get("pack") == a.pack]
        if len(hits) == 1:
            f.update({"table": a.table, "pack": hits[0]["pack"], "kind": hits[0]["kind"], "connector": hits[0].get("connector")})
        else:
            f["table"] = a.table
    return f


def say_checks(res: dict) -> None:
    """The checks as a person reads them."""
    for c in res.get("checks") or []:
        print(f"{c.get('status', ''):<9}{c.get('text', '')}")
        if c.get("fix"):
            print(f"{'':<9}{c['fix']}")


def register(groups, srv, ctx: dict) -> None:
    """Adds ``dataload status|run|retry|ready|undo``."""
    import argparse

    Api, CliError, ApiError, say_json = ctx["Api"], ctx["CliError"], ctx["ApiError"], ctx["say_json"]

    def wait(api, item: dict, json_out: bool) -> dict:
        import time
        while item.get("state") == "inspecting":
            time.sleep(0.5)
            item = api.json("GET", "/api/v1/admin/dataload/item/" + item["id"])
        if item.get("state") == "failed":
            raise CliError(f"{item.get('file')}: {item.get('error')}", 1)
        return item

    def ready(api, a) -> int:
        import os
        import urllib.parse
        choices = api.json("GET", "/api/v1/admin/dataload/choices")
        if a.inbox:
            item = api.json("POST", "/api/v1/admin/dataload/pick", {"file": a.inbox})
        elif a.file:
            name = os.path.basename(a.file)
            with open(a.file, "rb") as fh:
                item = api.json("POST", "/api/v1/admin/dataload/upload?file=" + urllib.parse.quote(name, safe=""), data=fh, ctype="application/octet-stream",
                                extra={"Content-Length": str(os.path.getsize(a.file))})
        else:
            raise CliError("give --file or --inbox", 2)
        item = wait(api, item, a.json)
        form = ready_form(a, item["id"], choices)
        if item.get("tar"):
            form = {k: v for k, v in form.items() if k in ("item", "lake", "mode", "hold", "ackReplace")}
        prev = api.json("POST", "/api/v1/admin/dataload/ready/preview", form)
        out = {"item": item, "ready": prev}
        code = 1 if prev.get("errors") else 0
        if not a.json:
            print(prev.get("text") or "", end="")
            for field, why in (prev.get("errors") or {}).items():
                print(f"error    {field}: {why}")
        if a.check or a.push:
            res = api.json("POST", "/api/v1/admin/dataload/ready/check", form)
            out["checks"] = res
            if not a.json:
                say_checks(res)
            if res.get("blocked") or (res.get("tick") and a.push and not a.yes_replace):
                code = 1
        if a.push and code == 0:
            out["pushed"] = api.json("POST", "/api/v1/admin/dataload/ready/push", form)
            if not a.json:
                print(f"pushed {out['pushed'].get('file')} and its .ready to the inbox on {out['pushed'].get('node')}")
        elif item.get("source") == "upload":                      # not pushed: nothing stays in the staging folder
            api.json("DELETE", "/api/v1/admin/dataload/upload/" + item["id"])
        if a.json:
            say_json(out)
        return code

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
            if act == "ready":
                return ready(api, a)
            if act == "undo":
                body = {"loadId": a.load_id}
                if a.yes:
                    body["confirm"] = a.load_id
                if a.lake:
                    body["lake"] = a.lake
                res = api.json("POST", "/api/v1/admin/dataload/undo", body)
                if a.json:
                    say_json(res)
                    return 0
                for line in res.get("lines") or []:
                    print(f"  {line.get('table')} {line.get('date')}: {line.get('removed')} rows removed"
                          + (f", {line.get('restored')} rows of the date before restored" if line.get("replaced") else ""))
                print(res.get("text") or "")
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

    p = groups.add_parser("dataload", help="dataload status|run|retry|ready|undo: the data inbox (files dropped in <data>/dataload/inbox, loaded into the lake)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="dataload_cmd", metavar="ACTION", required=True)
    for act, what in (("status", "the files waiting, the last cycles, the failures with their reasons"), ("run", "Load now: a cycle at once"),
                      ("retry", "a failed file and its .ready back into the inbox"),
                      ("ready", "upload a file, print its exact .ready, check and push it (same endpoints as the Upload page)"),
                      ("undo", "undo a load by its id in the lake it wrote into: a dry run, or --yes")):
        c = sub.add_parser(act, parents=[srv], help=what, description=what, epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
        c.set_defaults(func=cmd)
        if act == "retry":
            c.add_argument("file", help="the failed file's name as it was dropped (trades-2026-11-03.jsonl.gz)")
        if act == "ready":
            c.add_argument("--file", metavar="F", help="the data file to upload (.jsonl, .gz, .zip, .tar, .tar.gz, .tgz)")
            c.add_argument("--inbox", metavar="NAME", help="a file already in the inbox folder, with no .ready (nothing is uploaded)")
            c.add_argument("--table", metavar="T", help="the table (default: from the file name); a tar names its own")
            c.add_argument("--lake", metavar="L", help="the lake (default: the inbox's)")
            c.add_argument("--date", metavar="D", help="the business date, YYYY-MM-DD (default: from the file name)")
            c.add_argument("--mode", choices=("append", "replace"), help="append (the default) or replace")
            c.add_argument("--key", metavar="K", help="the field that identifies a row (default: the contract's)")
            c.add_argument("--pack", metavar="P", help="only for a new table, or a table name in two packs")
            c.add_argument("--kind", metavar="K", help="only for a new table")
            c.add_argument("--new-table", action="store_true", help="the file may create a table no pack has")
            c.add_argument("--hold", action="store_true", help="wait in the inbox until released")
            c.add_argument("--check", action="store_true", help="run the checks and print them")
            c.add_argument("--push", action="store_true", help="push it after its checks; a refusal stops it, exit 1")
            c.add_argument("--yes-replace", action="store_true", help="say yes to a replace that removes rows the table already holds for the date")
        if act == "undo":
            c.add_argument("load_id", metavar="LOAD_ID", help="the load's id (a load report's loads, or a row's _load_id)")
            c.add_argument("--yes", action="store_true", help="undo it (without: what would be undone, nothing changes)")
            c.add_argument("--lake", metavar="NAME", help="the lake of config/lakes/ to undo in (default: the load's, from the ledger)")
