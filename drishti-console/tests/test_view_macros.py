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
    assert 'href="/help/panel-kinds#' in page and "data-export-panel=" in page


def test_provenance_banners_come_from_the_macro_and_the_embed_has_no_console_link(client, embed):
    env = client.app.state.templates.env
    parts = env.get_template("_macros/view.html").module
    vm = {"ref": {"id": "T-1"}, "provenance": {"source": "ledger", "stale": True, "updatedAt": "2026-10-06T10:00:00Z", "staleAfter": "PT5M"}}
    html = str(parts.provenance_banners(vm, "live", {}, True))
    assert "stale-banner" in html and "<b>ledger</b> is behind" in html and "5m" in html
    dated = {"ref": {"id": "T-1"}, "provenance": {"source": "ledger"}}
    assert "Back to live" in str(parts.provenance_banners(dated, "2026-10-01", {"selected": "2026-10-01"}, False, "/v/x"))
    assert "Back to live" not in str(parts.provenance_banners(dated, "2026-10-01", {"selected": "2026-10-01"}, True))
