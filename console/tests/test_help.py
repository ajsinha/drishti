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

"""Help centre and About: catalogue, rendering, in-help links, search, F1 context, and the About page."""
from pathlib import Path

import yaml

CONSOLE = Path(__file__).resolve().parent.parent


def test_every_catalogued_guide_exists_and_renders(client):
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    slugs = [g["slug"] for c in catalogue["categories"] for g in c["guides"]]
    index = client.get("/help")
    assert index.status_code == 200 and "Help &amp; documentation" in index.text
    for slug in slugs:
        r = client.get(f"/help/{slug}")
        assert r.status_code == 200, slug
        assert 'class="help-article"' in r.text, slug
    assert len(slugs) == len(set(slugs)) >= 25


def test_guides_render_examples_boxes_toc_and_internal_links(client):
    page = client.get("/help/getting-started").text
    assert 'class="help-example"' in page and "data-copy" in page
    assert 'class="help-box tip"' in page and "On this page" in page
    assert 'href="/help/using-the-terminal"' in page
    ref = client.get("/help/rachana-reference").text
    assert "DRS-2101" in ref and "panel kinds" in ref.lower()
    arch = client.get("/help/architecture").text
    assert 'href="/help/rachana-reference"' in arch


def test_search_and_missing_guides(client):
    r = client.get("/help/search", params={"q": "lockout"})
    assert r.status_code == 200 and "/help/user-management" in r.text
    missing = client.get("/help/no-such-guide")
    assert missing.status_code == 404 and "There is no guide" in missing.text


def test_f1_context_and_panel_help(client):
    assert client.get("/help/context/view", follow_redirects=False).headers["location"] == "/help/using-the-terminal"
    assert client.get("/help/context/studio", follow_redirects=False).headers["location"] == "/help/sutra-studio"
    view = client.get("/v/trade/IRS-48213").text
    assert 'data-screen="view"' in view and 'href="/help/panel-kinds#table"' in view
    kinds = client.get("/help/panel-kinds").text
    for kind in ("kv", "table", "tabs", "line", "area", "hbar", "ladder", "links", "status", "provenance", "markdown", "gauge"):
        assert f'id="{kind}"' in kinds, kind


def test_about_page(client):
    r = client.get("/about")
    assert r.status_code == 200
    for text in ("1.2.0", "21.0.12", "1h 2m", "irs-vanilla v3", "demo", "DRISHTI SOFTWARE LICENCE", "Copyright", "/help/notices"):
        assert text in r.text, text


def test_competitive_landscape(client):
    r = client.get("/about/competitive")
    assert r.status_code == 200
    for text in ("Competitive landscape", "Market data terminals", "Low-code internal tools", "Categories, not vendors",
                 "cmp cmp-yes", "cmp cmp-no", 'id="no-coded-screens"', "Market data content and analytics"):
        assert text in r.text, text
    assert "/about/competitive" in client.get("/about").text and "/about/competitive" in client.get("/help").text
