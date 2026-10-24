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

"""A screen laid out with ``layout:`` (SUTRA_NEXT.md 13), the console side: panels grouped into areas or sections and sorted by order, the
reader's arrangement applied only where the Sutra allows it, the phone order; the page drawn with the grid (areas, tabs, a split, sticky,
sections, panels that fold) and without one exactly as before; the reader's arrangement passed to the server and back."""
import copy
import json
import re

import pytest

from core import screen_grid

AREAS = {"areas": ["header header", "trades detail"], "columns": [7, 5], "split": {"between": ["trades", "detail"], "resizable": True, "remember": "user"},
         "tabs": ["detail"], "sticky": "header", "arrange": True, "phone": ["header", "detail"],
         "place": {"built": {"area": "header"}, "refs": {"area": "detail"}, "x": {"area": "trades", "order": 1}, "y": {"area": "detail", "collapsible": True}}}
PANELS = [{"id": "built"}, {"id": "refs"}, {"id": "x"}, {"id": "y"}]


def names(g):
    return {a["name"]: [p["id"] for p in a["panels"]] for a in g["areas"]}


def test_no_layout_no_grid():
    assert screen_grid.arrange(None, PANELS) is None


def test_areas_order_and_phone():
    g = screen_grid.arrange(AREAS, PANELS)
    assert g["mode"] == "areas" and names(g) == {"header": ["built"], "trades": ["x"], "detail": ["refs", "y"]}
    assert g["columns"] == [7, 5] and g["split"] == {"left": "trades", "right": "detail", "resizable": True, "remember": "user"}
    assert [a["name"] for a in g["areas"] if a["tabs"]] == ["detail"] and [a["name"] for a in g["areas"] if a["sticky"]] == ["header"]
    assert g["phone"] == {"built": 0, "refs": 1, "y": 2, "x": 3}            # header, then detail, then the rest as the picture reads
    assert g["fold"] == {"y": False}


def test_the_readers_arrangement_only_where_the_sutra_allows():
    mine = {"columns": [6, 6], "collapsed": {"y": True}, "panels": [{"id": "x", "area": "detail", "span": 6, "order": 0}, {"id": "built", "area": "nowhere"}]}
    g = screen_grid.arrange(AREAS, PANELS, mine)
    assert names(g)["detail"] == ["x", "refs", "y"] and names(g)["header"] == ["built"]    # an area the screen does not have is ignored
    assert [p for p in g["areas"][2]["panels"] if p["id"] == "x"][0]["span"] == 6
    assert g["columns"] == [6.0, 6.0] and g["fold"] == {"y": True} and g["own"]
    fixed = screen_grid.arrange({**AREAS, "arrange": False, "split": {**AREAS["split"], "remember": "none"}}, PANELS, mine)
    assert names(fixed)["trades"] == ["x"] and fixed["columns"] == [7, 5]


def test_flow_sections():
    lay = {"sections": [{"id": "risk", "title": "Risk", "collapsed": True}, {"id": "more", "title": "More"}], "arrange": False,
           "place": {"x": {"section": "risk", "order": 9}, "y": {"section": "risk", "order": 1}, "refs": {"section": "more"}}}
    g = screen_grid.arrange(lay, PANELS, {"collapsed": {"more": True, "risk": False}})
    assert g["mode"] == "flow" and [p["id"] for p in g["top"]] == ["built"]
    assert [(s["id"], s["collapsed"], [p["id"] for p in s["panels"]]) for s in g["sections"]] == [("risk", False, ["y", "x"]), ("more", True, ["refs"])]


# ---- the page --------------------------------------------------------------------------------------------------------------------------

@pytest.fixture()
def gridded(client, backend, monkeypatch):
    kept: dict = {}
    real_view, real_send = backend.view, backend._send
    layout = {"value": copy.deepcopy(AREAS)}

    async def view(kind, id_, user, controls=None, panels=None, **kw):
        vm = await real_view(kind, id_, user, controls, panels)
        vm["provenance"]["sutra"] = "book-desk"
        vm["provenance"]["layout"] = "Sutra book-desk v1"
        if layout["value"] is not None:
            vm["layout"] = layout["value"]
        return vm

    async def send(method, path, ident=None, **kw):
        if not path.startswith("/me/arrange/"):
            return await real_send(method, path, ident, **kw)
        name = path.rsplit("/", 1)[1]
        if method == "PUT":
            kept[name] = kw["json"]
        elif method == "DELETE":
            kept.pop(name, None)
        return kept.get(name, {})

    monkeypatch.setattr(backend, "view", view)
    monkeypatch.setattr(backend, "_send", send, raising=False)
    return {"kept": kept, "layout": layout}


def test_a_grid_page_draws_its_areas(client, gridded):
    html = client.get("/v/trade/IRS-47102").text
    assert "data-sgrid" in html and 'class="vgrid sg sg-areas"' in html and "screen-grid.js" in html
    assert re.search(r'data-sg-area="header".*?id="p-built"', html, re.S) and re.search(r'data-sg-area="detail".*?data-sg-tab="refs"', html, re.S)
    assert 'class="vmain"' not in html and "data-layout-open" not in html          # layout mode (Alt+L) gives way to the grid
    assert "data-sg-arrange" in html and "Reset layout" in html
    cfg = json.loads(re.search(r'data-sg="([^"]*)"', html).group(1).replace("&#34;", '"').replace("&quot;", '"').replace("&amp;", "&"))
    assert cfg["picture"] == ["header header", "trades detail"] and cfg["split"]["left"] == "trades"


def test_a_page_without_a_layout_is_as_before(client, gridded):
    gridded["layout"]["value"] = None
    html = client.get("/v/trade/IRS-47102").text
    assert "data-sgrid" not in html and 'class="vmain"' in html


def test_sections_and_a_readers_arrangement(client, gridded):
    gridded["layout"]["value"] = {"sections": [{"id": "risk", "title": "Risk", "collapsed": False}], "arrange": False,
                                  "place": {"refs": {"section": "risk", "collapsible": True}, "built": {}}}
    r = client.put("/me/arrange/book-desk", json={"collapsed": {"risk": True}})
    assert r.status_code == 200 and gridded["kept"]["book-desk"] == {"collapsed": {"risk": True}}
    html = client.get("/v/trade/IRS-47102").text
    assert 'data-sg-section="risk"' in html and 'aria-expanded="false"' in html and "data-sg-arrange" not in html
    assert client.get("/me/arrange/book-desk").json() == {"collapsed": {"risk": True}}
    assert client.delete("/me/arrange/book-desk").status_code == 200 and "book-desk" not in gridded["kept"]
    assert client.get("/me/arrange/Bad Name").status_code in (400, 404)
