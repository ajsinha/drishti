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

"""The view parts as macros (ELEMENTS.md step 5): the full page and the embed payload are rendered by the same templates,
so a panel's HTML is the same in both (the embed drops only the console-only help link and CSV download)."""
import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings
from test_embed import HOST, make_client, token

KIND, ID = "trade", "IRS-47102"
AFFORDANCES = re.compile(r'<a class="pnl-help" data-export-panel[^>]*>.*?</a>', re.S)   # the CSV download: console-only


@pytest.fixture
def embed(backend):
    return make_client(backend)


def sections(html: str) -> dict:
    """Every top-level panel of a page by id, as the exact text from its <section> to its balanced </section>."""
    found = {}
    for m in re.finditer(r'<section class="pnl[^"]*" id="p-([^"]+)"', html):
        depth, pos = 0, m.start()
        for tag in re.finditer(r"<section\b|</section>", html[pos:]):
            depth += 1 if tag.group().startswith("<section") else -1
            if depth == 0:
                found[m.group(1)] = html[pos:pos + tag.end()]
                break
    return found


def test_a_panel_is_the_same_html_on_the_console_page_and_in_the_embed_payload(client, embed):
    page = sections(client.get(f"/v/{KIND}/{ID}").text)
    tok = token()
    body = embed.get(f"/embed/v1/views/{KIND}/{ID}", headers={"Authorization": f"Bearer {tok}", "Origin": HOST}).json()
    assert body["panels"] and page
    compared = 0
    for p in body["panels"]:
        if p["id"] not in page:
            continue
        # the panel ? stays (the element's About hints attach their popover to it); the CSV export is console-only
        assert "pnl-help" in p["html"] and "data-export-panel" not in p["html"]
        assert AFFORDANCES.sub("", page[p["id"]]) == p["html"], p["id"]
        compared += 1
    assert compared == len(body["panels"]) >= 2


def test_the_title_and_strip_are_the_page_s(client, embed):
    page = client.get(f"/v/{KIND}/{ID}").text
    tok = token()
    body = embed.get(f"/embed/v1/views/{KIND}/{ID}", headers={"Authorization": f"Bearer {tok}", "Origin": HOST}).json()
    strip = re.search(r'<dl class="strip">.*?</dl>', body["head"], re.S).group()
    assert strip in page                                              # the same macro, the same bytes
    assert f'<h1 class="vid mono">{body["title"]["id"]}</h1>' in page and f'<h1 class="vid mono">{body["title"]["id"]}</h1>' in body["head"]


def test_the_console_page_keeps_its_help_links_and_downloads(client):
    page = client.get(f"/v/{KIND}/{ID}").text
    assert 'href="/help/panels#' in page and "data-export-panel=" in page


def test_provenance_banners_come_from_the_macro_and_the_embed_has_no_console_link(client, embed):
    env = client.app.state.templates.env
    parts = env.get_template("_macros/view.html").module
    vm = {"ref": {"id": "T-1"}, "provenance": {"source": "ledger", "stale": True, "updatedAt": "2026-10-06T10:00:00Z", "staleAfter": "PT5M"}}
    html = str(parts.provenance_banners(vm, "live", {}, True))
    assert "stale-banner" in html and "<b>ledger</b> is behind" in html and "5m" in html
    dated = {"ref": {"id": "T-1"}, "provenance": {"source": "ledger"}}
    assert "Back to live" in str(parts.provenance_banners(dated, "2026-10-01", {"selected": "2026-10-01"}, False, "/v/x"))
    assert "Back to live" not in str(parts.provenance_banners(dated, "2026-10-01", {"selected": "2026-10-01"}, True))
    partial = {"ref": {"id": "BOOK-X"}, "provenance": {"source": "trade-pages", "partial": True,
                                                       "missing": ["node drishti-c (dead): not asked: it cannot be reached now"]}}
    html = str(parts.provenance_banners(partial, "live", {}, True))          # Drishti*net: a grouped page with a node down says so
    assert "data-partial" in html and "<b>Partial</b>" in html and "drishti-c (dead)" in html
    assert "data-partial" not in str(parts.provenance_banners(vm, "live", {}, True))


def test_how_this_view_was_built_links_its_sutra_to_the_designer_and_its_source_to_the_connector():
    """The provenance panel: the Sutra that drew the page opens in the designer, the connector it was read from opens on its page."""
    from jinja2 import Environment, FileSystemLoader
    env = Environment(loader=FileSystemLoader(str(CONSOLE / "web" / "templates")), extensions=["jinja2.ext.do"])
    t = env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    fields = [{"label": "Layout", "text": "Sutra endur-comswap v2 + inference"}, {"label": "Fingerprint", "text": "a1b2"},
              {"label": "Source", "text": "trade-endur, gen 4"}]
    h = t.render(p={"id": "built", "kind": "provenance", "title": "How this view was built", "data": {"fields": fields}})
    assert '<a data-prov-sutra href="/studio?sutra=endur-comswap%402"' in h and ">endur-comswap v2</a> + inference" in h
    assert '<a data-prov-source href="/admin/connectors/trade-endur"' in h and ">trade-endur</a>, gen 4" in h
    fields[0]["text"] = "inferred from the shape of the document"
    h = t.render(p={"id": "built", "kind": "provenance", "title": "How this view was built", "data": {"fields": fields}})
    assert "data-prov-sutra" not in h and "inferred from the shape of the document" in h
    fields[0]["text"] = "Board trading-irs-fixfloat (from Sutra irs-fixfloat v1)"      # a page served from its converted board (12.4)
    h = t.render(p={"id": "built", "kind": "provenance", "title": "How this view was built", "data": {"fields": fields}})
    assert '<a data-prov-board href="/build/board/trading-irs-fixfloat"' in h and "(from Sutra irs-fixfloat v1)" in h
    assert "studio?sutra=trading-irs-fixfloat" not in h


def test_the_about_layout_of_a_board_served_page_opens_the_board_and_its_sutra():
    """About, layer 4: a page served from its board links the board (Board mode) and the loaded Sutra it was converted from."""
    from jinja2 import Environment, FileSystemLoader
    env = Environment(loader=FileSystemLoader(str(CONSOLE / "web" / "templates")), extensions=["jinja2.ext.do"])
    t = env.from_string('{% from "_macros/about.html" import layer_layout %}{{ layer_layout(ex, True) }}')
    ex = {"ref": {"kind": "trade", "id": "MX-1"}, "layout": {"label": "Board trading-irs-fixfloat (from Sutra irs-fixfloat v1)",
          "board": "trading-irs-fixfloat", "sutra": {"name": "irs-fixfloat", "version": 1, "priority": 10}}}
    h = t.render(ex=ex)
    assert 'data-about-board href="/build/board/trading-irs-fixfloat"' in h
    assert 'href="/studio?sutra=irs-fixfloat%401&kind=trade&id=MX-1"' in h and "trading-irs-fixfloat%401" not in h
