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

"""Query by form (FORM <pack>), the console's side: the reserved word, type-ahead, the form for every field type, criteria in the address,
the command the server compiled, the results grid, regex refusals, saved searches. Against a stand-in server."""
import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from form_backend import FormBackend

from core import forms
from core.app import create_app
from core.config import load_settings


@pytest.fixture
def be():
    return FormBackend()


@pytest.fixture
def cl(be):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = be
    return TestClient(app)


def test_the_reserved_word_opens_the_form(cl):
    for typed, where in [("FORM trading", "/form/trading"), ("form trading <GO>", "/form/trading"), ("FORM trading desk-pnl", "/form/trading?kind=desk-pnl"),
                         ("FORM", "/forms")]:
        r = cl.get("/go", params={"q": typed}, follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"] == where, typed


def test_type_ahead_offers_only_packs_with_kinds_then_their_kinds(cl):
    packs = cl.get("/api/suggest", params={"q": "form "}).json()
    assert [s["complete"] for s in packs] == ["FORM trading "] and packs[0]["href"] == "/form/trading"       # "empty" has no kind to search
    assert [s["complete"] for s in cl.get("/api/suggest", params={"q": "FORM tra"}).json()] == ["FORM trading "]
    kinds = cl.get("/api/suggest", params={"q": "FORM trading "}).json()
    assert [(s["title"], s["href"]) for s in kinds] == [("Trade", "/form/trading?kind=trade"), ("Desk P&L", "/form/trading?kind=desk-pnl")]
    assert [s["title"] for s in cl.get("/api/suggest", params={"q": "FORM trading des"}).json()] == ["Desk P&L"]
    assert cl.get("/api/suggest", params={"q": "FORM nothing "}).json() == []


def test_the_form_has_an_input_for_every_type(cl):
    page = cl.get("/form/trading").text
    assert 'name="v.tradeId"' in page and "<textarea" in page                                  # id: paste a list
    assert 'name="op.counterparty.name"' in page and "matches regex" in page and 'name="cs.counterparty.name"' in page    # text: operators and case
    assert page.count('name="v.book"') == 3 and "BOOK-CREDIT-1" in page                        # enum: a checkbox per known value
    assert 'name="v2.mtm"' in page and 'inputmode="decimal"' in page and "250k, 1.5m, 2bn" in page    # amount: between has a second box
    assert 'type="date"' in page and 'list="qf-presets"' in page and "last 5 business days" in page   # date: pickers and relative presets
    assert 'type="radio"' in page and 'name="v.isActive"' in page                              # bool
    assert "internalNote" not in page                                                           # hidden fields are not offered
    assert "Identity" in page and "Money" in page and "The legal name." in page                  # groups and help
    assert page.count("slower: not indexed") == 1                                               # only the counterparty is not an indexed column
    assert 'href="/help/context/form"' in page                                                  # the help link
    assert "Desk P&amp;L" in page and "aria-current" in page                                    # several kinds: a selector


def test_a_form_nobody_declared_says_it_was_inferred(cl, be):
    be.declared = False
    assert "inferred from its search columns" in cl.get("/form/trading").text


def test_submitting_shows_the_command_and_the_results(cl, be):
    page = cl.get("/form/trading", params=[("kind", "trade"), ("run", "1"), ("op.mtm", "between"), ("v.mtm", "1m"), ("v2.mtm", "5m"),
                                           ("op.book", "in"), ("v.book", "BOOK-FI-1"), ("v.book", "BOOK-FI-2"), ("op.counterparty.name", "contains"),
                                           ("v.counterparty.name", "Meridian"), ("cs.counterparty.name", "1"), ("v.isActive", "any"), ("v.tradeId", ""),
                                           ("order", "mtm"), ("desc", "1"), ("limit", "50")]).text
    kind, body = be.compiled[-1]
    assert kind == "trade" and body["limit"] == 50 and body["orderBy"] == "mtm" and body["descending"] is True
    assert body["criteria"] == [{"path": "book", "op": "in", "values": ["BOOK-FI-1", "BOOK-FI-2"]},
                                {"path": "counterparty.name", "op": "contains", "values": ["Meridian"], "case": True},
                                {"path": "mtm", "op": "between", "values": ["1m", "5m"]}] or body["criteria"][0]["path"] in ("tradeId", "counterparty.name", "book")
    assert {c["path"] for c in body["criteria"]} == {"book", "counterparty.name", "mtm"}                  # empty and "any" boxes are not criteria
    assert "(mtm &gt;= 1m and mtm &lt;= 5m)" in page and "data-qf-command" in page and "data-qf-copy=" in page
    assert be.asked[-1].startswith("TRD where") and be.asked[-1].endswith("order by mtm desc limit 50")   # the ordinary search ran the command
    assert 'href="/v/trade/IRS-48213"' in page and 'class="tbl"' in page and "<b>2</b> of 36 trades match" in page    # the key cell opens the view
    assert "Watch as a monitor" in page and "Schedule a report" in page and "Save as named search" in page and "Share link" in page
    assert 'value="mtm" selected' in page and 'value="50" selected' in page and 'value="BOOK-FI-1" checked' in page    # the form keeps what was asked


def test_criteria_come_from_the_address_only_when_filled_in():
    fields = [{"path": "a", "type": "text", "operators": ["equals", "regex"]}, {"path": "n", "type": "amount", "operators": ["=", "between"]},
              {"path": "b", "type": "bool", "operators": ["is"]}, {"path": "e", "type": "enum", "operators": ["in"]}]
    assert forms.criteria_from([("v.a", " "), ("v.b", "any")], fields) == []
    got = forms.criteria_from([("op.a", "regex"), ("v.a", "^X"), ("op.n", "="), ("v.n", "5"), ("v2.n", "9"), ("v.b", "true"), ("v.e", "p"), ("v.e", "q")], fields)
    assert got == [{"path": "a", "op": "regex", "values": ["^X"]}, {"path": "n", "op": "=", "values": ["5"]}, {"path": "b", "op": "is", "values": ["true"]},
                   {"path": "e", "op": "in", "values": ["p", "q"]}]                                            # v2 matters only for between
    assert forms.address("trading", "trade", got[:1]).startswith("/form/trading?kind=trade&op.a=regex&v.a=%5EX") and forms.address("t", "k", []).endswith("&run=1")


def test_defaults_prefill_an_unsubmitted_form():
    form = {"fields": [{"path": "a", "type": "text", "operators": ["equals"], "default": "hello"}, {"path": "h", "type": "text", "operators": ["equals"], "hidden": True}]}
    assert forms.prepare(form, [])["groups"][0]["fields"][0]["held"] == ["hello"]
    assert forms.prepare(form, [("run", "1")])["groups"][0]["fields"][0]["held"] == []                  # cleared on purpose stays cleared
    assert len(forms.prepare(form, [])["groups"][0]["fields"]) == 1


def test_a_refused_regex_shows_its_code(cl, be):
    page = cl.get("/form/trading", params=[("run", "1"), ("op.counterparty.name", "regex"), ("v.counterparty.name", "(?=x)y")]).text
    assert "DRS-4010" in page and "look-around" in page and not be.asked and "data-qf-error" in page
    ok = cl.get("/form/trading", params=[("run", "1"), ("op.counterparty.name", "regex"), ("v.counterparty.name", "^Mer.*n$")]).text
    assert "matches(counterparty.name, " in ok and "DRS-4010" not in ok


def test_a_pack_you_cannot_open_is_a_clear_page(cl):
    r = cl.get("/form/finance")
    assert "no pack" in r.text and "FORM finance" in r.text


def test_the_index_lists_packs_with_something_to_search(cl):
    page = cl.get("/form").text
    assert "FORM trading" in page and "FORM empty" not in page


def test_a_search_is_saved_under_a_name_and_listed(cl, be):
    r = cl.post("/form/save", json={"name": "Big ones", "path": "/form/trading?kind=trade&run=1&op.mtm=%3E&v.mtm=1m"})
    assert r.status_code == 200 and be.settings_patched[-1]["savedSearches"][0]["name"] == "Big ones"
    page = cl.get("/form/trading").text
    assert "Your saved searches" in page and "Big ones" in page and "/form/trading?kind=trade" in page
    cl.post("/form/save", json={"name": "big ones", "path": "/form/trading?kind=trade&run=1"})              # the same name (any case) replaces
    assert [s["name"] for s in be.saved] == ["big ones"]
    assert cl.post("/form/save", content="name=x", headers={"content-type": "text/plain"}).status_code in (400, 415)


def test_the_forms_home_lists_packs_with_a_form_and_what_the_person_may_open(cl, be):
    from core.backend import BackendError
    page = cl.get("/forms").text
    assert 'href="/form/trading?kind=trade"' in page and "FORM trading" in page and "field" in page and "data-forms-filter" in page
    assert "/form/empty" not in page                                                       # no form section, nothing to search
    assert 'href="/forms"' in page and 'aria-label="Forms' not in page and 'aria-label="Reports — saved and scheduled reports, subscriptions, and BI reports"' in page
    assert cl.get("/form", follow_redirects=False).headers["location"] == "/forms"

    async def refuse(pack, kind="", ident=None):                                        # the server: no kind open to this role, or a hidden sample pack
        raise BackendError(403, "DRS-4030", "no access to any kind of pack")
    be.form = refuse
    assert "None of the packs open to you has a form" in cl.get("/forms").text
