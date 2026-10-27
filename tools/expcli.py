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

"""`drishti.py exposure ...`: the exposure engine from the command line (docs/guides/EXPOSURE_GUIDE.md; the API: /api/v1/exposure), as the
token's user sees it: a scope's figures and profile (show), its contributions (parts), the tree of legal entities, counterparties and
netting sets (tree), the profile as CSV (profile), the exposure distribution at a date with the path fan (distribution), the engine's
state (status), captures (capture, captures, compare), the official end-of-day record (official), the retention plan (retention), a
reload (administrators) and a pre-deal what-if (whatif: exact through the Python engine, or the conservative proxy).
Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import json
import urllib.parse

EPILOG = ("examples:\n  drishti.py exposure show NSET:NS-ALDERSHOT-NY\n  drishti.py exposure show CPTY:CP-ALDERSHOT --q 0.99 --date 2026-09-30\n"
          "  drishti.py exposure parts CPTY:CP-ALDERSHOT\n  drishti.py exposure parts NSET:NS-ALDERSHOT-NY --by trade --figure EE --top 10\n"
          "  drishti.py exposure tree\n  drishti.py exposure profile NSET:NS-ALDERSHOT-NY --csv > ns-ny.csv\n"
          "  drishti.py exposure profile NSET:NS-ALDERSHOT-NY --jsonl > ns-ny.jsonl\n"
          "  drishti.py exposure distribution NSET:NS-ALDERSHOT-NY --at 1Y\n  drishti.py exposure status\n"
          "  drishti.py exposure capture CPTY:CP-ALDERSHOT --note 'before the unwind'\n  drishti.py exposure compare XCAP-20260930-1 now\n"
          "  drishti.py exposure whatif CPTY:CP-ALDERSHOT --like CLY-4003028 --factor -1 --set NS-ALDERSHOT-FRA\n"
          "  drishti.py exposure whatif CPTY:CP-ALDERSHOT --candidates new-swap.json --method addon")


def money(v) -> str:
    return "—" if not isinstance(v, (int, float)) or isinstance(v, bool) else f"{v:,.0f}"


def table(rows: list[list[str]], say) -> None:
    if not rows:
        return
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    for n, r in enumerate(rows):
        say("  ".join(c.rjust(widths[i]) if i and n else c.ljust(widths[i]) for i, c in enumerate(r)).rstrip())
        if n == 0:
            say("  ".join("-" * w for w in widths))


def render_headline(h: dict, say) -> None:
    """A scope's figures in a few lines and its profile as a table."""
    say(f"EXP {h.get('canonical')}  ·  {h.get('label')}  ·  {h.get('kind')}  ·  {h.get('businessDate')}")
    q = (h.get("confidence") or 0) * 100
    say(f"  peak PFE {q:g}%   {money(h.get('peakPfe'))} USD at {h.get('peakPfeTenor')} ({h.get('peakPfeDate')})   PFE 99% {money(h.get('peakPfe99'))}")
    say(f"  EPE {money(h.get('epe'))}   EEPE {money(h.get('eepe'))}   peak EE {money(h.get('peakEe'))}   CE {money(h.get('ce'))}   "
        f"EAD (IMM, info) {money(h.get('eadImm'))}")
    lim = h.get("limit")
    if lim:
        say(f"  limit {money(lim.get('limit'))} USD ({lim.get('id')}, {lim.get('measure')})   used {100 * (lim.get('use') or 0):.1f}%")
    say(f"  run {h.get('runId')}, {h.get('paths')} paths, collateral {h.get('collateralModel')}, horizon {h.get('horizonYears')} years"
        + (f", flags {', '.join(h['flags'])}" if h.get("flags") else ""))
    rows = [["Tenor", "Date", "EE", "ENE", "EEE", "PFE", "PFE 99", "EE uncoll"]]
    for r in h.get("profile") or []:
        rows.append([str(r.get("tenor")), str(r.get("date")), money(r.get("ee")), money(r.get("ene")), money(r.get("eee")), money(r.get("pfe")),
                     money(r.get("pfe99")), money(r.get("eeUncoll"))])
    table(rows, say)
    p = h.get("provenance") or {}
    say(f"  {p.get('nettingSets')} netting sets, {p.get('trades')} trades" + (f", {p['hiddenSets']} sets hidden from you" if p.get("hiddenSets") else "")
        + f" · {p.get('tier')} · {p.get('computeMs')} ms ({p.get('cache')})")
    for w in p.get("warnings") or []:
        say(f"  warning: {w}")


def render_parts(c: dict, top: int, say) -> None:
    say(f"{c.get('canonical')}  by {c.get('by')}  {c.get('figure')} {money(c.get('total'))}" + (f" at {c.get('date')}" if c.get("date") else "")
        + (f"  ({c.get('parts')} parts, the largest shown)" if c.get("truncated") else ""))
    if c.get("note"):
        say("  " + c["note"])
    rows = [["Part", "Contribution", "% of total", "Incremental"]]
    for r in (c.get("rows") or [])[:top]:
        rows.append([str(r.get("label") or r.get("key")), money(r.get("value")), f"{100 * (r.get('pct') or 0):.1f}", money(r.get("incremental"))])
    table(rows, say)


def render_tree(t: dict, say) -> None:
    say(f"{t.get('businessDate')}  run {t.get('runId')}  q {t.get('confidence')}  netting sets with paths {t.get('nettingSetsWithPaths')} of "
        f"{t.get('nettingSetsKnown')}")
    rows = [["Node", "Peak PFE", "EPE", "Limit used"]]
    b = t.get("bankNode") or {}
    rows.append(["Bank", money(b.get("peakPfe")), money(b.get("epe")), ""])
    for le in t.get("legalEntities") or []:
        rows.append(["  " + str(le.get("label")), money(le.get("peakPfe")), money(le.get("epe")), ""])
    for g in t.get("groups") or []:
        for cp in g.get("children") or []:
            lim = cp.get("limit") or {}
            rows.append(["    " + str(cp.get("label")), "no paths" if cp.get("noPaths") else money(cp.get("peakPfe")), money(cp.get("epe")),
                         f"{100 * lim['use']:.1f}%" if lim.get("use") is not None else ""])
            for s in cp.get("children") or []:
                rows.append(["      " + str(s.get("id")), "no paths" if s.get("noPaths") else money(s.get("peakPfe")), money(s.get("epe")), ""])
    table(rows, say)


def render_whatif(w: dict, say) -> None:
    b, a, inc = w.get("base") or {}, w.get("with") or {}, w.get("incremental") or {}
    say(f"what-if on {w.get('canonical')}  method {w.get('method')}  {', '.join(w.get('flags') or []) or 'exact'}  run {w.get('runId')}")
    rows = [["Figure", "Before", "After", "Incremental"]]
    for name, k in (("Peak PFE", "peakPfe"), ("EPE", "epe"), ("Peak EE", "peakEe")):
        rows.append([name, money(b.get(k)), money(a.get(k)), money(inc.get(k))])
    table(rows, say)
    if w.get("note"):
        say("  " + w["note"])


def like_candidate(api, trade: str, factor: float, nset: str | None) -> dict:
    """A candidate copying a booked trade, scaled by ``factor``: its paths (the ``paths`` method) and, as the explorer's pre-deal form
    sends them, its asset class, notional, maturity and MtM scaled the same way (the ``addon`` proxy needs them)."""
    scope = f"NSET:{nset}" if nset else f"TRD:{trade}"
    rows = api.json("GET", "/api/v1/exposure/scope/contributions?" + urllib.parse.urlencode({"scope": scope, "by": "trade", "figure": "EE"})).get("rows") or []
    r = next((x for x in rows if x.get("key") == trade), {})
    return {"tradeId": "CANDIDATE-1", "label": f"{factor:g} x {trade}", "nettingSet": nset or r.get("nettingSet") or "",
            "paths": {"copyOf": trade, "factor": factor}, "assetClass": r.get("assetClass"),
            "notional": abs((r.get("notional") or 0) * factor), "maturityDate": r.get("maturity"), "mtm": (r.get("mtm") or 0) * factor,
            "delta": -1 if factor < 0 else 1}


def candidates_of(path: str, err) -> list[dict]:
    """The candidates of a JSON file: a list of {tradeId, nettingSet, paths: {...}, assetClass, notional, maturityDate, mtm}, or {"candidates": [...]}."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise err(f"cannot read the candidates in {path}: {e}", 2)
    items = data.get("candidates") if isinstance(data, dict) else data
    if not isinstance(items, list) or not items or not all(isinstance(i, dict) for i in items):
        raise err(f"{path}: give a list of candidate trades", 2)
    return items


def register(groups, srv, ctx: dict) -> None:
    """Adds ``exposure show|parts|tree|profile|distribution|status|capture|captures|compare|official|retention|reload|whatif``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def asked(a, params: dict) -> dict:
        if getattr(a, "date", None):
            params["asOf"] = a.date
        if getattr(a, "q", None):
            params["q"] = a.q
        if getattr(a, "collateral", None):
            params["collateral"] = a.collateral
        return params

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.exp_cmd
        if c == "show":
            res = api.json("GET", "/api/v1/exposure/scope?" + enc(asked(a, {"scope": a.scope})))
            say_json(res) if a.json else render_headline(res, print)
        elif c == "parts":
            p = asked(a, {"scope": a.scope, "by": a.by, "figure": a.figure})
            if a.at:
                p["date"] = a.at
            res = api.json("GET", "/api/v1/exposure/scope/contributions?" + enc(p))
            say_json(res) if a.json else render_parts(res, a.top, print)
        elif c == "tree":
            res = api.json("GET", "/api/v1/exposure/tree?" + enc(asked(a, {})))
            say_json(res) if a.json else render_tree(res, print)
        elif c == "profile":
            p = asked(a, {"scope": a.scope})
            if a.csv or a.jsonl:                          # the server's own file: CSV, or JSON Lines (a grid date a line)
                p["format"] = "jsonl" if a.jsonl else "csv"
                print(api.request("GET", "/api/v1/exposure/scope/profile?" + enc(p))[1].decode("utf-8"), end="")
            else:
                say_json(api.json("GET", "/api/v1/exposure/scope/profile?" + enc(p)))
        elif c == "distribution":
            p = asked(a, {"scope": a.scope, "bands": "true"})
            if a.at:
                p["date"] = a.at
            res = api.json("GET", "/api/v1/exposure/scope/distribution?" + enc(p))
            if a.json:
                say_json(res)
            else:
                print(f"{res.get('canonical')} at {res.get('tenor')} ({res.get('date')}): mean {money(res.get('mean'))}, "
                      f"{100 * (res.get('zeroShare') or 0):.1f}% of {res.get('paths')} paths without exposure")
                print("  quantiles  " + "   ".join(f"{float(k) * 100:g}% {money(v)}" for k, v in (res.get("quantiles") or {}).items()))
                rows = [["Tenor", "5%", "25%", "50%", "75%", "95%"]]
                for r in res.get("bands") or []:
                    rows.append([str(r.get("tenor"))] + [money(r.get(k)) for k in ("q5", "q25", "q50", "q75", "q95")])
                table(rows, print)
        elif c == "status":
            say_json(api.json("GET", "/api/v1/exposure/status"))
        elif c == "capture":
            body = {"scope": a.scope, "note": a.note or ""}
            if a.q:
                body["q"] = a.q
            say_json(api.json("POST", "/api/v1/exposure/captures?" + enc(asked(a, {})), body))
        elif c == "captures":
            say_json(api.json("GET", "/api/v1/exposure/captures" + ("?" + enc({"scope": a.scope}) if a.scope else "")))
        elif c == "compare":
            say_json(api.json("GET", "/api/v1/exposure/compare?" + enc({"a": a.a, "b": a.b or "now"})))
        elif c == "official":
            say_json(api.json("POST" if a.capture else "GET", "/api/v1/exposure/official?" + enc(asked(a, {}))))
        elif c == "retention":
            say_json(api.json("GET", "/api/v1/exposure/retention"))
        elif c == "reload":
            say_json(api.json("POST", "/api/v1/exposure/reload"))
        elif c == "whatif":
            if a.candidates:
                cands = candidates_of(a.candidates, CliError)
            elif a.like:
                cands = [like_candidate(api, a.like, a.factor, a.set)]
            else:
                raise CliError("give the candidates: --like TRADE [--factor F] [--set NETTING_SET], or --candidates FILE.json", 2)
            body = {"scope": a.scope, "method": a.method, "candidates": cands}
            if a.q:
                body["q"] = a.q
            if a.collateral:
                body["collateral"] = a.collateral
            res = api.json("POST", "/api/v1/exposure/what-if?" + enc(asked(a, {})), body)
            say_json(res) if a.json else render_whatif(res, print)
        return 0

    p = groups.add_parser("exposure", help="exposure: the exposure engine as you see it (show, parts, tree, profile, distribution, captures, what-if)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="exp_cmd", required=True)

    def scoped(name, help_):
        s = sub.add_parser(name, help=help_, parents=[srv])
        s.add_argument("scope", help="a scope: BANK, LE:LE-NY, GROUP:GRP-X, CPTY:CP-X, NSET:NS-X, TRD:<id>, DESK:, BOOK:, WHERE:<rule>, with + - &")
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest with paths)")
        s.add_argument("--q", type=float, metavar="Q", help="the PFE confidence (default the limit confidence, 0.95)")
        s.add_argument("--collateral", choices=["lagged", "current", "none"], help="the collateral model (default lagged)")
        return s

    scoped("show", "a scope's figures (peak PFE, EPE, EEPE, CE, EAD), its limit use and profile")
    pr = scoped("parts", "a scope's contributions: to the peak PFE (they add up), EE at a date or EPE, with incremental PFE")
    pr.add_argument("--by", default="child", choices=["child", "netting-set", "counterparty", "legal-entity", "trade", "asset"])
    pr.add_argument("--figure", default="PFE", choices=["PFE", "EE", "EPE"])
    pr.add_argument("--at", metavar="TENOR|DATE", help="the primary date of an EE contribution (1Y or 2027-09-30; default the peak)")
    pr.add_argument("--top", type=int, default=25, help="parts printed (default 25; --json has them all)")
    t = sub.add_parser("tree", help="the bank, legal entities, counterparties and netting sets with peak PFE, EPE and limit use", parents=[srv])
    t.add_argument("--date", metavar="YYYY-MM-DD")
    t.add_argument("--q", type=float, metavar="Q")
    pf = scoped("profile", "a scope's profile at every primary date (--csv: tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll)")
    pf.add_argument("--csv", action="store_true")
    pf.add_argument("--jsonl", action="store_true", help="the profile as JSON Lines: a grid date a line, keyed as the CSV's header")
    ds = scoped("distribution", "the exposure distribution at a primary date (the peak by default) and the path fan at every date")
    ds.add_argument("--at", metavar="TENOR|DATE")
    sub.add_parser("status", help="the engine's settings, hot files and loaded dates", parents=[srv])
    cp = scoped("capture", "capture a scope's answer, immutable (needs risk:write)")
    cp.add_argument("--note")
    cs = sub.add_parser("captures", help="the captures you may read, newest first", parents=[srv])
    cs.add_argument("--scope")
    cm = sub.add_parser("compare", help="compare two captures, or a capture with now", parents=[srv])
    cm.add_argument("a")
    cm.add_argument("b", nargs="?")
    of = sub.add_parser("official", help="the date's official end-of-day record (--capture: take it now; administrators)", parents=[srv])
    of.add_argument("--date", metavar="YYYY-MM-DD")
    of.add_argument("--capture", action="store_true")
    sub.add_parser("retention", help="what the retention keeps of each date held, and the dates whose trade paths are beyond it", parents=[srv])
    sub.add_parser("reload", help="drop the loaded dates (administrators): the next request maps the hot file again or reads the lake", parents=[srv])
    w = scoped("whatif", "a scope's exposure before and after candidate trades: exact (paths, the Python engine) or the proxy (addon)")
    w.add_argument("--like", metavar="TRADE", help="a candidate copying a booked trade's paths on the run")
    w.add_argument("--factor", type=float, default=1.0, help="the copy's scale (-1: the trade's unwind; default 1)")
    w.add_argument("--set", metavar="NETTING_SET", help="the netting set the candidate joins (default none: a netting set of its own)")
    w.add_argument("--candidates", metavar="FILE.json", help="candidates: [{tradeId, nettingSet, paths: {copyOf, factor} or {runId, mtm}, "
                                                             "assetClass, notional, maturityDate, mtm}]")
    w.add_argument("--method", default="paths", choices=["paths", "addon"])
    p.set_defaults(func=cmd, cmd=None)
