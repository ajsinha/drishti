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
JavaScript sums, averages or groups risk or BI figures: every figure (totals, medians, averages, deviations, risk) is the server's, in Java,
through the one engine (the console sends rows it read to the pivot engine, POST /api/v1/pivot/inline, core/engine_figures.py, and shows
its cells). The check finds every aggregation idiom in the console's own code (Python: sum, statistics, a groupby, a division by len;
JavaScript: a reduce that adds, an average), outside strings and comments (a measure's text sent to the engine, ``"sum(MTM)"``, is the
server's language, not a sum here), and lets through what is plainly not a figure (counting items, sizes and bytes, timings, promise
chains, list concatenation, extremes for layout); every other one must be listed below with its reason: LAYOUT (arithmetic for drawing:
widths, angles, shares of a bar already sent) or OWN (the console's own data, not a risk or BI figure: usage, run counts, rows read
while building a pack, a person's own Python cell). There is no third reason: since 2.9.11.b the browser computes nothing, so a new
aggregation of a figure fails the check until it is moved to the server; an entry that no longer matches fails too, so the list stays
true."""
import io
import re
import tokenize

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

PY_DIRS = ("core", "routes")
JS_DIR = "web/static/js"
REASONS = ("LAYOUT", "OWN")

PY_AGG = re.compile(r"\bsum\(|\bfsum\(|statistics\.(mean|fmean|median|pstdev|stdev|variance|pvariance)\(|\.groupby\(|/\s*len\(")
JS_AGG = re.compile(r"\.reduce\(")

# What is plainly not a figure: counting, sizes, bytes, timings, booleans, list and promise plumbing, extremes for layout.
PY_PLAIN = re.compile(r"sum\(\s*1\s+for\b|sum\(\s*len\(|sum\(\s*int\(|st_size|sum\(\s*\(len\(|sum\(\s*\w+\[[\"']state[\"']\]\s*!=|\blen\(\w+\)\s*\+\s*sum\(len\("
                      r"|sum\(\s*low\.count\(|sum\(\s*\d+\s+if\b|sum\(\s*\d+\s+for\b|\.count\(w\)")
JS_PLAIN = re.compile(r"\.then\(|\.concat\(|Promise|Math\.max\(m,|\.length;\s*\},\s*0\)|String\(.*\)\.length|offsetHeight|\.ms\b|byClass|\.runs\b|"
                      r"\.rows\b|\.bytes\b|\.docs\.length|counts\.panels|sel\[r\]\.length|n\(y\)|function \(p, |var chain = ")
JS_STRING = re.compile(r"'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\"|`(?:[^`\\]|\\.)*`")

# Every other aggregation idiom in the console, with its reason. Keys: "<path>: <the line's code, stripped, first 70 characters>".
KNOWN = {
    # LAYOUT: arithmetic for drawing, on figures the server sent
    "core/designer_grid.py: if sum(widths) != UNITS:": "LAYOUT: a designer grid's column widths",
    'core/designer_grid.py: raise DesignerError(f"the columns share {UNITS} units: these add up to': "LAYOUT: the same, in the message",
    "web/static/js/drishti_designer-grid.js: var sum = out.reduce(function (a, b) { return a + b; }, 0);": "LAYOUT: column widths",
    "web/static/js/drishti_screen-grid.js: bar.setAttribute('aria-valuenow', String(Math.round(cols.slice(0, k).r": "LAYOUT: a column splitter's position",
    "web/static/js/drishti_screen-grid.js: var want = (ev.clientX - box.left) / box.width * 12, have = cols.slice": "LAYOUT: the same, while dragging",
    "web/static/js/drishti_admin-net-ring.js: var total = weights.reduce(function (a, b) { return a + b; }, 0) || 1;": "LAYOUT: a ring's arc lengths",
    "web/static/js/drishti_bi-visuals.js: const total = sr.values.reduce((a, v) => a + (typeof v === 'number' ? ":
        "LAYOUT: a donut's angles from the values the server sent",
    "web/static/js/drishti_table-chart-engine.js: var t = on.reduce(function (a, d) { return a + (num(d.data[j]) ? Math.":
        "LAYOUT: a stacked chart's height scale",
    # OWN: the console's own data, not a risk or BI figure
    "web/static/js/drishti_capacity-page.js: var x = p[0].split('+').reduce(function (a, n) { return a + (v(n) || 0":
        "OWN: the capacity planner's inputs (a person's sizing sums)",
    "web/static/js/drishti_admin-lake-maintain.js: var tabs = h.tables || [], sum = function (side, k) { return tabs.redu":
        "OWN: files and bytes of lake maintenance",
    'core/bi_usage.py: total = sum(x["questions"] for x in models)': "OWN: BI usage counts",
    'core/bi_usage.py: cached = sum(x["cached"] for x in models)': "OWN: BI usage counts",
    'core/bi_usage.py: "total": total, "sql": sum(x["sql"] for x in models), "hitRate": round': "OWN: BI usage counts",
    'routes/lake_routes.py: "rows": sum(rows.values()) if isinstance(rows, dict) else rows, "rejec': "OWN: rows loaded into a lake",
    'core/schemakit/plan.py: c.ann["items"] = {"min": min(lists), "max": max(lists), "avg": round(s': "OWN: list lengths in a pack's sample",
    "core/schemakit/anonymise.py: if len(counts) <= VOCAB_MAX and sum(counts.values()) >= 2 * len(counts":
        "OWN: how often a sample's words repeat (anonymising)",
    "core/schemakit/related.py: doc[name] = _clean(sum(x for x in (_num(v) for v in vals) if x is not ":
        "OWN: a pack draft's sample documents for a related page (the pack maker's fixtures, made offline too by tools/packmake.py; "
        "never a figure people read: the server's derived connector computes the pages)",
    "core/quant/standin.py: return sum(c / (1 + i) ** k for k in range(1, n + 1)) + face / (1 + i)":
        "OWN: the stand-in pricer a person's own Python cell calls as `quant` in its sandbox (a coupon's discounting, not a total of "
        "figures; QLRisk replaces it on the server, docs/admin/AI.md section 16)",
    'core/quant/standin.py: return sum(_num({"x": f}, "x") / (1 + r) ** t for t, f in enumerate(fl': "OWN: the same pricer's NPV",
    "core/pyrunner_worker.py: groups = df.groupby(by, sort=True, dropna=False) if by else [((), df)]":
        "OWN: a person's own Python cell, sandboxed (ONE_GRAMMAR.md part 4: Python stays the open-ended escape)",
    "core/pyrunner_worker.py: nulls = int(s.isna().sum())": "OWN: nulls in a person's own Python cell's frame",
}


def _code(line: str, js: bool) -> str:
    """The line without its comment (a comment may name what is gone)."""
    return re.sub(r"\s//.*$", "", line) if js else re.sub(r"\s#.*$", "", line)


def _py_code_only(text: str) -> list[str]:
    """The file's lines with every string (docstrings, f-strings' text, a measure sent to the engine) and comment blanked out."""
    lines = [list(x) for x in text.splitlines()]
    blank = (tokenize.STRING, tokenize.COMMENT) + tuple(getattr(tokenize, n) for n in ("FSTRING_MIDDLE",) if hasattr(tokenize, n))
    for tok in tokenize.generate_tokens(io.StringIO(text).readline):
        if tok.type not in blank:
            continue
        (r0, c0), (r1, c1) = tok.start, tok.end
        for r in range(r0, r1 + 1):
            row = lines[r - 1]
            for c in range(c0 if r == r0 else 0, c1 if r == r1 else len(row)):
                row[c] = " "
    return ["".join(x) for x in lines]


def found() -> dict[str, str]:
    out = {}
    files = [(p, False) for d in PY_DIRS for p in sorted((CONSOLE / d).rglob("*.py"))]
    files += [(p, True) for p in sorted((CONSOLE / JS_DIR).glob("*.js"))]
    for path, js in files:
        rel = path.relative_to(CONSOLE).as_posix()
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        bare = [JS_STRING.sub("''", _code(x, True)) for x in lines] if js else _py_code_only(text)
        for line, plain_code in zip(lines, bare):
            code = _code(line, js).strip()
            if not code or code.startswith(("#", "//", "*", "/*")):
                continue
            agg, plain = (JS_AGG, JS_PLAIN) if js else (PY_AGG, PY_PLAIN)
            if agg.search(plain_code) and not plain.search(code):
                out[f"{rel}: {code[:70]}"] = code
    return out


def test_the_console_aggregates_no_figure_beyond_the_listed_exceptions():
    seen = found()
    new = sorted(k for k in seen if k not in KNOWN)
    assert not new, ("the console aggregates here: move it to the server (the pivot engine through core/engine_figures.py, Rachana's "
                     "aggregates) or, for layout or the console's own data, list it in KNOWN with its reason:\n  " + "\n  ".join(new))


def test_every_listed_exception_is_still_there_and_has_a_reason():
    seen = found()
    stale = sorted(k for k in KNOWN if k not in seen)
    assert not stale, "these entries match nothing any more: remove them\n  " + "\n  ".join(stale)
    assert all(v.split(":")[0] in REASONS for v in KNOWN.values()), "a reason is LAYOUT or OWN: a figure is the server's, never a debt"


def test_a_sum_hidden_in_a_string_is_not_a_sum_but_one_in_code_is():
    """The check reads code, not text: a measure sent to the engine passes, the same sum written in code is found."""
    lines = _py_code_only('x = {"measure": "sum(MTM)"}\n"""a docstring: sum(x)"""\ny = sum(v)  # sum(z)\n')
    assert [bool(PY_AGG.search(x)) for x in lines] == [False, False, True]
    assert not JS_AGG.search(JS_STRING.sub("''", "var m = 'a.reduce(b)';")) and JS_AGG.search("var t = xs.reduce(add, 0);")
