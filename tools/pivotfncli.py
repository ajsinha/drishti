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

"""`drishti.py pivot functions|explain|whatif`: custom aggregation functions, explanations and what-ifs of the pivot engine
(docs/guides/BI_GUIDE.md sections on custom functions, explain and what-if; the API: /api/v1/pivot/functions, /explain, /whatif).

    drishti.py pivot functions list
    drishti.py pivot functions test wret.yaml --sample rows.csv [--group DESK]
    drishti.py pivot functions save wret.yaml --sample rows.csv
    drishti.py pivot functions submit asha/wret@1 [--note "..."]
    drishti.py approvals approve function asha:wret@1 --comment "reviewed" [--param scope=group:rates]   (the one Approvals queue)
    drishti.py pivot functions revoke asha/wret@1 --reason "..."
    drishti.py pivot explain --source trade --rows DESK --values "sum(NOTIONAL)" --cell DESK=RATES --other-cell DESK=FX --by CURRENCY
    drishti.py pivot explain --view asha/desk-risk --cell DESK=EMEA --from 2026-09-30 --to 2026-10-30 --by BOOK
    drishti.py pivot whatif --view asha/desk-risk --scale PRICE=1.1@DESK=RATES --exclude BOOK=B-17 --add-json rows.json

A function file is YAML or JSON: name, language (formula, sql, python, java), code, entry, inputs, kind, tolerance, description. Sample rows
are CSV with a header (or JSON). Exit codes: 0, 1 (the server said no), 2 (usage).
"""
from __future__ import annotations

import csv
import json


def _definition(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if path.endswith(".py"):
        raise SystemExit("pivot functions: a .py file is the code; write a small YAML with name, language: python, entry and code: |")
    try:
        return json.loads(text)
    except ValueError:
        import yaml  # noqa: WPS433 (the CLI ships with PyYAML)
        return yaml.safe_load(text)


def _sample(path: str | None) -> list:
    if not path:
        return []
    if path.endswith(".json"):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            out = {}
            for k, v in r.items():
                try:
                    out[k] = float(v) if v not in ("", None) else None
                except ValueError:
                    out[k] = v
            rows.append(out)
    return rows


def _ref(text: str) -> tuple[str, str]:
    owner, _, rest = text.partition("/")
    if not owner or "@" not in rest:
        raise SystemExit(f"pivot functions: name a version: owner/name@3, not {text}")
    return owner, rest


def _coords(text: str | None) -> dict:
    out = {}
    for part in (text or "").split(","):
        if part.strip():
            k, _, v = part.partition("=")
            out[k.strip()] = v.strip()
    return out


def register(ps, srv, ctx: dict, view_args, body) -> None:
    """Adds ``pivot functions ...``, ``pivot explain`` and ``pivot whatif`` to the ``pivot`` group's subcommands ``ps``."""
    Api, say_json = ctx["Api"], ctx["say_json"]

    def functions(a, extra=None) -> int:
        api = Api.from_args(a)
        c = a.fn_cmd
        if c == "list":
            res = api.json("GET", "/api/v1/pivot/functions")
            if a.json:
                say_json(res)
                return 0
            on = ", ".join(k for k, v in res.get("languages", {}).items() if v)
            print(f"languages on: {on}")
            for f in res["functions"]:
                print(f"{f['ref']:<36} {f['language']:<8} {f['kind']:<10} {f['status']:<10} {f.get('description') or ''}")
            return 0
        if c in ("test", "save"):
            d = _definition(a.file)
            d["sample"] = _sample(a.sample)
            if c == "test":
                if a.group:
                    d["group"] = a.group
                res = api.json("POST", "/api/v1/pivot/functions/test", d)
                if a.json:
                    say_json(res)
                    return 0
                h = res["harness"]
                print(("PASSED" if h["passed"] else "FAILED") + f": {h['function']} ({h['kind']}), one pass {h['onePass']}, {h['rows']} rows, "
                      f"{h['nsPerRow']} ns a row, state {h['stateBytes']} bytes")
                for ch in h["checks"]:
                    print(f"  {'ok ' if ch['passed'] else 'NO '} {ch['check']:<20} {ch['detail']}")
                pg = res.get("playground") or {}
                for s in pg.get("steps", []):
                    print(f"  {s['what']:<34} {s['value']}  ({s['bytes']} bytes)")
                return 0 if h["passed"] else 1
            res = api.json("POST", "/api/v1/pivot/functions", d)
            say_json(res) if a.json else print(f"saved {res['ref']} ({res['kind']}): private until an approver who is not you approves it")
            return 0
        owner, rest = _ref(a.ref)
        path = f"/api/v1/pivot/functions/{owner}/{rest}/{c}"
        payload = {"submit": {"note": a.note or ""}, "revoke": {"reason": a.reason or ""}}[c]
        res = api.json("POST", path, payload)
        say_json(res) if a.json else print(f"{res['ref']}: {res['status']}")
        return 0

    def explain(a, extra=None) -> int:
        api = Api.from_args(a)
        b = body(a)
        b.update({"kind": "pivot", "measure": "cell", "part": "why", "from": _coords(a.cell), "by": a.by})
        if a.other_cell:
            b["to"] = _coords(a.other_cell)
        if a.from_date:
            b["fromDate"] = a.from_date
        if a.to_date:
            b["toDate"] = a.to_date
        if a.measure:
            b["value"] = a.measure                                # the value of the view to explain (its label)
        for k in ("order", "price", "quantity"):
            if getattr(a, k):
                b[k] = getattr(a, k)
        out = api.json("POST", "/api/v1/explain", b)              # the one Explain, kind pivot (API_REFERENCE.md, "Explain")
        if a.json:
            say_json(out)
            return 0
        res = (out.get("why") or {}).get("answer") or {}
        print(res["words"])
        print(f"  {res['method']}")
        for s in res["steps"]:
            print(f"  {str(s['member']):<28} {s['cause']:<9} {s['step']:>18,.4f}   cumulative {s['cumulative']:,.4f}")
        r = res["residual"]
        print(f"  {'residual':<28} {'':<9} {r['value']:>18,.4f}   ({r['name']})")
        print(f"  difference {res['difference']:,.4f}: the steps add up")
        return 0

    def whatif(a, extra=None) -> int:
        api = Api.from_args(a)
        b = body(a)
        o: dict = {"exclude": {}, "changes": [], "add": []}
        for e in a.exclude or []:
            k, _, vals = e.partition("=")
            o["exclude"].setdefault(k, []).extend(x for x in vals.split(",") if x)
        for kind, items in (("scale", a.scale or []), ("set", a.set or [])):
            for s in items:
                what, _, where = s.partition("@")
                field, _, value = what.partition("=")
                ch = {"field": field, kind: float(value), "where": {}}
                for k, v in _coords(where.replace("/", ",")).items():
                    ch["where"][k] = [v]
                o["changes"].append(ch)
        if a.add_json:
            with open(a.add_json, encoding="utf-8") as f:
                o["add"] = json.load(f)
        b["whatIf"] = o
        res = api.json("POST", "/api/v1/pivot/whatif", b)
        if a.json:
            say_json(res)
            return 0
        changed = [(k, d) for k, d in res["diff"].items() if d.get("status") or any(x for x in (d.get("delta") or {}).values())]
        print(f"{len(changed)} cell(s) change; differences are what-if minus original")
        for k, d in changed[:50]:
            print(f"  {k:<40} {d.get('status') or ', '.join(f'{m} {v:+,.2f}' for m, v in d['delta'].items() if v)}")
        return 0

    fn = ps.add_parser("functions", help="custom aggregation functions: list, test (the harness and the playground), save, submit, revoke (approve: approvals)",
                       parents=[srv])
    fs = fn.add_subparsers(dest="fn_sub", required=True)
    fl = fs.add_parser("list", help="your functions and those approved for you", parents=[srv])
    fl.set_defaults(func=functions, fn_cmd="list")
    for name, hlp in (("test", "run the harness and the playground on sample rows (nothing saved)"),
                      ("save", "save a new version after its harness passes")):
        p = fs.add_parser(name, help=hlp, parents=[srv])
        p.add_argument("file", help="the function: YAML or JSON (name, language, code, entry, inputs, kind, ...)")
        p.add_argument("--sample", metavar="ROWS.csv", help="sample rows: CSV with a header, or JSON")
        if name == "test":
            p.add_argument("--group", help="a column of the sample to try the function in a little pivot by")
        p.set_defaults(func=functions, fn_cmd=name)
    for name, hlp in (("submit", "ask for a version to be shared (an approver approves it in `approvals`)"),
                      ("revoke", "withdraw a version: views using it say so in its cells")):
        p = fs.add_parser(name, help=hlp, parents=[srv])
        p.add_argument("ref", help="owner/name@version")
        p.add_argument("--reason")
        p.add_argument("--note")
        p.set_defaults(func=functions, fn_cmd=name)

    ex = ps.add_parser("explain", help="the difference between two cells, or a cell on two dates, in steps that add up", parents=[srv])
    view_args(ex)
    ex.add_argument("--cell", required=True, metavar="DIM=V,DIM=V", help="the first cell (empty: the grand total)")
    ex.add_argument("--other-cell", metavar="DIM=V,DIM=V", help="the second cell (default: the same cell)")
    ex.add_argument("--from", dest="from_date", metavar="YYYY-MM-DD", help="the first cell's date")
    ex.add_argument("--to", dest="to_date", metavar="YYYY-MM-DD", help="the second cell's date")
    ex.add_argument("--by", required=True, help="the dimension whose members split the difference")
    ex.add_argument("--measure", help="the value explained (default: the first)")
    ex.add_argument("--order", choices=["member", "size"], help="the order of the steps")
    ex.add_argument("--price", help="with --quantity: price, quantity and cross effects")
    ex.add_argument("--quantity")
    ex.set_defaults(func=explain)

    wi = ps.add_parser("whatif", help="the view with an overlay (members left out, fields scaled or set, rows added) beside the original",
                       parents=[srv])
    view_args(wi)
    wi.add_argument("--exclude", action="append", metavar="FIELD=V1,V2", help="leave these members out; repeatable")
    wi.add_argument("--scale", action="append", metavar="FIELD=X[@DIM=V/DIM=V]", help="scale a number field where the condition holds")
    wi.add_argument("--set", action="append", metavar="FIELD=X[@DIM=V]", help="set a number field where the condition holds")
    wi.add_argument("--add-json", metavar="ROWS.json", help="hypothetical rows to add (a JSON list of objects)")
    wi.set_defaults(func=whatif)

