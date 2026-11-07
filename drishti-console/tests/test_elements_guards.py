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
"""The guards of Drishti Elements (docs/architecture/ELEMENTS.md section 11, "No second implementation"): one implementation of each
thing, in the libraries under static/elements/. No console script (static/js, the pages' own scripts in templates):

* calls ECharts (or Vega) itself: charts are drishti-chart's (its Chart, its panel charts, its maps);
* builds its own grid: sorting a table's rows by its headings (aria-sort) is drishti-grid's (the grid, or its document tables);
* formats numbers on its own (Intl.NumberFormat, toFixed, toPrecision, a number's toLocaleString): numbers are drishti-core's
  (formatNumber, formatCompact, formatPercent, formatNamed: the server's rules, half up);
* opens its own EventSource or WebSocket: the connection has one home, drishti-data's LiveClient.

ALLOWED lists the files other builders are still moving, each with the branch moving it. It only shrinks: an entry whose file is gone,
or no longer breaks the rule, fails the check, so the list stays true. The geometry of a hand-drawn SVG (a coordinate) is not a number
shown to people and does not use the formatters."""
import re

import pytest

from conftest import CONSOLE

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

WEB = CONSOLE / "web"

RULES = {
    "echarts": (re.compile(r"\becharts\s*\.\s*(?:init|getInstanceByDom|registerMap|connect|dispose|use)\b|\.setOption\s*\(|\bvegaEmbed\b"),
                "calls ECharts or Vega itself: draw it with drishti-chart"),
    "grid": (re.compile(r"aria-sort|\bariaSort\b"),
             "sorts a table by its headings itself: use drishti-grid (the grid, or its document tables)"),
    "numbers": (re.compile(r"Intl\.NumberFormat|\.toFixed\s*\(|\.toPrecision\s*\(|\.toLocaleString\s*\("),
                "formats numbers itself: use drishti-core's formatNumber, formatCompact, formatPercent or formatNamed"),
    "live": (re.compile(r"\bnew\s+(?:[\w$.]*\.)?(?:EventSource|WebSocket)\b|\b(?:window|self|globalThis)\.(?:EventSource|WebSocket)\s*="),
             "opens its own EventSource or WebSocket: use drishti-data's LiveClient"),
}

# A date's or a time's toLocaleString is a date, not a number (drishti-core's formatDate has no clock yet).
DATE_LINE = re.compile(r"\bDate\b|\bmonth\s*:|\bhour\s*:|\bat\b\.toLocaleString|\btimeZone\b")

BI = "wip/e-migrate-bi"
VAR = "wip/e-migrate-var"
# file (relative to drishti-console/web) -> {rule: the branch moving it}
ALLOWED = {
    # BI and the Pivot (wip/e-migrate-bi)
    "static/js/drishti_bi-ai.js": {"echarts": BI},
    "static/js/drishti_bi-govern.js": {"numbers": BI},
    "static/js/drishti_bi-layer-ops.js": {"numbers": BI},
    "static/js/drishti_bi-lib.js": {"numbers": BI},
    "static/js/drishti_bi-notebook-profile.js": {"numbers": BI},
    "static/js/drishti_bi-python.js": {"echarts": BI},
    "static/js/drishti_bi-runs-admin.js": {"numbers": BI},
    "static/js/drishti_bi-runs.js": {"numbers": BI},
    "static/js/drishti_bi-transform-view.js": {"numbers": BI},
    "static/js/drishti_bi-transform.js": {"numbers": BI},
    "static/js/drishti_bi-vega.js": {"echarts": BI},
    "static/js/drishti_bi-visuals.js": {"echarts": BI},
    "static/js/drishti_geomap.js": {"echarts": BI},
    "static/js/drishti_mr-lens.js": {"numbers": BI},
    "static/js/drishti_pivot-engine.js": {"numbers": BI},
    "static/js/drishti_pivot-functions.js": {"numbers": BI},
    "static/js/drishti_table-chart.js": {"echarts": BI},
    # VaR, exposure and the risk screens (wip/e-migrate-var)
    "static/js/drishti_exposure-explorer.js": {"numbers": VAR},
    "static/js/drishti_frtb-sa.js": {"numbers": VAR},
    "static/js/drishti_risk-landing.js": {"numbers": VAR},
    "static/js/drishti_scenario-matrix.js": {"numbers": VAR},
    "static/js/drishti_simm.js": {"numbers": VAR},
    "static/js/drishti_stress-window.js": {"numbers": VAR},
    "static/js/drishti_var-backtesting.js": {"numbers": VAR},
    "static/js/drishti_var-basket.js": {"numbers": VAR},
    "static/js/drishti_var-explorer.js": {"numbers": VAR},
    "static/js/drishti_var-parametric.js": {"numbers": VAR},
    "static/js/drishti_var-playground.js": {"numbers": VAR},
    "static/js/drishti_var-reports.js": {"numbers": VAR},
}

COMMENT = re.compile(r"/\*.*?\*/|(?<![:\\'\"])//[^\n]*|\{#.*?#\}|<!--.*?-->", re.S)


def _sources():
    files = sorted((WEB / "static" / "js").rglob("*.js")) + sorted((WEB / "templates").rglob("*.html"))
    return {str(p.relative_to(WEB)): p for p in files}


def _broken(text, script=True):
    """{rule: the first offending line} of one file, comments left out. A page's own aria-sort (a template) states the order the server
    sent; sorting by headings is a script's behaviour, so the grid rule reads scripts."""
    code = COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    out = {}
    for rule, (pattern, _) in RULES.items():
        if rule == "grid" and not script:
            continue
        for line in code.splitlines():
            if pattern.search(line) and not (rule == "numbers" and ".toLocaleString" in line
                                               and not re.search(r"Intl\.NumberFormat|\.toFixed|\.toPrecision", line)
                                               and DATE_LINE.search(line)):
                out[rule] = line.strip()[:140]
                break
    return out


@pytest.fixture(scope="module")
def findings():
    return {name: _broken(p.read_text(encoding="utf-8", errors="replace"), name.endswith(".js")) for name, p in _sources().items()}


@pytest.mark.parametrize("rule", sorted(RULES))
def test_no_console_script_keeps_a_second_implementation(findings, rule):
    bad = [f"{name}: {RULES[rule][1]}\n      {found[rule]}" for name, found in findings.items()
           if rule in found and ALLOWED.get(name, {}).get(rule) is None]
    assert not bad, "\n".join(bad)


def test_the_allow_list_only_shrinks():
    """Every entry is a file another builder is still moving, and it still breaks the rule (else it comes off the list)."""
    sources = _sources()
    stale = []
    for name, rules in ALLOWED.items():
        if name not in sources:
            stale.append(f"{name}: gone (take it off the list)")
            continue
        found = _broken(sources[name].read_text(encoding="utf-8", errors="replace"), name.endswith(".js"))
        for rule, branch in rules.items():
            assert branch in (BI, VAR), f"{name}: an allowed file names the branch moving it"
            if rule not in found:
                stale.append(f"{name}: keeps the rule {rule} now (take it off the list)")
    assert not stale, "\n".join(stale)


def test_the_guard_finds_what_it_looks_for():
    """The patterns find each idiom, and leave a date's toLocaleString and comments alone."""
    assert set(_broken("var c = echarts.init(el); c.setOption({});")) == {"echarts"}
    assert set(_broken("th.setAttribute('aria-sort', 'ascending');")) == {"grid"}
    assert set(_broken("x.textContent = (v / 1e6).toFixed(1) + 'm';")) == {"numbers"}
    assert set(_broken("x.textContent = n.toLocaleString('en-US');")) == {"numbers"}
    assert set(_broken("var es = new EventSource(url);")) == {"live"}
    assert set(_broken("var ws = new window.WebSocket(url);")) == {"live"}
    assert _broken("t.textContent = new Date(ms).toLocaleString();") == {}
    assert _broken("// echarts.init(el) is drishti-chart's\n/* new EventSource(u) */") == {}


# ---- the generic libraries know no domain (the product owner, 2026-11-07) ----------------------------------------------------------
# Domain logic stays in its domain's pack, backend module and JS library (risk: drishti-risk, the banking module's). drishti-core,
# drishti-grid, drishti-chart, drishti-inputs and drishti-data carry generic hooks only (a tone, a threshold line, a server-computed
# figure, a kind a domain library registers), never a domain's words in their code: a risk measure's name, a risk kind, a regulatory
# rule. Comments and JSDoc are documentation and may use a domain's examples; so are the harness pages (the gallery's sample data). A
# threshold (`limit`, a row cap or a line a caller draws) is a generic hook and is not listed.
GENERIC = ("drishti-core", "drishti-grid", "drishti-chart", "drishti-inputs", "drishti-data")
DOMAIN_WORDS = re.compile(r"\b(?:VaR|VAR|var\d\d|ES|EEPE|EPE|PFE|MTM|DV01|FRTB|SIMM|P&L|[Ee]xposures?|[Bb]acktest\w*|Basel|[Tt]raffic light"
                          r"|[Nn]etting|[Cc]ounterpart(?:y|ies)|risk\.[a-z-]+)\b")
# Documentation only: the generated manifest names every library with its title (drishti-risk's names its kinds).
DOMAIN_DOCS = {"drishti-core/src/drishti_manifest.js"}


def _domain_words(text):
    code = COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    return [f"{n}: {line.strip()[:120]}" for n, line in enumerate(code.splitlines(), 1) if DOMAIN_WORDS.search(line)]


def test_the_generic_libraries_hold_no_domain_vocabulary():
    elements = WEB / "static" / "elements"
    bad = []
    for lib in GENERIC:
        for p in sorted((elements / lib / "src").glob("*.js")) + sorted((elements / lib).glob("drishti_*.css")):
            rel = str(p.relative_to(elements))
            if rel in DOMAIN_DOCS or "harness" in p.name:
                continue
            bad += [f"{rel}:{hit}" for hit in _domain_words(p.read_text(encoding="utf-8"))]
    assert not bad, "domain words in a generic library: move the logic to the domain's library (risk: drishti-risk) behind a generic hook\n" + "\n".join(bad)


def test_the_domain_words_check_finds_them_and_leaves_documentation_alone():
    assert _domain_words("if (k === 'risk.backtest') { zoom(); }")
    assert _domain_words("label: 'VaR 99%'")
    assert _domain_words("const exposure = rows.map(peak);")
    assert not _domain_words("/** The sum of VaR is not a VaR: a non-additive total is the server's. */\nconst total = server(cell);")
    assert not _domain_words("const limit = o.limit || 2000; const Source = o.EventSource;")
    assert all((WEB / "static" / "elements" / d).is_file() for d in DOMAIN_DOCS)
