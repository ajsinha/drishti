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
"""`drishti.py fva ...` and `drishti.py play fva ...`: FVA, funding cost and benefit, from the command line (docs/guides/FVA_GUIDE.md
section 7; the API: /api/v1/fva), as the token's user sees it: a scope's FCA, FBA and FVA with its netting sets (fva show), the netting
sets by FVA (fva sets), the settings and the curves of the newest date (fva status), and the playground: its terms by date and the
workbook of live formulas (play fva). Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import urllib.parse

EPILOG_FVA = ("examples:\n  drishti.py fva show NSET:NS-ALDERSHOT-FRA\n  drishti.py fva show CPTY:CP-ALDERSHOT\n  drishti.py fva sets BANK\n"
              "  drishti.py fva show NSET:NS-ALDERSHOT-FRA --spread 0.006 --fba off\n  drishti.py fva status")
EPILOG_PLAY = ("examples:\n  drishti.py play fva NSET:NS-ALDERSHOT-FRA\n  drishti.py play fva CPTY:CP-ALDERSHOT --xlsx aldershot-fva.xlsx\n"
               "  drishti.py play fva NSET:NS-ALDERSHOT-FRA --mode paths --xlsx fva-paths.xlsx\n  drishti.py play fva --example --xlsx fva-example.xlsx")


def money(v) -> str:
    return "—" if v is None else f"{v:,.2f}"


def render(a: dict, say) -> None:
    f = a.get("figures", {})
    say(f"FVA {a.get('scope')} · {a.get('label') or ''} · {a.get('businessDate')} · run {a.get('runId')} ({a.get('calibration') or 'calibration not stated'}) · USD")
    say(f"FCA {money(f.get('fca'))} · FBA {money(f.get('fba'))} · FVA {money(f.get('fva'))} · CVA (planned)")
    r = a.get("epeReading", {})
    if r.get("flatSpread"):
        say(f"the EPE reading: spread {r.get('spread')} × life {r.get('life')} × EPE_life {money(r.get('epeLife'))} = {money(r.get('product'))} (before discounting)")
    if a.get("flags"):
        say("flags: " + ", ".join(a["flags"]))
    sets = a.get("nettingSets", [])
    if sets:
        say(f"{'netting set':<26} {'counterparty':<18} {'CSA':<18} {'FCA':>16} {'FBA':>16} {'FVA':>16}")
        for n in sets:
            say(f"{n['nettingSet']:<26} {n.get('counterparty') or '':<18} {n.get('csa') or '':<18} {money(n['fca']):>16} {money(n['fba']):>16} {money(n['fva']):>16}")


def terms(a: dict, say) -> None:
    say(f"{'k':>3} {'date':<11} {'t':>7} {'Δt':>7} {'DF':>9} {'s_B':>8} {'EE':>16} {'ENE':>16} {'FCA term':>14} {'FBA term':>14}")
    for t in a.get("terms", []):
        say(f"{t['k']:>3} {t['date']:<11} {t['t']:>7.4f} {t['dt']:>7.4f} {t['df']:>9.6f} {t['sb']:>8.5f} {money(t['ee']):>16} {money(t['ene']):>16}"
            f" {money(t['fca']):>14} {money(t['fba']):>14}")


def register(groups, srv, ctx: dict) -> None:
    """Adds ``fva show|sets|status`` and ``play fva``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def params(a, **more) -> list:
        q = [(k, v) for k, v in more.items() if v not in (None, "")]
        for attr, name in (("date", "asOf"), ("ee_point", "eePoint"), ("fba", "fba"), ("survival", "survival"), ("spread", "spread"), ("mode", "mode")):
            if getattr(a, attr, None):
                q.append((name, getattr(a, attr)))
        return q

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.fva_cmd
        if c in ("show", "sets"):
            res = api.json("GET", "/api/v1/fva/scope?" + enc(params(a, scope=a.scope or "BANK")))
            if a.json:
                say_json(res)
            elif c == "show":
                render(res, print)
            else:
                print(f"{res['scope']} · {res['businessDate']} · FVA {money(res['figures']['fva'])} over {res['nettingSetCount']} netting sets (sorted by FVA)")
                for n in res.get("nettingSets", []):
                    print(f"{n['nettingSet']:<26} {n.get('counterparty') or '':<18} {money(n['fca']):>16} {money(n['fba']):>16} {money(n['fva']):>16}")
                if not res.get("setsShown", True):
                    print("your role sees these netting sets only in aggregate: the total alone")
        elif c == "status":
            res = api.json("GET", "/api/v1/fva/status")
            if a.json:
                say_json(res)
            else:
                print(f"funding spread {res['fundingSpread']} · lending {res['lendingSpread']} · discount {res['discountCurve']} · ee-point {res['eePoint']}"
                      f" · FBA {'on' if res['fba'] else 'off'} · survival {'on' if res['fundingSurvival'] else 'off'} · require risk-neutral {res['requireRiskNeutral']}")
                fc = res.get("fundingCurve")
                if fc:
                    print(f"{res.get('businessDate')}: funding curve {fc['id']} ({fc['source']}): "
                          + ", ".join(f"{p.get('tenor') or p['t']} {p['spread']}" for p in fc["points"]))
                if res.get("flags"):
                    print("flags: " + ", ".join(res["flags"]))
        elif c == "play":
            if a.example:
                if not a.xlsx:
                    raise CliError("--example writes the hand-built workbook: give --xlsx FILE.xlsx", 2)
                _, body = api.request("GET", "/api/v1/fva/playground/template")
            elif not a.scope:
                raise CliError("give a netting set or a counterparty (NSET:NS-..., CPTY:CP-...), or --example --xlsx FILE", 2)
            elif a.xlsx:
                _, body = api.request("GET", "/api/v1/fva/playground.xlsx?" + enc(params(a, scope=a.scope)))
            else:
                res = api.json("GET", "/api/v1/fva/playground?" + enc(params(a, scope=a.scope)))
                if a.json:
                    say_json(res)
                else:
                    render(res, print)
                    terms(res, print)
                return 0
            with open(a.xlsx, "wb") as f:
                f.write(body)
            print(f"wrote {a.xlsx} ({len(body):,} bytes): the FVA Playground workbook, every step a formula, the choices cells of Config, each figure "
                  "checked against the engine's on Main")
        return 0

    def choices(s) -> None:
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest)")
        s.add_argument("--ee-point", choices=["end", "mid"], help="the period's exposure: its end (default) or the average of both ends")
        s.add_argument("--fba", choices=["on", "off"], help="off: FCA only (asymmetric funding)")
        s.add_argument("--survival", choices=["on", "off"], help="weight each term by both survival probabilities")
        s.add_argument("--spread", metavar="CURVE-OR-FRACTION", help="another funding curve id, or a flat spread (0.006 is 60 bp)")

    fv = groups.add_parser("fva", help="fva: funding cost and benefit (FCA, FBA, FVA) of any exposure scope, by netting set",
                           epilog=EPILOG_FVA, formatter_class=argparse.RawDescriptionHelpFormatter)
    fs = fv.add_subparsers(dest="fva_sub", required=True)
    for name, help_ in (("show", "a scope's FCA, FBA and FVA, its netting sets and flags (NSET:, CPTY:, GROUP:, LE:, BANK)"),
                        ("sets", "the netting sets of a scope by FVA, the costliest first (default BANK)")):
        p = fs.add_parser(name, help=help_, parents=[srv])
        p.add_argument("scope", metavar="SCOPE", nargs="?" if name == "sets" else None)
        choices(p)
        p.set_defaults(func=cmd, fva_cmd=name)
    st = fs.add_parser("status", help="the settings and the curves of the newest date", parents=[srv])
    st.set_defaults(func=cmd, fva_cmd="status")

    ps = ctx.get("play_subparsers")
    if ps is None:
        play = groups.add_parser("play", help="play: the playgrounds from the command line", epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
        ps = play.add_subparsers(dest="play_cmd", required=True)
        ctx["play_subparsers"] = ps
    pp = ps.add_parser("fva", help="the FVA Playground: the terms by date, the workbook of live formulas, the hand-built example", parents=[srv],
                       epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
    pp.add_argument("scope", metavar="SCOPE", nargs="?")
    choices(pp)
    pp.add_argument("--xlsx", metavar="FILE.xlsx", help="write the workbook of live formulas")
    pp.add_argument("--example", action="store_true", help="the hand-built case F1 (b) instead of a scope (with --xlsx)")
    pp.add_argument("--mode", choices=["profile", "paths"], help="the workbook's mode: profile (EE and ENE as data, the default) or paths (their means over the paths)")
    pp.set_defaults(func=cmd, fva_cmd="play")
