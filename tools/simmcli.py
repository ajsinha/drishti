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

"""`drishti.py simm ...` and `drishti.py play simm ...`: ISDA SIMM from the command line (docs/guides/SIMM_GUIDE.md section 9; the API:
/api/v1/risk/simm), as the token's user sees it: a netting set's IM to collect and post (simm show), every netting set of the date (simm list),
the steps (simm explain), the margin against the counterparty's call (simm against), the installed parameter versions (simm versions),
and the playground: a comparison of one switch, the workbook of live formulas, a saved recipe and its replay to the cent (play simm).
Exit codes: 0, 1 (the server said no, or a recipe did not reproduce), 2 (usage)."""
from __future__ import annotations

import urllib.parse

EPILOG_SIMM = ("examples:\n  drishti.py simm show NS-ALDERSHOT-LDN\n  drishti.py simm show NS-ALDERSHOT-LDN --concentration off\n"
               "  drishti.py simm list --counterparty CP-ALDERSHOT\n  drishti.py simm explain NS-ALDERSHOT-LDN --direction post --class Equity\n"
               "  drishti.py simm against NS-ALDERSHOT-LDN\n  drishti.py simm versions\n  drishti.py simm show EXAMPLE")
EPILOG_PLAY = ("examples:\n  drishti.py play simm NS-CORAL-NY --compare hvr=off\n  drishti.py play simm NS-CORAL-NY --side post --xlsx coral.xlsx\n"
               "  drishti.py play simm EXAMPLE --save\n  drishti.py play simm --recipe SM-1A2B3C4D-XYZ")


def money(v) -> str:
    return "—" if v is None or v == "—" else f"{v:,.2f}"


def render(a: dict, say) -> None:
    say(f"SIMM {a.get('NETTING_SET') or a.get('SCOPE')} · {a.get('COUNTERPARTY') or a.get('label') or ''} · {a.get('BUSINESS_DATE') or 'the hand-built examples'}"
        f" · {a.get('CALCULATION_CURRENCY')} · SIMM {a.get('SIMM_VERSION')}" + ("" if a.get("REGULATORY") or a.get("KIND") != "netting set" else " · NOT THE REGULATORY FIGURE"))
    say(f"IM collect {money(a.get('IM_COLLECT'))} ({a.get('COLLECT_REGIME')}) · IM post {money(a.get('IM_POST'))} ({a.get('POST_REGIME')})"
        f" · add-ons {money(a.get('ADDON_COLLECT'))} / {money(a.get('ADDON_POST'))}")
    say(f"{'product class':<14} {'collect':>20} {'post':>20}")
    for p in a.get("BY_PRODUCT_CLASS", []):
        say(f"{p['PRODUCT_CLASS']:<14} {money(p['COLLECT']):>20} {money(p['POST']):>20}")
    say(f"{'product · risk class':<26} {'delta':>16} {'vega':>16} {'curvature':>16} {'IM':>18}")
    for r in a.get("BY_RISK_CLASS", []):
        say(f"{r['PRODUCT_CLASS'] + ' · ' + r['RISK_CLASS']:<26} {money(r['DELTA']):>16} {money(r['VEGA']):>16} {money(r['CURVATURE']):>16} {money(r['TOTAL']):>18}")
    if a.get("MISSING_TRADES"):
        say(f"{a['MISSING_TRADES']} trades of the netting set have no sensitivities: incomplete")
    if a.get("notRegulatory"):
        say(a["notRegulatory"])


def register(groups, srv, ctx: dict) -> None:
    """Adds ``simm show|list|explain|against|versions`` and ``play simm``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def params(a, **more) -> list:
        q = [(k, v) for k, v in more.items() if v not in (None, "")]
        for attr, name in (("date", "asOf"), ("version", "version"), ("concentration", "concentration"), ("hvr", "hvr")):
            if getattr(a, attr, None):
                q.append((name, getattr(a, attr)))
        for o in getattr(a, "override", None) or []:
            q.append(("override", o))
        return q

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.simm_cmd
        if c == "show":
            res = api.json("GET", "/api/v1/risk/simm?" + enc(params(a, nettingSet=a.netting_set)))
            say_json(res) if a.json else render(res, print)
        elif c == "list":
            res = api.json("GET", "/api/v1/risk/simm/netting-sets?" + enc(params(a, counterparty=a.counterparty)))
            if a.json:
                say_json(res)
            else:
                print(f"{res['date']} · default SIMM {res.get('defaultVersion') or 'none installed'} · {res['computed']} computed")
                for r in res["rows"]:
                    print(f"{r['nettingSet']:<26} {r.get('counterparty') or '':<18} {money(r.get('imCollect')):>20} {money(r.get('imPost')):>20}  {r['status']}")
                print(f"{'sum (no netting across netting sets)':<45} {money(res['sumCollect']):>20} {money(res['sumPost']):>20}")
                if res.get("notice"):
                    print(res["notice"])
        elif c == "explain":
            say_json(api.json("GET", "/api/v1/risk/simm/explain?" + enc(params(a, nettingSet=a.netting_set, direction=a.direction, **{"class": a.cls}))))
        elif c == "against":
            res = api.json("GET", "/api/v1/risk/simm/against?" + enc(params(a, nettingSet=a.netting_set)))
            if a.json:
                say_json(res)
            else:
                for r in res["rows"]:
                    print(f"{r['direction']:<8} Drishti {money(r['drishti']):>18} · theirs {money(r['theirs']):>18} · difference {money(r['difference']):>16} · {r['status']}")
                if res.get("note"):
                    print(res["note"])
        elif c == "versions":
            res = api.json("GET", "/api/v1/risk/simm/versions")
            if a.json:
                say_json(res)
            else:
                print(f"{res['parametersDir']} · default {res.get('default') or 'none'}")
                for v in res["versions"]:
                    print(f"  {v['version']:<16} {v['file']:<28} " + (f"installed, SHA-256 {v['sha256'][:12]}…" if v["installed"] else f"refused: {v['problem'][:140]}"))
                print(res["licence"])
        elif c == "play":
            if a.recipe:
                res = api.json("GET", "/api/v1/risk/simm/playground/recipes/" + urllib.parse.quote(a.recipe))
                if a.json:
                    say_json(res)
                else:
                    render(res, print)
                    print(res["reproduced"])
                return 0 if res["reproduced"].startswith("reproduced") else 1
            if not a.netting_set:
                raise CliError("give a netting set (NS-...), EXAMPLE or a scope, or --recipe SM-...", 2)
            if a.xlsx:
                _, body = api.request("GET", "/api/v1/risk/simm/playground.xlsx?" + enc(params(a, nettingSet=a.netting_set, side=a.side)))
                with open(a.xlsx, "wb") as f:
                    f.write(body)
                print(f"wrote {a.xlsx} ({len(body):,} bytes): the SIMM Playground workbook, every figure a formula, the switches and the side cells of "
                      "Config (a recipe was saved with it: its id is on Main and Provenance; the licensed parameters make it confidential)")
            elif a.save:
                res = api.json("POST", "/api/v1/risk/simm/playground/recipes?" + enc(params(a, nettingSet=a.netting_set)))
                say_json(res) if a.json else print(f"saved recipe {res['id']}: {res['command']} (inputs as known at {res['knownAt']})")
            else:
                res = api.json("GET", "/api/v1/risk/simm/playground?" + enc(params(a, nettingSet=a.netting_set, side=a.side, compare=a.compare)))
                if a.json:
                    say_json(res)
                else:
                    render(res, print)
                    for s in res.get("choices", []):
                        print(f"  {s['name']:<18} {str(s['value']):<16} (default {s['default']}; {s['rule']})")
                    if res.get("compare"):
                        print(res["compare"]["words"])
        return 0

    def switches(s) -> None:
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest)")
        s.add_argument("--version", metavar="SIMM-VERSION", help="another installed version (default the CSA's)")
        s.add_argument("--concentration", choices=["on", "off"])
        s.add_argument("--hvr", choices=["on", "off"])
        s.add_argument("--override", action="append", metavar="KEY=VALUE", help="a parameter override by its Config key (not the regulatory figure), repeatable")

    sm = groups.add_parser("simm", help="simm: ISDA SIMM per netting set (collect and post), the list, the steps, against the counterparty's call",
                           epilog=EPILOG_SIMM, formatter_class=argparse.RawDescriptionHelpFormatter)
    ss = sm.add_subparsers(dest="simm_sub", required=True)
    for name, help_ in (("show", "a netting set's IM to collect and post, by product and risk class (EXAMPLE: the hand-built examples; a VaR scope: an analytic)"),
                        ("explain", "the steps: buckets, factors, CR, WS, K_b and S_b, the curvature's θ and λ"),
                        ("against", "the margin against the counterparty's call (crif-external), by risk class")):
        p = ss.add_parser(name, help=help_, parents=[srv])
        p.add_argument("netting_set", metavar="NETTING-SET")
        switches(p)
        if name == "explain":
            p.add_argument("--direction", choices=["collect", "post"], default="collect")
            p.add_argument("--class", dest="cls", help="one risk class (InterestRate, CreditQ, CreditNonQ, Equity, Commodity, FX)")
        p.set_defaults(func=cmd, simm_cmd=name)
    li = ss.add_parser("list", help="every netting set of the date: computed, stored (labelled) or why not computed", parents=[srv])
    li.add_argument("--counterparty", metavar="CP-...")
    switches(li)
    li.set_defaults(func=cmd, simm_cmd="list")
    ve = ss.add_parser("versions", help="the installed SIMM parameter files (the bank's licensed ones) and the refused ones, with why", parents=[srv])
    ve.set_defaults(func=cmd, simm_cmd="versions")

    ps = ctx.get("play_subparsers")
    if ps is None:
        play = groups.add_parser("play", help="play: the playgrounds from the command line", epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
        ps = play.add_subparsers(dest="play_cmd", required=True)
    pp = ps.add_parser("simm", help="the SIMM Playground: the switches, a comparison in words, the workbook, a recipe and its replay", parents=[srv],
                       epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
    pp.add_argument("netting_set", metavar="NETTING-SET", nargs="?")
    switches(pp)
    pp.add_argument("--side", choices=["collect", "post"], default="collect")
    pp.add_argument("--compare", metavar="SWITCH=VALUE", help="flip one switch (concentration=off, hvr=off, simm-parameters=<version>) and explain the difference")
    pp.add_argument("--xlsx", metavar="FILE.xlsx", help="write the workbook of live formulas (and save a recipe)")
    pp.add_argument("--save", action="store_true", help="save the run as a recipe")
    pp.add_argument("--recipe", metavar="SM-...", help="replay a saved recipe; exit 1 when it does not reproduce to the cent")
    pp.set_defaults(func=cmd, simm_cmd="play")
