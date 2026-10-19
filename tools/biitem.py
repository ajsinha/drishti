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

"""BI items from files on the command line (docs/guides/CLI_GUIDE.md "bi item, bi dataset"): what My workspace's New item, the item page's Edit and
the pages' Run the tests and Refresh do, over REST as the token's user. Loaded by drishti.py; the server checks every text and decides who may do what.

  bi item new WORKSPACE FILE [--title T] [--audience ROLE,...]   a draft from a YAML file (kind from the file name: x.model.yaml, x.dataset.yaml ...)
  bi item save ID FILE [--message M]                              a new saved state of the draft
  bi item tests ID [--role R]                                     run the item's expected values (tests:) as you or a role you hold (exit 1 on a failure)
  bi dataset refresh ID [--now]                                   the refresh state of a dataset; --now asks for a refresh and waits for it
"""
import argparse
import pathlib
import time

KINDS = ("model", "dataset", "report", "board", "notebook", "python", "alert", "theme")      # a board is stored as a report with board pages (2.4.0)


def register(bi, srv, ctx) -> None:
    """Adds `bi item` and `bi dataset` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    it = bi.add_parser("item", help="bi item new|save|tests: BI items from YAML files", description="BI items (models, datasets, reports, ...) from files, as you.")
    ia = it.add_subparsers(dest="action", metavar="ACTION", required=True)
    nw = ia.add_parser("new", help="a draft in a workspace from a YAML file", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi item new economics-desk docs/tutorials/data/trades-daily.dataset.yaml\n"
                              "  drishti.py bi item new rates-desk my.model.yaml --title \"Trades model\" --audience risk,desk-head")
    nw.add_argument("workspace", help="the workspace id (me: your private workspace)")
    nw.add_argument("file", help="the item's YAML; its kind is read from the file name (name.<kind>.yaml) or --kind")
    nw.add_argument("--kind", choices=KINDS)
    nw.add_argument("--title", help="the item's title (default: the YAML's title:)")
    nw.add_argument("--audience", default="", help="roles that may read it once published, comma-separated")
    nw.set_defaults(func=lambda a, extra: item_new(a, ctx))
    sv = ia.add_parser("save", help="a new saved state of a draft from a YAML file", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi item save bi-3f2a9c10de my.model.yaml --message \"add the tests\"")
    sv.add_argument("id")
    sv.add_argument("file")
    sv.add_argument("--message", default="", help="what changed (the history shows it)")
    sv.set_defaults(func=lambda a, extra: item_save(a, ctx))
    ts = ia.add_parser("tests", help="run the item's expected values (exit 1 when one fails)", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi item tests bi-3f2a9c10de\n  drishti.py bi item tests bi-3f2a9c10de --role desk-head")
    ts.add_argument("id")
    ts.add_argument("--role", help="run as a role you hold (a bi-steward: any role)")
    ts.set_defaults(func=lambda a, extra: item_tests(a, ctx))

    ds = bi.add_parser("dataset", help="bi dataset refresh: a dataset's refresh", description="Datasets of the model layer, as you.")
    da = ds.add_subparsers(dest="action", metavar="ACTION", required=True)
    rf = da.add_parser("refresh", help="the refresh state; --now refreshes and waits", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi dataset refresh bi-3f2a9c10de\n  drishti.py bi dataset refresh bi-3f2a9c10de --now")
    rf.add_argument("id")
    rf.add_argument("--now", action="store_true", help="ask for a refresh now (bi-author) and wait until it ends")
    rf.add_argument("--wait", type=float, default=120, help="seconds to wait with --now (default 120)")
    rf.set_defaults(func=lambda a, extra: dataset_refresh(a, ctx))


def kind_of(path: pathlib.Path, given: str | None, ctx) -> str:
    if given:
        return given
    parts = path.name.split(".")
    if len(parts) >= 3 and parts[-1] in ("yaml", "yml") and parts[-2] in KINDS:
        return parts[-2]
    raise ctx["CliError"](f"{path.name}: say the kind with --kind, or name the file <name>.<kind>.yaml", 2)


def title_of(text: str) -> str:
    for line in text.splitlines():
        if line.startswith("title:"):
            return line.split(":", 1)[1].split(" #")[0].strip().strip("\"'")
    return ""


def item_new(a, ctx) -> int:
    from urllib.parse import quote
    path = pathlib.Path(a.file)
    text = path.read_text(encoding="utf-8")
    body = {"kind": kind_of(path, a.kind, ctx), "title": a.title or title_of(text) or path.name.split(".")[0], "description": "", "body": text}
    roles = [r.strip() for r in a.audience.split(",") if r.strip()]
    if roles:
        body["audienceRoles"] = roles
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/workspaces/" + quote(a.workspace, safe="") + "/items", body)
    if a.json:
        ctx["say_json"](r)
    else:
        print(f"{r.get('id')}  {r.get('kind')}  {r.get('title')}  {r.get('status')} in {r.get('workspaceName') or r.get('workspace')}")
    return 0


def item_save(a, ctx) -> int:
    from urllib.parse import quote
    text = pathlib.Path(a.file).read_text(encoding="utf-8")
    r = ctx["Api"].from_args(a).json("PUT", "/api/v1/bi/items/" + quote(a.id, safe=":"), {"body": text, "message": a.message})
    if a.json:
        ctx["say_json"](r)
    else:
        print(f"{r.get('id')}  saved  ({r.get('status')}" + (f", published v{r['publishedVersion']}" if r.get("publishedVersion") else "") + ")")
    return 0


def item_tests(a, ctx) -> int:
    from urllib.parse import quote
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/items/" + quote(a.id, safe=":") + "/tests", {"role": a.role} if a.role else {})
    if a.json:
        ctx["say_json"](r)
        return 0 if r.get("ok") else 1
    for x in r.get("results", []):
        v = x.get("actual")
        found = "nothing" if v is None else f"{int(v):,}" if isinstance(v, float) and v.is_integer() else f"{v:,}" if isinstance(v, (int, float)) else str(v)
        print(f"{x.get('status'):<6}{x.get('name')}: expected {x.get('expect')}, found {found}" + (f"  ({x['message']})" if x.get("message") and x.get("status") != "fail" else ""))
    print(f"{r.get('passed', 0)} passed, {r.get('failed', 0)} failed" + (f", {r['errors']} could not run" if r.get("errors") else "")
          + (f"  (as {r['role']})" if r.get("role") else "") if r.get("total") else "the item declares no tests")
    return 0 if r.get("ok") else 1


def own(st: dict) -> dict:
    """One state for a dataset of one table: the server keeps the schedule on the table and a run someone asked for on the dataset (as the page shows it)."""
    tables, d = st.get("tables") or [], st.get("dataset")
    if len(tables) != 1 or not d:
        return d or (tables[0] if tables else {})
    t = tables[0]
    later = d if not t.get("lastRun") or (d.get("lastRun") and d["lastRun"] > t["lastRun"]) else t
    return {**later, "schedule": t.get("schedule") or d.get("schedule"), "nextRun": t.get("nextRun") or d.get("nextRun")}


def dataset_refresh(a, ctx) -> int:
    from urllib.parse import quote
    api = ctx["Api"].from_args(a)
    path = "/api/v1/bi/datasets/" + quote(a.id, safe=":") + "/refresh"
    if a.now:
        api.json("POST", path, {})
        end = time.monotonic() + a.wait
        while time.monotonic() < end:                                  # the run is recorded on the dataset when it starts; wait until it ends
            st = api.json("GET", path)
            if st.get("dataset") and st["dataset"].get("status") != "running":
                break
            time.sleep(1)
    st = api.json("GET", path)
    if a.json:
        ctx["say_json"](st)
        return 0
    tables = st.get("tables") or []
    rows = [("dataset", own(st))] if len(tables) <= 1 else ([("dataset", st["dataset"])] if st.get("dataset") else []) + [("table " + t.get("table", ""), t) for t in tables]
    print(f"{'scheduled' if st.get('scheduled') else 'no schedule'}  ({st.get('zone')}, calendar {st.get('calendar')})")
    for what, s in rows:
        took = f"  last run {s['lastRun'][:19].replace('T', ' ')} UTC by {s.get('trigger') or '?'}: {s.get('lastRows')} rows in {s.get('lastDurationMs')} ms" if s.get("lastRun") else ""
        print(f"  {what:<10}{s.get('schedule') or 'on demand':<22}{s.get('status') or ''}{took}" + (f"  next {s['nextRun']}" if s.get("nextRun") else ""))
    return 0
