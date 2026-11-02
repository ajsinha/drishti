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

"""`drishti.py sens ...`, `drishti.py frtb ...` and `drishti.py play frtb ...`: position sensitivities and FRTB-SA from the command line
(docs/guides/FRTB_SA_GUIDE.md section 9; the API: /api/v1/sensitivities, /api/v1/risk-factors, /api/v1/frtb), as the token's user sees
them: a scope's sensitivities summed by factor or bucket (sens show), the factor dictionary (sens factors), the FRTB-SA capital of a scope
(frtb sa), its steps (frtb explain), its contributions (frtb contributions), and the playground: a comparison of one switch, the workbook
of live formulas, a saved recipe and its replay to the cent (play frtb). Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import urllib.parse

SWITCHES = {"curvature_source": "curvatureSource", "girr_curvature_shock": "girrCurvatureShock", "fx_curvature_divisor": "fxCurvatureDivisor",
            "girr_correlation": "girrCorrelation", "scenario": "scenario"}
EPILOG_SENS = ("examples:\n  drishti.py sens show DESK:DESK-RATES --by BUCKET\n  drishti.py sens show BOOK:BOOK-FX-1 --by RISK_FACTOR,MEASURE\n"
               "  drishti.py sens factors --q SOFR")
EPILOG_FRTB = ("examples:\n  drishti.py frtb sa BANK\n  drishti.py frtb sa DESK:DESK-RATES --girr-correlation exact\n"
               "  drishti.py frtb explain DESK:DESK-RATES --class GIRR\n  drishti.py frtb contributions BANK --by DESK\n  drishti.py frtb sa EXAMPLE")
EPILOG_PLAY = ("examples:\n  drishti.py play frtb DESK:DESK-RATES --compare girr-correlation=exact\n"
               "  drishti.py play frtb EXAMPLE --xlsx cases.xlsx\n  drishti.py play frtb BOOK:BOOK-FX-1 --save\n  drishti.py play frtb --recipe FR-1A2B3C4D-XYZ")


def money(v) -> str:
    return "—" if v is None else f"{v:,.2f}"


def render_sa(a: dict, say) -> None:
    say(f"FRTB-SA {a.get('canonical') or a.get('scope')} · {a.get('date') or 'the hand-built cases'} · {a.get('currency')} · binding {a.get('binding')}"
        + ("" if a.get("regulatory") else " · NOT THE REGULATORY FIGURE"))
    say(f"{'class':<10} {'measure':<10} {'low':>18} {'medium':>18} {'high':>18}")
    for c in a.get("classes", []):
        say(f"{c['class']:<10} {c['measure'].lower():<10} {money(c['low']):>18} {money(c['medium']):>18} {money(c['high']):>18}")
    s = a.get("sbm", {})
    say(f"{'SBM':<21} {money(s.get('low')):>18} {money(s.get('medium')):>18} {money(s.get('high')):>18}")
    say(f"SBM {money(a.get('SBM'))} · DRC {money(a.get('DRC')) if a.get('DRC') is not None else a.get('drcNote', 'not computed')} · "
        f"RRAO {money(a.get('RRAO')) if a.get('RRAO') is not None else a.get('rraoNote', 'not computed')} · total {money(a.get('total'))}")
    for k in ("notRegulatory", "estimateNote"):
        if a.get(k):
            say(a[k])


def register(groups, srv, ctx: dict) -> None:
    """Adds ``sens show|factors``, ``frtb sa|explain|contributions`` and ``play frtb``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def params(a, **more) -> list:
        q = [(k, v) for k, v in more.items() if v not in (None, "")]
        if getattr(a, "date", None):
            q.append(("asOf", a.date))
        if getattr(a, "ccy", None):
            q.append(("ccy", a.ccy))
        for attr, name in SWITCHES.items():
            if getattr(a, attr, None):
                q.append((name, getattr(a, attr)))
        for o in getattr(a, "override", None) or []:
            q.append(("override", o))
        return q

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.frtb_cmd
        if c == "sens-show":
            res = api.json("GET", "/api/v1/sensitivities?" + enc(params(a, scope=a.scope, by=a.by)))
            if a.json:
                say_json(res)
            else:
                print(f"{res['canonical']} · {res['date']} · {res['currency']} · {res['records']} records")
                for r in res["rows"][: a.top]:
                    print(f"{r['key']:<48} {r['measure']:<15} {money(r['value']):>20} {r['positions']:>6} positions")
        elif c == "sens-factors":
            res = api.json("GET", "/api/v1/risk-factors?" + enc(params(a, q=a.q, limit=a.limit)))
            if a.json:
                say_json(res)
            else:
                print(f"{res['factors']} factors ({res['version']}); refused on load: {res['refusals'] or 'none'}")
                for r in res["rows"]:
                    print(f"{r['factor']:<40} {r['class']:<7} bucket {r['frtbBucket']:<5} {', '.join(r['sourceNames'])}")
        elif c == "sa":
            res = api.json("GET", "/api/v1/frtb/sa?" + enc(params(a, scope=a.scope)))
            say_json(res) if a.json else render_sa(res, print)
        elif c == "explain":
            say_json(api.json("GET", "/api/v1/frtb/sa/explain?" + enc(params(a, scope=a.scope, **{"class": a.cls}))))
        elif c == "contributions":
            res = api.json("GET", "/api/v1/frtb/sa/contributions?" + enc(params(a, scope=a.scope, by=a.by)))
            if a.json:
                say_json(res)
            else:
                print(f"{res['scope']} by {res['by']}: total {money(res['total'])}, the contributions add up: {res['addsUp']}")
                for r in res["rows"][: a.top]:
                    print(f"{r['key']:<44} {money(r['contribution']):>20}")
        elif c == "play":
            if a.recipe:
                res = api.json("GET", "/api/v1/frtb/playground/recipes/" + urllib.parse.quote(a.recipe))
                if a.json:
                    say_json(res)
                else:
                    render_sa(res, print)
                    print(res["reproduced"])
                return 0 if res["reproduced"].startswith("reproduced") else 1
            if not a.scope:
                raise CliError("give a scope (BANK, DESK:DESK-RATES, EXAMPLE) or --recipe FR-...", 2)
            if a.xlsx:
                _, body = api.request("GET", "/api/v1/frtb/playground.xlsx?" + enc(params(a, scope=a.scope)))
                with open(a.xlsx, "wb") as f:
                    f.write(body)
                print(f"wrote {a.xlsx} ({len(body):,} bytes): the FRTB-SA Playground workbook, every figure a formula and every switch a cell of "
                      "Config (a recipe was saved with it: its id is on Main and Provenance)")
            elif a.save:
                res = api.json("POST", "/api/v1/frtb/playground/recipes?" + enc(params(a, scope=a.scope)))
                say_json(res) if a.json else print(f"saved recipe {res['id']}: {res['command']} (inputs as known at {res['knownAt']})")
            else:
                res = api.json("GET", "/api/v1/frtb/playground?" + enc(params(a, scope=a.scope, compare=a.compare)))
                if a.json:
                    say_json(res)
                else:
                    render_sa(res, print)
                    for s in res.get("choices", []):
                        print(f"  {s['name']:<22} {s['value']:<24} (default {s['default']}; {s['rule']})")
                    if res.get("compare"):
                        print(res["compare"]["words"])
        return 0

    def switches(s) -> None:
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest)")
        s.add_argument("--ccy", metavar="CCY", help="the reporting currency (default USD)")
        s.add_argument("--curvature-source", choices=["revaluation", "gamma"])
        s.add_argument("--girr-curvature-shock", choices=["undivided", "divided"])
        s.add_argument("--fx-curvature-divisor", choices=["non-reporting-options", "off", "all"])
        s.add_argument("--girr-correlation", choices=["table", "exact"])
        s.add_argument("--scenario", choices=["binding", "low", "medium", "high"])
        s.add_argument("--override", action="append", metavar="PATH=VALUE", help="a parameter override (not the regulatory figure), repeatable")

    sens = groups.add_parser("sens", help="sens: position sensitivities summed for any scope, and the factor dictionary", epilog=EPILOG_SENS,
                             formatter_class=argparse.RawDescriptionHelpFormatter)
    ss = sens.add_subparsers(dest="sens_cmd", required=True)
    s = ss.add_parser("show", help="a scope's sensitivities summed by RISK_FACTOR, BUCKET, RISK_CLASS, TENOR, MEASURE or an attribute", parents=[srv])
    s.add_argument("scope")
    s.add_argument("--by", default="RISK_FACTOR")
    s.add_argument("--date", metavar="YYYY-MM-DD")
    s.add_argument("--ccy", metavar="CCY")
    s.add_argument("--top", type=int, default=40)
    s.set_defaults(func=cmd, frtb_cmd="sens-show")
    f = ss.add_parser("factors", help="the factor dictionary: factors, their FRTB buckets and the source systems' names", parents=[srv])
    f.add_argument("--q", help="a name, a source name, a class or 'CLASS BUCKET'")
    f.add_argument("--limit", type=int, default=50)
    f.add_argument("--date", metavar="YYYY-MM-DD")
    f.set_defaults(func=cmd, frtb_cmd="sens-factors")

    fr = groups.add_parser("frtb", help="frtb: FRTB-SA of any scope (SBM by class and scenario, DRC, RRAO), its steps and contributions",
                           epilog=EPILOG_FRTB, formatter_class=argparse.RawDescriptionHelpFormatter)
    fs = fr.add_subparsers(dest="frtb_sub", required=True)
    for name, help_ in (("sa", "the capital of a scope: SBM per class, measure and scenario, the binding one, DRC, RRAO and the total"),
                        ("explain", "the steps: buckets, factors, WS, K_b and S_b per scenario, the curvature sides"),
                        ("contributions", "the contributions (Euler for the SBM) by an attribute; they add up to the total")):
        p = fs.add_parser(name, help=help_, parents=[srv])
        p.add_argument("scope", help="a VaR scope (BANK, DESK:DESK-RATES, BOOK:..., POS:..., + and -) or EXAMPLE (the hand-built cases)")
        switches(p)
        if name == "explain":
            p.add_argument("--class", dest="cls", help="one risk class (GIRR, CSR_NS, EQ, COMM, FX)")
        if name == "contributions":
            p.add_argument("--by", default="DESK")
            p.add_argument("--top", type=int, default=25)
        p.set_defaults(func=cmd, frtb_cmd=name)

    play = groups.add_parser("play", help="play: the playgrounds from the command line (play frtb; PLAY VAR is drishti.py var playground)",
                             epilog=EPILOG_PLAY, formatter_class=argparse.RawDescriptionHelpFormatter)
    ps = play.add_subparsers(dest="play_cmd", required=True)
    pf = ps.add_parser("frtb", help="the FRTB-SA Playground: the switches, a comparison in words, the workbook, a recipe and its replay", parents=[srv])
    pf.add_argument("scope", nargs="?")
    switches(pf)
    pf.add_argument("--compare", metavar="SWITCH=VALUE", help="flip one switch and explain the difference by step")
    pf.add_argument("--xlsx", metavar="FILE.xlsx", help="write the workbook of live formulas (and save a recipe)")
    pf.add_argument("--save", action="store_true", help="save the run as a recipe")
    pf.add_argument("--recipe", metavar="FR-...", help="replay a saved recipe; exit 1 when it does not reproduce to the cent")
    pf.set_defaults(func=cmd, frtb_cmd="play")
