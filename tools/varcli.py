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

"""`drishti.py var ...`: the VaR engine from the command line (docs/guides/VAR_GUIDE.md; the API: /api/v1/var), as the token's user sees
it: a scope's headline (show), its ranked P&L path (path), history and backtest (history), contributions (parts), the hierarchy
(tree), the engine's state and tiers (status), captures (capture, captures, compare), baskets (basket, baskets), the official end-of-day
record (official), a reload (administrators) and a what-if through the Python engine (whatif: hypothetical trades from a JSON file).
Every figure in a reporting currency with --ccy (2.9.4; USD by default): the vectors converted through USD with each scenario's FX, so
the currency move is part of the risk (VAR_GUIDE.md section 15.10). Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import json
import urllib.parse

EPILOG = ("examples:\n  drishti.py var show 'DESK:RATES'\n  drishti.py var show 'BANK - DESK:CREDIT' --date 2026-09-30\n"
          "  drishti.py var show BANK --ccy EUR\n"
          "  drishti.py var parts 'DESK:RATES' --by position --top 10\n  drishti.py var path 'DESK:RATES' --size 10\n"
          "  drishti.py var history 'DESK:RATES'\n  drishti.py var tree --hierarchy LEGAL\n  drishti.py var status\n"
          "  drishti.py var basket rates-hedges \"WHERE:'hedge' in TAGS\" --title 'Rates hedges'\n"
          "  drishti.py var capture 'BASKET:asha/rates-hedges' --note 'before the unwind'\n  drishti.py var compare CAP-20260930-7 now\n"
          "  drishti.py var whatif 'DESK:RATES' --trades new-swap.json --stress-window 250")


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
    """A scope's headline in a few lines: VaR, ES, limit, parts, backtest and where the answer came from."""
    say(f"VAR {h.get('canonical')}  ·  {h.get('label')}  ·  {h.get('kind')}  ·  {h.get('businessDate')}")
    conf = h.get("confidence") or 0
    cur = h.get("currency") or "USD"
    say(f"  VaR {conf * 100:g}%   {money(h.get('var'))} {cur}   (the {h.get('k')}th worst of {h.get('scenarios')})")
    say(f"  ES {(h.get('esConfidence') or 0) * 100:g}%   {money(h.get('es'))} {cur}   (mean of the {h.get('esCount')} worst)")
    if h.get("limit") is not None:
        say(f"  limit      {money(h.get('limit'))} {cur}   use {100 * (h.get('limitUse') or 0):.1f}%")
    if h.get("children"):
        say(f"  parts      {h['children']} {h.get('childKind') or ''}   standalone sum {money(h.get('sumStandalone'))}   "
            f"diversification {money(h.get('diversification'))}")
    b = h.get("backtest") or {}
    if b:
        say(f"  backtest   {b.get('light')}  {b.get('exceptions')} exceptions in {b.get('days')} days   "
            f"Kupiec p {b.get('kupiecPValue', 0):.3f}   Christoffersen p {b.get('christoffersenPValue', 0):.3f}")
    p = h.get("provenance") or {}
    say(f"  {p.get('trades')} trades in {p.get('positions')} positions" + (f", {p['hidden']} hidden from you" if p.get("hidden") else "")
        + (f"; unknown {', '.join(p['unknown'])}" if p.get("unknown") else ""))
    say(f"  {p.get('vectors')}")
    fx = p.get("fx") or {}
    if cur != "USD" or fx.get("rates"):
        rates = "; ".join(f"{c} {r.get('source')} ({r.get('base'):.6g} USD)" for c, r in sorted((fx.get("rates") or {}).items())
                          if isinstance(r, dict) and isinstance(r.get("base"), (int, float)))
        say(f"  in {cur}, crosses {fx.get('crosses') or 'through USD'}" + (f"; FX {rates}" if rates else ""))
    say(f"  {p.get('tier')} · {p.get('method')} · {p.get('computeMs')} ms ({p.get('cache')})")
    for w in p.get("warnings") or []:
        say(f"  warning: {w}")


def render_parts(c: dict, top: int, say) -> None:
    say(f"{c.get('canonical')}  by {c.get('by')}  VaR {money(c.get('var'))}  ES {money(c.get('es'))} {c.get('currency') or 'USD'}  "
        f"sum of components {money(c.get('sumComponent'))}" + (f"  ({c.get('total')} parts, the largest shown)" if c.get("truncated") else ""))
    rows = [["Part", "Standalone VaR", "Component VaR", "% of VaR", "Incremental VaR", "Component ES"]]
    for r in (c.get("rows") or [])[:top]:
        rows.append([str(r.get("label") or r.get("key")), money(r.get("standaloneVar")), money(r.get("componentVar")),
                     f"{100 * (r.get('pct') or 0):.1f}", money(r.get("incrementalVar")), money(r.get("componentEs"))])
    table(rows, say)


def render_path(pth: dict, say) -> None:
    say(f"{pth.get('canonical')}  VaR {money(pth.get('var'))} (rank {pth.get('k')})  ES {money(pth.get('es'))} (worst {pth.get('esCount')})  "
        f"{pth.get('currency') or 'USD'}")
    rows = [["Rank", "Scenario day", "P&L", "Lost most"]]
    for r in pth.get("rows") or []:
        rows.append([str(r.get("rank")), str(r.get("date")), money(r.get("pnl")), f"{r.get('from') or ''} {money(r.get('fromPnl'))}".strip()])
    table(rows, say)


def render_history(hs: dict, say) -> None:
    b = hs.get("backtest") or {}
    say(f"history ({hs.get('mode')}, {hs.get('currency') or 'USD'}): {hs.get('note') or ''}".rstrip())
    say(f"  {b.get('light')}  {b.get('exceptions')} exceptions in {b.get('days')} days   Kupiec LR {b.get('kupiecLr', 0):.3f} "
        f"(p {b.get('kupiecPValue', 0):.3f})   Christoffersen LR {b.get('christoffersenLr', 0):.3f} (p {b.get('christoffersenPValue', 0):.3f})")
    rows = [["Date", "VaR", "ES", "P&L", "Exception"]]
    for r in (hs.get("rows") or [])[-15:]:
        rows.append([str(r.get("date")), money(r.get("var")), money(r.get("es")), money(r.get("pnl")), "yes" if r.get("exception") else ""])
    table(rows, say)


def trades_of(path: str, err) -> list[dict]:
    """The what-if trades of a JSON file: a list of {LABEL, PNL: [509 numbers], FACTOR}, or {"items": [...]}."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise err(f"cannot read the what-if trades in {path}: {e}", 2)
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list) or not all(isinstance(i, dict) and isinstance(i.get("PNL"), list) for i in items):
        raise err(f"{path}: give a list of trades, each with its PNL (one number per scenario day)", 2)
    return items


def register(groups, srv, ctx: dict) -> None:
    """Adds ``var show|parts|path|history|tree|status|capture|captures|compare|basket|baskets|official|reload|whatif``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def dated(a, params: dict) -> dict:
        if getattr(a, "date", None):
            params["asOf"] = a.date
        if getattr(a, "ccy", None):
            params["ccy"] = a.ccy
        return params

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        c = a.var_cmd
        if c == "show":
            res = api.json("GET", "/api/v1/var/scope?" + enc(dated(a, {"scope": a.scope})))
            say_json(res) if a.json else render_headline(res, print)
        elif c == "parts":
            res = api.json("GET", "/api/v1/var/scope/contributions?" + enc(dated(a, {"scope": a.scope, "by": a.by, **({"scenario": a.scenario}
                                                                                                                     if a.scenario else {})})))
            say_json(res) if a.json else render_parts(res, a.top, print)
        elif c == "path":
            res = api.json("GET", "/api/v1/var/scope/path?" + enc(dated(a, {"scope": a.scope, "page": a.page, "size": a.size})))
            say_json(res) if a.json else render_path(res, print)
        elif c == "history":
            res = api.json("GET", "/api/v1/var/scope/history?" + enc(dated(a, {"scope": a.scope})))
            say_json(res) if a.json else render_history(res, print)
        elif c == "tree":
            res = api.json("GET", "/api/v1/var/tree?" + enc(dated(a, {"hierarchy": a.hierarchy})))
            if a.json:
                say_json(res)
            else:
                print(f"{res.get('hierarchy')} on {res.get('businessDate')}, in {res.get('currency') or 'USD'}")
                rows = [["Node", "VaR", "ES", "Limit use", "Trades"]]
                for n in res.get("nodes") or []:
                    rows.append(["  " * int(n.get("depth") or 0) + str(n.get("name")), money(n.get("var")), money(n.get("es")),
                                 "" if n.get("limitUse") is None else f"{100 * n['limitUse']:.1f}%", str(n.get("trades"))])
                table(rows, print)
        elif c == "status":
            say_json(api.json("GET", "/api/v1/var/status"))
        elif c == "capture":
            say_json(api.json("POST", "/api/v1/var/captures?" + enc(dated(a, {})), {"scope": a.scope, "note": a.note or ""}))
        elif c == "captures":
            say_json(api.json("GET", "/api/v1/var/captures" + ("?" + enc({"scope": a.scope}) if a.scope else "")))
        elif c == "compare":
            say_json(api.json("GET", "/api/v1/var/compare?" + enc({"a": a.a, "b": a.b or "now"})))
        elif c == "basket":
            body = {"name": a.name, "scope": a.scope, "title": a.title or a.name, "description": a.description or "",
                    "sharedWith": a.share or []}
            say_json(api.json("POST", "/api/v1/var/baskets", body))
        elif c == "baskets":
            say_json(api.json("GET", "/api/v1/var/baskets"))
        elif c == "official":
            say_json(api.json("POST" if a.capture else "GET", "/api/v1/var/official?" + enc(dated(a, {}))))
        elif c == "reload":
            say_json(api.json("POST", "/api/v1/var/reload"))
        elif c == "whatif":
            params = {"stress-window": a.stress_window}
            if a.ccy:
                params["ccy"] = a.ccy
            if a.trades:
                box = api.json("POST", "/api/v1/compute/sandboxes", {"connector": "var-whatif", "title": f"what-if on {a.scope}",
                                                                      "items": trades_of(a.trades, CliError)})
                params["sandbox"] = box.get("id")
            body = {"id": a.scope, "params": params, "wait": a.wait}
            if a.date:
                body["asOf"] = a.date
            res = api.json("POST", "/api/v1/compute/var-whatif/var-whatif", body)
            if a.json:
                say_json(res)
            else:
                r = res.get("result") or {}
                print(f"what-if on {a.scope}  {str(res.get('state', '')).upper()}  ({res.get('requestId')})  in {r.get('CURRENCY') or 'USD'}")
                if res.get("message"):
                    print("  " + res["message"])
                for k in ("BEFORE_VAR", "AFTER_VAR", "CHANGE_VAR", "BEFORE_ES", "AFTER_ES", "CHANGE_ES", "STRESSED_VAR_BEFORE", "STRESSED_VAR_AFTER"):
                    if k in r:
                        print(f"  {k:<20} {money(r[k])}")
            return 0 if res.get("state") in ("ready", "computing", "stale") else 1
        return 0

    p = groups.add_parser("var", help="var: the VaR engine as you see it (show, parts, path, history, tree, status, captures, baskets, what-if)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="var_cmd", required=True)

    def scoped(name, help_):
        s = sub.add_parser(name, help=help_, parents=[srv])
        s.add_argument("scope", help="a scope: BANK, DESK:RATES, POS:<id>, TRD:<id>, PORT:<id>, BASKET:<owner>/<name>, WHERE:<rule>, with + - &")
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest with vectors)")
        s.add_argument("--ccy", metavar="CCY", help="the reporting currency (USD by default; any currency with scenario FX on the date)")
        return s

    scoped("show", "a scope's headline: VaR, ES, limit, parts, backtest, provenance")
    pr = scoped("parts", "a scope's contributions: standalone, component, incremental VaR per part")
    pr.add_argument("--by", default="child", choices=["child", "node", "position", "trade", "desk", "book"])
    pr.add_argument("--scenario", metavar="YYYY-MM-DD", help="each part's P&L on this scenario day")
    pr.add_argument("--top", type=int, default=25, help="parts printed (default 25; --json has them all)")
    pa = scoped("path", "a scope's P&L path, worst first, and what lost most on each day")
    pa.add_argument("--page", type=int, default=0)
    pa.add_argument("--size", type=int, default=20)
    scoped("history", "a scope's VaR history and backtest (official for a node, else the replay of today's vector)")
    t = sub.add_parser("tree", help="the hierarchy with every node's VaR, ES and limit use", parents=[srv])
    t.add_argument("--hierarchy", default="MGMT")
    t.add_argument("--date", metavar="YYYY-MM-DD")
    t.add_argument("--ccy", metavar="CCY", help="the reporting currency (USD by default)")
    sub.add_parser("status", help="the engine's settings, tiers (hot files on disk, mapped dates) and loaded dates", parents=[srv])
    cp = scoped("capture", "capture a scope's answer, immutable (needs risk:write)")
    cp.add_argument("--note")
    cs = sub.add_parser("captures", help="the captures you may read, newest first", parents=[srv])
    cs.add_argument("--scope")
    cm = sub.add_parser("compare", help="compare two captures, or a capture with now", parents=[srv])
    cm.add_argument("a")
    cm.add_argument("b", nargs="?")
    bk = sub.add_parser("basket", help="save a basket (a named scope; dynamic for rules and nodes)", parents=[srv])
    bk.add_argument("name")
    bk.add_argument("scope")
    bk.add_argument("--title")
    bk.add_argument("--description")
    bk.add_argument("--share", action="append", metavar="USER|role:NAME")
    sub.add_parser("baskets", help="the baskets you may read", parents=[srv])
    of = sub.add_parser("official", help="the date's official end-of-day record (--capture: take it now; administrators)", parents=[srv])
    of.add_argument("--date", metavar="YYYY-MM-DD")
    of.add_argument("--capture", action="store_true")
    of.add_argument("--ccy", metavar="CCY", help="the record in another currency of drishti.var.official.currencies (USD by default)")
    sub.add_parser("reload", help="drop the loaded dates (administrators): the next request maps the hot file again or reads the lake",
                   parents=[srv])
    w = scoped("whatif", "a scope's VaR before and after hypothetical trades (the Python engine, var-whatif), and its stressed window")
    w.add_argument("--trades", metavar="FILE.json", help="the hypothetical trades: [{LABEL, PNL: [one per scenario day], FACTOR}]")
    w.add_argument("--stress-window", type=int, default=250, metavar="DAYS", help="scenario days of the stressed window (0: none)")
    w.add_argument("--wait", type=int, default=10000, metavar="MS")
    p.set_defaults(func=cmd, cmd=None)
