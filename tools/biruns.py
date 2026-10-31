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

"""BI runs on the command line (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 14.4, docs/guides/BI_GUIDE.md): what the Runs tab does, over REST as the
token's user. Loaded by drishti.py; the server decides who may see and do what (a run's `can`).

  bi runs list [--state S] [--source report|mq|sql] [--since 7d] [--all-nodes]     your runs and the ones shared with you, newest first
  bi runs get RUN                                                                    one run: state, as-of, data version, estimate and actual
  bi runs submit (--mq FILE | --sql TEXT --model M | --report ID --page P --visual V [--state FILE]) [--wait 2s] [--yes] [--title T]
  bi runs estimate (the same source options)                                         tier, engine and estimate; runs nothing
  bi runs wait RUN [--timeout 30m]                                                   until it ends: exit 0 done, 3 failed, 4 cancelled
  bi runs result RUN [--format csv|json|jsonl|arrow|parquet] [--out FILE]
  bi runs cancel|rerun|export|share|unshare|promote|keep|schedule RUN ...
  bi runs schedules [list|edit ID WHEN|disable ID|enable ID|delete ID]
  bi runs storage                                                                    bi-admin: the result store, the queue, what runs
  bi runs suggestions [list|accept ID|dismiss ID]                                    bi-admin: repeated heavy questions as rollups (8.3a)
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import time
from urllib.parse import quote, urlencode

STATES = ("planned", "awaiting-confirm", "queued", "running", "storing", "done", "reused", "failed", "cancelled", "expired")
ACTIVE = ("planned", "awaiting-confirm", "queued", "running", "storing")
EXIT = {"done": 0, "reused": 0, "failed": 3, "cancelled": 4, "expired": 3}


def seconds(text: str) -> float:
    """2s, 1.5s, 90, 30m, 1h, PT2S as seconds."""
    t = str(text).strip()
    m = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", t, re.I)
    if m and any(m.groups()):
        return float(m.group(1) or 0) * 3600 + float(m.group(2) or 0) * 60 + float(m.group(3) or 0)
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)?", t)
    if not m:
        raise argparse.ArgumentTypeError(f"{text!r} is not a duration (2s, 30m, 1h)")
    return float(m.group(1)) * {"ms": 0.001, "s": 1, None: 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]


def duration(sec) -> str:
    if sec is None:
        return "-"
    sec = int(round(float(sec)))
    if sec < 60:
        return f"{sec} s"
    if sec < 3600:
        return f"{sec // 60} min" + (f" {sec % 60} s" if sec % 60 else "")
    return f"{sec // 3600} h" + (f" {round((sec % 3600) / 60)} min" if round((sec % 3600) / 60) else "")


def est_range(est: dict | None) -> str:
    s = (est or {}).get("seconds") or {}
    if s.get("low") is None or s.get("high") is None:
        return ""
    hi = float(s["high"])
    unit, name = (3600, "h") if hi >= 5400 else (60, "min") if hi > 90 else (1, "s")
    lo, hi = max(1, int(float(s["low"]) / unit + 0.5)), max(1, int(hi / unit + 0.5))      # half up, as the page rounds
    return f"about {hi} {name}" if lo == hi else f"{lo} to {hi} {name}"


CODES = {"DRS-9080": "no such run, or not one you may see", "DRS-9081": "you have the most runs allowed queued or running: wait for one, or cancel one",
         "DRS-9083": "the run has no result yet", "DRS-9084": "the result was removed: re-run it on the same or the newest data",
         "DRS-9085": "the server stopped while it ran and it could not start again", "DRS-9086": "too large: aggregate, narrow the dates or add a rollup",
         "DRS-9087": "the node that owns it is not answering", "DRS-9088": "the result store is full", "DRS-9089": "this interaction needs a background run",
         "DRS-9024": "it took longer than its time limit", "DRS-9028": "it ran out of its memory budget"}


def status_line(r: dict) -> str:
    st = r.get("state", "")
    if st == "failed":
        return f"failed: {CODES.get(r.get('code'), '')}{'; ' if CODES.get(r.get('code')) and r.get('reason') else ''}{r.get('reason') or ''} ({r.get('code')})"
    if st == "cancelled":
        return "cancelled" + (f": {r['reason']}" if r.get("reason") else "")
    bits = [st]
    if st in ACTIVE:
        if r.get("phase"):
            bits.append(r["phase"])
        if r.get("progress") is not None:
            bits.append(f"{round(r['progress'] * 100)} %")
    else:
        act = r.get("actual") or {}
        if act.get("seconds") is not None:
            bits.append(duration(act["seconds"]))
        if act.get("rowsOut") is not None:
            bits.append(f"{act['rowsOut']:,} rows")
    return " · ".join(bits)


def as_of(r: dict) -> str:
    a = r.get("asOf") or {}
    if isinstance(a, str):
        return a
    if a.get("from") and a.get("to") and a["from"] != a["to"]:
        return f"{a['from']} to {a['to']}" + (f" ({a['dates']} dates)" if a.get("dates") else "")
    return a.get("to") or a.get("from") or ""


def register(bi, srv, ctx) -> None:
    """Adds `bi runs` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    rs = bi.add_parser("runs", help="bi runs list|get|submit|estimate|wait|result|cancel|rerun|export|share|unshare|promote|keep|schedule|schedules|storage|suggestions: BI runs",
                       description="BI runs (BI_DESIGN.md): questions that run in the background, as you.")
    ra = rs.add_subparsers(dest="action", metavar="ACTION", required=True)
    ex = argparse.RawDescriptionHelpFormatter

    def sub(name, help_, epilog, func, own_timeout=False):
        p = ra.add_parser(name, help=help_, parents=[] if own_timeout else [srv], formatter_class=ex, epilog="examples:\n" + epilog)
        if own_timeout:                         # `wait --timeout` is the wait for the run (section 14.4): the connection options without the call's --timeout
            g = p.add_argument_group("server connection")
            for action in srv._actions:                                       # noqa: SLF001 - the shared parent's options, copied as they are
                if action.option_strings and "--timeout" not in action.option_strings and "-h" not in action.option_strings:
                    g._add_action(action)                                     # noqa: SLF001
        p.set_defaults(func=lambda a, extra: func(a, ctx))
        return p

    ls = sub("list", "your runs and those shared with you, newest first", "  drishti.py bi runs list\n  drishti.py bi runs list --state running --since 7d", cmd_list)
    ls.add_argument("--state", help="one or more states, comma separated (" + ", ".join(STATES) + ")")
    ls.add_argument("--source", choices=("report", "board", "mq", "sql", "statement", "schedule"))
    ls.add_argument("--since", default="30d", help="how far back (1d, 7d, 30d; default 30d)")
    ls.add_argument("--workspace", help="the runs of one workspace's items")
    ls.add_argument("--q", help="search the titles")
    ls.add_argument("--all-nodes", action="store_true", help="ask every server of the cluster (a per-node identity database)")
    ls.add_argument("--limit", type=int, default=50)
    g = sub("get", "one run in detail", "  drishti.py bi runs get r-java-2-01JA...", cmd_get)
    g.add_argument("run")

    def source_opts(p):
        p.add_argument("--mq", metavar="FILE", help="a model query (JSON or YAML file)")
        p.add_argument("--sql", metavar="TEXT", help="a Drishti SQL statement (with --model)")
        p.add_argument("--model", help="the model the SQL reads")
        p.add_argument("--report", metavar="ID", help="a report visual: the report's id (with --page and --visual)")
        p.add_argument("--page")
        p.add_argument("--visual")
        p.add_argument("--state", dest="state_file", metavar="FILE", help="the slicers, parameters and drill path of the report visual (JSON)")
    sm = sub("submit", "ask a question as a run: the answer when it is done in time, else the run's id",
             "  drishti.py bi runs submit --sql \"SELECT desk, sum(mtm) FROM trades GROUP BY desk\" --model trading --wait 0\n"
             "  drishti.py bi runs submit --mq year.mq.json --yes --title \"Desk P&L 2026\"", cmd_submit)
    source_opts(sm)
    sm.add_argument("--wait", type=seconds, default=2.0, help="how long to wait for the answer inline (default 2s; 0: return the run at once)")
    sm.add_argument("--yes", action="store_true", help="accept the confirmation of a large run in advance")
    sm.add_argument("--title", help="the run's title in Runs")
    es = sub("estimate", "the tier, engine and estimate of a question; runs nothing", "  drishti.py bi runs estimate --mq year.mq.json", cmd_estimate)
    source_opts(es)
    w = sub("wait", "wait until a run ends (exit 0 done, 3 failed, 4 cancelled)", "  drishti.py bi runs wait r-java-2-01JA... --timeout 30m", cmd_wait, own_timeout=True)
    w.add_argument("run")
    w.add_argument("--timeout", type=seconds, default=1800.0, help="give up waiting for the run after this long (default 30m; exit 5); each call to the server waits 60 s at most")
    rr = sub("result", "a finished run's rows", "  drishti.py bi runs result r-java-2-01JA... --format csv --out pnl.csv", cmd_result)
    rr.add_argument("run")
    rr.add_argument("--format", choices=("csv", "json", "jsonl", "arrow", "parquet"), default="csv")
    rr.add_argument("--out", metavar="FILE", help="write it to this file (default: print CSV, JSON or JSON Lines)")
    c = sub("cancel", "cancel a run: everything it holds is freed at once", "  drishti.py bi runs cancel r-java-2-01JA...", cmd_cancel)
    c.add_argument("run")
    re_ = sub("rerun", "the same question again, on the same data (reuses the result) or the newest", "  drishti.py bi runs rerun r-java-2-01JA... --data latest", cmd_rerun)
    re_.add_argument("run")
    re_.add_argument("--data", choices=("same", "latest"), default="same")
    re_.add_argument("--wait", type=seconds, default=2.0)
    xp = sub("export", "the stored result as a CSV, Excel or JSON Lines file, your own version of it (masked cells stay masked)",
             "  drishti.py bi runs export r-java-2-01JA... --format xlsx --out pnl.xlsx", cmd_export)
    xp.add_argument("run")
    xp.add_argument("--format", choices=("csv", "xlsx", "jsonl"), default="csv")
    xp.add_argument("--out", metavar="FILE", required=True)
    sh = sub("share", "share a run: each recipient sees it with their own rights", "  drishti.py bi runs share r-java-2-01JA... --to bob --to role:risk-team", cmd_share)
    sh.add_argument("run")
    sh.add_argument("--to", action="append", required=True, metavar="WHO", help="a user, or role:<name> (repeatable)")
    us = sub("unshare", "stop sharing a run with someone (a run recomputed as them stays theirs)", "  drishti.py bi runs unshare r-java-2-01JA... bob", cmd_unshare)
    us.add_argument("run")
    us.add_argument("who", nargs="+", help="the recipients to remove")
    pr = sub("promote", "promote a model query's or SQL run's result to a draft dataset snapshot, or a draft report on it (in your workspace)",
             "  drishti.py bi runs promote r-java-2-01JA... --name desk-pnl-2026\n  drishti.py bi runs promote r-java-2-01JA... --to report --name desk-pnl-2026 --title 'Desk P&L 2026'", cmd_promote)
    pr.add_argument("run")
    pr.add_argument("--to", choices=("dataset", "report"), default="dataset", help="a dataset snapshot (default), or a report on it and its dataset")
    pr.add_argument("--as", dest="as_", choices=("snapshot", "report"), help=argparse.SUPPRESS)          # the earlier spelling: snapshot is --to dataset
    pr.add_argument("--name", help="the snapshot's and the dataset's name (the server makes one when left out); the same name again is its next version")
    pr.add_argument("--title", help="the dataset's title (the server's default names the run and its dates)")
    kp = sub("keep", "keep a result longer than the retention", "  drishti.py bi runs keep r-java-2-01JA... --days 90", cmd_keep)
    kp.add_argument("run")
    kp.add_argument("--days", type=int, required=True)
    sc = sub("schedule", "run the same question on a schedule (each firing is a run and a notice)",
             "  drishti.py bi runs schedule r-java-2-01JA... \"business-days 07:30\"\n  drishti.py bi runs schedule r-java-2-01JA... \"weekly mon 08:00\" --data same", cmd_schedule)
    sc.add_argument("run")
    sc.add_argument("when", help="daily 07:00 · weekdays 07:30 · business-days 07:30 · weekly mon 08:00 · monthly last 18:00 [business]")
    sc.add_argument("--data", choices=("latest", "same"), default="latest")
    ss = sub("schedules", "your scheduled runs: list, edit, disable, enable or delete one",
             "  drishti.py bi runs schedules\n  drishti.py bi runs schedules edit sch-1 \"daily 06:30\"\n  drishti.py bi runs schedules disable sch-1", cmd_schedules)
    ss.add_argument("verb", nargs="?", choices=("list", "edit", "disable", "enable", "delete"), default="list")
    ss.add_argument("id", nargs="?")
    ss.add_argument("when", nargs="?", help="edit: the new schedule (daily 07:00, weekly mon 08:00, ...)")
    ss.add_argument("--data", choices=("latest", "same"), help="edit: on the newest data or the same dates")
    sub("storage", "bi-admin: the result store, the queue and what runs now", "  drishti.py bi runs storage", cmd_storage)
    sg = sub("suggestions", "bi-admin: repeated heavy questions a rollup would answer; accept (adds the rollup to the model's draft) or dismiss",
             "  drishti.py bi runs suggestions\n  drishti.py bi runs suggestions accept sg-1\n  drishti.py bi runs suggestions dismiss sg-2", cmd_suggestions)
    sg.add_argument("verb", nargs="?", choices=("list", "accept", "dismiss"), default="list")
    sg.add_argument("id", nargs="?")
    sg.add_argument("--model", help="list: only this model's suggestions")


def _api(a, ctx):
    return ctx["Api"].from_args(a)


def _rid(a) -> str:
    return quote(a.run, safe="")


def cmd_list(a, ctx) -> int:
    p = {"mine": "true", "shared": "true", "since": a.since, "limit": a.limit}
    for k in ("state", "source", "workspace", "q"):
        if getattr(a, k):
            p[k] = getattr(a, k)
    if a.all_nodes:
        p["allNodes"] = "true"
    r = _api(a, ctx).json("GET", "/api/v1/bi/runs?" + urlencode(p))
    if a.json:
        ctx["say_json"](r)
        return 0
    runs = r.get("runs") or []
    if not runs:
        print("no runs")
    for x in runs:
        shared = f"  (shared by {x['shared']['from']})" if (x.get("shared") or {}).get("from") else ""
        print(f"{x.get('id')}  {x.get('title')}{shared}\n    {status_line(x)}  · as of {as_of(x) or '-'}  · {(x.get('data') or {}).get('words') or '-'}"
              + (f"  · est. {est_range(x.get('estimate'))}" if est_range(x.get("estimate")) else ""))
    st = r.get("storage") or {}
    if st.get("bytes") is not None:
        print(f"storage: {st['bytes'] / 1024 ** 3:.1f} GB of your results" + (f" ({st['results']})" if st.get("results") is not None else ""))
    for n in (r.get("nodes") or {}).get("failed") or []:
        print(f"node {n} did not answer: its runs' state is unknown until it returns", file=sys.stderr)
    return 0


def print_run(r: dict) -> None:
    s, est, act = r.get("source") or {}, r.get("estimate") or {}, r.get("actual") or {}
    lines = [(r.get("title") or r.get("id")) + f"  [{r.get('id')}]",
             f"  status      {status_line(r)}",
             f"  source      {s.get('type')} {s.get('title') or s.get('id') or ''}" + (f", page {s['page']}" if s.get("page") else "") + (f", visual {s['visual']}" if s.get("visual") else ""),
             f"  run by      {r.get('runner')}" + (f" for {r['owner']}" if r.get("owner") and r.get("owner") != r.get("runner") else "") + f" · {str(r.get('submittedAt') or '')[:16].replace('T', ' ')}" + (f" on {r['node']}" if r.get("node") else ""),
             f"  as of       {as_of(r) or '-'}",
             f"  data        {(r.get('data') or {}).get('words') or '-'}",
             f"  estimate    {est_range(est) or '-'}" + (f", {est['readRows']:,} rows read" if est.get("readRows") is not None else "") + (f" ({est['basis']})" if est.get("basis") else "")
             + (f" · actual {duration(act['seconds'])}" if act.get("seconds") is not None and r.get("state") in ("done", "reused") else ""),
             f"  tier        {r.get('tier')} · {r.get('engine')} engine"]
    if est.get("why"):
        lines.append(f"  why         {est['why']}")
    if r.get("expiresAt"):
        lines.append(f"  kept until  {str(r['expiresAt'])[:10]}")
    for x in (r.get("shared") or {}).get("with") or []:
        lines.append(f"  shared with {x.get('recipient')} ({x.get('mode')})")
    print("\n".join(lines))


def cmd_get(a, ctx) -> int:
    r = _api(a, ctx).json("GET", "/api/v1/bi/runs/" + _rid(a))
    if a.json:
        ctx["say_json"](r)
    else:
        print_run(r)
    return 0


def source_of(a, ctx) -> dict:
    if a.mq:
        text = pathlib.Path(a.mq).read_text(encoding="utf-8")
        try:
            mq = json.loads(text)
        except ValueError:
            import yaml
            mq = yaml.safe_load(text)
        return {"type": "mq", "mq": mq}
    if a.sql:
        if not a.model:
            raise ctx["CliError"]("--sql needs --model", 2)
        return {"type": "sql", "sql": a.sql, "model": a.model}
    if a.report:
        if not (a.page and a.visual):
            raise ctx["CliError"]("--report needs --page and --visual", 2)
        src = {"type": "report", "id": a.report, "page": a.page, "visual": a.visual}
        if a.state_file:
            src["state"] = json.loads(pathlib.Path(a.state_file).read_text(encoding="utf-8"))
        return src
    raise ctx["CliError"]("say what to run: --mq FILE, --sql TEXT --model M, or --report ID --page P --visual V", 2)


def ask_async(api, ctx, method: str, path: str, body: dict, wait: float):
    """POST with `Prefer: respond-async, wait=N`: (status, body). A 409 is returned as (409, problem) for the caller to explain."""
    try:
        status, raw = api.request(method, path, body, extra={"Prefer": f"respond-async, wait={wait:g}"})
    except ctx["CliError"] as e:
        if getattr(e, "status", None) == 409 and getattr(e, "body", None):
            return 409, e.body
        raise
    return status, (json.loads(raw) if raw.strip() else {})


def explain_refusal(problem: dict) -> str:
    est = problem.get("estimate") or {}
    words = {"DRS-9082": "this question is large: it needs your confirmation (add --yes to accept it)",
             "DRS-9089": "this interaction needs a background run: submit it as a run"}.get(problem.get("code"), problem.get("detail") or "")
    bits = [f"{problem.get('code')}: {words}"]
    if est.get("readRows") is not None:
        bits.append(f"about {est['readRows']:,} rows" + (f" over {est['dates']} business days" if est.get("dates") else "") + (f" ({est['basis']})" if est.get("basis") else ""))
    if est_range(est):
        bits.append(f"expected {est_range(est)}")
    if est.get("why"):
        bits.append(est["why"])
    return "; ".join(bits)


def show_outcome(a, ctx, status: int, body: dict) -> int:
    if status == 409:
        if a.json:
            ctx["say_json"](body)
        print(explain_refusal(body), file=sys.stderr)
        return 2
    if status == 202:
        run = body.get("run") or body
        if a.json:
            ctx["say_json"](run)
        else:
            print(f"{run.get('id')}  {status_line(run)}" + (f"  (expected {est_range(run.get('estimate'))})" if est_range(run.get("estimate")) else ""))
            print(f"  follow it:  drishti.py bi runs wait {run.get('id')}")
        return 0
    ans = body.get("answer") if isinstance(body, dict) and "answer" in body and "run" in body else body
    if a.json:
        ctx["say_json"](ans)
        return 0
    cols = [c.get("name") for c in (ans or {}).get("columns", [])]
    print("\t".join(cols))
    for row in (ans or {}).get("rows", []):
        print("\t".join("" if v is None else str(v) for v in row))
    print(f"{len((ans or {}).get('rows', []))} row(s), answered inline")
    return 0


def cmd_submit(a, ctx) -> int:
    body = {"source": source_of(a, ctx), "options": {"wait": f"PT{a.wait:g}S", "confirm": bool(a.yes), "title": a.title}}
    api = _api(a, ctx)
    status, r = ask_async(api, ctx, "POST", "/api/v1/bi/runs", body, a.wait)
    return show_outcome(a, ctx, status, r)


def cmd_estimate(a, ctx) -> int:
    r = _api(a, ctx).json("POST", "/api/v1/bi/runs/estimate", {"source": source_of(a, ctx)})
    if a.json:
        ctx["say_json"](r)
        return 0
    est = r.get("estimate") or {}
    print(f"tier {r.get('tier')} · engine {r.get('engine')}" + (" · asks to confirm" if r.get("confirm") else ""))
    print(f"  {est_range(est) or 'no time estimate'}" + (f", about {est['readRows']:,} rows read" if est.get("readRows") is not None else "") + (f" ({est['basis']})" if est.get("basis") else ""))
    if est.get("why"):
        print(f"  {est['why']}")
    if r.get("reuse"):
        print(f"  a stored result answers it: run {r['reuse'].get('runId')}")
    return 0


def cmd_wait(a, ctx) -> int:
    api = _api(a, ctx)
    api.timeout = 60                                    # --timeout here is the wait for the run (section 14.4), not one call's
    end = time.monotonic() + a.timeout
    last = None
    while True:
        r = api.json("GET", "/api/v1/bi/runs/" + _rid(a))
        line = status_line(r)
        if line != last and not a.json:
            print(line, flush=True)
            last = line
        if r.get("state") not in ACTIVE:
            if a.json:
                ctx["say_json"](r)
            return EXIT.get(r.get("state"), 3)
        if time.monotonic() >= end:
            print(f"still {r.get('state')} after {duration(a.timeout)}: it goes on; wait again later", file=sys.stderr)
            return 5
        time.sleep(1.0 if r.get("state") != "awaiting-confirm" else 2.0)


def cmd_result(a, ctx) -> int:
    api = _api(a, ctx)
    path = "/api/v1/bi/runs/" + _rid(a) + "/result"
    if a.format == "jsonl":                          # the server's JSON Lines: a row a line, the export's column names
        text = api.request("GET", path + "?format=jsonl&limit=50000")[1].decode("utf-8")
        if a.out:
            pathlib.Path(a.out).write_text(text, encoding="utf-8")
            print(f"wrote {a.out}")
        else:
            sys.stdout.write(text)
        return 0
    if a.format in ("json", "csv"):
        ans = api.json("GET", path + "?format=json")
        if a.format == "json":
            text = json.dumps(ans, indent=2)
        else:
            import csv
            import io
            buf = io.StringIO()
            w = csv.writer(buf)
            w.writerow([c.get("name") for c in ans.get("columns", [])])
            masked = {c.get("name") for c in ans.get("columns", []) if c.get("masked")}
            cols = [c.get("name") for c in ans.get("columns", [])]
            for row in ans.get("rows", []):
                w.writerow(["•••" if v is None and cols[i] in masked else "" if v is None else v for i, v in enumerate(row)])
            text = buf.getvalue()
        if a.out:
            pathlib.Path(a.out).write_text(text, encoding="utf-8")
            print(f"wrote {a.out}")
        else:
            sys.stdout.write(text if text.endswith("\n") else text + "\n")
        return 0
    if not a.out:
        raise ctx["CliError"](f"--format {a.format} is binary: say where with --out FILE", 2)
    if a.format == "arrow":
        _, raw = api.request("GET", path + "?format=arrow")
    else:
        _, raw = api.request("POST", "/api/v1/bi/runs/" + _rid(a) + "/export", {"format": "parquet"})
    pathlib.Path(a.out).write_bytes(raw)
    print(f"wrote {a.out} ({len(raw):,} bytes)")
    return 0


def cmd_cancel(a, ctx) -> int:
    r = _api(a, ctx).json("POST", "/api/v1/bi/runs/" + _rid(a) + "/cancel", {})
    if a.json:
        ctx["say_json"](r)
    else:
        print(f"{r.get('id')}  {status_line(r)}")
    return 0


def cmd_rerun(a, ctx) -> int:
    status, r = ask_async(_api(a, ctx), ctx, "POST", "/api/v1/bi/runs/" + _rid(a) + "/rerun", {"data": a.data, "wait": f"PT{a.wait:g}S"}, a.wait)
    return show_outcome(a, ctx, status, r)


def cmd_export(a, ctx) -> int:
    _, raw = _api(a, ctx).request("POST", "/api/v1/bi/runs/" + _rid(a) + "/export", {"format": a.format})
    pathlib.Path(a.out).write_bytes(raw)
    print(f"wrote {a.out} ({len(raw):,} bytes)")
    return 0


MODES = {"same": "sees the same (equal rights: your stored result)", "re-masked": "re-masked (your result with their extra masks)",
         "recomputed": "recomputed as them (their rights differ: a run of their own)"}


def cmd_share(a, ctx) -> int:
    r = _api(a, ctx).json("POST", "/api/v1/bi/runs/" + _rid(a) + "/share", {"to": a.to})
    if a.json:
        ctx["say_json"](r)
        return 0
    refused = 0
    for s in r.get("shares") or []:
        if not s.get("mode"):
            refused += 1
            print(f"{s.get('recipient')}: not shared: {s.get('detail') or CODES.get(s.get('code'), '')} ({s.get('code')})", file=sys.stderr)
            continue
        print(f"{s.get('recipient')}: {MODES.get(s.get('mode'), s.get('mode'))}" + (f" (run {s['recipientRun']})" if s.get("recipientRun") else ""))
    return 0 if refused < len(r.get("shares") or []) or not refused else 1


def cmd_unshare(a, ctx) -> int:
    api = _api(a, ctx)
    for who in a.who:
        api.request("DELETE", "/api/v1/bi/runs/" + _rid(a) + "/share/" + quote(who, safe=""))
        print(f"{who}: no longer shared")
    return 0


def cmd_promote(a, ctx) -> int:
    """POST /runs/{id}/promote {name?, title?, to} (BI_DESIGN › BI runs, 9.4 as built): 201 {snapshot, version, dataset: {name, item}, report: {item}|null, yaml}."""
    to = {"snapshot": "dataset", "report": "report"}.get(a.as_ or "", a.to)
    body = {"to": to}
    for k in ("name", "title"):
        if getattr(a, k):
            body[k] = getattr(a, k)
    r = _api(a, ctx).json("POST", "/api/v1/bi/runs/" + _rid(a) + "/promote", body)
    if a.json:
        ctx["say_json"](r)
        return 0
    ds, rep = r.get("dataset") or {}, r.get("report") or {}
    print(f"snapshot {r.get('snapshot')} version {r.get('version')}: draft dataset {ds.get('item')} ({ds.get('name')})" + (f", draft report {rep.get('item')}" if rep.get("item") else "")
          + " in your workspace")
    print("  review it before you publish it")
    return 0


def cmd_keep(a, ctx) -> int:
    r = _api(a, ctx).json("POST", "/api/v1/bi/runs/" + _rid(a) + "/keep", {"days": a.days})
    if a.json:
        ctx["say_json"](r)
    else:
        print(f"{r.get('id')}  kept until {str(r.get('expiresAt') or '')[:10]}")
    return 0


def cmd_schedule(a, ctx) -> int:
    r = _api(a, ctx).json("POST", "/api/v1/bi/runs/" + _rid(a) + "/schedule", {"schedule": a.when, "data": a.data})
    if a.json:
        ctx["say_json"](r)
    else:
        print(f"{r.get('id')}  {r.get('schedule')}  on {r.get('data')} data" + (f"  next {str(r['nextAt'])[:16].replace('T', ' ')}" if r.get("nextAt") else ""))
    return 0


def cmd_schedules(a, ctx) -> int:
    api = _api(a, ctx)
    if a.verb != "list":
        if not a.id:
            raise ctx["CliError"](f"bi runs schedules {a.verb} needs the schedule's id", 2)
        path = "/api/v1/bi/runs/schedules/" + quote(a.id, safe="")
        if a.verb == "edit":
            if not a.when and not a.data:
                raise ctx["CliError"]("bi runs schedules edit ID needs the new schedule (\"daily 06:30\") or --data", 2)
            body = {k: v for k, v in (("schedule", a.when), ("data", a.data)) if v}
            r = api.json("PUT", path, body)
            if a.json:
                ctx["say_json"](r)
            else:
                print(schedule_line(r))
            return 0
        if a.verb == "delete":
            api.request("DELETE", path)
            print(f"{a.id}  deleted")
        else:
            r = api.json("PUT", path, {"enabled": a.verb == "enable"})
            print(f"{r.get('id', a.id)}  {'enabled' if r.get('enabled', a.verb == 'enable') else 'disabled'}")
        return 0
    r = api.json("GET", "/api/v1/bi/runs/schedules")
    if a.json:
        ctx["say_json"](r)
        return 0
    rows = r.get("schedules") or []
    if not rows:
        print("no schedules")
    for s in rows:
        print(schedule_line(s))
    return 0


def schedule_line(s: dict) -> str:
    return (f"{s.get('id')}  {s.get('schedule')}  {'on' if s.get('enabled', True) else 'off'}" + (f"  on {s['data']} data" if s.get("data") else "")
            + (f"  next {str(s['nextAt'])[:16].replace('T', ' ')}" if s.get("nextAt") else "") + (f"  last run {s['lastRun']}" if s.get("lastRun") else "")
            + (f"  ({s['title']})" if s.get("title") else ""))


def cmd_suggestions(a, ctx) -> int:
    api = _api(a, ctx)
    if a.verb != "list":
        if not a.id:
            raise ctx["CliError"](f"bi runs suggestions {a.verb} needs the suggestion's id", 2)
        r = api.json("POST", "/api/v1/bi/admin/runs/suggestions/" + quote(a.id, safe="") + "/" + a.verb, {})
        if a.json:
            ctx["say_json"](r)
        elif a.verb == "dismiss":
            print(f"{r.get('id', a.id)}  dismissed")
        elif r.get("item"):
            print(f"{r.get('id', a.id)}  accepted: the rollup is in the draft of {r.get('model')} ({r['item']}); review it and publish it")
        else:
            print(f"{r.get('id', a.id)}  accepted: {r.get('model')} is a pack's model; add this to it by hand:\n{r.get('yaml') or ''}")
        return 0
    r = api.json("GET", "/api/v1/bi/admin/runs")
    rows = [s for s in r.get("suggestions") or [] if not a.model or s.get("model") == a.model]
    if a.json:
        ctx["say_json"](rows)
        return 0
    if not rows:
        print("no suggestions: no heavy question was asked often enough")
    for s in rows:
        grain = " x ".join(s.get("grain") or [])
        print(f"{s.get('id')}  {s.get('model')}  {grain}  {s.get('status')}  {s.get('runs')} runs, about {duration(s.get('medianSeconds'))} each"
              + (f"  (by {s['decidedBy']})" if s.get("decidedBy") else ""))
        if s.get("words"):
            print(f"    {s['words']}")
    return 0


def cmd_storage(a, ctx) -> int:
    r = _api(a, ctx).json("GET", "/api/v1/bi/admin/runs")
    if a.json:
        ctx["say_json"](r)
        return 0
    st, q = r.get("storage") or {}, r.get("queue") or {}
    gb = lambda b: f"{b / 1024 ** 3:.1f} GB" if b is not None else "-"           # noqa: E731
    print(f"results    {gb(st.get('resultsBytes'))}" + (f" of {gb(st.get('maxBytes'))}" if st.get("maxBytes") else "") + (f" ({st['results']:,} results)" if st.get("results") is not None else ""))
    print(f"snapshots  {gb(st.get('snapshotsBytes'))}" + (f" ({st['snapshots']})" if st.get("snapshots") is not None else ""))
    for u in st.get("topUsers") or []:
        print(f"  {u.get('user'):<14}{gb(u.get('bytes'))}")
    queued = sum(int(v or 0) for v in (q.get("byClass") or {}).values())
    print(f"queue      {queued} queued" + ("; per person: " + ", ".join(f"{k} {v}" for k, v in (q.get("byUser") or {}).items()) if q.get("byUser") else ""))
    print(f"running    {len(r.get('running') or [])}")
    for x in r.get("running") or []:
        print(f"  {x.get('id')}  {x.get('runner')}  {x.get('title')}  {status_line(x)}")
    return 0


def asked(a, ctx, path: str, body: dict) -> int:
    """`bi mq --async` and `bi sql --async`: the question with `Prefer: respond-async, wait=<--wait>`; the answer when it is done in time, else the run."""
    wait = seconds(a.wait)
    if getattr(a, "yes", False):                                          # --yes: confirmed in advance, as POST /runs options.confirm (BI_DESIGN › BI runs, 14.5, BIR-05)
        body = dict(body, confirm=True)
    status, r = ask_async(_api(a, ctx), ctx, "POST", path, body, wait)
    return show_outcome(a, ctx, status, r)
