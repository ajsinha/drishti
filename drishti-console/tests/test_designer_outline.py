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

"""The designer's Outline mode (SCREEN_DESIGNER.md section 31): the tree in plain words, its steps over the Sutra (move, nest under a table
so it opens on a click, un-nest, sections added, renamed and removed, settings) and the routes; Customize's layout handles (span, area)."""
import pytest
import yaml

from core import designer, designer_outline
from test_designer import SUTRA, on  # noqa: F401 - the designer routes' fixture

GRID = SUTRA + """layout:
  areas: ["top top", "left right"]
  columns: [8, 4]
"""


def test_the_tree_reads_in_plain_words():
    doc = designer.load(SUTRA)
    t = designer_outline.tree(doc, {"trading/deal-ticket@1": {"summary": "The deal's terms at a glance"}})
    [page] = t["sections"]
    assert [p["title"] for p in page["panels"]] == ["Deal ticket", "Flows", "Data provenance"]
    assert page["panels"][0]["description"] == "The deal's terms at a glance"
    assert page["panels"][1]["description"] == "A table of cash flows, 3 columns" and page["panels"][1]["table"]
    assert t["canSection"] and t["areas"] == []


def test_reorder_sections_nest_and_unnest():
    doc, keep = designer.load(SUTRA), {}
    sid = designer_outline.add_section(doc, "Risk & limits")
    assert sid == "risk-limits" and doc["layout"]["sections"] == [{"id": "risk-limits", "title": "Risk & limits"}]
    designer_outline.move(doc, 2, 0, "risk-limits")
    assert doc["panels"][0] == {"use": "data-provenance@1", "section": "risk-limits"}
    t = designer_outline.tree(doc)
    assert [s["id"] for s in t["sections"]] == ["", "risk-limits"] and [p["index"] for p in t["sections"][1]["panels"]] == [0]
    designer_outline.nest(doc, keep, 0, "flows", "detail-right")      # the provenance panel opens on a click on a flow
    flows = designer.find_panel(doc, "flows")
    assert flows["children"][0]["place"] == "detail-right" and flows["children"][0]["panels"] == [{"use": "data-provenance@1"}]
    assert flows["emits"][0]["on"] == "row-click" and len(doc["panels"]) == 2
    kids = designer_outline.tree(doc)["sections"][0]["panels"][1]["children"]
    assert kids[0]["title"] == "Data provenance" and kids[0]["opens"] == "the right column, when a row is picked"
    designer_outline.setting(doc, 1, "show", "always", 0)
    assert flows["children"][0]["show"] == "always"
    designer_outline.unnest(doc, keep, "flows")
    assert len(doc["panels"]) == 3 and "children" not in designer.find_panel(doc, "flows")
    designer_outline.rename_section(doc, "risk-limits", "Risk")
    designer_outline.remove_section(doc, "risk-limits")
    assert "layout" not in doc
    with pytest.raises(designer.DesignerError, match="only open under a table"):
        designer_outline.nest(doc, keep, 1, "nowhere", "tabs")


def test_settings_and_areas():
    doc = designer.load(GRID)
    designer_outline.setting(doc, 1, "span", 6)
    designer_outline.setting(doc, 1, "area", "right")
    designer_outline.setting(doc, 1, "collapsible", True)
    assert {k: doc["panels"][1][k] for k in ("span", "area", "collapsible")} == {"span": 6, "area": "right", "collapsible": True}
    designer_outline.setting(doc, 1, "span", 12)
    assert "span" not in doc["panels"][1]
    with pytest.raises(designer.DesignerError, match="areas"):
        designer_outline.setting(doc, 1, "area", "elsewhere")
    with pytest.raises(designer.DesignerError, match="by area"):
        designer_outline.add_section(doc, "S")
    with pytest.raises(designer.DesignerError, match="does not refresh"):
        designer_outline.setting(doc, 1, "refresh", 30)
    assert designer_outline.tree(doc)["areas"] == ["top", "left", "right"]


def test_outline_routes(client, on):
    assert client.get("/designer/new").text.count('href="/designer/outline"') == 1           # Outline enabled in the mode switcher
    r = client.get("/designer/outline?kind=trade&id=IRS-47102", follow_redirects=False)
    assert r.status_code == 303 and "design=" in r.headers["location"]
    draft = r.headers["location"].split("design=")[1]
    page = client.get(r.headers["location"]).text
    assert "data-ol-tree" in page and f'data-draft="{draft}"' in page and "designer-outline.js" in page
    st = client.get(f"/designer/outline/state/{draft}").json()
    assert [p["title"] for p in st["sections"][0]["panels"]] == ["Deal ticket", "Flows", "Data provenance"]
    body = {"kind": "trade", "id": "IRS-47102"}
    assert client.post(f"/designer/outline/op/{draft}", json={**body, "op": "add-section", "title": "More"}).json() == {"ok": True}
    assert client.post(f"/designer/outline/op/{draft}", json={**body, "op": "move", "index": 0, "to": 2, "section": "more"}).json() == {"ok": True}
    assert client.post(f"/designer/outline/op/{draft}", json={**body, "op": "nest", "index": 1, "under": "flows", "place": "tabs"}).json() == {"ok": True}
    st = client.get(f"/designer/outline/state/{draft}").json()
    assert [s["title"] for s in st["sections"]] == ["The page", "More"]
    assert st["sections"][0]["panels"][0]["children"][0]["title"] == "Data provenance"
    bad = client.post(f"/designer/outline/op/{draft}", json={**body, "op": "nest", "index": 0, "under": "nope", "place": "tabs"})
    assert bad.status_code == 400 and "table" in bad.json()["detail"]
    assert client.post(f"/designer/outline/op/{draft}", json={**body, "op": "unnest", "parent": "flows"}).json() == {"ok": True}
    doc = yaml.safe_load(st["yaml"])
    assert doc["layout"]["sections"] == [{"id": "more", "title": "More"}]


def test_customize_layout_handles(client, on, backend, monkeypatch):
    async def grid_source(name, version, ident=None):
        return GRID
    monkeypatch.setattr(backend, "sutra_source", grid_source)
    draft = client.post("/designer/customize", json={"kind": "trade", "id": "IRS-47102"}).json()["draft"]
    st = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()
    assert st["grid"] and st["areas"] == ["top", "left", "right"] and st["parts"][1]["span"] == 12
    body = {"kind": "trade", "id": "IRS-47102"}
    assert client.post(f"/designer/op/{draft}", json={**body, "op": "span", "index": 1, "value": 5}).json() == {"ok": True}
    assert client.post(f"/designer/op/{draft}", json={**body, "op": "area", "index": 1, "value": "right"}).json() == {"ok": True}
    st = client.get(f"/designer/state/{draft}?kind=trade&id=IRS-47102").json()
    assert (st["parts"][1]["span"], st["parts"][1]["area"]) == (5, "right")
