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

"""`drishti.py saccr ...` and `drishti.py play saccr ...`: SA-CCR from the command line (docs/guides/SACCR_GUIDE.md section 9; the API:
/api/v1/saccr), as the token's user sees it: a scope's EAD and its netting sets (saccr show), its parts (saccr contributions), a trade's
terms (saccr trade), a new trade's incremental EAD (saccr what-if), the parameter file (saccr parameters), and the playground's workbook of
live formulas (play saccr). Every figure is the server's. Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import json
import urllib.parse

EPILOG_SACCR = ("examples:\n  drishti.py saccr show NSET:NS-ALDERSHOT-NY\n  drishti.py saccr show CPTY:CP-ALDERSHOT\n  drishti.py saccr show BANK --strict\n"
                "  drishti.py saccr show EXAMPLE:S5 --no-cap\n  drishti.py saccr contributions CPTY:CP-ALDERSHOT --by hedging-set\n"
                "  drishti.py saccr trade MX-30001436\n  drishti.py saccr what-if NS-ALDERSHOT-NY --candidates new-swap.json\n  drishti.py saccr parameters")
EPILOG_PLAY = "examples:\n  drishti.py play saccr NSET:NS-ALDERSHOT-LDN --xlsx ldn.xlsx\n  drishti.py play saccr EXAMPLE"


def money(v) -> str:
    return "—" if v is None else f"{v:,.2f}"


def render(a: dict, say) -> None:
    """The scope's answer as text: the totals, then one line per netting set."""
    t = a.get("totals") or {}
    say(f"SA-CCR {a.get('scope')} · {a.get('kind')} · {a.get('businessDate') or 'the hand-built cases'} · {a.get('regulation')} · USD")
    say(f"EAD {money(t.get('EAD'))} · RC {money(t.get('RC'))} · PFE {money(t.get('PFE'))} · {t.get('NETTING_SETS')} netting sets · "
        f"{t.get('ROWS')} rows · {t.get('EXCLUDED_TRADES')} excluded · {t.get('UNCLASSIFIED_TRADES')} unclassified")
    say(f"{'netting set':<28} {'margining':<11} {'RC':>16} {'add-on':>16} {'multiplier':>10} {'PFE':>16} {'EAD':>18}  status")
    for s in a.get("sets", []):
        mult = "—" if s.get("MULTIPLIER") is None else f"{s['MULTIPLIER']:.6f}"
        say(f"{s['nettingSet']:<28} {s.get('margining', '—'):<11} {money(s.get('RC')):>16} {money(s.get('ADD_ON')):>16} {mult:>10} {money(s.get('PFE')):>16} "
            f"{money(s.get('EAD')):>18}  {s.get('status')}" + (" (capped)" if s.get("CAPPED") else ""))
    sel = a.get("selected") or {}
    for line in sel.get("formula") or []:
        say("  " + line)


def register(groups, srv, ctx: dict) -> None:
    """Adds ``saccr show|contributions|trade|what-if|parameters`` and ``play saccr``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def params(a, **more) -> list:
        q = [(k, v) for k, v in more.items() if v not in (None, "")]
        if getattr(a, "date", None):
            q.append(("asOf", a.date))
        if getattr(a, "no_ir_offset", False):
            q.append(("irBucketOffset", "false"))
        if getattr(a, "no_cap", False):
            q.append(("cap", "false"))
        return q

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.saccr_cmd
        if c == "show":
            res = api.json("GET", "/api/v1/saccr/scope?" + enc(params(a, scope=a.scope, nettingSet=a.set, strict="true" if a.strict else None)))
            say_json(res) if a.json else render(res, print)
        elif c == "contributions":
            res = api.json("GET", "/api/v1/saccr/scope/contributions?" + enc(params(a, scope=a.scope, by=a.by)))
            if a.json:
                say_json(res)
            else:
                print(f"{res['scope']} by {res['by']} · EAD {money(res['EAD'])}")
                for r in res["rows"]:
                    print(f"  {r['key']:<40} {r['nettingSet']:<26} {r['measure']:<6} {money(r['value']):>20}"
                          + (f"  standalone EAD {money(r['standaloneEad'])}" if "standaloneEad" in r else ""))
                print(res["note"])
        elif c == "trade":
            res = api.json("GET", "/api/v1/saccr/trade/" + urllib.parse.quote(a.trade_id) + ("?" + enc(params(a)) if params(a) else ""))
            if a.json:
                say_json(res)
            else:
                print(f"{res['tradeId']} in {res['nettingSet']} on {res['businessDate']}")
                for d in res["drivers"]:
                    print(f"  driver {d['driver']}: {d['assetClass']} {d['hedgingSet']} {d['subKey']} · SD {d['SD']:.6f} · d {money(d['d'])} · δ {d['delta']:.6f} · "
                          f"MF {d['MF_UNMARGINED']:.6f}/{d['MF_MARGINED']:.6f} · D {money(d['D_UNMARGINED'])}"
                          + (f" · standalone EAD {money(d['standaloneEad'])}" if d.get("standaloneEad") is not None else ""))
        elif c == "what-if":
            try:
                with open(a.candidates, encoding="utf-8") as f:
                    cands = json.load(f)
            except (OSError, ValueError) as e:
                raise CliError(f"{a.candidates}: {e}", 2)
            body = {"nettingSet": a.netting_set, "candidates": cands if isinstance(cands, list) else [cands], "remove": a.remove or []}
            res = api.json("POST", "/api/v1/saccr/what-if" + ("?" + enc(params(a)) if params(a) else ""), body)
            if a.json:
                say_json(res)
            else:
                print(f"{res['nettingSet']}: EAD {money(res['eadBefore'])} → {money(res['eadAfter'])} · incremental {money(res['incrementalEad'])}")
                for h in res["hedgingSets"]:
                    print(f"  {h['assetClass']:<10} {h['hedgingSet']:<16} add-on {money(h['addOnBefore'])} → {money(h['addOnAfter'])}")
        elif c == "parameters":
            res = api.json("GET", "/api/v1/saccr/parameters")
            if a.json:
                say_json(res)
            else:
                print(f"{res['regulation']} {res['version']} · {res['file']} · SHA-256 {res['sha256'][:12]}…")
                for v in res["values"]:
                    print(f"  {v['key']:<44} {v['value']:>14g}  {v['ref']}")
        elif c == "play":
            if a.xlsx:
                _, body = api.request("GET", "/api/v1/saccr/playground.xlsx?" + enc(params(a, scope=a.scope)))
                with open(a.xlsx, "wb") as f:
                    f.write(body)
                print(f"wrote {a.xlsx} ({len(body):,} bytes): the SA-CCR Playground workbook, every figure a formula checked against the engine")
            else:
                res = api.json("GET", "/api/v1/saccr/playground?" + enc(params(a, scope=a.scope)))
                say_json(res) if a.json else render(res, print)
        return 0

    def switches(s) -> None:
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest)")
        s.add_argument("--no-ir-offset", action="store_true", help="IR without offsets across maturity buckets (not the regulatory figure)")
        s.add_argument("--no-cap", action="store_true", help="a margined set's EAD without the cap at its unmargined EAD (not the regulatory figure)")

    sc = groups.add_parser("saccr", help="saccr: SA-CCR exposure at default of a scope, its parts, a trade's terms, a what-if, the parameters",
                           epilog=EPILOG_SACCR, formatter_class=argparse.RawDescriptionHelpFormatter)
    ss = sc.add_subparsers(dest="saccr_sub", required=True)
    p = ss.add_parser("show", help="a scope's EAD and its netting sets (NSET:, CPTY:, LE:, BANK, EXAMPLE)", parents=[srv])
    p.add_argument("scope", metavar="SCOPE")
    p.add_argument("--set", metavar="NS-...", help="the netting set to show in full (its formula)")
    p.add_argument("--strict", action="store_true", help="refuse a scope with live derivatives that have no classification row (DRS-5411)")
    switches(p)
    p.set_defaults(func=cmd, saccr_cmd="show")
    p = ss.add_parser("contributions", help="a scope's rows by netting set, asset class, hedging set or trade", parents=[srv])
    p.add_argument("scope", metavar="SCOPE")
    p.add_argument("--by", choices=["netting-set", "asset-class", "hedging-set", "trade"], default="netting-set")
    switches(p)
    p.set_defaults(func=cmd, saccr_cmd="contributions")
    p = ss.add_parser("trade", help="a trade's SA-CCR terms per driver and its standalone EAD", parents=[srv])
    p.add_argument("trade_id", metavar="TRADE-ID")
    p.add_argument("--date", metavar="YYYY-MM-DD")
    p.set_defaults(func=cmd, saccr_cmd="trade")
    p = ss.add_parser("what-if", help="a netting set's EAD before and after candidate saccr-trade rows (each with its MTM)", parents=[srv])
    p.add_argument("netting_set", metavar="NETTING-SET")
    p.add_argument("--candidates", metavar="FILE.json", required=True, help="a saccr-trade row, or a list of them, each with MTM")
    p.add_argument("--remove", action="append", metavar="TRADE-ID", help="a trade taken out of the set, repeatable")
    p.add_argument("--date", metavar="YYYY-MM-DD")
    p.set_defaults(func=cmd, saccr_cmd="what-if")
    p = ss.add_parser("parameters", help="the regulation's parameter file: every value with its CRE52 paragraph", parents=[srv])
    p.set_defaults(func=cmd, saccr_cmd="parameters")

    ps = ctx.get("play_subparsers")
    if ps is None:
        play = groups.add_parser("play", help="play: the playgrounds from the command line", epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
        ps = play.add_subparsers(dest="play_cmd", required=True)
        ctx["play_subparsers"] = ps
    pp = ps.add_parser("saccr", help="the SA-CCR Playground: the scope's rows and the workbook of live formulas", parents=[srv],
                       epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
    pp.add_argument("scope", metavar="SCOPE", nargs="?", default="EXAMPLE")
    switches(pp)
    pp.add_argument("--xlsx", metavar="FILE.xlsx", help="write the workbook of live formulas")
    pp.set_defaults(func=cmd, saccr_cmd="play")
