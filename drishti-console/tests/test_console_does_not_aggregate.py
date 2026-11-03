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

"""One grammar, one engine (docs/architecture/ONE_GRAMMAR.md section 5): the server computes, the console displays. No console Python or
JavaScript sums, averages or groups risk or BI figures. The check finds every aggregation idiom in the console's own code (Python: sum,
statistics, a groupby, a division by len; JavaScript: a reduce that adds, an average) and lets through what is plainly not a figure
(counting items, sizes and bytes, timings, promise chains, list concatenation, extremes for layout); every other one must be listed
below with its reason: LAYOUT (arithmetic for drawing: widths, angles, shares of a bar already sent), OWN (the console's own data, not a
risk or BI figure: usage, run counts, rows read while building a pack) or DEBT (a figure the console aggregates today, to move to the
server; listed so that no new one is added, and named in ONE_GRAMMAR.md section 5). A new aggregation fails the check until it is moved
to the server or listed with its reason; an entry that no longer matches fails too, so the list stays true."""
import re

from conftest import CONSOLE

PY_DIRS = ("core", "routes")
JS_DIR = "web/static/js"

PY_AGG = re.compile(r"\bsum\(|\bfsum\(|statistics\.(mean|fmean|median|pstdev|stdev|variance|pvariance)\(|\.groupby\(|/\s*len\(")
JS_AGG = re.compile(r"\.reduce\(")

# What is plainly not a figure: counting, sizes, bytes, timings, booleans, list and promise plumbing, extremes for layout.
PY_PLAIN = re.compile(r"sum\(\s*1\s+for\b|sum\(\s*len\(|sum\(\s*int\(|st_size|sum\(\s*\(len\(|sum\(\s*\w+\[[\"']state[\"']\]\s*!=|\blen\(\w+\)\s*\+\s*sum\(len\("
                      r"|sum\(\s*low\.count\(|sum\(\s*\d+\s+if\b|sum\(\s*\d+\s+for\b|\.count\(w\)")
JS_PLAIN = re.compile(r"\.then\(|\.concat\(|Promise|Math\.max\(m,|\.length;\s*\},\s*0\)|String\(.*\)\.length|offsetHeight|\.ms\b|byClass|\.runs\b|"
                      r"\.rows\b|\.bytes\b|\.docs\.length|counts\.panels|sel\[r\]\.length|n\(y\)|function \(p, |var chain = ")

# Every other aggregation idiom in the console, with its reason. Keys: "<path>: <the line's code, stripped, first 70 characters>".
KNOWN = {
    # LAYOUT: arithmetic for drawing, on figures the server sent
    "core/designer_grid.py: if sum(widths) != UNITS:": "LAYOUT: a designer grid's column widths",
    'core/designer_grid.py: raise DesignerError(f"the columns share {UNITS} units: these add up to': "LAYOUT: the same, in the message",
    "web/static/js/designer-grid.js: var sum = out.reduce(function (a, b) { return a + b; }, 0);": "LAYOUT: column widths",
    "web/static/js/screen-grid.js: bar.setAttribute('aria-valuenow', String(Math.round(cols.slice(0, k).r": "LAYOUT: a column splitter's position",
    "web/static/js/screen-grid.js: var want = (ev.clientX - box.left) / box.width * 12, have = cols.slice": "LAYOUT: the same, while dragging",
    "web/static/js/admin-net-ring.js: var total = weights.reduce(function (a, b) { return a + b; }, 0) || 1;": "LAYOUT: a ring's arc lengths",
    "web/static/js/bi-visuals.js: const total = sr.values.reduce((a, v) => a + (typeof v === 'number' ? ":
        "LAYOUT: a donut's angles from the values the server sent",
    "web/static/js/table-chart-engine.js: var t = on.reduce(function (a, d) { return a + (num(d.data[j]) ? Math.":
        "LAYOUT: a stacked chart's height scale",
    # OWN: the console's own data, not a risk or BI figure
    "web/static/js/capacity-page.js: var x = p[0].split('+').reduce(function (a, n) { return a + (v(n) || 0":
        "OWN: the capacity planner's inputs (a person's sizing sums)",
    "web/static/js/admin-lake-maintain.js: var tabs = h.tables || [], sum = function (side, k) { return tabs.redu":
        "OWN: files and bytes of lake maintenance",
    'core/bi_usage.py: total = sum(x["questions"] for x in models)': "OWN: BI usage counts",
    'core/bi_usage.py: cached = sum(x["cached"] for x in models)': "OWN: BI usage counts",
    'core/bi_usage.py: "total": total, "sql": sum(x["sql"] for x in models), "hitRate": round': "OWN: BI usage counts",
    'routes/lake_routes.py: "rows": sum(rows.values()) if isinstance(rows, dict) else rows, "rejec': "OWN: rows loaded into a lake",
    'core/schemakit/plan.py: c.ann["items"] = {"min": min(lists), "max": max(lists), "avg": round(s': "OWN: list lengths in a pack's sample",
    "core/schemakit/anonymise.py: if len(counts) <= VOCAB_MAX and sum(counts.values()) >= 2 * len(counts":
        "OWN: how often a sample's words repeat (anonymising)",
    'core/schemakit/bi.py: measures[f"{f.label} (total)"] = {"expr": f"sum({base}.{_ident(f.name)': "OWN: a BI measure's text for the server",
    "core/schemakit/bi.py: measures[f\"{humanize(singular(arr.name.lower()))} {c.lower().replace('": "OWN: the same",
    "core/pyrunner_worker.py: groups = df.groupby(by, sort=True, dropna=False) if by else [((), df)]":
        "OWN: a person's own Python cell, sandboxed (ONE_GRAMMAR.md part 4: Python stays the open-ended escape)",
    "core/pyrunner_worker.py: nulls = int(s.isna().sum())": "OWN: nulls in a person's own Python cell's frame",
    'core/python_items.py: out = trades.groupby("desk", as_index=False)["mtm"].sum()': "OWN: an example Python cell, shown as text",
    # DEBT: figures the console aggregates today, to move to the server (ONE_GRAMMAR.md section 5). Never add to this list.
    "core/trading_landing.py: total_pos = sum(positive.values())": "DEBT: the trading landing's shares of positive MTM",
    'core/trading_landing.py: "mtm": round(sum(_num(r.get("MTM")) or 0 for r in rows.values())) if n': "DEBT: the trading landing's MTM",
    'core/trading_landing.py: "dv01": round(sum(dv01s)) if dv01s else None, "dv01Trades": len(dv01s)': "DEBT: the trading landing's DV01",
    'core/trading_landing.py: return {"available": True, "scope": text, "rows": rows, "days": len(ro': "DEBT: backtest exceptions counted",
    'core/market_landing.py: median, change = (statistics.median(spreads) if spreads else None), No': "DEBT: the market landing's median spread",
    'core/market_landing.py: change = {"delta": statistics.median([m["to"] for m in moves]) - stati': "DEBT: the market landing's median move",
    "core/market_landing.py: sd = statistics.pstdev(hist) if len(hist) >= SIGMA_MIN_DAYS else 0.0": "DEBT: the market landing's sigma",
    "web/static/js/market-landing.js: sums[gid] = ns.reduce(function (a, b) { return a + b; }, 0);": "DEBT: the market landing's group sums",
    "core/facts_expr.py: return sum(vals) if vals else (0 if isinstance(xs, (list, tuple)) else":
        "DEBT: the FACT expressions' sum (an evaluator in the console; to be Rachana on the server)",
    "core/facts_expr.py: return sum(min(w, tb[k]) for k, w in ta.items() if k in tb)": "DEBT: the same evaluator's overlap",
    "core/schemakit/related.py: doc[name] = _clean(sum(x for x in (_num(v) for v in vals) if x is not ":
        "DEBT: a related document's summed field in a pack's sample",
    "core/quant/standin.py: return sum(c / (1 + i) ** k for k in range(1, n + 1)) + face / (1 + i)":
        "DEBT: the quant stand-in's price (QLRisk will price on its engine)",
    'core/quant/standin.py: return sum(_num({"x": f}, "x") / (1 + r) ** t for t, f in enumerate(fl': "DEBT: the same",
    "web/static/js/colfilter.js: if (c.op === 'avga' || c.op === 'avgb') { x.avg = ns.length ? ns.reduc":
        "DEBT: a column filter's above/below-average threshold over the rows shown",
    "web/static/js/pivot.js: var sum = values.reduce(function (s, x) { return s + x; }, 0);": "DEBT: the pivot chart's diversification bar (total − Σ parts): the engine to send it",
}


def _code(line: str, js: bool) -> str:
    """The line without its comment (a comment may name what is gone)."""
    return re.sub(r"\s//.*$", "", line) if js else re.sub(r"\s#.*$", "", line)


def found() -> dict[str, str]:
    out = {}
    files = [(p, False) for d in PY_DIRS for p in sorted((CONSOLE / d).rglob("*.py"))]
    files += [(p, True) for p in sorted((CONSOLE / JS_DIR).glob("*.js"))]
    for path, js in files:
        rel = path.relative_to(CONSOLE).as_posix()
        for line in path.read_text(encoding="utf-8").splitlines():
            code = _code(line, js).strip()
            if not code or code.startswith(("#", "//", "*", "/*")):
                continue
            agg, plain = (JS_AGG, JS_PLAIN) if js else (PY_AGG, PY_PLAIN)
            if agg.search(code) and not plain.search(code):
                out[f"{rel}: {code[:70]}"] = code
    return out


def test_the_console_aggregates_no_figure_beyond_the_listed_exceptions():
    seen = found()
    new = sorted(k for k in seen if k not in KNOWN)
    assert not new, ("the console aggregates here: move it to the server (the pivot engine, Rachana's aggregates) or, for layout or the "
                     "console's own data, list it in KNOWN with its reason:\n  " + "\n  ".join(new))


def test_every_listed_exception_is_still_there_and_has_a_reason():
    seen = found()
    stale = sorted(k for k in KNOWN if k not in seen)
    assert not stale, "these entries match nothing any more: remove them\n  " + "\n  ".join(stale)
    assert all(v.split(":")[0] in ("LAYOUT", "OWN", "DEBT") for v in KNOWN.values())
