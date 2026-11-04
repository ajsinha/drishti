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
(tree), a trade's, position's or node's scenario matrix over its last N business dates (matrix, 2.9.7), the engine's state and
tiers (status), the shards across Drishti*net (shards: the date's shard map, owners, members; --drain), captures
(capture, captures, compare), baskets (basket, baskets; basket-delete: yours, or with --owner anyone's for an administrator; baskets --all: everyone's, administrators), the official end-of-day
record (official), a reload (administrators) and a pre-deal what-if by the server's VaR engine (whatif: copies of booked trades or
scopes with --copy, or trades priced elsewhere from a JSON file).
Every figure in a reporting currency with --ccy (2.9.4; USD by default): the vectors converted through USD with each scenario's FX, so
the currency move is part of the risk (VAR_GUIDE.md section 15.10). Every answer of a scope, the tree and a capture through a lens
(2.9.7, section 18.6): --horizon 10d with --method sqrt10 or sum (none: the setting's), --confidence and --es-confidence from the
configured lists (0.95 or 95), and --stressed (section 18.8: stressed VaR, the scope on the date's stress scenario set, in USD).
Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import json
import sys
import urllib.parse

EPILOG = ("examples:\n  drishti.py var show 'DESK:RATES'\n  drishti.py var show 'BANK - DESK:CREDIT' --date 2026-09-30\n"
          "  drishti.py var show BANK --ccy EUR\n"
          "  drishti.py var show BANK --horizon 10d --method sum\n  drishti.py var show BANK --confidence 95\n"
          "  drishti.py var show BANK --stressed\n  drishti.py var show BANK --stressed --horizon 10d --method sum\n"
          "  drishti.py var parts 'DESK:RATES' --by position --top 10\n  drishti.py var path 'DESK:RATES' --size 10\n"
          "  drishti.py var history 'DESK:RATES'\n  drishti.py var tree --hierarchy LEGAL\n  drishti.py var status\n"
          "  drishti.py var matrix 'DESK:RATES' --days 20 --csv > rates.csv\n  drishti.py var matrix POS-BOOK-1~SEC-A --by trade\n"
          "  drishti.py var shards --date 2026-09-30\n"
          "  drishti.py var basket rates-hedges \"WHERE:'hedge' in TAGS\" --title 'Rates hedges'\n"
          "  drishti.py var basket-delete rates-hedges\n  drishti.py var basket-delete old-unwind --owner asha --reason 'left the desk'\n"
          "  drishti.py var capture 'BASKET:asha/rates-hedges' --note 'before the unwind'\n  drishti.py var compare CAP-20260930-7 now\n"
          "  drishti.py var whatif 'DESK:RATES' --copy BOOK:BOOK-RATES-3=-0.5\n  drishti.py var whatif 'DESK:RATES' --trades new-swap.json\n"
          "  drishti.py var explain BANK\n  drishti.py var explain 'DESK:RATES' --from 2026-09-29 --to 2026-09-30 --xlsx why.xlsx")


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
    lens = h.get("lens") or {}
    unit = " overlapping windows" if lens.get("method") == "SUM" else ""
    say(f"  horizon    {lens.get('label') or '1-day'}" + (f"   ({lens.get('describe')})" if lens.get("describe") else ""))
    say(f"  VaR {conf * 100:g}%   {money(h.get('var'))} {cur}   (the {h.get('k')}th worst of {h.get('scenarios')}{unit})")
    say(f"  ES {(h.get('esConfidence') or 0) * 100:g}%   {money(h.get('es'))} {cur}   (mean of the {h.get('esCount')} worst)")
    if h.get("override"):
        say("  asked     " + str(h["override"].get("note") or ""))
    avg = h.get("stressAverage")
    if avg:                                                             # stressed VaR (VAR_GUIDE.md section 18.8): its 12-week average
        say(f"  {avg.get('weeks')}-week avg " + (f"{money(avg.get('average'))} {cur}   ({avg.get('note')})" if avg.get("average") is not None
                                                 else str(avg.get("note") or "")))
    if h.get("limit") is not None:
        say(f"  limit      {money(h.get('limit'))} {cur}   " + (f"use {100 * (h.get('limitUse') or 0):.1f}%" if h.get("limitComparable") is not False
                                                              else str(h.get("limitNote") or "not comparable")))
    if h.get("children"):
        say(f"  parts      {h['children']} {h.get('childKind') or ''}   standalone sum {money(h.get('sumStandalone'))}   "
            f"diversification {money(h.get('diversification'))}")
    b = h.get("backtest") or {}
    if b and b.get("applicable") is False:
        say(f"  backtest   {b.get('note')}")
    elif b:
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
    i = h.get("intraday")
    if i:
        say(f"  intraday   {i.get('trades')} trades updated since the morning run ({i.get('amended')} amended, {i.get('added')} new, "
            f"{i.get('cancelled')} cancelled) in {i.get('batchCount')} batches, latest {i.get('lastAt')}; the official records are the morning run's")
    say(f"  {p.get('tier')} · {p.get('method')} · {p.get('computeMs')} ms ({p.get('cache')})")
    for w in p.get("warnings") or []:
        say(f"  warning: {w}")
    pm = h.get("parametric")
    if pm:                                                              # PVAR (PARAMETRIC_VAR.md): the cross-check, never official
        gp = pm.get("gapPct")
        say(f"  parametric VaR {money(pm.get('var'))} {cur} = z·σ − μ = {pm.get('z', 0):.4f} × {money(pm.get('sigma'))} − ({money(pm.get('mu'))})   "
            f"ES {money(pm.get('es'))}   fat-tail gap {'+' if (pm.get('gap') or 0) >= 0 else ''}{money(pm.get('gap'))}"
            + (f" ({100 * gp:+.1f}%)" if gp is not None else "") + "   a cross-check, not official")


def shown_line(c: dict) -> str:
    """The rows-shown line of a capped answer: '5,000 of 18,567 positions shown (the largest by gross size); totals are over all 18,567'."""
    total = int(c.get("total") or 0)
    shown = int(c.get("shown") if c.get("shown") is not None else len(c.get("rows") or []))
    what = {"position": "positions", "trade": "trades"}.get(c.get("by"), "parts")
    return f"{shown:,} of {total:,} {what} shown (the largest by gross size); totals are over all {total:,}"


def render_parts(c: dict, top: int, say) -> None:
    say(f"{c.get('canonical')}  by {c.get('by')}  VaR {money(c.get('var'))}  ES {money(c.get('es'))} {c.get('currency') or 'USD'}  "
        f"sum of components {money(c.get('sumComponent'))}" + (f"  ({shown_line(c)})" if c.get("truncated") else ""))
    pm = c.get("parametric")
    rows = [["Part", "Standalone VaR", "Component VaR", "% of VaR", "Incremental VaR", "Component ES"] + (["Param. component", "ρ"] if pm else [])]
    for r in (c.get("rows") or [])[:top]:
        rows.append([str(r.get("label") or r.get("key")), money(r.get("standaloneVar")), money(r.get("componentVar")),
                     f"{100 * (r.get('pct') or 0):.1f}", money(r.get("incrementalVar")), money(r.get("componentEs"))]
                    + ([money(r.get("pComponentVar")), f"{r.get('rho') or 0:.3f}"] if pm else []))
    table(rows, say)
    if pm:
        say(f"parametric VaR {money(pm.get('var'))}: the components add up to {money(pm.get('pSumComponent'))}"
            + ("" if pm.get("addsUp") else " (the parts shown are not all the scope)") + f"; diversification {money(pm.get('pDiversification'))}")
        m = pm.get("matrix")
        if m:
            say(f"correlations ({m.get('engine')}{', the largest ' + str(len(m.get('keys') or [])) if m.get('truncated') else ''}):")
            labels = [str(x)[:12] for x in m.get("labels") or []]
            table([[""] + labels] + [[labels[i]] + [f"{v:.2f}" for v in row] for i, row in enumerate(m.get("correlation") or [])], say)


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


def render_stress_window(d: dict, say) -> None:
    """The stress window selection as text: the history, the current and worst windows, the alert, the candidates, each node."""
    h, cur, worst, al = d.get("history") or {}, d.get("current") or {}, d.get("worst") or {}, d.get("alert") or {}
    say(f"SWIN {d['node']['id']}  ·  positions {d.get('date')}  ·  history {h.get('first')} to {h.get('last')} ({h.get('days')} days"
        f"{', synthetic' if h.get('synthetic') else ''})  ·  windows of {d.get('length')} days at {d.get('confidence')}")
    say(f"current {cur.get('first', '-')} to {cur.get('last', '-')}: {money(cur.get('var'))}" if cur else (d.get("currentNote") or "no current window"))
    say(f"worst   {worst.get('first')} to {worst.get('last')}: {money(worst.get('var'))}")
    if al.get("gap") is not None:
        say(f"{'ALERT' if al.get('raised') else 'no alert'}: worst against current {al['gap'] * 100:+.1f}% (threshold {al.get('threshold', 0) * 100:.1f}%)")
    rows = [["#", "window", "stressed VaR", "vs current", ""]]
    for c in d.get("candidates") or []:
        vs = c.get("vsCurrent")
        rows.append([str(c.get("rank")), f"{c.get('first')} to {c.get('last')}", money(c.get("var")), "-" if vs is None else f"{vs * 100:+.1f}%",
                     " ".join(t for t, on in (("worst", c.get("worst")), ("current", c.get("current"))) if on)])
    table(rows, say)
    rows = [["node", "current", "its worst", "starts", "gap"]]
    for m in d.get("byNode") or []:
        g = m.get("gap")
        rows.append([m["id"], money(m.get("current")), money(m.get("worst")), m.get("worstFirst") or "-", "-" if g is None else f"{g * 100:+.1f}%"])
    table(rows, say)


def render_proposals(b: dict, say) -> None:
    rows = [["id", "node", "window", "stressed VaR", "status", "proposed by", "decided by"]]
    for r in b.get("items") or []:
        p = r.get("proposed") or {}
        rows.append([r.get("id"), (r.get("node") or {}).get("id", ""), p.get("first", ""), money(p.get("var")), r.get("status"),
                     r.get("proposedBy") or "", r.get("decidedBy") or "-"])
    table(rows, say)


def render_matrix(m: dict, top: int, say) -> None:
    """The scenario matrix (VAR_GUIDE.md section 3.9) in a few lines: each date's source, VaR, ES and gap, then the scenarios worst on the
    newest date (a gap or a day outside a date's window shows as a dash, never a zero)."""
    dates = m.get("dates") or []
    say(f"SMAT {m.get('scope')}  ·  {m.get('kind')}  ·  {len(dates)} business dates  ·  {m.get('currency') or 'USD'}"
        + (f"  ·  {m['note']}" if m.get("note") else ""))
    by_trade = m.get("by") == "trade"
    if by_trade:
        say(f"  by trade, under the scenario {m.get('scenario')}")
    head = [["Date", "Source", "VaR (rank)" if not by_trade else "Position", "ES" if not by_trade else "Rank", "Base PV" if not by_trade else "", "Gap"]]
    for c in dates:
        if by_trade:
            head.append([str(c.get("date")), str(c.get("source")), money(c.get("total")), str(c.get("rank") or ""), "", str(c.get("gap") or "")])
        else:
            head.append([str(c.get("date")), str(c.get("source")), f"{money(c.get('var'))} (#{c.get('k')})" if c.get("k") else "—",
                         money(c.get("es")), money(c.get("basePv")), str(c.get("gap") or "")])
    table(head, say)
    if not dates:
        return
    newest = dates[-1]["date"]
    first = "trade" if by_trade else "scenario"
    rows = sorted(m.get("rows") or [], key=lambda r: (r.get(newest) is None, r.get(newest) if r.get(newest) is not None else 0))
    shown = [d["date"] for d in dates[-6:]]
    tails = {c["date"]: {t["scenario"]: t["rank"] for t in c.get("tail") or []} for c in dates}
    out = [[first.capitalize()] + [d[5:] for d in shown]]
    for r in rows[:top]:
        out.append([str(r.get(first))] + [("—" if r.get(d) is None else money(r.get(d))) + ("*" if not by_trade and r.get(first) in tails.get(d, {}) else "")
                                          for d in shown])
    table(out, say)
    if not by_trade:
        say("  * in that date's VaR or ES tail; the newest 6 dates shown (--json or --csv has them all)")


def render_playground(g: dict, say) -> None:
    """The VaR Playground (VAR_GUIDE.md section 3.10) in a few lines: the headline, the checks and the largest positions' figures."""
    f, ln = g.get("figures") or {}, g.get("lens") or {}
    pm = g.get("parametric")
    if pm:                                                              # the Parametric tab: the server's steps, as the page shows them
        for s in pm.get("steps") or []:
            say(s)
    say(f"{g.get('label')} on {g.get('businessDate')} in {g.get('currency')}: {g.get('shown')} of {g.get('total')} positions, "
        f"{len(g.get('scenarios') or [])} scenarios")
    say(f"VaR {100 * (ln.get('confidence') or 0.99):g}% {f.get('var'):,.2f} (k {f.get('k')}, scenario {f.get('varScenarioDate')}), "
        f"ES {f.get('es'):,.2f}, 10-day √10 {f.get('var10sqrt'):,.2f}, 10-day sum {f.get('var10sum'):,.2f}")
    for c in g.get("checks") or []:
        say(("  ✓ " if c.get("ok") else "  ✗ ") + c.get("name", ""))
    for p in (g.get("positions") or [])[:10]:
        inc = p.get("incrementalVar")
        say(f"  {p.get('id'):<34} {p.get('form'):<3} {p.get('currency'):<3} component {p.get('componentVar'):>16,.2f}  standalone "
            f"{p.get('standaloneVar'):>16,.2f}" + (f"  incremental {inc:,.2f}" if inc is not None else ""))
    if g.get("rest"):
        say(f"  Rest of scope ({g['rest'].get('count')} positions) component {g['rest'].get('componentVar'):,.2f}")


def render_explain(x: dict, say, bar_width: int = 32) -> None:
    """Why VaR moved (VAR_GUIDE.md section 3.11): the sentences, the waterfall as aligned lines (a bar to scale each, + raises VaR, -
    lowers it), the states with their VaR scenario, each step's top drivers, the order-free view and the add-up check."""
    for s in x.get("sentences") or []:
        say(s)
    say("")
    lines = x.get("lines") or []
    biggest = max([abs(l.get("effect") or 0) for l in lines] + [1e-9])
    labels = [f"VaR {x.get('from')}"] + [str(l.get("label")) for l in lines] + [f"VaR {x.get('to')}", "change"]
    w = max(len(t) for t in labels)
    say(f"{labels[0]:<{w}}  {money(x.get('varFrom')):>16}")
    for l in lines:
        e = l.get("effect") or 0
        n = int(round(abs(e) / biggest * bar_width))
        say(f"{str(l.get('label')):<{w}}  {signed(e):>16}  {('+' if e >= 0 else '-') * n}".rstrip())
    say(f"{labels[-2]:<{w}}  {money(x.get('varTo')):>16}")
    say(f"{'change':<{w}}  {signed(x.get('change') or 0):>16}  the steps add up: {'yes' if x.get('addsUp') else 'NO'}")
    say("")
    rows = [["State", "After", "Trades", "Scenarios", "VaR", "VaR scenario"]]
    for st in x.get("states") or []:
        rows.append([str(st.get("id")), str(st.get("what")), str(st.get("trades")), str(st.get("scenarios")), money(st.get("var")),
                     str(st.get("varScenario") or "")])
    table(rows, say)
    for st in x.get("steps") or []:
        d = st.get("drivers") or {}
        top = (d.get("rows") or [])[:5]
        if not top:
            continue
        say("")
        say(f"{st.get('label')}: {signed(st.get('effect') or 0)}")
        for r in top:
            if d.get("kind") == "children":
                say(f"  {str(r.get('label')):<28} {money(r.get('componentBefore')):>14} -> {money(r.get('componentAfter')):>14}  {signed(r.get('change') or 0):>12}")
            elif d.get("kind") == "days":
                say(f"  {r.get('day')}  P&L {money(r.get('pnl')):>14}  rank {r.get('rank')}{'  in the tail' if r.get('inTail') else ''}")
            else:
                say(f"  {str(r.get('id')):<28} {signed(r.get(d.get('field') or 'incrementalVar') or 0):>14}  {r.get('reason') or r.get('how') or ''}".rstrip())
        if d.get("restLabel"):
            say(f"  {str(d.get('restLabel')):<28} {signed(d.get('rest') or 0):>14}")
    of = x.get("orderFree") or {}
    if of.get("rows"):
        say("")
        say(f"Order-free, by {of.get('by')}: component VaR on {x.get('to')} less on {x.get('from')}")
        for r in of["rows"][:10]:
            say(f"  {str(r.get('label')):<28} {signed(r.get('change') or 0):>14}")
        say(f"  {'total':<28} {signed(of.get('total') or 0):>14}  adds up: {'yes' if of.get('addsUp') else 'NO'}")
    for wn in x.get("warnings") or []:
        say(f"note: {wn}")


def signed(v) -> str:
    return ("+" if v >= 0 else "-") + money(abs(v))


def render_explain_matrix(m: dict, say) -> None:
    """The Matrix view: one row a node, VaR before, each step, VaR after."""
    steps = m.get("steps") or []
    rows = [["Node", f"VaR {m.get('from')}"] + [str(s.get("label")) for s in steps] + [f"VaR {m.get('to')}", "Change"]]
    for r in m.get("rows") or []:
        eff = r.get("effects") or {}
        rows.append(["  " * int(r.get("depth") or 0) + str(r.get("label")), money(r.get("varFrom"))]
                    + [money(eff.get(s.get("key"))) for s in steps] + [money(r.get("varTo")), signed(r.get("change") or 0)])
    table(rows, say)
    if m.get("note"):
        say(m["note"])


def render_estimate(e: dict, say) -> None:
    """Each export size: positions, layout, size, recalculation time and the applications, or why it cannot be made."""
    say(f"{e.get('canonical')}: {e.get('positions'):,} positions, {e.get('scenarios')} scenarios")
    for c in e.get("choices") or []:
        say(f"  {c.get('label'):<14} " + (f"refused: {c['refused']}" if c.get("refused") else
            f"{c.get('size'):>8}  {c.get('layout'):<20} recalculates in {c.get('recalc'):<14} {', '.join(c.get('apps') or [])}"))


def matrix_csv(m: dict) -> str:
    """The matrix as CSV, as the console's export writes it (routes/smat_routes.py to_csv): a row per scenario, a column per date, then
    VaR, ES, base PV, the source and the gap of each date."""
    import csv
    import io
    dates = [c.get("date") for c in m.get("dates") or []]
    first = "trade" if m.get("by") == "trade" else "scenario"
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\n")
    w.writerow([first] + dates)
    for r in m.get("rows") or []:
        w.writerow([r.get(first)] + ["" if r.get(d) is None else r.get(d) for d in dates])
    labels = m.get("labels") or {}
    meta = m.get("dates") or []
    keys = (("total", "total"),) if first == "trade" else (("var", labels.get("var", "VaR")), ("es", labels.get("es", "ES")),
                                                         ("basePv", labels.get("basePv", "Base PV")))
    for k, label in keys:
        w.writerow([label] + ["" if c.get(k) is None else c.get(k) for c in meta])
    w.writerow(["source"] + [c.get("source") or "" for c in meta])
    w.writerow(["gap"] + [c.get("gap") or "" for c in meta])
    return out.getvalue()


def render_shards(s: dict, say) -> None:
    """VaR sharding across Drishti*net (docs/architecture/VAR_SHARDING.md): the settings, the members, the date's shards and their owners."""
    if not s.get("enabled"):
        say(f"VaR sharding is off ({s.get('origin')}): every date is loaded whole on each server"
            + (f"; {s['problem']}" if s.get("problem") else ""))
        return
    say(f"VaR sharding on ({s.get('origin')}): key {'/'.join(s.get('key') or [])}, target {s.get('targetShardMb')} MB a shard, "
        f"{s.get('replicas')} owners each; this member {s.get('self')}" + (" (coordinator only)" if s.get("coordinatorOnly") else "")
        + (", draining" + (": safe to stop" if s.get("safeToStop") else ": handing its shards over") if s.get("draining") else ""))
    rows = [["Member", "Owns shards", "In view", "Draining", "Zone"]]
    for m in s.get("members") or []:
        rows.append([str(m.get("node")) + (" (this)" if m.get("self") else ""), "yes" if m.get("owner") else "no",
                     "yes" if m.get("inView") else "no", "yes" if m.get("draining") else "", str(m.get("zone") or "")])
    table(rows, say)
    mp = s.get("map")
    if mp:
        say(f"{mp.get('businessDate')}: {len(mp.get('shards') or [])} shards, map {str(mp.get('digest'))[:16]}"
            + (f" ({mp['note']})" if mp.get("note") else ""))
        rows = [["Shard", "Range", "Rows", "Owners", "Ready on"]]
        for sh in mp.get("shards") or []:
            rows.append([str(sh.get("shard")), str(sh.get("range")), str(sh.get("rows")), ", ".join(sh.get("owners") or []),
                         ", ".join(sh.get("ready") or [])])
        table(rows, say)
    held = s.get("held") or []
    if held:
        say("held here: " + ", ".join(f"{h.get('businessDate')} {h.get('shard')} {h.get('currency')} ({h.get('state')})" for h in held))


def trades_of(path: str, err) -> list[dict]:
    """The what-if trades of a JSON file as the API's items: a list (or {"items": [...]}) of trades priced elsewhere, {LABEL, PNL:
    [509 numbers], FACTOR}, or copies, {COPY_OF: <trade id or scope>, FACTOR, LABEL}; lower-case keys (label, pnl, factor, copyOf) too."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise err(f"cannot read the what-if trades in {path}: {e}", 2)
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list) or not all(isinstance(i, dict) for i in items):
        raise err(f"{path}: give a list of trades, each with its PNL (one number per scenario day) or COPY_OF (a booked trade)", 2)
    out = []
    for i in items:
        it = {"label": i.get("LABEL", i.get("label")), "factor": i.get("FACTOR", i.get("factor", 1)),
              "pnl": i.get("PNL", i.get("pnl")), "copyOf": i.get("COPY_OF", i.get("copyOf"))}
        if not isinstance(it["pnl"], list) and not it["copyOf"]:
            raise err(f"{path}: give a list of trades, each with its PNL (one number per scenario day) or COPY_OF (a booked trade)", 2)
        out.append({k: v for k, v in it.items() if v is not None})
    return out


def copies_of(specs: list[str], err) -> list[dict]:
    """``--copy MX-30000121=-1`` or ``--copy BOOK:BOOK-RATES-3=-0.5``: a copy of a booked trade or a scope, scaled (1 when no factor)."""
    out = []
    for spec in specs or []:
        what, _, f = spec.rpartition("=") if "=" in spec else (spec, "", "1")
        try:
            out.append({"copyOf": what.strip(), "factor": float(f)})
        except ValueError:
            raise err(f"--copy {spec}: a trade or scope, then =FACTOR (-1: the unwind)", 2) from None
    return out


#: the fields of an intraday row that carry its business date (moved with --date)
DATED = ("BUSINESS_DATE", "VALUATION_DATE", "BASE_VALUATION_DATE", "COMPUTED_AT", "BATCH_ID", "SCENARIO_PV_ID", "VECTOR_ID")


def route(rows: list[dict]) -> dict[str, list[dict]]:
    """A file's records by kind, routed by each record's VALUE_TYPE (PV: scenario-pv, PNL: pnl-vector), so one file may mix both
    (VAR_GUIDE.md section 15.13); a record without one is refused with its line (drishti.py var convert adds it)."""
    out: dict[str, list[dict]] = {}
    for n, r in enumerate(rows, 1):
        vt = str(r.get("VALUE_TYPE") or "").upper()
        if vt not in ("PV", "PNL"):
            raise ValueError(f"line {n}: VALUE_TYPE is {r.get('VALUE_TYPE')!r}, not PV or PNL: every record says what it is "
                             "(drishti.py var convert --set VALUE_TYPE=... adds it to an older file)")
        out.setdefault("scenario-pv" if vt == "PV" else "pnl-vector", []).append(r)
    return out


def intraday_kind(rows: list[dict]) -> str:
    """The kind of an intraday file whose records are all one form (by VALUE_TYPE)."""
    kinds = route(rows)
    if len(kinds) != 1:
        raise ValueError("these records are scenario PVs and P&L vectors: drishti.py var intraday --load routes them by VALUE_TYPE")
    return next(iter(kinds))


def move_dates(rows: list[dict], day: str) -> list[dict]:
    """The rows with their business date moved to ``day`` (every date written as the rows' own BUSINESS_DATE)."""
    out = []
    for r in rows:
        old = str(r.get("BUSINESS_DATE") or "")[:10]
        r = dict(r)
        for f in DATED:
            if old and isinstance(r.get(f), str):
                r[f] = r[f].replace(old, day).replace(old.replace("-", ""), day.replace("-", ""))
        out.append(r)
    return out


def load_intraday(path: str, lake: str, day: str | None, signal: str | None, token: str | None, dry_run: bool, say) -> int:
    """Appends an intraday file (JSONL, one kind) to the lake with lake build --mode append, then raises its data-load signal."""
    import os
    import subprocess
    import sys
    import tempfile
    from pathlib import Path
    src = Path(path)
    opener = __import__("gzip").open if src.name.endswith(".gz") else open
    with opener(src, "rt", encoding="utf-8") as fh:
        rows = [json.loads(x) for x in fh if x.strip()]
    if not rows:
        raise ValueError(f"{path}: no rows")
    if day:
        rows = move_dates(rows, day)
    kinds = route(rows)
    for kind, kr in kinds.items():
        marked = sum(1 for r in kr if str(r.get("RUN_KIND") or "").upper() == "INTRADAY")
        say(f"{path}: {len(kr)} {kind} rows ({marked} marked RUN_KIND INTRADAY), business date {kr[0].get('BUSINESS_DATE')}, "
            f"batches {sorted({str(r.get('BATCH_ID')) for r in kr})}")
    if dry_run:
        say("dry run: nothing written")
        return 0
    root = Path(__file__).resolve().parents[1]
    rc = 0
    for kind, kr in kinds.items():
        key = "SCENARIO_PV_ID" if kind == "scenario-pv" else "VECTOR_ID"
        with tempfile.TemporaryDirectory(prefix="drishti-intraday-") as tmp:
            f = Path(tmp) / "rows.jsonl"
            f.write_text("".join(json.dumps(r) + "\n" for r in kr), encoding="utf-8")
            cmd = [sys.executable, str(root / "tools" / "drishti.py"), "lake", "build", "--from", str(f), "--pack",
                   str(root / "config" / "packs" / "market-risk"), "--connector", "risk-store", "--kind", kind, "--key", key, "--date",
                   "BUSINESS_DATE", "--mode", "append", "--lake", lake, "-q"]
            if signal:
                cmd += ["--signal", signal]                     # each kind's own signal: the engine reads a batch's two kinds apart
            env = dict(os.environ, **({"DRISHTI_TOKEN": token} if token else {}))
            rc = rc or subprocess.run(cmd, env=env, cwd=root).returncode
    return rc


def register(groups, srv, ctx: dict) -> None:
    """Adds ``var show|parts|path|history|tree|status|shards|capture|captures|compare|basket|baskets|basket-delete|official|reload|whatif``."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]
    enc = urllib.parse.urlencode

    def dated(a, params: dict) -> dict:
        if getattr(a, "date", None):
            params["asOf"] = a.date
        if getattr(a, "ccy", None):
            params["ccy"] = a.ccy
        return params

    def lensed(a, params: dict) -> dict:
        """The lens's parameters: horizon, method, confidence, esConfidence, and set=stressed (--stressed)."""
        for k, f in (("horizon", "horizon"), ("method", "method"), ("confidence", "confidence"), ("esConfidence", "es_confidence")):
            if getattr(a, f, None) is not None:
                params[k] = getattr(a, f)
        if getattr(a, "stressed", False):
            params["set"] = "stressed"
        return params

    def cmd(a, extra) -> int:
        if a.var_cmd == "convert":
            return convert(a)
        api = Api.from_args(a)
        c = a.var_cmd
        if c == "show":
            q = {"scope": a.scope}
            if a.window is not None and a.stressed:
                print("--window recomputes the normal set's VaR: not with --stressed", file=sys.stderr)
                return 2
            if a.window is not None:                                 # 2.9.4: the newest N scenarios, to compare (1-day, never official)
                for k, v in (("window", a.window), ("confidence", a.confidence), ("es-confidence", a.es_confidence)):
                    if v is not None:
                        q[k] = v
            else:
                lensed(a, q)
            if a.no_parametric:
                q["parametric"] = "false"
            res = api.json("GET", "/api/v1/var/scope?" + enc(dated(a, q)))
            say_json(res) if a.json else render_headline(res, print)
        elif c == "parts":
            res = api.json("GET", "/api/v1/var/scope/contributions?" + enc(lensed(a, dated(a, {"scope": a.scope, "by": a.by, **(
                {"scenario": a.scenario} if a.scenario else {}), **({"matrix": "true"} if a.matrix else {}),
                **({"parametric": "false"} if a.no_parametric and not a.matrix else {})}))))
            say_json(res) if a.json else render_parts(res, a.top, print)
        elif c == "path":
            res = api.json("GET", "/api/v1/var/scope/path?" + enc(lensed(a, dated(a, {"scope": a.scope, "page": a.page, "size": a.size}))))
            say_json(res) if a.json else render_path(res, print)
        elif c == "history":
            res = api.json("GET", "/api/v1/var/scope/history?" + enc(lensed(a, dated(a, {"scope": a.scope}))))
            say_json(res) if a.json else render_history(res, print)
        elif c == "stress-window":
            if a.decide:
                if not a.decision:
                    print("--decide needs --decision approve or reject", file=sys.stderr)
                    return 2
                res = api.json("POST", f"/api/v1/var/stress-window/proposals/{urllib.parse.quote(a.decide)}/decision",
                               {"decision": a.decision, "note": a.note or ""})
                say_json(res) if a.json else print(f"{res.get('id')}  {res.get('status')}  by {res.get('decidedBy')}")
            elif a.proposals:
                res = api.json("GET", "/api/v1/var/stress-window/proposals")
                say_json(res) if a.json else render_proposals(res, print)
            elif a.propose:
                body = {"scope": a.scope, "hierarchy": a.hierarchy, "note": a.note or ""}
                for k, v in (("date", a.date), ("length", a.length), ("confidence", a.confidence), ("first", a.first)):
                    if v is not None:
                        body[k] = v
                res = api.json("POST", "/api/v1/var/stress-window/proposals", body)
                say_json(res) if a.json else print(f"{res.get('id')}  {res.get('status')}  window {(res.get('proposed') or {}).get('first')}")
            else:
                q = {"scope": a.scope, "hierarchy": a.hierarchy}
                for k, v in (("length", a.length), ("confidence", a.confidence)):
                    if v is not None:
                        q[k] = v
                res = api.json("GET", "/api/v1/var/stress-window?" + enc(dated(a, q)))
                say_json(res) if a.json else render_stress_window(res, print)
        elif c == "matrix":
            q = {"id": a.id, "days": a.days}
            for k, v in (("by", a.by), ("scenario", a.scenario), ("confidence", a.confidence), ("es-confidence", a.es_confidence),
                         ("window", a.window)):
                if v not in (None, "", "scenario"):
                    q[k] = v
            res = api.json("GET", "/api/v1/var/matrix?" + enc(dated(a, q)))
            if a.json:
                say_json(res)
            elif a.csv:
                sys.stdout.write(matrix_csv(res))
            else:
                render_matrix(res, a.top, print)
        elif c == "playground":
            q = {"ids": a.ids} if a.ids else {"scope": a.scope}
            if a.measure == "parametric":                             # the Parametric figures, and the workbook's Parametric tab
                q["measure"] = "parametric"
            if a.estimate:
                res = api.json("GET", "/api/v1/var/playground/estimate?" + enc(lensed(a, dated(a, q))))
                say_json(res) if a.json else render_estimate(res, print)
            elif a.xlsx:
                q["count"] = a.count
                _, body = api.request("GET", "/api/v1/var/playground.xlsx?" + enc(lensed(a, dated(a, q))))
                with open(a.xlsx, "wb") as f:
                    f.write(body)
                tabs = "Main, Config, Data, FX, PnL, " + ("Parametric, " if getattr(a, "measure", None) == "parametric" else "") + "Explain, Provenance"
                print(f"wrote {a.xlsx} ({len(body):,} bytes): the VaR Playground workbook, every figure a formula ({tabs})")
            else:
                res = api.json("GET", "/api/v1/var/playground?" + enc(lensed(a, dated(a, q))))
                say_json(res) if a.json else render_playground(res, print)
        elif c == "explain":
            q = {"scope": a.scope}
            for k, v in (("from", a.from_), ("to", a.to), ("confidence", a.confidence)):
                if v is not None:
                    q[k] = v
            if a.xlsx:
                _, body = api.request("GET", "/api/v1/var/explain.xlsx?" + enc(q))
                with open(a.xlsx, "wb") as f:
                    f.write(body)
                print(f"wrote {a.xlsx} ({len(body):,} bytes): why VaR moved, every state and step a formula (Main, Data, States, Drivers, "
                      "Explain, Provenance)")
            elif a.matrix:
                res = api.json("GET", "/api/v1/var/explain/matrix?" + enc(q))
                say_json(res) if a.json else render_explain_matrix(res, print)
            else:
                res = api.json("GET", "/api/v1/var/explain?" + enc(q))
                say_json(res) if a.json else render_explain(res, print)
        elif c == "tree":
            res = api.json("GET", "/api/v1/var/tree?" + enc(lensed(a, dated(a, {"hierarchy": a.hierarchy}))))
            if a.json:
                say_json(res)
            else:
                ln = res.get("lens") or {}
                print(f"{res.get('hierarchy')} on {res.get('businessDate')}, in {res.get('currency') or 'USD'}, {ln.get('label') or '1-day'} "
                      f"VaR {100 * (ln.get('confidence') or 0.99):g}%")
                rows = [["Node", "VaR", "ES", "Limit use", "Trades"]]
                for n in res.get("nodes") or []:
                    rows.append(["  " * int(n.get("depth") or 0) + str(n.get("name")), money(n.get("var")), money(n.get("es")),
                                 "" if n.get("limitUse") is None else f"{100 * n['limitUse']:.1f}%", str(n.get("trades"))])
                table(rows, print)
        elif c == "status":
            st = api.json("GET", "/api/v1/var/status")
            if a.json:
                say_json(st)
            else:
                say_json(st)
                for d in st.get("loaded") or []:
                    i = d.get("intraday")
                    print(f"{d.get('businessDate')} {d.get('currency') or 'USD'}: " + (f"intraday, {i.get('trades')} trades updated in "
                          f"{i.get('batchCount')} batches, latest {i.get('lastAt')}" if i else "the morning run, no intraday update"))
        elif c == "shards":
            if a.drain or a.undrain:
                res = api.json("POST", "/api/v1/var/shards/drain", {"on": bool(a.drain)})
            else:
                res = api.json("GET", "/api/v1/var/shards?" + enc(dated(a, {})))
            say_json(res) if a.json else render_shards(res, print)
        elif c == "intraday":
            if a.load:
                return load_intraday(a.load, a.lake, a.date, None if a.no_signal else api.base, api.token, a.dry_run, print)
            st = api.json("GET", "/api/v1/var/status")
            rows = [d for d in st.get("loaded") or [] if not d.get("currency") and (not a.date or d.get("businessDate") == a.date)]
            if a.json:
                say_json([{"businessDate": d.get("businessDate"), "intraday": d.get("intraday")} for d in rows])
            for d in rows if not a.json else []:
                i = d.get("intraday")
                if not i:
                    print(f"{d.get('businessDate')}: the morning run, no intraday update")
                    continue
                print(f"{d.get('businessDate')}: intraday, {i.get('updates')} updates of {i.get('trades')} trades ({i.get('amended')} amended, "
                      f"{i.get('added')} new, {i.get('cancelled')} cancelled) in {i.get('batchCount')} batches, latest {i.get('lastAt')}")
                print(f"  batches {', '.join(i.get('batches') or [])}; ignored as older {i.get('olderIgnored')}, before the morning run "
                      f"{i.get('beforeMorningIgnored')}")
                for r in i.get("rejections") or []:
                    print(f"  rejected: {r}")
        elif c == "capture":
            say_json(api.json("POST", "/api/v1/var/captures?" + enc(dated(a, {})), lensed(a, {"scope": a.scope, "note": a.note or ""})))
        elif c == "captures":
            say_json(api.json("GET", "/api/v1/var/captures" + ("?" + enc({"scope": a.scope}) if a.scope else "")))
        elif c == "compare":
            say_json(api.json("GET", "/api/v1/var/compare?" + enc({"a": a.a, "b": a.b or "now"})))
        elif c == "basket":
            body = {"name": a.name, "scope": a.scope, "title": a.title or a.name, "description": a.description or "",
                    "sharedWith": a.share or []}
            say_json(api.json("POST", "/api/v1/var/baskets", body))
        elif c == "baskets":
            if a.all or a.owner:                                # every person's (administrators), optionally one owner's
                say_json(api.json("GET", "/api/v1/var/admin/baskets" + ("?" + enc({"owner": a.owner}) if a.owner else "")))
            else:
                say_json(api.json("GET", "/api/v1/var/baskets"))
        elif c == "basket-delete":
            q = urllib.parse.quote
            params = {k: v for k, v in (("reason", a.reason), ("version", a.version)) if v is not None and v != ""}
            path = f"/api/v1/var/baskets/{q(a.owner, safe='')}/{q(a.name, safe='')}" if a.owner else f"/api/v1/var/baskets/{q(a.name, safe='')}"
            api.json("DELETE", path + ("?" + enc(params) if params else ""))
            print(f"deleted the basket {(a.owner + '/') if a.owner else ''}{a.name}")
        elif c == "official":
            if a.synthetic_history:                                  # a demo server's synthetic official history (VAR_GUIDE.md 18.10)
                say_json(api.json("POST", "/api/v1/var/official/synthetic-history?" + enc({"days": a.synthetic_history})))
                return 0
            say_json(api.json("POST" if a.capture else "GET", "/api/v1/var/official?" + enc(dated(a, {}))))
        elif c == "backtest":
            q = dated(a, {"scope": a.scope})
            if a.xlsx:
                _, raw = api.request("GET", "/api/v1/var/backtesting?" + enc({**q, "format": "xlsx"}))
                with open(a.xlsx, "wb") as f:
                    f.write(raw)
                print(f"wrote {a.xlsx} ({len(raw):,} bytes)")
                return 0
            res = api.json("GET", "/api/v1/var/backtesting?" + enc(q))
            say_json(res) if a.json else render_backtest(res, print)
        elif c == "stats":
            res = api.json("GET", "/api/v1/var/statistics?" + enc({"scope": a.scope, "period": a.period or "", "measure": a.measure}))
            say_json(res) if a.json else render_stats(res, print)
        elif c == "report":
            return report_cmd(api, a, say_json)
        elif c == "reload":
            say_json(api.json("POST", "/api/v1/var/reload" + ("?" + enc({"date": a.date}) if getattr(a, "date", None) else "")))
        elif c == "whatif":
            items = copies_of(a.copy, CliError) + (trades_of(a.trades, CliError) if a.trades else [])
            if not items:
                raise CliError("a what-if needs trades: --copy <trade or scope>=FACTOR, or --trades FILE.json", 2)
            body = {"scope": a.scope, "items": items}
            if a.ccy:
                body["ccy"] = a.ccy
            res = api.json("POST", "/api/v1/var/what-if" + ("?" + enc({"asOf": a.date}) if a.date else ""), body)
            if a.json:
                say_json(res)
                return 0
            print(f"what-if on {a.scope}  {res.get('businessDate', '')}  in {res.get('currency') or 'USD'}  ({res.get('engine', '')})")
            for k, label in (("var", "VaR"), ("es", "ES"), ("stressedVar", "stressed VaR")):
                b, x, d = (res.get("before") or {}).get(k), (res.get("after") or {}).get(k), (res.get("change") or {}).get(k)
                print(f"  {label:<13} {money(b):>16} -> {money(x):>16}" + (f"   change {money(d)}" if d is not None else ""))
            for it in res.get("items") or []:
                print(f"  {it.get('label')}: standalone {money(it.get('standaloneVar'))}, incremental {money(it.get('incrementalVar'))}, "
                      f"component {money(it.get('componentVar'))}")
        return 0

    p = groups.add_parser("var", help="var: the VaR engine as you see it (show, parts, path, history, tree, status, captures, baskets, what-if)",
                          description=__doc__.split("\n\n")[0], epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="var_cmd", required=True)

    def scoped(name, help_):
        s = sub.add_parser(name, help=help_, parents=[srv])
        s.add_argument("scope", help="a scope: BANK, DESK:RATES, POS:<id>, TRD:<id>, PORT:<id>, BASKET:<owner>/<name>, WHERE:<rule>, with + - &")
        s.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default the newest with vectors)")
        s.add_argument("--ccy", metavar="CCY", help="the reporting currency (USD by default; any currency with scenario FX on the date)")
        lens_args(s, name != "show")
        return s

    def lens_args(s, confidences: bool) -> None:
        """The lens (2.9.7, VAR_GUIDE.md section 18.6): the horizon, the 10-day method and (show has its own) the confidences."""
        s.add_argument("--horizon", choices=["1d", "10d"], help="1d (the default) or 10d: from the same 1-day scenarios, no new data")
        s.add_argument("--method", choices=["sqrt10", "sum"], help="the 10-day method: sqrt10 (the 1-day × √10) or sum (10 consecutive "
                       "days, overlapping windows); none: drishti.var.ten-day-method")
        if confidences:
            s.add_argument("--confidence", type=float, metavar="Q", help="VaR at this confidence (one of drishti.var.confidences: 0.95, 95)")
            s.add_argument("--es-confidence", type=float, metavar="Q", help="ES at this confidence (one of drishti.var.es-confidences)")
        s.add_argument("--stressed", action="store_true", help="stressed VaR: the scope on the date's stress scenario set (today's positions "
                       "under a 12-month stress window), in USD; a date without one says so")

    sh = scoped("show", "a scope's headline: VaR, ES, limit, parts, backtest, provenance")
    sh.add_argument("--window", metavar="N", help="VaR and ES on the newest N scenarios (250: one year), to compare; never official")
    sh.add_argument("--confidence", type=float, metavar="Q", help="VaR at this confidence (one of drishti.var.confidences: 0.95 or 95; "
                    "another, with --window, the 2.9.4 comparison); never official")
    sh.add_argument("--es-confidence", type=float, metavar="Q", help="ES at this confidence (one of drishti.var.es-confidences); never official")
    sh.add_argument("--no-parametric", action="store_true", help="leave out the parametric line (PVAR's cross-check; parametric=false)")
    pr = scoped("parts", "a scope's contributions: standalone, component, incremental VaR per part")
    pr.add_argument("--matrix", action="store_true", help="the parametric components and the parts' correlation matrix (matrix=true)")
    pr.add_argument("--no-parametric", action="store_true", help="leave out the parametric columns (parametric=false)")
    pr.add_argument("--by", default="child", choices=["child", "node", "position", "trade", "desk", "book"])
    pr.add_argument("--scenario", metavar="YYYY-MM-DD", help="each part's P&L on this scenario day")
    pr.add_argument("--top", type=int, default=25, help="parts printed (default 25; --json has them all)")
    pa = scoped("path", "a scope's P&L path, worst first, and what lost most on each day")
    pa.add_argument("--page", type=int, default=0)
    pa.add_argument("--size", type=int, default=20)
    scoped("history", "a scope's VaR history and backtest (official for a node, else the replay of today's vector)")
    mx = sub.add_parser("matrix", help="a trade's, position's or VaR node's scenario P&L by scenario day across its last N business "
                        "dates (SMAT, 2.9.7)", parents=[srv])
    mx.add_argument("id", help="a trade id, a position id, a P&L vector's or scenario PV's id, or a VaR scope (DESK:RATES, BOOK:BOOK-RATES-1, BANK)")
    mx.add_argument("--days", type=int, default=20, help="the business dates (default 20; at most drishti.var.smat.max-days, 60)")
    mx.add_argument("--date", metavar="YYYY-MM-DD", help="the newest business date (default the newest with vectors)")
    mx.add_argument("--ccy", metavar="CCY", help="the reporting currency (USD by default)")
    mx.add_argument("--by", default="scenario", choices=["scenario", "trade"], help="trade: a position's trades under one scenario day")
    mx.add_argument("--scenario", metavar="YYYY-MM-DD", help="with --by trade: the scenario day (default the newest date's VaR scenario)")
    mx.add_argument("--confidence", type=float, metavar="Q", help="VaR at this confidence (0.95); the tail and the labels follow")
    mx.add_argument("--es-confidence", type=float, metavar="Q", help="ES at this confidence")
    mx.add_argument("--window", metavar="N", help="the newest N scenarios of each date (all by default)")
    mx.add_argument("--top", type=int, default=20, help="scenarios printed, worst on the newest date first (default 20)")
    mx.add_argument("--csv", action="store_true", help="the whole matrix as CSV on standard output (as the page's export)")
    pg = scoped("playground", "the VaR Playground (PLAY VAR, VAR_GUIDE.md section 3.10): a scope's VaR worked out position by position; "
                "--xlsx writes the workbook of real formulas")
    pg.add_argument("--ids", metavar="ID,ID", help="positions or trades instead of the scope (at most drishti.var.playground.max-ids, 50)")
    pg.add_argument("--xlsx", metavar="FILE", help="write the workbook (Main, Config, Data, FX, PnL, Explain, Provenance; with --measure parametric also Parametric) to FILE")
    pg.add_argument("--count", default="500", help="positions in the workbook: 100, 500 (default), 1000, 10000 or all; the rest in Rest of scope")
    pg.add_argument("--estimate", action="store_true", help="each export size's estimated file size, recalculation time and the applications that open it")
    pg.add_argument("--measure", choices=["historical", "parametric"], default="historical",
                    help="parametric: the playground's Parametric tab, the parametric figures and the workbook's Parametric tab (PARAMETRIC_VAR.md section 10)")
    ex = sub.add_parser("explain", help="why VaR moved (VAR EXPLAIN, VAR_GUIDE.md section 3.11): the change between two business dates in "
                        "causes that add up exactly (restatement, hierarchy, trades out, the window, the market, trades in), with who drove each",
                        parents=[srv])
    ex.add_argument("scope", help="a scope: BANK, DESK:RATES, NODE:<id>, PORT:<id>, BASKET:<owner>/<name> ...")
    ex.add_argument("--from", dest="from_", metavar="YYYY-MM-DD", help="the earlier business date (default the one before --to)")
    ex.add_argument("--to", metavar="YYYY-MM-DD", help="the later business date (default the newest with vectors)")
    ex.add_argument("--confidence", type=float, metavar="Q", help="VaR at this confidence (one of drishti.var.confidences)")
    ex.add_argument("--matrix", action="store_true", help="the scope and each child node, the steps across (the Matrix view)")
    ex.add_argument("--xlsx", metavar="FILE", help="write the explanation as a workbook of formulas to FILE")
    sw = sub.add_parser("stress-window", help="the stress window selection (SWIN): every window of a node's long-history P&L as a "
                        "stressed VaR, the worst against the current, the candidates; --propose freezes it, --decide approves or rejects",
                        parents=[srv])
    sw.add_argument("scope", nargs="?", default="BANK", help="the node: BANK, DESK:DESK-RATES, MGMT~DESK-RATES (default BANK)")
    sw.add_argument("--hierarchy", default="MGMT")
    sw.add_argument("--date", metavar="YYYY-MM-DD", help="the positions date (default the newest with long-history rows)")
    sw.add_argument("--length", type=int, metavar="DAYS", help="the window's days (default drishti.var.stress-window.length, 250)")
    sw.add_argument("--confidence", type=float, metavar="Q", help="the VaR confidence (default the engine's, 0.99)")
    sw.add_argument("--propose", action="store_true", help="freeze the analysis and propose a window (--first; default the worst)")
    sw.add_argument("--first", metavar="YYYY-MM-DD", help="with --propose: the proposed window's first day")
    sw.add_argument("--note", help="with --propose or --decide: a note for the record")
    sw.add_argument("--proposals", action="store_true", help="the proposals, newest first")
    sw.add_argument("--decide", metavar="SWP-ID", help="approve or reject a proposal (another person than its proposer)")
    sw.add_argument("--decision", choices=["approve", "reject"])
    t = sub.add_parser("tree", help="the hierarchy with every node's VaR, ES and limit use", parents=[srv])
    t.add_argument("--hierarchy", default="MGMT")
    t.add_argument("--date", metavar="YYYY-MM-DD")
    t.add_argument("--ccy", metavar="CCY", help="the reporting currency (USD by default)")
    lens_args(t, True)
    sub.add_parser("status", help="the engine's settings, tiers (hot files on disk, mapped dates) and loaded dates", parents=[srv])
    sh = sub.add_parser("shards", help="VaR sharding across Drishti*net: the date's shard map, each shard's owners and the members ready for it; "
                        "--drain hands this member's shards over before it stops (administrators)", parents=[srv])
    sh.add_argument("--date", metavar="YYYY-MM-DD", help="the business date (default: the newest)")
    dr = sh.add_mutually_exclusive_group()
    dr.add_argument("--drain", action="store_true", help="hand this member's shards to other owners and take none (a scale-in)")
    dr.add_argument("--undrain", action="store_true", help="take shards again")
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
    bl = sub.add_parser("baskets", help="the baskets you may read; --all: every person's (administrators)", parents=[srv])
    bl.add_argument("--all", action="store_true", help="every person's baskets with owner, scope, sharing, dates and size (administrators)")
    bl.add_argument("--owner", help="with --all: only the baskets whose owner contains this")
    bd = sub.add_parser("basket-delete", help="delete a saved basket: yours, or with --owner anyone's (administrators); captures of it are kept",
                        parents=[srv])
    bd.add_argument("name", help="the basket's name (its id after the owner: rates-hedges in BASKET:asha/rates-hedges)")
    bd.add_argument("--owner", help="the owner's user name, to delete someone else's basket (administrators)")
    bd.add_argument("--reason", help="why (administrators): written to the audit log and sent to the owner's inbox")
    bd.add_argument("--version", type=int, help="the basket's version you saw: refused (409) when it changed since")
    of = sub.add_parser("official", help="the date's official end-of-day record (--capture: take it now; administrators)", parents=[srv])
    of.add_argument("--date", metavar="YYYY-MM-DD")
    of.add_argument("--capture", action="store_true")
    of.add_argument("--ccy", metavar="CCY", help="the record in another currency of drishti.var.official.currencies (USD by default)")
    of.add_argument("--synthetic-history", type=int, metavar="DAYS", help="seed a synthetic official history of up to DAYS business days "
                    "before the oldest record (demo servers: drishti.regulatory.synthetic-history; administrators)")
    bt = sub.add_parser("backtest", help="the regulatory backtest of a node: exceptions at 99%% and 97.5%% against actual and hypothetical "
                        "P&L, the zone, the multiplier, the desks", parents=[srv])
    bt.add_argument("scope", nargs="?", default="BANK", help="BANK, DESK:RATES, LE:LE-NY or NODE:<id> (default BANK)")
    bt.add_argument("--date", metavar="YYYY-MM-DD", help="the last day tested (default: the newest official record)")
    bt.add_argument("--xlsx", metavar="FILE.xlsx", help="write the backtest as a workbook instead")
    st = sub.add_parser("stats", help="a node's period statistics over the official records: period end, average, high, low, the 60-day "
                        "and 12-week stressed averages", parents=[srv])
    st.add_argument("scope", nargs="?", default="BANK")
    st.add_argument("--period", help="FY2026 (default: the current fiscal year), FY2026Q1, or 2026-01-01..2026-03-31")
    st.add_argument("--measure", default="var", choices=["var", "es", "svar", "ses"])
    rp = sub.add_parser("report", help="report runs: list, run, show, sign (approve, return, narrative) and file", parents=[srv])
    rp.add_argument("action", choices=["list", "run", "show", "sign", "file"])
    rp.add_argument("report", nargs="?", help="the report's id (annual-report-var, var-backtesting-chart)")
    rp.add_argument("period", nargs="?", help="FY2026, FY2026Q1 or 2026-01-01..2026-03-31 (run: default the current fiscal year)")
    rp.add_argument("--version", type=int, help="a run's version (default the newest)")
    rp.add_argument("--do", dest="sign_action", choices=["approve", "return", "narrative"], default="approve", help="sign: the action")
    rp.add_argument("--step", help="sign: the step you sign (maker, checker, approver)")
    rp.add_argument("--comment", help="sign: your comment (a note for a check that needs one; why, when sending back)")
    rp.add_argument("--narrative", help="sign --do narrative: the maker's text")
    rp.add_argument("--format", default="xlsx", choices=["xlsx", "csv", "pdf"], help="file: the format")
    rp.add_argument("--out", metavar="FILE", help="file: where to write (default REPORT_PERIOD.FORMAT)")
    rp.add_argument("--draft", action="store_true", help="file: a draft before the last approval, marked so")
    it = sub.add_parser("intraday", help="the intraday updates applied on each loaded date; --load FILE appends an intraday batch (JSONL) "
                        "to the lake and signals it", parents=[srv])
    it.add_argument("--date", metavar="YYYY-MM-DD", help="one date; with --load, the business date the file's rows are moved to")
    it.add_argument("--load", metavar="FILE.jsonl", help="an intraday batch file (scenario PVs or P&L vectors, RUN_KIND INTRADAY)")
    it.add_argument("--lake", default="data/delta", help="the Delta root to append to (default data/delta, the demo lake)")
    it.add_argument("--no-signal", action="store_true", help="append only; no data-load signal")
    it.add_argument("--dry-run", action="store_true", help="read and say what would be appended; write nothing")
    cv = sub.add_parser("convert", help="add the fields every PV record says what it is with (VALUE_TYPE, GRAIN, CURRENCY, ...) to an "
                        "older JSONL file: --set FIELD=VALUE, or --infer from each record")
    cv.add_argument("file", metavar="FILE.jsonl", help="a scenario-pv or pnl-vector file (JSONL, .jsonl.gz too)")
    cv.add_argument("--set", action="append", default=[], metavar="FIELD=VALUE",
                    help="set a field on every record that lacks it (VALUE_TYPE=PNL, GRAIN=TRADE, CURRENCY=USD, SOURCE_SYSTEM=MUREX)")
    cv.add_argument("--infer", action="store_true",
                    help="VALUE_TYPE from the payload (SCENARIO_PV or PNL), GRAIN from the id (TRADE_ID or POSITION_ID), where a record lacks them")
    cv.add_argument("--out", metavar="FILE.jsonl", help="where to write (default: FILE with .converted before .jsonl)")
    rl = sub.add_parser("reload", help="drop the loaded dates (administrators): the next request maps the hot file again or reads the lake",
                        parents=[srv])
    rl.add_argument("--date", metavar="YYYY-MM-DD", help="drop only this business date (2.9.7); the others stay open")
    w = scoped("whatif", "a scope's VaR, ES and stressed VaR before and after hypothetical trades (the server's VaR engine)")
    w.add_argument("--copy", action="append", metavar="TRADE=FACTOR", help="a copy of a booked trade or a scope, scaled: MX-30000121=-1 "
                   "(its unwind), BOOK:BOOK-RATES-3=-0.5 (repeat for more)")
    w.add_argument("--trades", metavar="FILE.json", help="trades priced elsewhere: [{LABEL, PNL: [one per scenario day], FACTOR}] "
                   "(or {COPY_OF, FACTOR, LABEL})")
    p.set_defaults(func=cmd, cmd=None)


enc = urllib.parse.urlencode


def render_backtest(b: dict, say) -> None:
    """The backtest of a node: its zone, counts and multiplier, its exceptions, then its desks."""
    if b.get("applicable") is False:
        say(f"{b.get('node')}: {b.get('note')}")
        return
    say(f"backtest {b.get('node')} ({b.get('name')}): {b.get('days')} business days {b.get('from')} to {b.get('to')}"
        + (f"  ({b.get('synthetic')} on synthetic demo records)" if b.get("synthetic") else ""))
    say(f"  zone {str(b.get('zone')).upper()}   multiplier {b.get('multiplier')}   VaR today {money(b.get('var'))}"
        + (f"   {b['short']}" if b.get("short") else ""))
    rows = [["Confidence", "Actual", "Hypothetical", "Risk-theoretical", "Expected"]]
    for c in b.get("counts") or []:
        rows.append([f"{c['confidence']:.3g}", str(c.get("actual")), str(c.get("hypothetical")), str(c.get("riskTheoretical")),
                     str(c.get("expected"))])
    table(rows, say)
    ex = [["Date", "VaR (day before)", "Actual", "Hypothetical", "Which", "Desks"]]
    for e in b.get("exceptions") or []:
        ex.append([e["date"], money(e.get("var990")), money(e.get("actual")), money(e.get("hypothetical")), e.get("which", ""),
                   ", ".join(e.get("drivers") or [])])
    if len(ex) > 1:
        say("")
        table(ex, say)
    for p in (b.get("parts") or [])[1:]:
        c = (p.get("counts") or [{}])[0]
        say(f"  {p['node']:<28} VaR {money(p.get('var')):>14}  actual {c.get('actual')}  hypo {c.get('hypothetical')}  {p.get('zone')}  "
            f"{p.get('multiplier')}")


def render_stats(s: dict, say) -> None:
    """A node's period statistics."""
    say(f"{s.get('node')} {str(s.get('measure')).upper()} {s.get('period')} ({s.get('from')} to {s.get('to')}): {s.get('records')} official "
        "records" + (f", {s.get('synthetic')} synthetic" if s.get("synthetic") else ""))
    if s.get("previewNote"):
        say("  preview: " + s["previewNote"])
    if not s.get("periodEnd"):
        say("  " + str(s.get("note")))
        return
    say(f"  period end {money(s['periodEnd']['value'])} ({s['periodEnd']['date']})   average {money(s.get('average'))}")
    say(f"  high {money(s['high']['value'])} ({s['high']['date']})   low {money(s['low']['value'])} ({s['low']['date']})")
    for k, v in s.items():
        if k.startswith("average") and k[7:].isdigit() and isinstance(v, dict):
            say(f"  {k[7:]}-day average {money(v.get('average'))} ({v.get('records')} records)")
    if isinstance(s.get("stressedAverage"), dict):
        say(f"  stressed VaR {s['stressedAverage'].get('weeks')}-week average {money(s['stressedAverage'].get('average'))}")
    if s.get("missing"):
        say("  missing: " + ", ".join(s["missing"]))


def report_cmd(api, a, say_json) -> int:
    """``var report list|run|show|sign|file``: the report runs and their sign-off (VAR_GUIDE.md section 18.14)."""
    q = urllib.parse.quote
    if a.action == "list":
        res = api.json("GET", "/api/v1/var/reports")
        if a.json:
            say_json(res)
            return 0
        for r in res.get("reports") or []:
            run = r.get("run") or {}
            print(f"{r['report']:<24} {r['currentPeriod']:<8} {('v' + str(run['version'])) if run else '-':<4} "
                  f"{run.get('state', 'not generated'):<16} {r['title']}")
        return 0
    if not a.report:
        print("give the report's id", file=sys.stderr)
        return 2
    base = f"/api/v1/var/reports/{q(a.report)}/runs"
    if a.action == "run":
        res = api.json("POST", base + ("?" + enc({"period": a.period}) if a.period else ""))
    else:
        if not a.period:
            print("give the period (FY2026)", file=sys.stderr)
            return 2
        base += "/" + q(a.period)
        if a.action == "file":
            params = {"format": a.format, **({"version": a.version} if a.version else {}), **({"draft": "true"} if a.draft else {})}
            _, raw = api.request("GET", base + "/file?" + enc(params))
            out = a.out or f"{a.report}_{a.period}.{a.format}"
            with open(out, "wb") as f:
                f.write(raw)
            print(f"wrote {out} ({len(raw):,} bytes)")
            return 0
        if a.action == "sign":
            body = {k: v for k, v in (("version", a.version), ("action", a.sign_action), ("step", a.step), ("comment", a.comment),
                                      ("narrative", a.narrative)) if v is not None}
            res = api.json("POST", base + "/signoff", body)
        else:
            res = api.json("GET", base + ("?" + enc({"version": a.version}) if a.version else ""))
    if a.json:
        say_json(res)
        return 0
    so = res.get("signoff") or {}
    print(f"{res.get('report')} {res.get('period')} run v{res.get('version')} frozen {res.get('frozenAt')} by {res.get('frozenBy')}: "
          f"{so.get('state')}")
    for c in res.get("checks") or []:
        print(f"  {c.get('status'):<5} {c.get('title')}: {c.get('evidence')}")
    return 0


LEAD = ("VALUE_TYPE", "GRAIN", "TRADE_ID", "POSITION_ID", "CURRENCY", "SOURCE_SYSTEM")


def convert(a) -> int:
    """``drishti.py var convert FILE --set VALUE_TYPE=PNL --set GRAIN=TRADE --set CURRENCY=USD`` (VAR_GUIDE.md section 15.13): an older
    PV file made to say what each record is; the fields lead each line; a record already carrying a field keeps its value. Prints what
    still lacks a required field (the engine refuses those, DRS-5208)."""
    import gzip
    import json
    from pathlib import Path
    src = Path(a.file)
    sets = {}
    for kv in a.set:
        k, sep, v = kv.partition("=")
        if not sep or not k.strip():
            print(f"--set {kv}: FIELD=VALUE", file=sys.stderr)
            return 2
        sets[k.strip().upper()] = v.strip()
    opener = gzip.open if src.name.endswith(".gz") else open
    out = Path(a.out) if a.out else src.with_name(src.name.replace(".jsonl", ".converted.jsonl", 1))
    rows, lacking = [], 0
    with opener(src, "rt", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            r = json.loads(line)
            for k, v in sets.items():
                if r.get(k) in (None, ""):
                    r[k] = v
            if a.infer:
                if r.get("VALUE_TYPE") in (None, ""):
                    r["VALUE_TYPE"] = "PV" if "SCENARIO_PV" in r or "BASE_PV" in r else "PNL" if "PNL" in r else None
                if r.get("GRAIN") in (None, ""):
                    r["GRAIN"] = "POSITION" if r.get("POSITION_ID") and not r.get("TRADE_ID") else "TRADE" if r.get("TRADE_ID") else None
            lead = {k: r[k] for k in LEAD if k in r}
            r = {**lead, **{k: v for k, v in r.items() if k not in lead}}
            if any(r.get(k) in (None, "") for k in ("VALUE_TYPE", "GRAIN", "CURRENCY", "SOURCE_SYSTEM")):
                lacking += 1
            rows.append(r)
    text = "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows)
    if out.name.endswith(".gz"):
        with gzip.open(out, "wt", encoding="utf-8") as fh:
            fh.write(text)
    else:
        out.write_text(text, encoding="utf-8")
    print(f"wrote {len(rows):,} records to {out}" + (f"; {lacking:,} still lack VALUE_TYPE, GRAIN, CURRENCY or SOURCE_SYSTEM "
                                                     "(the engine refuses them: DRS-5208)" if lacking else ""))
    return 1 if lacking else 0
