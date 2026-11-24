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
"""The Elements gallery's wiring in the console (docs/architecture/THIN_CONSOLE_DESIGN.md): /elements and /elements/gallery open the
vendored release tree's gallery page with the query kept and the hosted modules' visuals asked for, the page is a Build page (the edition
gate's build feature, Build -> Learn's menu item), and every file the page loads is there. The gallery itself is tested in the Drishti
Elements project."""
import re
from pathlib import Path

import yaml
from fastapi import FastAPI
from fastapi.testclient import TestClient

from core.edition import FEATURE_PATHS
from core import elements_pin as _pin
from routes import elements_routes

CONSOLE = Path(__file__).resolve().parents[1]
GALLERY = _pin.ROOT / "gallery"
PAGE = _pin.URL + "/gallery/drishti_gallery.html"


def test_the_routes_open_the_vendored_gallery_with_the_query_kept():
    app = FastAPI()
    app.include_router(elements_routes.router)
    c = TestClient(app)
    for path in ("/elements", "/elements/gallery"):
        r = c.get(path, follow_redirects=False)
        assert r.status_code == 302 and r.headers["location"] == PAGE + "?visuals=/api/elements/visuals"
    r = c.get("/elements?mode=docs&c=pivot&tab=api", follow_redirects=False)
    assert r.headers["location"] == PAGE + "?mode=docs&c=pivot&tab=api&visuals=/api/elements/visuals"


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
