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

"""The risk cubes are Pivot presets (config/pivot-presets.yaml; docs/guides/PIVOT_GUIDE.md › Presets: the risk cubes): one grid, several doors.

**Parity.** ``fixtures/cube_presets/mcube_before.json`` was recorded from the MR cube's own page (``/mcube``, before it became the preset) for
eight typed MCUBE commands: the source, the lens, the view laid over the landing, the saved view and the date the pivot screen asked the server
for, and the screen's title. The same commands now lead to ``/pivot?preset=mr``; the screen must ask the server for exactly the same view (the
grid's every figure is the server's answer to that view, as the person: the same request, the same answers). Then the doors: the MCUBE word, the
VaR explorer's link and the Views menu open the preset; the Counterparties menu opens the CCR cube, marked under construction."""
from __future__ import annotations

import html
import json
import re
from pathlib import Path

import pytest

from core import pivot_presets
from test_play import RISK_ABOUT, _app

BEFORE = json.loads((Path(__file__).parent / "fixtures" / "cube_presets" / "mcube_before.json").read_text(encoding="utf-8"))
ATTRS = ("data-source", "data-lens", "data-preset", "data-view-id", "data-date")


def asked(page: str) -> dict:
    """What the pivot screen asks the server for: the attributes of its root, the preset as data, and the screen's title."""
    m = re.search(r'<div class="pv[^"]*" data-pivot-screen([^>]*)>', page)
    assert m, "no pivot screen"
    out = {}
    for a in ATTRS:
        mm = re.search(" " + a + r'="([^"]*)"', m.group(1))
        out[a] = mm.group(1) if mm else None
    if out["data-preset"]:
        out["data-preset"] = json.loads(html.unescape(out["data-preset"]))
    out["title"] = re.search(r"<title>([^<]*)</title>", page).group(1).split(" · ")[0]
    return out


@pytest.mark.parametrize("typed", sorted(BEFORE))
def test_the_mr_preset_asks_the_server_for_exactly_what_the_cube_did(backend, monkeypatch, typed):
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/go", params={"q": typed}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/pivot?preset=mr")
    page = c.get(r.headers["location"]).text
    assert asked(page) == BEFORE[typed], typed
    assert 'data-preset-name="mr"' in page and "drishti_mr-lens.js" in page


def test_the_cubes_own_page_is_gone_and_its_address_leads_to_the_preset(backend, monkeypatch):
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    r = c.get("/mcube", params={"text": "DESK:RATES BY MGMT.BOOK", "date": "2026-09-30"}, follow_redirects=False)
    assert r.status_code == 308 and r.headers["location"] == "/pivot?preset=mr&text=DESK%3ARATES+BY+MGMT.BOOK&date=2026-09-30"
    src = (Path(__file__).resolve().parents[1] / "routes" / "mcube_routes.py").read_text(encoding="utf-8")
    assert "render(" not in src and "import render" not in src                                  # no page of its own: the preset is the Pivot's
    tpl = (Path(__file__).resolve().parents[1] / "web" / "templates" / "terminal" / "pivot.html").read_text(encoding="utf-8")
    assert "{{ cube.title }}" in tpl and ">MR cube<" not in tpl and ">MCUBE<" not in tpl                    # the screen's name is the preset's


def test_the_presets_are_defined_in_configuration():
    mr, ccr = pivot_presets.get("mr"), pivot_presets.get("ccr")
    assert (mr.title, mr.word, mr.source, mr.lens, mr.help, mr.engine) == ("MR cube", "MCUBE", "var", "mr", "mcube", "mr-cube")
    assert (ccr.title, ccr.under_construction, ccr.source) == ("CCR cube", "ccr-cube-preset", "")
    assert pivot_presets.get("nothing") is None


def test_the_ccr_preset_is_under_construction_behind_its_counterparties_door(backend, monkeypatch):
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    page = c.get("/pivot", params={"preset": "ccr"}).text
    assert "<title>CCR cube" in page and "data-cube-building" in page and 'data-uc="ccr-cube-preset"' in page and 'href="/exposure"' in page
    assert 'data-preset-name="ccr"' in page and 'data-source=""' in page
    reg = (Path(__file__).resolve().parents[2] / "config" / "under-construction.yaml").read_text(encoding="utf-8")
    assert "id: ccr-cube-preset" in reg and "menus: [ccr-cube]" in reg
    menus = (Path(__file__).resolve().parents[1] / "config" / "menus.yaml").read_text(encoding="utf-8")
    assert 'id: ccr-cube, cmd: CCUBE, href: "/pivot?preset=ccr"' in menus
    assert pivot_presets.command("CCUBE <GO>".split()) == "/pivot?preset=ccr"
    r = c.get("/go", params={"q": "CCUBE <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/pivot?preset=ccr" and 'id: mcube, cmd: MCUBE, href: "/pivot?preset=mr"' in menus


def test_the_var_explorer_opens_its_scope_in_the_mr_preset(backend, monkeypatch):
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    page = c.get("/var", params={"scope": "DESK:RATES"}).text
    m = re.search(r'<a class="vx-link vx-cube" data-vx-cube href="([^"]*)"', page)
    assert m and html.unescape(m.group(1)) == "/pivot?preset=mr&text=DESK%3ARATES"
    js = (Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "drishti_var-explorer.js").read_text(encoding="utf-8")
    assert '"/pivot?preset=mr&text=" + encodeURIComponent' in js
