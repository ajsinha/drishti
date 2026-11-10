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

"""Drishti Genomics walked in a browser (DISTRIBUTIONS.md section 4): the thin kernel (drishti-kernel-app's jar, no banking) started with
``--distribution genomics`` and the genomics module's ``.dmod`` dropped into its modules folder (auto-deploy), a console in front of it.
Its Home, every top-bar link, the Research lens, the module's pages (the gene's lollipop drawn) and Help open clean (the page walk's
rules: no 4xx/5xx, no console error, nothing empty, within the budget); no finance is linked anywhere; the Elements gallery shows the
module's visual. Skipped without the thin jar (./mvnw -o package -pl drishti-kernel-app -am -DskipTests) or the module's package
(./mvnw -o package -pl modules/genomics -am)."""
from __future__ import annotations

import json
import time
import urllib.request

import pytest

from page_walk import dedupe, menu_links, new_page, open_page, report
from test_workbench_browser import browser  # noqa: F401 - the fixture
from wb_live import ROOT

FINANCE = ("/var", "/exposure", "/predeal", "/frtb", "/simm", "/mcube", "/play", "CPTY", "VRES", "TRD", "ECON", "USEQ")


def _thin_jar():
    found = sorted((ROOT / "drishti-kernel-app" / "target").glob("drishti-kernel-*-exec.jar"), key=lambda p: p.stat().st_mtime)
    return found[-1] if found else None


@pytest.fixture(scope="module")
def genomics(tmp_path_factory):
    import wb_live

    jar, dmod = _thin_jar(), ROOT / "modules" / "genomics" / "target" / "dmod" / "genomics-0.1.0.dmod"
    if jar is None or not dmod.is_file():
        pytest.skip("needs the thin kernel jar and the genomics package (see the module docstring)")
    work = tmp_path_factory.mktemp("genomics-server")
    modules = work / "drop"
    modules.mkdir()
    (modules / dmod.name).write_bytes(dmod.read_bytes())
    placement = work / "placement.yaml"
    placement.write_text("modules:\n  genomics:\n    parts:\n      api: { nodes: [local] }\n", encoding="utf-8")
    env = {"DRISHTI_DISTRIBUTION": "genomics", "DRISHTI_MODULES_PATH": str(modules), "DRISHTI_PLACEMENT_FILE": str(placement),
           "DRISHTI_MODULE_MANIFESTS_DIR": str(ROOT / "modules"), "DRISHTI_MODULES_AUTODEPLOY_SECONDS": "1", "DRISHTI_DEMO_ENABLED": "false",
           "DRISHTI_OFFLINE": "true"}
    before = wb_live.jar
    wb_live.jar = _thin_jar
    try:
        for st in wb_live._stack(tmp_path_factory, env, work=work):
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:          # auto-deploy picks the dropped package up within a few seconds
                try:
                    with urllib.request.urlopen(st.server + "/api/v1/genomics/cohorts", timeout=3) as r:
                        if json.loads(r.read()).get("count") == 2:
                            break
                except OSError:
                    pass
                time.sleep(0.5)   # wait-ok: polling the module's deployment
            else:
                pytest.fail("the genomics module did not deploy: " + (work / "server.log").read_text()[-600:])
            yield st
    finally:
        wb_live.jar = before


def test_drishti_genomics_walks_clean_with_no_finance_anywhere(genomics, browser):
    base = str(genomics)
    pg = new_page(browser)
    try:
        pg.goto(base + "/")
        assert "Drishti Genomics" in pg.content()
        links = menu_links(pg, base)
        lenses = [h for h in links if h.startswith("/lens/")]
        for lens in lenses:
            pg.goto(base + lens)
            links += menu_links(pg, base)
        pg.goto(base + "/lens/leave")
    finally:
        pg.context.close()
    links = dedupe(links)
    assert not [h for h in links for f in FINANCE if h == f or h.startswith(f + "?") or (f.isupper() and f in h)], links
    assert "/m/genomics/genes" in links and "/m/genomics/cohorts" in links
    pages = [h for h in links if not h.startswith("/lens/")] + ["/m/genomics/genes/TP53", "/m/genomics/variants/HX-A001",
                                                                 "/m/genomics/cohorts/COH-A", "/help"] + lenses
    pg = new_page(browser)
    try:
        results = [open_page(pg, base, h) for h in dedupe(pages)]
        pg.goto(base + "/m/genomics/genes/TP53")
        pg.wait_for_selector("[data-genomics-chart] canvas", timeout=20000)      # the lollipop, drawn by drishti-chart
        pg.wait_for_selector('[data-grid="variants"] [role="row"]', timeout=20000)
    finally:
        pg.context.close()
    bad, slow = report(results)
    assert not bad, "\n".join(bad)


def test_the_gallery_shows_the_modules_visual(genomics, browser):
    base = str(genomics)
    pg = new_page(browser)
    try:
        pg.goto(base + "/static/gallery/drishti_gallery.html#c=genomics.lollipop")
        pg.wait_for_function("() => window.__gallery && window.__gallery.demos.some((d) => d.id === 'genomics.lollipop')", timeout=30000)
        pg.wait_for_selector("drishti-chart canvas", timeout=20000)
        assert pg.evaluate("() => window.__gallery.id") == "genomics.lollipop"
    finally:
        pg.context.close()
