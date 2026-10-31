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

"""Customize's settings of a part from the UI kit, Remove with Undo, and one add per click (RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31,
core/designer_settings.py): the questions come from the fragment's doc and the record's own fields, an unset required parameter is said,
only a part the person added is removed, and a repeated step (the same nonce) is done once."""
import pytest

from core import designer, designer_settings
from test_designer import KIT, RECORD, SUTRA, on  # noqa: F401 - the routes' fake server

KF = {"ref": "key-figure@1", "name": "key-figure", "params": {"id": "figure", "title": "Key figure", "value": "$.VALUE", "label": "Value", "fmt": "amount2", "code": ""},
      "doc": {"summary": "One headline number", "params": {
          "id": {"type": "id", "default": "figure"}, "title": {"type": "text", "default": "Key figure"},
          "value": {"type": "expression", "default": "$.VALUE", "meaning": "The expression giving the number"},
          "label": {"type": "text", "default": "Value"}, "fmt": {"type": "format", "default": "amount2"}, "code": {"type": "key", "default": ""}}}}
Q = {"kind": "trade", "id": "IRS-47102"}


def test_a_key_figure_s_settings_ask_which_number_from_the_record_and_say_what_is_unset():
    f = designer_settings.form(KF, {"use": "key-figure@1", "with": {"id": "figure"}}, RECORD)
    which = next(x for x in f["fields"] if x["name"] == "value")
    assert which["question"] == "Which number?" and which["unset"] and ["$.NOTIONAL", "NOTIONAL"] in which["choices"]
    assert ["$.TRADE_ID", "TRADE_ID"] not in which["choices"]                           # a number, not an id
    assert f["unset"] == ["Which number?"] and "id" not in [x["name"] for x in f["fields"]]
    assert next(x for x in f["fields"] if x["name"] == "code")["advanced"]
    assert designer_settings.guess_value(KF, RECORD) == {"value": "$.NOTIONAL"}
    doc = designer.load(SUTRA)
    designer.add(doc, "key-figure@1", {"id": "figure"})
    designer_settings.set_with(doc, 3, {"value": "$.NOTIONAL", "label": "Notional", "id": "hack", "nope": "x"}, KF)
    assert doc["panels"][3]["with"] == {"id": "figure", "value": "$.NOTIONAL", "label": "Notional"}
    assert designer_settings.form(KF, doc["panels"][3], RECORD)["unset"] == []


def test_every_kit_part_gets_questions_and_a_list_part_offers_the_record_s_lists():
    ladder = {**KIT[1], "doc": {**KIT[1]["doc"], "params": {**KIT[1]["doc"]["params"], "rows": {"type": "expression", "meaning": "The list of flows"}}}}
    f = designer_settings.form(ladder, {"use": "cashflow-ladder@1", "with": {"id": "flows"}}, RECORD)
    rows = next(x for x in f["fields"] if x["name"] == "rows")
    assert rows["question"] == "Which list?" and ["$.LEGS", "legs (1 rows)"] in rows["choices"] and rows["unset"]


def test_a_row_s_links_come_in_the_same_order_whatever_the_order_of_its_fields():
    """The drill's flake: the first trade of a book is of one source system or another, their fields in another order, and the links
    past the first three (the counterparty among them) were not offered: its credit limits went missing from When I click."""
    from core import designer_links
    cat = {"links": {"TRADE_ID": {"kind": "trade", "label": "Trade"}, "PORTFOLIO": {"kind": "book", "label": "Portfolio"},
                     "LEGAL_ENTITY": {"kind": "legal-entity", "label": "Legal entity"}, "COUNTERPARTY": {"kind": "counterparty", "label": "Counterparty"}}}
    endur = {"TRADE_ID": "T1", "PORTFOLIO": "B", "LEGAL_ENTITY": "L", "COUNTERPARTY": "C"}
    murex = {"COUNTERPARTY": "C", "LEGAL_ENTITY": "L", "TRADE_ID": "T1", "PORTFOLIO": "B"}
    assert designer_links.targets(endur, cat) == designer_links.targets(murex, cat)
    assert [t[0] for t in designer_links.targets(murex, cat)[:3]] == ["TRADE_ID", "COUNTERPARTY", "LEGAL_ENTITY"]


def test_only_a_part_the_person_added_is_removed_and_undo_puts_it_back():
    doc, keep = designer.load(SUTRA), {}
    designer.add(doc, "key-figure@1", {"id": "figure"}, 1)
    designer_settings.mark_added(keep, doc["panels"][1])
    with pytest.raises(designer.DesignerError, match="hide the pack"):
        designer_settings.remove(doc, keep, 0)
    designer_settings.remove(doc, keep, 1)
    assert [designer_settings.added_id(e) for e in doc["panels"]] == ["", "flows", ""]
    designer_settings.undo_remove(doc, keep)
    assert doc["panels"][1]["use"] == "key-figure@1" and "removed" not in keep
    with pytest.raises(designer.DesignerError):
        designer_settings.undo_remove(doc, keep)
    assert not designer_settings.seen(keep, "n1") and designer_settings.seen(keep, "n1") and not designer_settings.seen(keep, "")


def test_add_opens_settings_remove_undo_and_a_repeated_nonce_adds_once(client, on, backend, monkeypatch):  # noqa: F811
    async def ui_kit(ident=None):
        return {"fragments": [KF, *KIT[1:]]}
    monkeypatch.setattr(backend, "ui_kit", ui_kit)
    draft = client.post("/designer/customize", json=Q).json()["draft"]
    state = f"/designer/state/{draft}?kind=trade&id=IRS-47102"
    r = client.post(f"/designer/op/{draft}", json={"op": "add", "ref": "key-figure@1", "with": {"id": "figure"}, "nonce": "a1", **Q}).json()
    assert r == {"ok": True, "index": 3, "settings": True}
    assert client.post(f"/designer/op/{draft}", json={"op": "add", "ref": "key-figure@1", "with": {"id": "figure"}, "nonce": "a1", **Q}).json()["repeat"]
    st = client.get(state).json()
    assert len(st["parts"]) == 4 and st["parts"][3]["added"] and not st["parts"][0]["added"]
    assert st["parts"][3]["unset"] == []                                                 # the number was guessed: NOTIONAL
    s = client.get(f"/designer/settings/{draft}?index=3&kind=trade&id=IRS-47102").json()
    assert next(x for x in s["fields"] if x["name"] == "value")["value"] == "$.NOTIONAL"
    client.post(f"/designer/op/{draft}", json={"op": "settings", "index": 3, "values": {"value": "$.MISSING"}, **Q})
    assert client.get(state).json()["parts"][3]["unset"] == ["Which number?"]
    assert client.post(f"/designer/op/{draft}", json={"op": "remove", "index": 0, **Q}).status_code == 400
    client.post(f"/designer/op/{draft}", json={"op": "remove", "index": 3, **Q})
    st = client.get(state).json()
    assert len(st["parts"]) == 3 and st["undo"]
    client.post(f"/designer/op/{draft}", json={"op": "undo-remove", **Q})
    assert len(client.get(state).json()["parts"]) == 4
