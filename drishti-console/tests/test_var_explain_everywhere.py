#
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
#
"""Guard: every place that shows a VaR or ES carries the one Explain › (drishti-insight's ``Drishti.Explain.link``, or an element
marked ``data-explain-link`` that it upgrades; VAR_GUIDE.md section 3.11): the VaR explorer (its tree's rows and its VaR and ES tiles),
the VaR reports (a cell's lineage, the period-end tile), Home's VaR tile, the Sutra pages' VaR and ES the engine computed (a system
basket, a position, a desk, a book, a legal entity), a BI visual of one VaR or ES, and the MR cube's figures. A new screen that shows
VaR or ES is added here with its control; none draws an Explain link of its own."""
import re
from pathlib import Path

CONSOLE = Path(__file__).resolve().parents[1]
JS = CONSOLE / "plugins" / "banking" / "static" / "js"

#: (file, what it shows, the text that proves the one control is used there)
SITES = [
    (JS / "drishti_var-explorer.js", "the tree's rows", 'E.link(explainReq("var", r.scope)'),
    (JS / "drishti_var-explorer.js", "the VaR and ES tiles", 'E.link(explainReq(x[1])'),
    (JS / "drishti_var-reports.js", "a report cell's lineage", 'E.link({ kind: "var", measure: "var", scope: "NODE:" + l.node'),
    (JS / "drishti_var-reports.js", "the period-end tile", "E.link({ kind: \"var\", measure: \"var\", scope: String(st.node)"),
    (JS / "drishti_risk-landing.js", "Home's VaR tile", 'E.link({ kind: "var", measure: "var", scope: "BANK" }'),
    (CONSOLE / "web" / "static" / "js" / "drishti_bi-frame.js", "a BI visual of one VaR or ES", "E.link(req, { cls: 'bi-vis-explain' })"),
    (CONSOLE / "web" / "templates" / "_macros" / "view.html", "a Sutra page's VaR and ES", "figure_explain(vm, c)"),
    (CONSOLE / "plugins" / "banking" / "console_hooks.py", "the rule naming the VaR and ES figures", 'plugins.figure_explainer({"kind": "var"'),
    (JS / "drishti_mr-lens.js", "the MR cube's figures", 'setAttribute("data-explain"'),
]


def test_every_var_and_es_figure_carries_the_one_explain_control():
    for path, what, proof in SITES:
        assert proof in path.read_text(encoding="utf-8"), f"{what} ({path.name}) lost its Explain ›"


def test_no_screen_draws_an_explain_link_of_its_own():
    """The words "Explain ›" in a link are the control's alone (a toolbar button may name itself so)."""
    own = re.compile(r"<a[^>]*>\s*Explain ›")
    for p in list((CONSOLE / "plugins").rglob("*.html")) + list((CONSOLE / "web" / "templates").rglob("*.html")):
        for m in own.finditer(p.read_text(encoding="utf-8")):
            assert "data-explain-link" in m.group(0), f"{p.name}: an Explain › link that is not the one control"


def test_a_sutra_pages_engine_figures_carry_explain_of_their_scope(client):
    t = client.app.state.templates.env.from_string('{% from "_macros/view.html" import strip %}{{ strip(vm) }}')
    vm = {"ref": {"kind": "system-basket", "id": "PF-USD-RATES"},
          "strip": [{"label": "Positions", "text": "12", "path": "$.POSITIONS"}, {"label": "VaR 99% 1D", "text": "1.2m", "path": "$.VAR_ENGINE.VAR"},
                    {"label": "ES", "text": "1.4m", "path": "$.VAR_ENGINE.ES"}]}
    html = t.render(vm=vm)
    assert html.count("data-explain-link") == 2
    assert "&#34;scope&#34;: &#34;PORT:PF-USD-RATES&#34;" in html and "&#34;measure&#34;: &#34;es&#34;" in html
    vm["ref"] = {"kind": "counterparty", "id": "CP-1"}                          # a kind with no VaR scope word: no control
    assert "data-explain-link" not in t.render(vm=vm)


def test_the_limits_screen_vlim_carries_explain_of_its_nodes_var(client):
    """A VaR limit's page (VLIM): its figure explains the node's VaR (the id VL-<node>), a portfolio's limit its portfolio."""
    t = client.app.state.templates.env.from_string('{% from "_macros/view.html" import strip %}{{ strip(vm) }}')
    vm = {"ref": {"kind": "var-limit", "id": "VL-LL~LL-0003"}, "strip": [{"label": "Limit", "text": "8.5m", "path": "$.LIMIT_VAR"}]}
    html = t.render(vm=vm)
    assert html.count("data-explain-link") == 1 and "&#34;scope&#34;: &#34;NODE:LL~LL-0003&#34;" in html
    vm["ref"]["id"] = "VL-PF-RATES"
    assert "&#34;scope&#34;: &#34;PORT:PF-RATES&#34;" in t.render(vm=vm)
