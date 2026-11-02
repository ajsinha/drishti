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

"""`drishti.py cube ...`: the MR cube from the command line (docs/guides/MR_CUBE_GUIDE.md section 10; the API: /api/v1/cube), as the token's
user sees it: a view's pivot (cube query), a cell with its tail and drill (cube cell), the export (cube export), the DV01 ladder or the
heat map (cube ladder), why a cell moved (cube explain) and saved views (cube views, cube save). Every figure is the server's. Exit codes: 0,
1 (the server said no), 2 (usage)."""
from __future__ import annotations

import urllib.parse

EPILOG = ("examples:\n  drishti.py cube query \"MCUBE BANK BY MGMT.DESK,RISK_CLASS MEASURE VAR\"\n"
          "  drishti.py cube query \"MCUBE DESK:DESK-RATES BY TT_BOOK MEASURE VAR,COMP_VAR,PV\" --json\n"
          "  drishti.py cube cell \"MCUBE BANK BY MGMT.DESK\" --row MGMT~DESK-RATES\n"
          "  drishti.py cube export \"MCUBE BANK BY DESK,CURRENCY\" --format xlsx -o cube.xlsx\n"
          "  drishti.py cube ladder DESK:DESK-RATES --grid BANK-IR-TENOR\n  drishti.py cube explain DESK:DESK-RATES\n"
          "  drishti.py cube save morning \"MCUBE BANK BY MGMT.DESK,RISK_CLASS\" --share role:risk\n  drishti.py cube views")


def money(v) -> str:
    return "—" if v is None else f"{v:,.0f}"


def render(a: dict, out) -> None:
    """The pivot as text: the rows, the columns, each cell's first measure, the totals and the diversification line."""
    m = a["view"]["measures"][0]
    cols = a["columns"]
    cells = {(c["r"], c["c"]): c for c in a["cells"]}
    out(f"{a['text']} · {a['date']} · {a['canonical']} · {a['cellCount']} cells, {a['fromCache']} from the cache, {a['computeMs']} ms")
    head = f"{'/'.join(a['rowDimensions']) or '':<28}" + "".join(f"{' › '.join(c['labels'])[:16]:>18}" for c in cols) + f"{'total':>18}"
    out(head)
    for i, r in enumerate(a["rows"]):
        line = f"{' › '.join(r['labels'])[:28]:<28}"
        for j in range(len(cols)):
            c = cells.get((i, j))
            line += f"{money(c['values'].get(m)) if c else '·':>18}"
        out(line + f"{money(r['values'].get(m)):>18}")
    t = a["total"]
    out(f"{(a['canonical'] + ' (re-aggregated)')[:28]:<28}" + "".join(f"{money(c['values'].get(m)):>18}" for c in cols)
        + f"{money(t['values'].get(m)):>18}")
    d = a.get("diversification", {}).get(m)
    if d:
        out(f"Σ rows − total: {money(d.get('totalAcrossRows'))}" + (f" · Σ columns − total: {money(d.get('totalAcrossColumns'))}" if cols else ""))


def register(groups, srv, ctx: dict) -> None:
    """Adds ``cube query|cell|export|ladder|explain|views|save``."""
    import argparse

    Api, say_json = ctx["Api"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def view(a) -> dict:
        t = a.view.strip()
        if t.startswith("@"):
            return {"id": t[1:]}
        return {"text": t if t.upper().startswith("MCUBE") else "MCUBE " + t}

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.cube_cmd
        if c == "query":
            res = api.json("POST", "/api/v1/cube/mr/query", view(a))
            say_json(res) if a.json else render(res, print)
        elif c == "cell":
            res = api.json("POST", "/api/v1/cube/mr/cell", {"view": view(a), "row": a.row or "", "column": a.column or ""})
            if a.json:
                say_json(res)
            else:
                d = res["detail"]
                print(f"{res['cell'].get('scope')} · VaR {money(d['var'])} (#{d['k']} of {d['scenarios']}, {d.get('varDate')}) · ES {money(d['es'])} · "
                      f"{d['trades']} trades")
                for r in res.get("drill", {}).get("rows", [])[: a.top]:
                    print(f"  {' › '.join(r['labels']):<40} {money(r['values'].get('VAR')):>18} {r['members']:>6} trades")
        elif c == "export":
            _, body = api.request("POST", "/api/v1/cube/mr/export?" + enc({"format": a.format}), view(a))
            with open(a.out, "wb") as f:
                f.write(body)
            print(f"wrote {a.out} ({len(body):,} bytes)")
        elif c == "ladder":
            q = {"scope": a.scope, "grid": a.grid, "by": a.by, "class": a.cls}
            if a.date:
                q["asOf"] = a.date
            res = api.json("GET", "/api/v1/cube/mr/sensitivities?" + enc({k: v for k, v in q.items() if v}))
            if a.json:
                say_json(res)
            else:
                print(f"{res['mode']} · {res['canonical']} · {res['date']} · {res['riskClass']} {res['measure']} · {res['currency']}")
                print(f"{'':<10}" + "".join(f"{c:>14}" for c in res["columns"]) + f"{'net':>14}")
                for r in res["rows"]:
                    print(f"{r['key']:<10}" + "".join(f"{money(x['net']) if x['net'] is not None else '·':>14}" for x in r["cells"]) + f"{money(r['net']):>14}")
                if res.get("limits"):
                    print(res["limits"])
        elif c == "explain":
            q = {"scope": a.scope, "from": a.start, "to": a.end}
            res = api.json("GET", "/api/v1/cube/mr/explain?" + enc({k: v for k, v in q.items() if v}))
            if a.json:
                say_json(res)
            else:
                print(f"{res['canonical']}: {res['from']} {money(res['varFrom'])} → {res['to']} {money(res['varTo'])}; the steps add up: {res['addsUp']}")
                for line in res.get("lines", []):
                    print(f"  {line['label']:<36} {money(line['effect']):>18}")
        elif c == "views":
            res = api.json("GET", "/api/v1/cube/views")
            if a.json:
                say_json(res)
            else:
                for v in res:
                    print(f"{v['id']:<32} {v['view']['text']}  (shared with: {', '.join(v.get('sharedWith') or []) or 'nobody'})")
        elif c == "save":
            body = {"name": a.name, "view": view(a), "sharedWith": a.share or []}
            res = api.json("POST", "/api/v1/cube/views", body)
            say_json(res) if a.json else print(f"saved {res['id']} (version {res['version']}): MCUBE @{res['id']} opens it, as the reader")
        return 0

    cube = groups.add_parser("cube", help="cube: the MR cube: any rows and columns with the right totals, a cell, exports, the ladder, explain",
                             epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    cs = cube.add_subparsers(dest="cube_sub", required=True)
    q = cs.add_parser("query", help="a view's pivot: cells, totals re-aggregated (never sums) and the diversification", parents=[srv])
    q.add_argument("view", help="the view as text (MCUBE BANK BY MGMT.DESK,RISK_CLASS MEASURE VAR) or @owner/name")
    q.set_defaults(func=cmd, cube_cmd="query")
    ce = cs.add_parser("cell", help="one cell: its VaR, its tail and its members by the next dimension (the drill)", parents=[srv])
    ce.add_argument("view")
    ce.add_argument("--row", help="the row's key in the answer (blank: the total)")
    ce.add_argument("--column", help="the column's key (blank: the row's total)")
    ce.add_argument("--top", type=int, default=25)
    ce.set_defaults(func=cmd, cube_cmd="cell")
    ex = cs.add_parser("export", help="the view as csv, xlsx (SUM formulas where additive, values said so where not) or arrow", parents=[srv])
    ex.add_argument("view")
    ex.add_argument("--format", choices=["csv", "xlsx", "arrow", "json"], default="csv")
    ex.add_argument("-o", "--out", required=True, metavar="FILE")
    ex.set_defaults(func=cmd, cube_cmd="export")
    la = cs.add_parser("ladder", help="the DV01 ladder by a grid's buckets, or with --by two factor parts the heat map", parents=[srv])
    la.add_argument("scope")
    la.add_argument("--grid", help="BANK-IR-TENOR (default) or FRTB-GIRR")
    la.add_argument("--by", help="two factor parts for the heat map: QUALIFIER,TENOR")
    la.add_argument("--class", dest="cls", help="the risk class (GIRR by default)")
    la.add_argument("--date", metavar="YYYY-MM-DD")
    la.set_defaults(func=cmd, cube_cmd="ladder")
    xp = cs.add_parser("explain", help="why a cell's VaR moved: the engine's exact steps on its scope", parents=[srv])
    xp.add_argument("scope")
    xp.add_argument("--from", dest="start", metavar="YYYY-MM-DD")
    xp.add_argument("--to", dest="end", metavar="YYYY-MM-DD")
    xp.set_defaults(func=cmd, cube_cmd="explain")
    vs = cs.add_parser("views", help="your saved views and those shared with you", parents=[srv])
    vs.set_defaults(func=cmd, cube_cmd="views")
    sv = cs.add_parser("save", help="save a view under a name (private, or shared with --share)", parents=[srv])
    sv.add_argument("name")
    sv.add_argument("view")
    sv.add_argument("--share", action="append", metavar="WHO", help="a person or role:<role>, repeatable")
    sv.set_defaults(func=cmd, cube_cmd="save")
