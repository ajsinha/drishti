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

"""Forms on view pages (SUTRA_NEXT.md 10), the console's half: a panel with a form carries it as data-form (never in an embed); a page
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
    assert designer_forms.form_of(doc["panels"][0]) == {"mode": "cells", "new": True, "fields": ["LIMIT", "REVIEW_DATE"]}
    designer_forms.edit(doc, "buckets", "off")
    assert "form" not in doc["panels"][0]
    for bad in (("buckets", "inline", None), ("nothing", "beside", None), ("buckets", "beside", [])):
        with pytest.raises(designer.DesignerError):
            designer_forms.edit(doc, *bad)


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
