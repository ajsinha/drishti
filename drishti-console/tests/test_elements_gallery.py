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
"""The Elements gallery's wiring in the console (ELEMENTS.md section 10): /elements and /elements/gallery open the static page with the
query kept, the page is a Build page (the edition gate's build feature, Build -> Learn's menu item), and every file the page loads is
there. The gallery itself is tested in Node (tests/elements/gallery.test.mjs) and in a browser (test_elements_gallery_browser.py)."""
import re
from pathlib import Path

import yaml
from fastapi import FastAPI
from fastapi.testclient import TestClient

from core.edition import FEATURE_PATHS
from routes import elements_routes

CONSOLE = Path(__file__).resolve().parents[1]
GALLERY = CONSOLE / "web" / "static" / "gallery"


def test_the_routes_open_the_static_gallery_with_the_query_kept():
    app = FastAPI()
    app.include_router(elements_routes.router)
    c = TestClient(app)
    for path in ("/elements", "/elements/gallery"):
        r = c.get(path, follow_redirects=False)
        assert r.status_code == 302 and r.headers["location"] == "/static/gallery/drishti_gallery.html"
    r = c.get("/elements?mode=docs&c=pivot&tab=api", follow_redirects=False)
    assert r.headers["location"] == "/static/gallery/drishti_gallery.html?mode=docs&c=pivot&tab=api"


def test_it_is_a_build_page_listed_under_build_learn():
    assert "/elements" in FEATURE_PATHS["build"]
    menus = yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))
    items = [i for m in menus["menus"] for col in m.get("columns") or [] for i in col.get("items") or []]
    item = next(i for i in items if i.get("id") == "elements-gallery")
    assert item["href"] == "/elements" and item["requires"] == ["feature:build"]


def test_every_file_the_page_loads_is_there_and_nothing_is_inline():
    html = (GALLERY / "drishti_gallery.html").read_text(encoding="utf-8")
    refs = re.findall(r'(?:src|href)="([^"]+)"', html)
    assert refs
    for ref in refs:
        assert (GALLERY / ref).resolve().is_file(), ref
    assert not re.search(r"<script>|<style|\sstyle=|\son[a-z]+=", html)       # a strict CSP holds: no inline script, style or handler
