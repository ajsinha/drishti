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

"""About this page, step 4 (docs/architecture/CONTEXT_HELP.md): layer 2 (what each number means) in the drawer, the index the browser
draws panel popovers and field hints from, escaping of authored text, and the clean "Where next" labels."""
import html as htmllib
import json
import re
from pathlib import Path

from conftest import FIXTURES

WEB = Path(__file__).resolve().parent.parent / "web"


def with_answer(backend, monkeypatch, change):
    ex = json.loads((FIXTURES / "explain_trade_IRS-48213.json").read_text())
    change(ex)

    async def explain(kind, id_, user, generation=None):
        return ex

    monkeypatch.setattr(backend, "explain", explain)
    return ex


def test_layer_two_lists_each_entry_with_unit_sign_formula_values_and_the_hidden_note(client):
    h = client.get("/v/trade/IRS-48213/about").text
    assert 'data-layer="glossary"' in h and "What each number means" in h and "(5)" in h
    assert "Mark to market" in h and "Unit: <b>USD</b>" in h and "Sign: Positive is an asset to us." in h
    assert "<code>fixed + spread</code>" in h and "<b>Pay</b>: We pay fixed and receive floating." in h
    assert re.search(r'data-term="trader".*?hidden for your role', h, re.S)                      # a hidden field keeps its definition, marked
    assert 'id="term-cashflows-rate"' in h and 'data-term="cashflows.rate"' in h              # the hook a field hint opens the drawer at
    assert h.index('data-layer="glossary"') < h.index('data-layer="data"')                      # layer 2 follows layer 1, before the data layer


def test_the_first_two_layers_open_and_the_data_layer_opens_only_without_a_glossary(client, backend, monkeypatch):
    h = client.get("/v/trade/IRS-48213/about").text
    assert re.search(r'data-layer="glossary" id="about-l2" open', h) and not re.search(r'data-layer="data" id="about-l3" open', h)
    with_answer(backend, monkeypatch, lambda ex: ex.pop("glossary"))
    h = client.get("/v/trade/IRS-48213/about").text
    assert 'data-layer="glossary"' not in h                                                      # nothing to say: the layer is absent
    assert re.search(r'data-layer="data" id="about-l3" open', h)


def test_the_index_for_the_browser_has_the_terms_and_each_panels_text(client, backend, monkeypatch):
    with_answer(backend, monkeypatch, lambda ex: ex.update(about={"panels": [{"id": "cashflows", "title": "Cashflows", "description": "One row per accrual period."}]}))
    h = client.get("/v/trade/IRS-48213/about").text
    idx = json.loads(htmllib.unescape(re.search(r'data-about-index="([^"]*)"', h).group(1)))
    assert [t["key"] for t in idx["terms"]][:2] == ["mtm", "direction"]
    assert idx["terms"][0]["shownIn"] == ["strip"] and idx["terms"][1]["values"] == {"Pay": "We pay fixed and receive floating."}
    assert idx["panels"]["cashflows"]["about"] == "One row per accrual period."
    assert idx["panels"]["curve"]["notes"] == ["Shows no data ($.curve is missing in this document)."]
    assert idx["panels"]["refs"]["notes"] == ["Added by inference: table-of-objects, score 0.82."]


def test_glossary_text_is_escaped_in_the_drawer_and_in_the_index(client):
    h = client.get("/v/trade/IRS-48213/about").text
    assert "<script>alert(1)</script>" not in h and "<img src=x" not in h and "<b>Evil</b>" not in h
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in h
    raw = re.search(r'data-about-index="([^"]*)"', h).group(1)
    assert "<" not in raw and '"' not in raw                                                      # the attribute cannot be broken out of


def test_where_next_labels_do_not_repeat_the_kind(client, backend, monkeypatch):
    with_answer(backend, monkeypatch, lambda ex: ex["next"].update(keys=[
        {"key": "F7", "label": "Desk", "link": {"kind": "desk", "id": "DESK-COMM", "mnemonic": "DESK"}},
        {"key": "F8", "label": "Gene", "link": {"kind": "gene", "id": "GENE", "mnemonic": "GENE"}}]))
    h = client.get("/v/trade/IRS-48213/about").text
    text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", h))
    assert "F7 Desk DESK-COMM" in text                                                            # a different id is useful, the code is not
    assert "F8 Gene" in text and "Gene GENE" not in text and "Desk DESK " not in text            # never "F7 Desk DESK", "F8 Gene GENE"
    assert 'href="/v/gene/GENE"' in h


def test_the_view_loads_the_hints_script_and_the_panel_help_stays_a_link_without_javascript(client):
    h = client.get("/v/trade/IRS-48213").text
    assert "/static/js/about-hints.js" in h
    assert 'class="pnl-help" href="/help/panel-kinds#table"' in h                                # today's link, enhanced by the script only
    js = (WEB / "static" / "js" / "about-hints.js").read_text()
    assert "innerHTML" not in js and "insertAdjacentHTML" not in js                              # every word of an entry goes in as text
    assert js.count("\n") < 300 and (WEB / "static" / "js" / "about.js").read_text().count("\n") < 300
