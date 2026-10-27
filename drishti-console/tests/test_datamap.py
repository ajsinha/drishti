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

"""The data map, DATAMAP (docs/guides/USER_GUIDE.md "The data map"): the page draws what the server infers (kinds, counts, contract joins and
their coverage), with domains from config/datamap.yaml (presentation only; a pack it does not name is a domain of its own), an automatic
layout, the commands that open each kind, and contract, guide and WHAT links that resolve; findings for joins whose ids mostly name nothing;
the reserved word DATAMAP with its focus; the Views menu; Help."""
import re

import pytest

from core import datamap


@pytest.fixture
def shipped(with_packs):
    with_packs("trading", "core", "counterparty-risk")


def test_the_map_draws_every_kind_the_server_infers_with_counts_joins_and_a_finding(client, shipped):
    page = client.get("/datamap").text
    for text in ("data-datamap", 'data-view="map"', "Data map", "DATAMAP", 'data-kind="trade"', 'data-kind="counterparty"', "15,000", "computed",
                 'class="dm-n dm-computed"', 'data-from="trade" data-to="counterparty"', "dm-j-low", "1 finding",
                 "Trade.PORTFOLIO → Book: 1 of 4 ids match (25%)", ">Trades<", ">Organisation<", ">Counterparty credit<", "datamap.js", "datamap.css",
                 'data-set-view="cards"', 'data-chip-set="trades"', "stored in the lake", "computed on demand, never stored"):
        assert text in page, text
    assert 'style="' not in page.split("data-datamap", 1)[1], "no inline style (CSP): positions are SVG attributes"


def test_the_model_links_each_kind_to_its_command_contract_guide_and_what(client, shipped):
    m = client.get("/datamap/data").json()
    trade = next(k for k in m["kinds"] if k["kind"] == "trade")
    assert trade["commands"][0] == {"text": "TRD T-1", "href": "/go?q=TRD%20T-1"}, "the command registry's example"
    assert trade["open"] == "/go?q=TRD%20T-1"
    labels = {l["label"]: l["href"] for l in trade["links"]}
    assert labels["Data contract"].startswith("/help/docs-contracts-packs-trading#trd-trade"), labels
    assert any(h == "/help/trading" for h in labels.values()), "the pack's guide"
    assert any(l.get("what") for l in trade["links"]), "a WHAT link: the glossary knows trade"
    cp = next(j for j in m["joins"] if j["field"] == "COUNTERPARTY")
    assert cp["text"] == "15,000 of 15,000 ids match (100%)" and not cp["low"] and cp["cross"]
    exp = next(j for j in m["joins"] if j["from"] == "exposure-result")
    assert exp["text"].startswith("computed on demand")
    for k in m["kinds"]:
        assert {"x", "y", "w", "h"} <= set(k), "every node is placed by the layout"
    names = [d["id"] for d in m["domains"]]
    assert names == ["trades", "organisation", "counterparty-credit"], "config/datamap.yaml's order"
    org = next(d for d in m["domains"] if d["id"] == "organisation")
    assert org["sources"] and "Synthetic" in org["sources"][0], "comes from: the pack README's sources"
    assert {"id": "trades", "title": "Trades", "fields": ["COUNTERPARTY", "PORTFOLIO"]} in org["crosses"]


def test_every_link_of_the_map_resolves(client, shipped):
    m = client.get("/datamap/data").json()
    hrefs = {l["href"] for k in m["kinds"] for l in k["links"]} | {l["href"] for d in m["domains"] for l in d["links"]}
    assert hrefs
    for href in hrefs:
        r = client.get(href.split("#")[0], follow_redirects=True)
        assert r.status_code == 200, href
        anchor = href.split("#")[1] if "#" in href else ""
        if anchor and href.startswith("/help/"):
            assert f'id="{anchor}"' in r.text, href


def test_a_pack_no_configuration_names_is_a_domain_of_its_own_and_is_laid_out():
    server = {"state": "ready", "packs": [{"name": "trading", "title": "Trading"}, {"name": "acme", "title": "Acme widgets"}],
              "kinds": [{"kind": "trade", "pack": "trading", "label": "Trade", "count": 3, "computed": False},
                        {"kind": "widget", "pack": "acme", "label": "Widget", "count": 2, "computed": False},
                        {"kind": "maker", "pack": "acme", "label": "Maker", "count": 1, "computed": False}],
              "joins": [{"from": "widget", "field": "MAKER", "to": "maker", "state": "checked", "rows": 2, "filled": 2, "values": 2, "matched": 2},
                        {"from": "widget", "field": "TRADE_ID", "to": "trade", "state": "checked", "rows": 2, "filled": 2, "values": 2, "matched": 0}]}
    m = datamap.build(server, {}, {"domains": [{"id": "trades", "title": "Trades", "packs": ["trading"]}]}, [])
    assert [(d["id"], d["title"]) for d in m["domains"]] == [("trades", "Trades"), ("acme", "Acme widgets")]
    widget = next(k for k in m["kinds"] if k["kind"] == "widget")
    assert widget["domain"] == "acme" and widget["degree"] == 2
    acme = next(d for d in m["domains"] if d["id"] == "acme")["box"]
    assert acme["x"] <= widget["x"] < acme["x"] + acme["w"] and acme["y"] <= widget["y"] < acme["y"] + acme["h"]
    assert [f["field"] for f in m["findings"]] == ["TRADE_ID"], "0 of 2 ids match: a finding"
    assert datamap.build(server, {}, {}, [])["domains"][0]["title"] == "Trading", "no configuration at all: one domain per pack"


def test_layout_is_deterministic_and_nodes_never_overlap(client, shipped):
    a = client.get("/datamap/data").json()
    b = client.get("/datamap/data").json()
    assert [(k["x"], k["y"]) for k in a["kinds"]] == [(k["x"], k["y"]) for k in b["kinds"]]
    boxes = [(k["x"], k["y"], k["x"] + k["w"], k["y"] + k["h"]) for k in a["kinds"]]
    for i, p in enumerate(boxes):
        for q in boxes[i + 1:]:
            assert p[2] <= q[0] or q[2] <= p[0] or p[3] <= q[1] or q[3] <= p[1], (p, q)


@pytest.mark.parametrize("typed,where", [("DATAMAP <GO>", "/datamap"), ("datamap", "/datamap"), ("DATAMAP trade", "/datamap?q=trade"),
                                         ("DataMap counterparty credit", "/datamap?q=counterparty%20credit")])
def test_the_reserved_word_datamap_opens_the_page(client, typed, where):
    r = client.get("/go", params={"q": typed}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == where


@pytest.mark.parametrize("words,where", [("trade", "/datamap?kind=trade"), ("CPTY", "/datamap?kind=counterparty"),
                                         ("counterparty", "/datamap?kind=counterparty"), ("organisation", "/datamap?domain=organisation"),
                                         ("counterparty-risk", "/datamap?domain=counterparty-credit"), ("LEI", "/datamap?find=LEI")])
def test_datamap_words_focus_a_kind_or_a_domain(client, shipped, words, where):
    r = client.get("/datamap", params={"q": words}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == where


def test_the_view_and_the_selection_come_from_the_address(client, shipped):
    page = client.get("/datamap", params={"view": "cards", "kind": "trade"}).text
    assert 'data-view="cards" data-kind="trade" data-domain="trades"' in page
    assert re.search(r'<section class="dmap-map" data-pane="map" aria-label="Map"\s+hidden>', page)
    assert 'class="dm-card on" href="/datamap?view=cards&amp;domain=trades"' in page
    assert 'value="DATAMAP trade"' in page, "the command line says what is open"


def test_the_page_follows_the_server_while_it_is_still_checking(client, backend, shipped):
    backend.datamap_state = "checking"
    page = client.get("/datamap").text
    assert "Counting and checking the data" in page
    assert client.get("/datamap/data").json()["state"] == "checking"


def test_datamap_in_the_type_ahead_the_views_menu_and_help(client, shipped):
    s = client.get("/api/suggest", params={"q": "DATAMAP coun"}).json()
    assert s[0]["mnemonic"] == "DATAMAP" and s[0]["href"] == "/datamap?q=coun" and s[0]["complete"] == "DATAMAP coun"
    page = client.get("/t").text
    assert 'href="/datamap"' in page and "Every kind of data, how it joins and how much there is · also DATAMAP &lt;GO&gt;" in page
    guide = client.get("/help/user-guide").text
    assert 'id="the-data-map"' in guide
