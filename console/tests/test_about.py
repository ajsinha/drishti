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

"""About this page (docs/architecture/CONTEXT_HELP.md, step 2): the drawer's body is a partial built from the server's explain answer,
fetched only when the drawer opens; each layer draws nothing when it has nothing to say; nothing masked leaks."""
import json
import re
from pathlib import Path

from conftest import FIXTURES

WEB = Path(__file__).resolve().parent.parent / "web"


def test_the_view_does_not_ask_the_server_to_explain(client, backend):
    html = client.get("/v/trade/IRS-48213").text
    assert 'id="aboutDrawer"' in html and "data-about-open" in html and 'data-about-url="/v/trade/IRS-48213/about"' in html
    assert not [c for c in backend.calls if c[0] == "explain"]                 # lazy: only the drawer's first open asks


def test_the_partial_shows_data_layout_next_and_the_sutra_description(client, backend):
    r = client.get("/v/trade/IRS-48213/about?generation=7")
    assert r.status_code == 200
    h = r.text
    assert "A vanilla interest-rate swap: legs, cashflows and risk." in h                      # layer 1: the Sutra description
    assert "trade-store" in h and ">up<" in h and "2026-10-03 06:12:00" in h and "fresh" in h      # layer 3, one health word
    assert "Sutra <b class=\"mono\">irs-vanilla v1</b>" in h and "irs-exotic v2" in h and "its condition was false" in h   # layer 4
    assert "References" in h and "table-of-objects, score 0.82" in h
    assert "<code>$.curve</code> is missing in this document" in h
    assert "1 field hidden for your role: <b>Trader</b>" in h and "Swaption" in h and "not shown" in h
    assert "/v/counterparty/CP-1" in h and "/impact/trade/IRS-48213" in h and "/help/panel-kinds#table" in h   # layer 5
    assert ("explain", "trade", "IRS-48213", 7) in backend.calls


def test_the_partial_hides_every_layer_with_nothing_to_say(client, backend, tmp_path, monkeypatch):
    ex = json.loads((FIXTURES / "explain_trade_IRS-48213.json").read_text())
    ex["layout"] = {"label": "inferred", "inferred": True}
    ex.pop("next")
    ex.pop("glossary")
    async def explain(kind, id_, user, generation=None):
        return ex
    monkeypatch.setattr(backend, "explain", explain)
    h = client.get("/v/trade/IRS-48213/about").text
    assert 'data-layer="about"' not in h                                       # no Sutra description: layer 1 hidden
    assert 'data-layer="data"' in h and 'data-layer="next"' in h
    assert "no Sutra applies" in h and "hidden for your role" not in h and "shows no data" not in h and "Not chosen" not in h


def test_a_newer_generation_offers_a_refresh(client):
    h = client.get("/v/trade/IRS-48213/about?generation=3").text
    assert "data-about-refresh" in h
    assert "data-about-refresh" not in client.get("/v/trade/IRS-48213/about?generation=7").text


def test_authored_text_is_escaped(client, backend, monkeypatch):
    ex = json.loads((FIXTURES / "explain_trade_IRS-48213.json").read_text())
    ex["layout"]["sutra"]["description"] = "<script>alert(1)</script>"
    async def explain(kind, id_, user, generation=None):
        return ex
    monkeypatch.setattr(backend, "explain", explain)
    h = client.get("/v/trade/IRS-48213/about").text
    assert "<script>" not in h and "&lt;script&gt;" in h


def test_an_unknown_entity_answers_with_the_servers_problem(client):
    r = client.get("/v/trade/NOPE-1/about")
    assert r.status_code == 404 and "DRS-1001" in r.text and "about-error" in r.text


def test_the_f1_key_yields_to_the_drawer_and_the_drawer_is_wired_to_the_guide():
    app_js = (WEB / "static" / "js" / "app.js").read_text()
    assert re.search(r"key === 'F1'\) \{\s*if \(e\.defaultPrevented\) \{ return; \}", app_js)
    about_js = (WEB / "static" / "js" / "about.js").read_text()
    assert "e.key === 'F1'" in about_js and "if (isOpen()) { return; }" in about_js      # F1 again inside the drawer: app.js goes to the guide
    assert "e.key === '?'" in about_js and "typing(e.target)" in about_js            # `?` is ignored in a text field


def test_the_drawer_uses_theme_tokens_only():
    css = (WEB / "static" / "css" / "about.css").read_text()
    assert not re.search(r"#[0-9a-fA-F]{3,6}\b|rgba?\(", css)                      # colours come from tokens.css, so all seven themes work
    tokens = (WEB / "static" / "css" / "tokens.css").read_text()
    for t in set(re.findall(r"var\((--d-[a-z0-9-]+)\)", css)):
        assert t + ":" in tokens, t


def test_the_packs_own_text_leads_layer_one_and_a_masked_value_stays_masked(client, backend, monkeypatch):
    # steps 2 and 3 were built side by side: the drawer must show the explain answer's `about` block (step 3), the
    # pack's text first, the Sutra description after it, the panel notes, and who wrote it; masked values arrive as •••
    ex = json.loads((FIXTURES / "explain_trade_IRS-48213.json").read_text())
    ex["about"] = {"pack": {"name": "market-risk", "title": "Market risk"}, "kindTitle": "Value-at-risk result",
                   "text": "VAR-EQD is a 1-day 99% historical VaR: 14.7m USD, ••• of its ••• limit.",
                   "sutraDescription": "Historical VaR and ES for a desk or portfolio.",
                   "panels": [{"id": "dist", "title": "Scenario P&L distribution", "description": "Days left of the markers are tail losses."}]}
    async def explain(kind, id_, user, generation=None):
        return ex
    monkeypatch.setattr(backend, "explain", explain)
    h = client.get("/v/trade/IRS-48213/about").text
    assert "Value-at-risk result" in h and "••• of its ••• limit" in h and "data-about-pack-text" in h
    assert h.index("14.7m USD") < h.index("Historical VaR and ES"), "the pack's text comes before the Sutra description"
    assert "Scenario P&amp;L distribution" in h and "Written by the Market risk pack." in h
