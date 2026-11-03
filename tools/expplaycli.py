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
"""`drishti.py play exp ...`: the Exposure Playground from the command line (docs/guides/EXPOSURE_GUIDE.md section 29; the API:
/api/v1/exposure/playground), as the token's user sees it: one netting set's profile and headline figures from its first paths, the
contributions, each export choice's size, and the workbook of live formulas (--xlsx). EXAMPLE is the hand-built case X1.
Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import urllib.parse

EPILOG = ("examples:\n  drishti.py play exp EXAMPLE\n  drishti.py play exp NS-ALDERSHOT-FRA --q 0.95,0.99 --collateral current\n"
          "  drishti.py play exp NS-ALDERSHOT-FRA --estimate\n  drishti.py play exp NS-ALDERSHOT-FRA --trades 50 --paths 1000 --xlsx aldershot.xlsx\n"
          "  drishti.py play exp --template x1.xlsx")


def num(v) -> str:
    if v is None:
        return "—"
    return f"{v:,.4f}" if abs(v) < 1000 else f"{v:,.2f}"


def render(a: dict, say) -> None:
    say(f"Exposure Playground · {a['nettingSet']} · {a.get('counterparty') or ''} · {a.get('businessDate')} · run {a.get('runId')}"
        f" ({a.get('calibration') or ''}) · the first {a['paths']} of {a['runPaths']} paths · {a.get('setTrades')} trades")
    ch = a.get("choices", {})
    say(f"choices: confidences {ch.get('confidences')} · rank rule {ch.get('rankRule')} · collateral {ch.get('collateralModel')}"
        f" · contribution date {ch.get('contributionDate')}")
    f = a["figures"]
    say(f"CE {num(f['ce'])} · T {f['horizonYears']:.4f} y · EPE {num(f['epe'])} · EEPE {num(f['eepe'])} · peak EE {num(f['peakEe'])} ({f['peakEeTenor']})"
        f" · EAD_IMM {num(f['eadImm'])} (informational)")
    for p in f.get("peakPfe", []):
        say(f"peak PFE {p['q'] * 100:g}% {num(p['peakPfe'])} ({p['tenor']})")
    qs = ch.get("confidences", [])
    say(f"{'date':<8} {'t':>7} {'EE':>16} {'ENE':>16} " + " ".join(f"{'PFE ' + format(q * 100, 'g') + '%':>16}" for q in qs) + f" {'EEE':>16}")
    for r in a.get("profile", []):
        say(f"{r['tenor']:<8} {r['t']:>7.4f} {num(r['ee']):>16} {num(r['ene']):>16} " + " ".join(f"{num(v):>16}" for v in r["pfe"]) + f" {num(r['eee']):>16}")
    c = a.get("contributions")
    if c:
        say(f"contributions to EE at {c['tenor']} ({num(c['ee'])}; the shares add up to {num(c['sum'])}), the largest {c['shown']} of {c['of']}:")
        for r in c["rows"]:
            say(f"  {r['label']:<40} {num(r['eeShare']):>16} {r['pct']:>7.1f}%")
    for k, v in (a.get("runFigures") or {}).items():
        say(f"{k}: {num(v)}")


def register(groups, srv, ctx: dict) -> None:
    """Adds ``play exp`` to the ``play`` group (frtbcli makes it; made here when it is not there)."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def params(a, **more) -> list:
        q = [(k, v) for k, v in more.items() if v not in (None, "")]
        for attr, name in (("date", "asOf"), ("q", "q"), ("rule", "rule"), ("collateral", "collateral"), ("contribution_date", "date"),
                           ("paths", "paths")):
            if getattr(a, attr, None):
                q.append((name, getattr(a, attr)))
        return q

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        if a.template:
            _, body = api.request("GET", "/api/v1/exposure/playground/template")
            with open(a.template, "wb") as f:
                f.write(body)
            print(f"wrote {a.template} ({len(body):,} bytes): the workbook of the hand-built case X1, every figure a formula")
            return 0
        if not a.netting_set:
            raise CliError("give a netting set (NS-...) or EXAMPLE, or --template FILE.xlsx", 2)
        if a.xlsx:
            _, body = api.request("GET", "/api/v1/exposure/playground.xlsx?" + enc(params(a, nettingSet=a.netting_set, trades=a.trades)))
            with open(a.xlsx, "wb") as f:
                f.write(body)
            print(f"wrote {a.xlsx} ({len(body):,} bytes): the Exposure Playground workbook, the paths as data and every step after them a formula, "
                  "each figure checked against the engine on Main")
        elif a.estimate:
            res = api.json("GET", "/api/v1/exposure/playground/estimate?" + enc(params(a, nettingSet=a.netting_set)))
            if a.json:
                say_json(res)
            else:
                print(f"{res['nettingSet']} · {res['setTrades']} trades · {res['paths']} paths")
                for e in res["estimates"]:
                    print(f"  {e['choice']:<6} {e['cells']:>14,} cells {e['size']:>10} recalc {e['recalc']:<14} "
                          + (f"refused: {e['refused']}" if e.get("refused") else "opens in " + ", ".join(e["apps"])))
        else:
            res = api.json("GET", "/api/v1/exposure/playground?" + enc(params(a, nettingSet=a.netting_set)))
            say_json(res) if a.json else render(res, print)
        return 0

    ps = ctx.get("play_subparsers")
    if ps is None:
        play = groups.add_parser("play", help="play: the playgrounds from the command line", epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
        ps = play.add_subparsers(dest="play_cmd", required=True)
        ctx["play_subparsers"] = ps
    pe = ps.add_parser("exp", help="the Exposure Playground: one netting set's EE, PFE, EPE and EEPE from its paths, its size, the workbook", parents=[srv],
                       epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    pe.add_argument("netting_set", metavar="NETTING-SET", nargs="?")
    pe.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest)")
    pe.add_argument("--q", metavar="Q[,Q]", help="the PFE confidences (default the engine's)")
    pe.add_argument("--rule", choices=["kth", "interpolated"])
    pe.add_argument("--collateral", choices=["lagged", "current", "none"])
    pe.add_argument("--contribution-date", dest="contribution_date", metavar="TENOR", help="the date the contributions split EE at (default peak)")
    pe.add_argument("--paths", type=int, help="the first N paths (default every path up to max-paths)")
    pe.add_argument("--trades", metavar="N|All", help="the workbook's trade columns; the rest are summed into Rest of set")
    pe.add_argument("--estimate", action="store_true", help="each export choice's size before it is made")
    pe.add_argument("--xlsx", metavar="FILE.xlsx", help="write the workbook of live formulas")
    pe.add_argument("--template", metavar="FILE.xlsx", help="write the workbook of the hand-built case X1")
    pe.set_defaults(func=cmd)
