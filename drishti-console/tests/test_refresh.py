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

"""Refresh and freshness on a view page (SUTRA_NEXT.md 9), the console's half: a refreshing panel carries its interval (data-refresh) and
whether the reader may pause it; the page carries the fastest any panel may go (views.refresh_min_seconds), its freshness facts and the
reader's choices for this screen; the panels endpoint answers with the provenance the freshness line reads; the designer's refresh step
writes refresh: on one part of the page. The browser half is test_refresh_browser.py."""
import json
import re

import pytest

from core import designer
from core.app import create_app
from core.config import Settings, load_settings
from conftest import CONSOLE


def _panel(client, p) -> str:
    return str(client.app.state.templates.env.get_template("_macros/panels.html").module.panel(p))


def test_a_refreshing_panel_says_its_interval_and_whether_it_may_be_paused(client):
    base = {"id": "curve", "kind": "kv", "title": "Curve", "data": {"fields": []}}
    assert "data-refresh" not in _panel(client, base)
    html = _panel(client, {**base, "refresh": {"every": 30, "userCanPause": True}})
    assert 'data-refresh="30"' in html and 'data-refresh-pause="1"' in html
    assert 'data-refresh-pause="0"' in _panel(client, {**base, "refresh": {"every": 30, "userCanPause": False}})


def test_the_page_carries_the_minimum_the_screen_and_the_readers_choices(client, backend, monkeypatch):
    async def settings(ident=None):
        return {"refresh": {"trade/irs-fixfloat": {"all": True, "panels": {"flows": {"paused": True, "every": 60}}}}}
    monkeypatch.setattr(backend, "settings", settings)
    page = client.get("/v/trade/IRS-47102").text
    view = re.search(r"<div class=\"view\" data-view[^>]*>", page, re.S).group(0)
    assert 'data-refresh-min="5"' in view and "refresh.js" in page and "refresh.css" in page
    screen = re.search(r'data-screen="([^"]+)"', view).group(1)
    assert screen.startswith("trade/")
    prefs = json.loads(re.search(r'data-refresh-prefs="([^"]*)"', view).group(1).replace("&#34;", '"').replace("&quot;", '"'))
    assert prefs == ({"all": True, "panels": {"flows": {"paused": True, "every": 60}}} if screen == "trade/irs-fixfloat" else {})


def test_the_minimum_is_configurable_and_bounded():
    for given, used in (("2", 2), ("0", 1), ("nonsense", 5)):
        data = load_settings(CONSOLE / "config").as_dict()
        data["views"] = {"refresh_min_seconds": given}
        app = create_app(Settings(data))
        assert app.state.templates.env.globals["REFRESH_MIN"] == used


def test_the_panels_endpoint_answers_with_the_provenance(client):
    r = client.get("/v/trade/IRS-47102/panels?panels=-")
    assert r.status_code == 200
    prov = r.json()["provenance"]
    assert set(prov) == {"source", "fetchedAt", "updatedAt", "staleAfter", "stale", "live", "generation"}


PROV = {"source": "quote-store", "kind": "quote", "fetchedAt": "2026-10-23T10:40:00Z", "updatedAt": "2026-10-23T10:39:00Z",
        "staleAfter": "PT2M", "stale": True}


def test_a_panel_with_its_own_source_carries_that_sources_provenance(client, backend, monkeypatch):
    base = {"id": "quote", "kind": "kv", "title": "Quote", "data": {"fields": []}}
    assert "data-prov" not in _panel(client, base)
    html = _panel(client, {**base, "provenance": PROV})
    prov = json.loads(re.search(r'data-prov="([^"]*)"', html).group(1).replace("&#34;", '"').replace("&quot;", '"'))
    assert prov == PROV

    real = backend.view

    async def view(*a, **kw):                              # the server's view: its first panel reads a source of its own
        vm = await real(*a, **kw)
        vm["panels"][0]["provenance"] = PROV
        return vm
    monkeypatch.setattr(backend, "view", view)
    out = client.get("/v/trade/IRS-47102/panels").json()["panels"]
    assert out[0]["provenance"] == PROV and 'data-prov="' in out[0]["html"]
    assert all("provenance" not in p for p in out[1:])      # the others: the page's provenance stands for them


def test_the_designer_writes_refresh_on_one_part_and_takes_it_off():
    doc = {"panels": [{"id": "a", "kind": "kv"}, {"use": "data-provenance@1"}]}
    designer.refresh(doc, 1, 30)
    assert doc["panels"][1]["refresh"] == 30 and designer.refresh_of(doc["panels"][1]) == 30
    assert designer.refresh_of({"refresh": {"every": 15, "user-can-pause": False}}) == 15
    assert designer.refresh_of({"refresh": True}) == 0
    designer.refresh(doc, 1, 0)
    assert "refresh" not in doc["panels"][1]
    for bad in ("soon", -1, 90000):
        with pytest.raises(designer.DesignerError):
            designer.refresh(doc, 0, bad)
    with pytest.raises(designer.DesignerError):
        designer.refresh(doc, 5, 30)
