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

"""`drishti.py pivot ...`: the pivot engine from the command line (docs/guides/PIVOT_GUIDE.md; the API: /api/v1/pivot), as the token's user
sees it: a view's answer (pivot run), its scan estimate (pivot estimate), an export (pivot export), the sources and a source's fields (pivot
sources, pivot fields) and saved views (pivot views, pivot save); custom functions, explain and what-if are tools/pivotfncli.py's;
the MR cube's lens on source var is pivot mr headline|cell|versions|explain|ladder|heat|whatif (docs/guides/MR_CUBE_GUIDE.md,
/api/v1/pivot/mr). Every figure is the server's. Exit codes: 0, 1 (the server said no),
2 (usage)."""
from __future__ import annotations

import json
import urllib.parse

EPILOG = ("examples:\n  drishti.py pivot sources\n  drishti.py pivot fields trade\n"
          "  drishti.py pivot run --source trade --rows productType --values \"count()\" --values \"sum(notional)\"\n"
          "  drishti.py pivot run --source trade --rows productType,counterparty --open productType=IRS --top productType=5\n"
          "  drishti.py pivot run --source var --rows DESK --cols CURRENCY --values \"var()\" --values \"es()\"\n"
          "  drishti.py pivot run --view asha/desk-risk --csv out.csv\n"
          "  drishti.py pivot export --view asha/desk-risk --format xlsx -o desk-risk.xlsx\n"
          "  drishti.py pivot export --source trade --rows productType --values \"count()\" --what definition -o view.yaml\n"
          "  drishti.py pivot save desk-risk --source var --rows DESK --values \"var()\" --share role:risk\n  drishti.py pivot views\n"
          "  drishti.py pivot functions test wret.yaml --sample rows.csv --group DESK\n"
          "  drishti.py pivot functions approve asha/wret@1 --reason \"reviewed\" --scope group:rates\n"
          "  drishti.py pivot explain --view asha/desk-risk --cell DESK=EMEA --from 2026-09-30 --to 2026-10-30 --by BOOK\n"
          "  drishti.py pivot whatif --view asha/desk-risk --scale PRICE=1.1@DESK=RATES --exclude BOOK=B-17\n"
          "  drishti.py pivot run --source var --rows MGMT.DESK --cols RISK_CLASS --values \"var()\" --values \"comp_var()\" --option horizon=10d\n"
          "  drishti.py pivot mr headline --source var --option scope=DESK:RATES\n"
          "  drishti.py pivot mr cell --source var --rows MGMT.DESK --cell MGMT.DESK=DESK-RATES\n"
          "  drishti.py pivot mr explain --scope DESK:RATES --from 2026-09-29\n"
          "  drishti.py pivot mr ladder --scope BANK --grid BANK-IR-TENOR\n"
          "  drishti.py pivot mr whatif --source var --rows MGMT.DESK --copy MX-20000001 --factor -1")


def fmt(v) -> str:
    if v is None:
        return "·"
    if isinstance(v, (int, float)) and abs(v) >= 1e6:
        return f"{v:,.0f}"
    return f"{v:,.2f}" if isinstance(v, float) else str(v)


def build_view(a) -> dict:
    """The view from the arguments: a saved view (--view owner/name), a JSON file (--json-view), or the flags."""
    if getattr(a, "json_view", None):
        with open(a.json_view, encoding="utf-8") as f:
            return json.load(f)
    if getattr(a, "view", None):
        return {"view": a.view.lstrip("@")}
    if not a.source:
        raise SystemExit("pivot: name a --source (drishti.py pivot sources lists them) or a saved --view owner/name")
    v: dict = {"source": a.source, "rows": [x for x in (a.rows or "").split(",") if x],
               "columns": [x for x in (a.cols or "").split(",") if x], "values": [{"measure": m} for m in (a.values or ["count()"])],
               "filters": {}, "expanded": []}
    if a.date:
        v["date"] = a.date
    for o in getattr(a, "option", None) or []:
        k, _, val = o.partition("=")
        v.setdefault("options", {})[k.strip()] = val.strip()
    for f in a.filter or []:
        k, _, vals = f.partition("=")
        v["filters"][k] = {"in": [x for x in vals.split(",") if x]}
    for o in a.open or []:
        path = []
        for part in o.split("/"):
            k, _, val = part.partition("=")
            path.append([k, val])
        v["expanded"].append({"axis": "rows", "path": path})
    if a.level:
        v["expandTo"] = {"rows": a.level}
    for t in a.top or []:
        k, _, n = t.partition("=")
        v["top"] = {**v.get("top", {}), k: {"n": int(n or 10)}}
    return v


def render(a: dict, out) -> None:
    """The answer as text: the rows as shown (indented by level), each column's figures, the totals."""
    ms = a["measures"]
    cols = a["columns"]
    comp = a.get("computed", {})
    out(f"{a.get('title') or a['source']} · {a.get('date') or 'current'} · {a['cellCount']} cells · {comp.get('sets')} sets, "
        f"{comp.get('fromCache')} from the cache · {comp.get('queries')} queries · {comp.get('ms')} ms · {a.get('exactness')}")
    heads = [f"{c['label'][:14]}|{m['label'][:12]}" if len(cols) > 1 else m["label"][:24] for c in cols for m in ms]
    out(f"{' › '.join(a['rowDimensions']) or '':<34}" + "".join(f"{h:>26}" for h in heads))
    for r in a["rows"]:
        lab = ("  " * max(0, r["depth"] - 1) + ("+ " if r.get("expandable") and not r.get("expanded") and r["kind"] == "node" else
                                                  "- " if r["kind"] == "header" else "") + r["label"])[:34]
        line = f"{lab:<34}"
        for c in cols:
            cell = None if r["kind"] == "header" else a["cells"].get(r["key"] + "|" + c["key"])
            for m in ms:
                line += f"{fmt(cell['values'].get(m['label'])) if cell else '':>26}"
        out(line)
    if (a.get("computedAs") or {}).get("words"):
        out(f"computed as: {a['computedAs']['words']}")
    for n in a.get("notes", []):
        out("note: " + n)


def register(groups, srv, ctx: dict) -> None:
    """Adds ``pivot sources|fields|run|estimate|export|views|save``."""
    import argparse
    Api, say_json = ctx["Api"], ctx["say_json"]

    def body(a) -> dict:
        v = build_view(a)
        return v if "view" in v and isinstance(v["view"], str) else {"view": v}

    def cmd(a, extra=None) -> int:
        api = Api.from_args(a)
        c = a.pivot_cmd
        if c == "sources":
            res = api.json("GET", "/api/v1/pivot/sources")
            if a.json:
                say_json(res)
            else:
                for s in res["sources"]:
                    print(f"{s['source']:<28} {s['provider']:<10} {s['title']}")
        elif c == "fields":
            q = {"source": a.name}
            if a.date:
                q["date"] = a.date
            res = api.json("GET", "/api/v1/pivot/catalogue?" + urllib.parse.urlencode(q))
            if a.json:
                say_json(res)
            else:
                print(f"{res['title']} ({res['provider']}) · {res.get('date') or 'current'} · {res.get('masked', 0)} masked for you")
                for f in res["fields"]:
                    print(f"  {f['name']:<32} {f['type']}")
                for m in res.get("measures", []):
                    print(f"  {m['name'] + '()':<32} {m['kind']}: {m['description']}")
                if res.get("functions"):
                    print("  functions: " + ", ".join(f"{f['name']}({f['args']})" for f in res["functions"]))
        elif c == "run":
            q = body(a)
            if a.exact or a.approximate:
                q["exact"] = bool(a.exact)          # exact on request, or every distinct count and percentile by its sketch
            if a.no_rollups:
                q["rollups"] = False                # the screen's "Use rollups" off: the table is read
            res = api.json("POST", "/api/v1/pivot/query", q)
            if a.csv:
                b = body(a)
                b.update({"format": "csv", "shape": "grid"})
                _, raw = api.request("POST", "/api/v1/pivot/export", b)
                with open(a.csv, "wb") as f:
                    f.write(raw)
                print(f"wrote {a.csv} ({len(raw):,} bytes)")
            elif a.json:
                say_json(res)
            else:
                render(res, print)
        elif c == "estimate":
            res = api.json("POST", "/api/v1/pivot/estimate", body(a))
            say_json(res) if a.json else print(res["words"])
        elif c == "export":
            b = body(a)
            b.update({"format": a.format, "what": a.what})
            if a.cell:
                b["coords"] = dict(p.partition("=")[::2] for p in a.cell.split(","))
            _, raw = api.request("POST", "/api/v1/pivot/export", b)
            with open(a.out, "wb") as f:
                f.write(raw)
            print(f"wrote {a.out} ({len(raw):,} bytes)")
        elif c == "views":
            res = api.json("GET", "/api/v1/pivot/views")
            if a.json:
                say_json(res)
            else:
                for v in res["views"]:
                    print(f"{v['id']:<32} @{v.get('version')} {v['view']['source']}  (shared with: {', '.join(v.get('sharedWith') or []) or 'nobody'})")
        elif c == "mr":
            return mr(api, a)
        elif c == "save":
            v = build_view(a)
            res = api.json("POST", "/api/v1/pivot/views", {"name": a.name, "view": v, "sharedWith": a.share or []})
            say_json(res) if a.json else print(f"saved {res['id']} (version {res['version']}): PIVOT @{res['id']} opens it, as the reader")
        elif c == "live":
            if a.action == "start":
                q = body(a)
                if a.approximate:
                    q["exact"] = False
                res = api.json("POST", "/api/v1/pivot/live", q)
                say_json(res) if a.json else print(f"live {res['id']} on {res.get('node') or 'this server'}: {res.get('words', '')}\n"
                                                    f"stream: GET /api/v1/pivot/live/{res['id']}/events · stop: drishti.py pivot live stop {res['id']}")
            elif a.action == "list":
                res = api.json("GET", "/api/v1/pivot/live")
                if a.json:
                    say_json(res)
                else:
                    for x in res.get("live", []):
                        print(f"{x['id']:<18} {x['source']:<20} {x.get('date') or 'latest'} · {x['recomputes']} recomputes, {x['unchanged']} unchanged"
                              f"{' · streaming' if x.get('streaming') else ''}")
            else:
                api.request("DELETE", f"/api/v1/pivot/live/{urllib.parse.quote(a.id or '')}")
                print(f"stopped {a.id}")
        elif c == "comments":
            base = "/api/v1/pivot/views/" + "/".join(urllib.parse.quote(x) for x in a.view_id.split("/", 1)) + "/comments"
            if a.add:
                spec = {"coords": dict(p.partition("=")[::2] for p in a.cell.split(",")) if a.cell and a.cell != "*" else {}, "text": a.add}
                if a.measure:
                    spec["measure"] = a.measure
                res = api.json("POST", base, spec)
                say_json(res) if a.json else print(f"comment {res['id']} on {res['cell']} (version {res['version']})")
            elif a.resolve or a.reopen:
                cid = a.resolve or a.reopen
                res = api.json("POST", f"{base}/{urllib.parse.quote(cid)}/{'resolve' if a.resolve else 'reopen'}", {})
                say_json(res) if a.json else print(f"{cid}: {'resolved' if a.resolve else 'open again'}")
            elif a.delete:
                api.json("DELETE", f"{base}/{urllib.parse.quote(a.delete)}")
                print(f"deleted {a.delete}")
            else:
                q = {k: v for k, v in {"cell": a.cell or "", "open": "true" if a.open else ""}.items() if v}
                res = api.json("GET", base + ("?" + urllib.parse.urlencode(q) if q else ""))
                if a.json:
                    say_json(res)
                else:
                    for x in res.get("comments", []):
                        state = f"resolved by {x['resolved']['by']}" if x.get("resolved") else "open"
                        print(f"{x['id']:<16} {x['cell']:<28} {x['by']:<12} {x['at'][:16]} {state}{' · older version' if x.get('older') else ''}\n"
                              f"    {x['text']}")
        elif c == "rollups":
            if a.action == "list":
                res = api.json("GET", "/api/v1/pivot/rollups")
                if a.json:
                    say_json(res)
                else:
                    built = {r["id"]: r for r in res.get("built", [])}
                    for d in res.get("definitions", []):
                        b = built.get(d["id"])
                        state = f"{b['rows']:,} rows at {b['version']} ({b['lastRefresh']})" if b else "not built yet"
                        print(f"{d['id']:<28} {d['source']} by {' x '.join(d['grain']) or 'nothing'}: {state}")
                    for x in res.get("advice", []):
                        print("advice: " + x["words"])
            elif a.action == "define":
                spec = {"id": a.id, "source": a.source, "grain": (a.grain or "").split(",") if a.grain else [],
                        "functions": a.functions or [], "dates": a.dates}
                res = api.json("POST", "/api/v1/pivot/rollups", spec)
                say_json(res) if a.json else print(f"defined {res['id']}: refresh {res.get('refresh')}")
            elif a.action == "refresh":
                res = api.json("POST", f"/api/v1/pivot/rollups/{urllib.parse.quote(a.id)}/refresh" + (f"?date={a.date}" if a.date else ""), {})
                say_json(res) if a.json else print(res["words"])
            elif a.action == "remove":
                res = api.json("DELETE", f"/api/v1/pivot/rollups/{urllib.parse.quote(a.id)}")
                say_json(res) if a.json else print(f"removed {res['removed']}")
        return 0

    def view_args(p) -> None:
        p.add_argument("--view", help="a saved view: owner/name")
        p.add_argument("--json-view", metavar="FILE", help="a view as JSON (what the API takes)")
        p.add_argument("--source", help="a table (drishti.py pivot sources) or var")
        p.add_argument("--date", metavar="YYYY-MM-DD")
        p.add_argument("--rows", help="row fields, outermost first: LEGAL_ENTITY,DESK (a date groups: TRADE_DATE:month)")
        p.add_argument("--cols", help="column fields")
        p.add_argument("--values", action="append", help="a measure: sum(NOTIONAL), count(), median(PNL), var(); repeatable")
        p.add_argument("--filter", action="append", metavar="FIELD=V1,V2", help="keep these values of a field; repeatable")
        p.add_argument("--open", action="append", metavar="DIM=V[/DIM=V]", help="open a row node (lazily computed); repeatable")
        p.add_argument("--level", type=int, help="open every row node down to this level")
        p.add_argument("--top", action="append", metavar="FIELD=N", help="the top N of a level, the rest in Others")
        p.add_argument("--option", action="append", metavar="KEY=V", help="a provider option; the VaR source's: scope, horizon (1d, 10d), "
                       "method (sqrt10, sum), confidence, esConfidence, set (normal, stressed), ccy; repeatable")

    def mr(api, a) -> int:
        w = a.what
        if w in ("headline", "cell", "whatif"):
            b = body(a)
            if w == "cell":
                b["coords"] = dict(p.partition("=")[::2] for p in (a.cell or "").split(",") if p)
            if w == "whatif":
                if not a.copy:
                    raise SystemExit("pivot mr whatif: name a booked trade to copy (--copy MX-1) and its --factor")
                b["items"] = [{"copyOf": a.copy, "factor": a.factor}]
            res = api.json("POST", "/api/v1/pivot/mr/" + {"headline": "headline", "cell": "detail", "whatif": "what-if"}[w], b)
        elif w == "versions":
            res = api.json("GET", "/api/v1/pivot/mr/versions")
        else:
            q = {"scope": a.scope or "BANK"}
            if w == "explain":                           # a cell's move: the one Explain of its VaR (kind var), Why it moved
                q.update({k: v for k, v in (("from", a.from_), ("to", a.to)) if v})
                q.update(kind="var", part="why")
                path = "/api/v1/explain?"
            else:
                q.update({k: v for k, v in (("grid", a.grid), ("asOf", a.date)) if v})
                if w == "heat":
                    q["by"] = a.by or "QUALIFIER,TENOR"
                path = "/api/v1/pivot/mr/sensitivities?"
            res = api.json("GET", path + urllib.parse.urlencode(q))
            if w == "explain":
                res = (res.get("why") or {}).get("answer", {})
        if a.json or w in ("versions", "ladder", "heat", "explain"):
            say_json(res)
        elif w == "headline":
            print(f"{res['scope']} · {res['date']} · {res['lens']}: VaR {fmt(res['var'])}  ES {fmt(res['es'])}"
                  + (f"  limit use {res['limitUse']:.1%}" if res.get("limitUse") is not None else "") + f"  · {res['trades']:,} trades")
        elif w == "cell":
            print(f"{res.get('scope') or 'no scope text'} · VaR {fmt(res['var'])} (#{res['k']} of {res['scenarios']}, {res.get('varDate')})  "
                  f"ES {fmt(res['es'])} · {res['trades']} trades in {res['positions']} positions")
        else:
            for r in res["rows"]:
                print(f"{r['label'][:32]:<34}" + (r.get("note") or f"{fmt(r.get('before')):>18}{fmt(r.get('after')):>18}{fmt(r.get('incremental')):>18}"))
        return 0

    pv = groups.add_parser("pivot", help="pivot: any table or VaR as a pivot computed by the server: run, estimate, export, saved views",
                           epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    ps = pv.add_subparsers(dest="pivot_sub", required=True)
    s = ps.add_parser("sources", help="the tables (and VaR) you may pivot", parents=[srv])
    s.set_defaults(func=cmd, pivot_cmd="sources")
    f = ps.add_parser("fields", help="a source's fields, measures and functions, as you may see them", parents=[srv])
    f.add_argument("name")
    f.add_argument("--date", metavar="YYYY-MM-DD")
    f.set_defaults(func=cmd, pivot_cmd="fields")
    r = ps.add_parser("run", help="a view's answer: rows as shown, every cell from its own members", parents=[srv])
    view_args(r)
    r.add_argument("--csv", metavar="FILE", help="write the grid as CSV instead of printing it")
    ex = r.add_mutually_exclusive_group()
    ex.add_argument("--exact", action="store_true", help="exact on request: distinct counts and percentiles from their values at any size")
    ex.add_argument("--approximate", action="store_true", help="distinct counts and percentiles by their sketches (≈, with a stated error) at any size; never VaR")
    r.add_argument("--no-rollups", action="store_true", help="read the table even when a rollup covers the view (the screen's Use rollups off)")
    r.set_defaults(func=cmd, pivot_cmd="run")
    lv = ps.add_parser("live", help="live pivots: start one (its first answer and id), list yours, stop one", parents=[srv])
    lv.add_argument("action", choices=["start", "list", "stop"])
    lv.add_argument("id", nargs="?", help="stop: the live pivot's id")
    view_args(lv)
    lv.add_argument("--approximate", action="store_true", help="a live view of the sketches (≈); a live view is exact otherwise")
    lv.set_defaults(func=cmd, pivot_cmd="live")
    cm = ps.add_parser("comments", help="cell comments of a saved view: list, add, resolve, reopen, delete", parents=[srv])
    cm.add_argument("view_id", metavar="OWNER/NAME")
    cm.add_argument("--cell", metavar="DIM=V,DIM=V", help="the cell (* the grand total); with no --add: list that cell's comments")
    cm.add_argument("--open", action="store_true", help="list only the comments not resolved")
    cm.add_argument("--add", metavar="TEXT", help="add a comment on --cell")
    cm.add_argument("--measure", help="with --add: the measure the comment is about")
    cm.add_argument("--resolve", metavar="ID")
    cm.add_argument("--reopen", metavar="ID")
    cm.add_argument("--delete", metavar="ID")
    cm.set_defaults(func=cmd, pivot_cmd="comments")
    e = ps.add_parser("estimate", help="what the view reads before it runs", parents=[srv])
    view_args(e)
    e.set_defaults(func=cmd, pivot_cmd="estimate")
    x = ps.add_parser("export", help="xlsx (outline, SUM formulas only where a figure adds up, Provenance), csv, arrow, or the definition", parents=[srv])
    view_args(x)
    x.add_argument("--format", choices=["xlsx", "csv", "arrow"], default="xlsx")
    x.add_argument("--what", choices=["pivot", "rows", "definition"], default="pivot")
    x.add_argument("--cell", metavar="DIM=V,DIM=V", help="with --what rows: the cell whose rows are exported")
    x.add_argument("-o", "--out", required=True, metavar="FILE")
    x.set_defaults(func=cmd, pivot_cmd="export")
    m = ps.add_parser("mr", help="the MR cube's lens on source var: headline, a cell's tail, the slider's dates, explain, the DV01 ladder, the "
                      "heat map, the what-if side by side", parents=[srv])
    m.add_argument("what", choices=["headline", "cell", "versions", "explain", "ladder", "heat", "whatif"])
    view_args(m)
    m.add_argument("--cell", metavar="DIM=V,DIM=V", help="mr cell: the cell's coordinates")
    m.add_argument("--scope", help="mr explain, ladder, heat: a scope (BANK, DESK:RATES, a cell's scope text)")
    m.add_argument("--from", dest="from_", metavar="YYYY-MM-DD", help="mr explain: the earlier date (default: the date before)")
    m.add_argument("--to", metavar="YYYY-MM-DD", help="mr explain: the later date (default: the latest)")
    m.add_argument("--grid", help="mr ladder: the bucket grid (BANK-IR-TENOR, FRTB-GIRR)")
    m.add_argument("--by", help="mr heat: two factor parts, rows then columns (QUALIFIER,TENOR)")
    m.add_argument("--copy", help="mr whatif: a booked trade whose vector is copied")
    m.add_argument("--factor", type=float, default=-1.0, help="mr whatif: the copy's scale (default -1: unwind)")
    m.set_defaults(func=cmd, pivot_cmd="mr")
    vs = ps.add_parser("views", help="your saved views and those shared with you", parents=[srv])
    vs.set_defaults(func=cmd, pivot_cmd="views")
    sv = ps.add_parser("save", help="save a view under a name (private, or shared with --share)", parents=[srv])
    sv.add_argument("name")
    view_args(sv)
    sv.add_argument("--share", action="append", metavar="WHO", help="a person or role:<role>, repeatable")
    sv.set_defaults(func=cmd, pivot_cmd="save")
    ru = ps.add_parser("rollups", help="rollups of partial states: list (with advice), define, refresh, remove (administrators change them)",
                       parents=[srv])
    ru.add_argument("action", choices=["list", "define", "refresh", "remove"])
    ru.add_argument("id", nargs="?", help="the rollup's id (define, refresh, remove)")
    ru.add_argument("--source", help="define: the source")
    ru.add_argument("--grain", help="define: dimensions, DESK,CURRENCY,TRADE_DATE:month")
    ru.add_argument("--functions", action="append", help="define: a function kept, sum(NOTIONAL_USD); repeatable")
    ru.add_argument("--dates", choices=["latest", "all"], default="latest")
    ru.add_argument("--date", metavar="YYYY-MM-DD", help="refresh: the date (default: the source's latest)")
    ru.set_defaults(func=cmd, pivot_cmd="rollups")
    import importlib.util  # noqa: WPS433 (custom functions, explain and what-if: tools/pivotfncli.py)
    import os  # noqa: WPS433
    spec = importlib.util.spec_from_file_location("pivotfncli", os.path.join(os.path.dirname(os.path.abspath(__file__)), "pivotfncli.py"))
    fncli = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fncli)
    fncli.register(ps, srv, ctx, view_args, body)
