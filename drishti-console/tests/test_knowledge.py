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

"""Knowledge (docs/knowledge/): WHAT, the reserved word (alone the home; a term, its long name or a synonym its glossary entry; other
words a search of the Knowledge pages), its type-ahead, the help centre's Knowledge section and the "What does this mean?" links."""
from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from core.knowledge import Glossary, anchor

CONSOLE = Path(__file__).resolve().parent.parent
DOCS = CONSOLE.parent / "docs"


@pytest.mark.parametrize("typed,where", [("WHAT <GO>", "/what"), ("what", "/what"), ("WHAT PFE <GO>", "/what?q=PFE"),
                                         ("What netting set", "/what?q=netting%20set")])
def test_the_reserved_word_what_goes_to_knowledge(client, typed, where):
    r = client.get("/go", params={"q": typed}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == where


@pytest.mark.parametrize("words,target", [
    ("PFE", "/help/knowledge-counterparty-credit-risk#term-pfe"),
    ("pfe", "/help/knowledge-counterparty-credit-risk#term-pfe"),
    ("potential future exposure", "/help/knowledge-counterparty-credit-risk#term-pfe"),
    ("netting set", "/help/knowledge-counterparty-credit-risk#term-netting-set"),
    ("Netting   Sets?", "/help/knowledge-counterparty-credit-risk#term-netting-set"),
    ("value at risk", "/help/knowledge-market-risk#term-var"),
    ("expected shortfall", "/help/knowledge-market-risk#term-es"),
    ("P&L vector", "/help/knowledge-market-risk#term-p-l-vector"),
    ("LEI", "/help/knowledge-entities-and-ownership#term-lei"),
    ("gdp", "/help/knowledge-economies#term-gdp"),
    ("ETF", "/help/knowledge-companies-funds-and-etfs#term-etf"),
    ("net asset value", "/help/knowledge-companies-funds-and-etfs#term-nav"),
    ("GOOGL", "/help/knowledge-companies-funds-and-etfs#term-share-class")])
def test_what_a_term_opens_its_glossary_entry(client, words, target):
    r = client.get("/what", params={"q": words}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == target
    page, _, frag = target.removeprefix("/help/").partition("#")
    assert f'id="{frag}"' in client.get(f"/help/{page}").text


def test_what_alone_opens_the_knowledge_home_and_it_lists_the_pages(client):
    r = client.get("/what", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/help/knowledge"
    home = client.get("/help/knowledge").text
    for slug in ("knowledge-market-risk", "knowledge-counterparty-credit-risk", "knowledge-economies"):
        assert f'href="/help/{slug}"' in home
    assert 'href="/help/knowledge-counterparty-credit-risk#term-pfe"' in home      # the glossary A to Z links each entry


def test_what_other_words_search_only_the_knowledge_pages(client):
    page = client.get("/what", params={"q": "collateral posted"})
    assert page.status_code == 200 and "data-what-search" in page.text
    assert 'href="/help/knowledge-counterparty-credit-risk"' in page.text
    assert 'href="/help/docs-guides-exposure-guide"' not in page.text           # the guides are a link away, not in the hits
    assert 'action="/what"' in page.text


def test_what_in_the_type_ahead_offers_the_home_then_the_terms(client):
    s = client.get("/api/suggest", params={"q": "WHAT"}).json()
    assert s[0]["mnemonic"] == "WHAT" and s[0]["href"] == "/help/knowledge" and s[0]["complete"] == "WHAT "
    s = client.get("/api/suggest", params={"q": "what netting se"}).json()
    assert s[0]["complete"] == "WHAT Netting set" and s[0]["href"] == "/help/knowledge-counterparty-credit-risk#term-netting-set"
    assert "Counterparty credit risk" in s[0]["subtitle"]
    s = client.get("/api/suggest", params={"q": "WHAT zzz qqq"}).json()
    assert s == [{"mnemonic": "WHAT", "title": "Search the Knowledge pages for zzz qqq", "subtitle": "No term of the glossary is called that",
                  "complete": "WHAT zzz qqq", "href": "/what?q=zzz%20qqq", "type": "what"}]


def test_the_glossary_matches_terms_names_and_synonyms_and_no_word_twice():
    g = Glossary(DOCS)
    assert g.find("EEPE").term == "EEPE" and g.find("effective expected positive exposure").term == "EEPE"
    assert g.find("CSAs").term == "CSA" and g.find("es").term == "ES"
    assert g.find("") is None and g.find("no such thing at all") is None
    seen = {}
    for e in g.entries():
        for w in e.words:
            assert seen.setdefault(w, e.term) == e.term, (w, seen[w], e.term)
    assert anchor("SA-CCR") == "term-sa-ccr"


def test_knowledge_is_the_first_section_of_the_help_centre(client):
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    first = catalogue["categories"][0]
    assert first["id"] == "knowledge" and first["guides"][0]["slug"] == "knowledge"
    assert {g["doc"] for g in first["guides"]} == {"knowledge/README.md"} | {f"knowledge/{p.name}" for p in (DOCS / "knowledge").glob("*.md")
                                                                              if p.name != "README.md"}
    index = client.get("/help").text
    assert index.index('id="cat-knowledge"') < index.index('id="cat-start"')


def test_every_what_does_this_mean_target_opens_its_section(client):
    targets = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())["knowledge"]
    assert {"var", "exposure", "var-result", "desk", "counterparty", "netting-set", "csa"} <= set(targets)
    for key, target in targets.items():
        page, _, frag = target.partition("#")
        r = client.get(f"/help/{page}")
        assert r.status_code == 200, key
        if frag:
            assert f'id="{frag}"' in r.text, key


def test_the_explorers_carry_the_link_beside_their_help(client):
    assert 'href="/help/knowledge-market-risk" title="What does this mean?' in client.get("/var").text
    assert 'href="/help/knowledge-counterparty-credit-risk" title="What does this mean?' in client.get("/exposure").text


def test_a_view_of_a_kind_with_a_knowledge_page_carries_the_link(client, monkeypatch):
    assert "data-what-link" not in client.get("/v/trade/IRS-48213").text           # no Knowledge target for the kind: no link
    monkeypatch.setitem(client.app.state.templates.env.globals["KNOWLEDGE"], "trade", "knowledge-trades-and-products#term-trade")
    page = client.get("/v/trade/IRS-48213").text
    assert 'href="/help/knowledge-trades-and-products#term-trade" data-what-link' in page and "What does this mean?" in page


def test_knowledge_is_in_the_build_and_help_menus(client):
    page = client.get("/t").text
    assert page.count('href="/what"') >= 2 and "also WHAT &lt;GO&gt;" in page
