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
module's visual. Skipped without the thin jar (./mvnw -o package -pl :drishti-kernel-app -am -DskipTests) or the module's package
(./mvnw -o package -pl modules/genomics/java/genomics -am)."""
from __future__ import annotations

import json
import time
import urllib.request

import pytest
from core import elements_pin

from page_walk import dedupe, menu_links, new_page, open_page, report
from test_workbench_browser import browser  # noqa: F401 - the fixture
from wb_live import ROOT

FINANCE = ("/var", "/exposure", "/predeal", "/frtb", "/simm", "/mcube", "/play", "CPTY", "VRES", "TRD", "ECON", "USEQ")


def _thin_jar():
    found = sorted((ROOT / "packaging/drishti-kernel-app" / "target").glob("drishti-kernel-*-exec.jar"), key=lambda p: p.stat().st_mtime)
    return found[-1] if found else None


@pytest.fixture(scope="module")
def genomics(tmp_path_factory):
    import wb_live

    jar, dmod = _thin_jar(), ROOT / "modules" / "genomics" / "java" / "genomics" / "target" / "dmod" / "genomics-0.1.0.dmod"
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
        pg.goto(base + elements_pin.URL + "/gallery/drishti_gallery.html?visuals=/api/elements/visuals#c=genomics.lollipop")   # the vendored gallery, asked for the modules' visuals
        pg.wait_for_function("() => window.__gallery && window.__gallery.demos.some((d) => d.id === 'genomics.lollipop')", timeout=30000)
        pg.wait_for_selector("drishti-chart canvas", timeout=20000)
        assert pg.evaluate("() => window.__gallery.id") == "genomics.lollipop"
    finally:
        pg.context.close()


ANSWERS = ROOT / "modules" / "genomics" / "java" / "genomics" / "src" / "test" / "resources" / "showcase" / "answers.json"


def _showcase_api(pg, asked):
    """The module's API answered from the server's own answers over its test fixture (ShowcaseTest keeps answers.json equal to them):
    the thin server here has no showcase packs, so the screens are drawn from those answers; every call is recorded."""
    answers = json.loads(ANSWERS.read_text(encoding="utf-8"))

    def handle(route):
        url = route.request.url
        path = url.split("/m/genomics/api/", 1)[1]
        asked.append(path)
        key = path.split("?", 1)[0]
        body = answers.get(key)
        if body is None:                                # a call with an id: its kind's answer (explain/variant/<id> -> explain/variant/)
            parts = key.split("/")
            for n in range(len(parts) - 1, 0, -1):
                body = answers.get("/".join(parts[:n]) + "/")
                if body is not None:
                    break
        if body is None:
            route.fulfill(status=404, content_type="application/json", body=json.dumps({"detail": "not in the fixture: " + key}))
        else:
            route.fulfill(status=200, content_type="application/json", body=json.dumps(body))
    pg.route("**/m/genomics/api/**", handle)


def test_the_showcase_screens_draw_link_and_explain(genomics, browser):
    """The board, its guided mode and the workbench (GENOMICS_SHOWCASE.md): every card drawn by Elements, a cluster and a rectangle sent
    as the next calls' selection, a lollipop head's Explain filling the records, Play the rounds and the leaderboard following the round,
    a missing pack said in words."""
    base = str(genomics)
    pg = new_page(browser)
    asked: list[str] = []
    try:
        errors: list[str] = []
        pg.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        pg.goto(base + "/m/genomics/showcase")                    # the real server first: no showcase packs, said in words
        pg.wait_for_selector("[data-showcase-missing]", timeout=20000)
        assert "association" in pg.get_attribute("[data-showcase-missing]", "data-showcase-missing")
        _showcase_api(pg, asked)
        pg.goto(base + "/m/genomics/showcase")
        pg.wait_for_selector("[data-showcase][data-state=ready]", timeout=30000)
        for c in ("manhattan", "oncoprint", "map", "lollipop"):
            pg.wait_for_selector(f'[data-chart="{c}"] canvas', timeout=20000)
        assert pg.text_content('[data-figure="samples"]').strip() == "60"
        pg.click("[data-showcase-clusters] button:has-text('C3 immune-hot')")
        pg.wait_for_function("() => document.querySelector('[data-showcase]').dataset.state === 'ready'")
        assert any(a.startswith("cohorts/ONC-1/oncoprint") and "cluster=C3%20immune-hot" in a for a in asked), asked
        canvas = pg.locator('[data-chart="map"] canvas')
        canvas.scroll_into_view_if_needed()                       # the mouse acts inside the viewport only: the map may sit below it
        box = canvas.bounding_box()
        pg.mouse.move(box["x"] + box["width"] * 0.3, box["y"] + box["height"] * 0.3)
        pg.mouse.down()
        pg.mouse.move(box["x"] + box["width"] * 0.7, box["y"] + box["height"] * 0.7, steps=6)
        pg.mouse.up()
        pg.wait_for_function("() => document.querySelector('[data-showcase]').dataset.state === 'ready'")
        assert any("box=" in a for a in asked), asked
        pg.click('[data-chart="lollipop"] [data-tool="explain"]')
        pg.wait_for_selector('[data-grid="records"] [role="row"]', timeout=20000)
        assert any(a.startswith("explain/variant/") for a in asked), asked

        pg.goto(base + "/m/genomics/showcase/guided")
        pg.wait_for_selector("[data-showcase][data-state=ready]", timeout=30000)
        assert pg.is_visible('[data-card="manhattan"]') and not pg.is_visible('[data-card="map"]')
        pg.click('[data-tour="next"]')
        assert pg.is_visible('[data-card="map"]') and not pg.is_visible('[data-card="manhattan"]')
        assert pg.text_content('[data-tour="at"]') == "Step 2 of 4"

        asked.clear()
        pg.goto(base + "/m/genomics/proteins/HLXK7")
        pg.wait_for_selector("[data-showcase][data-state=ready]", timeout=30000)
        for c in ("dms", "backbone", "landscape", "rounds"):
            pg.wait_for_selector(f'[data-chart="{c}"] canvas', timeout=20000)
        play = pg.locator('[data-chart="landscape"] [data-tool="play"]')
        assert "Play the rounds" in play.text_content()
        pg.eval_on_selector('[data-chart="landscape"] .dc-frame-slider', "(el) => { el.value = '0'; el.dispatchEvent(new Event('input', { bubbles: true })); }")
        pg.wait_for_function("() => document.querySelector('[data-chart=landscape] .dc-frame-label').textContent.startsWith('Round 1')")
        pg.wait_for_selector('[data-grid="leaderboard"] [role="row"]', timeout=20000)
        assert any(a.startswith("proteins/HLXK7/leaderboard") and "round=1" in a for a in asked), asked
        assert not [e for e in errors if "favicon" not in e], errors
    finally:
        pg.context.close()
