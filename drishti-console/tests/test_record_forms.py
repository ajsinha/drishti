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

"""Forms on view pages (RACHANA_REFERENCE.md 10), the console's half: a panel with a form carries it as data-form (never in an embed); a page
with one loads the form scripts; /api/drafts passes each call to the server as the signed-in person and hands back the issues of a
refused draft; the designer writes form: (Customize's Let people edit, Describe's edit … beside|cells and new … drawer). The browser
half is test_record_forms_browser.py."""
import json
import re

import pytest

from core import describe, designer, designer_forms
from core.backend import BackendError


def _panel(client, p, embed=False) -> str:
    return str(client.app.state.templates.env.get_template("_macros/panels.html").module.panel(p, embed))


FORM = {"edit": "cells", "key": "bucket", "fields": [{"id": "bucket", "label": "Bucket", "readonly": True}, {"id": "limit", "label": "Limit", "type": "number"}],
        "rules": [], "hints": [], "rows": []}


def test_a_panel_with_a_form_carries_it_but_not_in_an_embed(client):
    base = {"id": "buckets", "kind": "table", "title": "Buckets", "data": {"columns": ["Bucket"], "rows": []}}
    assert "data-form" not in _panel(client, base)
    html = _panel(client, {**base, "form": FORM})
    got = json.loads(re.search(r'data-form="([^"]*)"', html).group(1).replace("&#34;", '"').replace("&quot;", '"'))
    assert got["edit"] == "cells" and got["fields"][1]["id"] == "limit"
    assert "data-form" not in _panel(client, {**base, "form": FORM}, embed=True)


def test_a_page_with_a_form_loads_the_form_scripts(client, backend, monkeypatch):
    real = backend.view

    async def view(kind, id_, user, controls=None, panels=None):
        vm = await real(kind, id_, user, controls, panels)
        vm["panels"][0]["form"] = FORM
        return vm
    assert "record-forms.js" not in client.get("/v/trade/IRS-47102").text
    monkeypatch.setattr(backend, "view", view)
    page = client.get("/v/trade/IRS-47102").text
    assert "form-expr.js" in page and "record-forms.js" in page and "record-forms.css" in page


def test_drafts_pass_through_as_the_person_with_the_issues_of_a_refusal(client, backend, monkeypatch):
    calls = []

    async def send(method, path, ident=None, **kw):
        calls.append((method, path, kw.get("json")))
        if method == "POST" and (kw.get("json") or {}).get("records", [{}])[0].get("values", {}).get("notional") == -5:
            e = BackendError(422, "DRS-2060", "Notional is at least 0")
            e.extra = {"code": "DRS-2060", "detail": "Notional is at least 0", "issues": [{"field": "notional", "message": "Notional is at least 0", "severity": "error"}]}
            raise e
        if method == "DELETE":
            return None
        return {"draft": "d-0123456789ab"} if method in ("POST", "PUT") else []
    monkeypatch.setattr(backend, "_send", send)
    body = {"kind": "portfolio-trades", "id": "BOOK-1", "sutra": "book-desk", "panel": "trades", "mode": "edit", "records": [{"record": "T-1", "values": {"notional": 5}}]}
    assert client.post("/api/drafts", json=body).status_code == 201
    bad = client.post("/api/drafts", json={**body, "records": [{"record": "T-1", "values": {"notional": -5}}]})
    assert bad.status_code == 422 and bad.json()["issues"][0]["field"] == "notional"
    assert client.get("/api/drafts?kind=portfolio-trades&id=BOOK-1&other=x").status_code == 200
    assert calls[-1] == ("GET", "/drafts?kind=portfolio-trades&id=BOOK-1", None)
    assert client.put("/api/drafts/d-0123456789ab", json=body).status_code == 200
    assert client.delete("/api/drafts/d-0123456789ab").status_code == 204
    assert client.get("/api/drafts/nope").status_code == 400
    assert client.post("/api/drafts", content="x", headers={"Content-Type": "text/plain"}).status_code == 415


def test_an_entity_lookup_passes_through_as_the_person(client, backend, monkeypatch):
    calls = []

    async def send(method, path, ident=None, **kw):
        calls.append((method, path))
        if "q=deny" in path:
            raise BackendError(403, "DRS-1003", "tia may not open legal-entity entities")
        return {"kind": "legal-entity", "q": "bank", "results": [{"id": "LE-FRA", "label": "Drishti Bank Europe AG"}]}
    monkeypatch.setattr(backend, "_send", send)
    got = client.get("/api/forms/lookup?kind=legal-entity&pack=core&q=bank&label=NAME&other=x")
    assert got.status_code == 200 and got.json()["results"][0]["id"] == "LE-FRA"
    assert calls[-1] == ("GET", "/forms/lookup?kind=legal-entity&pack=core&q=bank&label=NAME")
    assert client.get("/api/forms/lookup?kind=legal-entity&q=deny").status_code == 403
    assert client.get("/api/forms/lookup?q=bank").status_code == 400


def test_customize_lets_people_edit_a_table_beside_or_in_its_cells():
    doc = {"panels": [{"id": "buckets", "kind": "table", "title": "By bucket", "rows": "$.BUCKETS",
                       "columns": [{"label": "Bucket", "bind": "@.BUCKET"}, {"label": "Limit", "bind": "@.LIMIT", "fmt": "compact"},
                                   {"label": "Review", "bind": "@.REVIEW_DATE"}, {"label": "Owner", "bind": "@.OWNER"}]}]}
    designer_forms.edit(doc, "buckets", "cells", ["LIMIT", "REVIEW_DATE"], new=True)
    f = doc["panels"][0]["form"]
    assert f["edit"] == "cells" and f["new"] == "drawer" and f["key"] == "bucket"
    by = {x["id"]: x for x in f["fields"]}
    assert by["bucket"]["readonly"] and by["limit"]["type"] == "number" and by["review_date"]["type"] == "date"
    assert by["owner"]["readonly"] and not by["limit"].get("readonly")
    assert designer_forms.form_of(doc["panels"][0]) == {"mode": "cells", "new": True, "fields": ["LIMIT", "REVIEW_DATE"],
                                                        "types": {"BUCKET": "text", "LIMIT": "number", "REVIEW_DATE": "date", "OWNER": "text"}}
    designer_forms.edit(doc, "buckets", "off")
    assert "form" not in doc["panels"][0]
    for bad in (("buckets", "inline", None), ("nothing", "beside", None), ("buckets", "beside", [])):
        with pytest.raises(designer.DesignerError):
            designer_forms.edit(doc, *bad)


def test_customize_guesses_or_takes_money_a_date_and_time_and_a_linked_record():
    doc = {"panels": [{"id": "trades", "kind": "table", "rows": "$.TRADES",
                       "columns": [{"bind": "@.TRADE_ID"}, {"bind": "@.NOTIONAL", "fmt": "compact"}, {"bind": "@.CURRENCY"},
                                   {"bind": "@.EXECUTED_AT"}, {"bind": "@.COUNTERPARTY"}, {"bind": "@.QUANTITY"}]}]}
    cat = {"links": {"COUNTERPARTY": {"kind": "counterparty", "label": "Counterparty"}}, "pack_of": {"counterparty": "core"},
           "columns": {"counterparty": ["COUNTERPARTY_ID", "NAME"]}}
    designer_forms.edit(doc, "trades", "beside", None, new=True, cat=cat)
    by = {x["id"]: x for x in doc["panels"][0]["form"]["fields"]}
    assert by["notional"]["type"] == "money" and by["notional"]["currency"] == "currency"       # the table's currency column
    assert by["executed_at"]["type"] == "datetime" and by["quantity"]["type"] == "number"
    assert by["counterparty"]["type"] == "entity"
    assert by["counterparty"]["options"] == {"source": {"pack": "core", "kind": "counterparty"}, "label": "NAME"}
    designer_forms.edit(doc, "trades", "cells", None, types={"QUANTITY": "money", "COUNTERPARTY": "text", "EXECUTED_AT": "date"}, cat=cat)
    by = {x["id"]: x for x in doc["panels"][0]["form"]["fields"]}
    assert (by["quantity"]["type"], by["counterparty"]["type"], by["executed_at"]["type"]) == ("money", "text", "date")
    assert designer_forms.form_of(doc["panels"][0])["types"]["QUANTITY"] == "money"
    with pytest.raises(designer.DesignerError, match="one of"):
        designer_forms.edit(doc, "trades", "cells", None, types={"QUANTITY": "colour"})
    doc["panels"][0]["columns"].pop(2)                                    # no currency column: a number, or money in a fixed currency
    designer_forms.edit(doc, "trades", "beside", None, cat=cat)
    assert {x["id"]: x for x in doc["panels"][0]["form"]["fields"]}["notional"]["type"] == "number"
    designer_forms.edit(doc, "trades", "beside", None, types={"NOTIONAL": "money"})
    assert {x["id"]: x for x in doc["panels"][0]["form"]["fields"]}["notional"]["currency"] == "USD"


def test_describe_edits_trades_beside_or_in_cells_and_starts_new_ones_in_a_drawer():
    aliases = {"TRD": {"kind": "trade", "label": "Trade"}}
    p = describe.parse("TRD BOOK-RATES-2 · edit trades beside · new trade in a drawer", aliases)
    assert p.ok and p.edit == "beside" and p.new
    assert [t.role for t in p.tokens][1:] == ["form", "form"]
    form = describe.sutra(p, "x")["panels"][0]["form"]
    assert form["edit"] == "beside" and form["new"] == "drawer" and [s["id"] for s in form["steps"]] == ["who", "what", "check"]
    assert form["hints"][0]["value"] == "counterparty.USED + notional"
    assert describe.parse("TRD BOOK-RATES-2 · edit trades in the cells", aliases).edit == "cells"
    q = describe.parse("CPTY CP-1 · edit trades beside", {"CPTY": {"kind": "counterparty"}})
    assert not q.ok and "Customize" in q.messages[0]


def test_describe_writes_the_notional_as_money_in_the_trades_currency_else_the_records():
    p = describe.parse("TRD BOOK-RATES-2 · edit trades beside · new trade in a drawer", {"TRD": {"kind": "trade", "label": "Trade"}})
    assert describe.currency_of({"TRADES": [{"TRADE_ID": "T1", "CURRENCY": "EUR"}], "CURRENCY": "GBP"}) == "field"
    assert describe.currency_of({"TRADES": [{"TRADE_ID": "T1"}], "BASE_CURRENCY": "GBP"}) == "GBP"
    assert describe.currency_of(None) is None and describe.currency_of({"CURRENCY": "lots"}) is None
    fields = {f["id"]: f for f in describe.sutra(p, "x", currency="field")["panels"][0]["form"]["fields"]}
    assert fields["notional"]["type"] == "money" and fields["notional"]["currency"] == "currency"
    assert fields["currency"]["bind"] == "CURRENCY" and "EUR" in fields["currency"]["options"] and fields["currency"]["step"] == "what"
    fields = {f["id"]: f for f in describe.sutra(p, "x", currency="GBP")["panels"][0]["form"]["fields"]}
    assert fields["notional"]["currency"] == "GBP" and "currency" not in fields
    assert {f["id"]: f for f in describe.sutra(p, "x")["panels"][0]["form"]["fields"]}["notional"]["currency"] == "USD"
