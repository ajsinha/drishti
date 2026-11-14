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

"""One Explain for every number (docs/architecture/SHARED_SCREENS.md section 3) in a real browser, over the market-risk pack's committed
VaR data built into a scratch lake (its three business dates, moved to end today), the demo connector off:

- old against new on the demo data: every row the VaR page's How calculated box drew (its own code, in Python below), every line the
  parametric ? showed and, when the server still answers the removed endpoints (a parity build), the Why it moved and MR cube answers,
  are the one Explain's, the same figures;
- a click on every button: the hover preview and its four links, the drawer's four tabs, the view switch and the dates of Why it moved,
  the Workbook's links (the .xlsx answers), Open as a page (four parts, Print, Back), the keys E, ? and Esc, the parametric ?;
- VAR EXPLAIN BANK opens the page at Why it moved, painted by the renderer; on a phone the drawer is a sheet, nothing scrolls sideways.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from test_var_explorer_browser import open_var, painted, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from var_stack import VAR_TRADES, var_data, var_lake, var_server


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    import os
    keep = os.environ.get("DRISHTI_TEST_EXPLAIN_WORK")           # a lake built before, reused (debugging: the build takes minutes)
    if keep and (Path(keep) / "delta" / "risk" / "scenario-pv").is_dir():
        lake = Path(keep) / "delta"
    else:
        lake = var_lake(tmp_path_factory, var_data(*VAR_TRADES))   # the committed three dates, ending today (the last weekday)
    yield from var_server(tmp_path_factory, lake, shared=not keep)   # reads only: the pool guards it


def get(url: str):
    """A JSON answer, or (status, None) when refused."""
    try:
        with urllib.request.urlopen(url, timeout=180) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, None


def explain(stack, **q) -> dict:
    status, body = get(str(stack) + "/api/explain?" + urllib.parse.urlencode(q))
    assert status == 200, (status, q)
    return body


# ---- the old panels' own code, in Python: what they drew from the answers they were given --------------------------------------------
def i(v) -> str:
    return "—" if v is None else f"{round(v):,}"


def old_how_calculated(d: dict) -> dict:
    """drishti_var-explorer.js renderProv (2.9.11.b to 2.9.11.d): the box's rows from the scope answer, the volatile Computed left out."""
    p, fx = d["provenance"], d["provenance"].get("fx") or {}
    systems = "; ".join(f"{k}: {'scenario PVs' if x.get('form') == 'pv' else 'P&L vectors'} per {x.get('grain')} ({i(x.get('rows'))})"
                        for k, x in sorted((p.get("inputsBySystem") or {}).items())) or "one form a trade"
    intra = p.get("intraday")
    rej = p.get("rejections") or []
    return {
        "Scope": d["kind"] + " " + d["label"], "Reporting currency": (p.get("currency") or "USD") + ", chosen in the header",
        "Input": p.get("input") or "P&L vectors (USD)", "By source system": systems,
        "Intraday": (f"{i(intra['updates'])} updates of {i(intra['trades'])} trades in {i(intra['batchCount'])} batches, latest {intra['lastAt']}; "
                     "the official records are the morning run's") if intra else "none: the morning run",
        "Scenario FX": fx.get("scenarioFx") or "none needed", "Cross rates": "via USD",
        "Resolved": "; ".join(p.get("resolved") or []) or p["scope"], "Positions": i(p["positions"]),
        "Trades": i(p["trades"]) + (f" · {p['failed']} without a vector" if p.get("failed") else ""),
        "Outside your view": i(p["hidden"]) + " trades not included" if p.get("hidden") else "none",
        "Unknown ids": ", ".join(p.get("unknown") or []) or "none", "Warnings": "; ".join(p.get("warnings") or []) or "none",
        "Rows left out": i(p.get("rejected") or 0) + " rejected" + (" (" + "; ".join(rej[:3]) + ")" if rej else "") + " · " + i(p.get("excluded") or 0)
        + " not live",
        "Vectors": p["vectors"] + (" · " + p["tier"] if p.get("tier") else ""), "Scenario set": p["scenarioSet"], "Method": p["method"],
        "ES": p["es"], "Components": p["components"], "Positions by": p.get("positionRules") or "", "Engine": p["engine"], "View": p["view"],
        "Members": p["membershipHash"]}


def old_question_mark(measure: str, pm: dict) -> list:
    """drishti_var-parametric.js headline (2.9.11.c to 2.9.11.d): the lines each ? showed."""
    if measure != "pgap":
        return pm["describe"]
    return [pm["describe"][0].replace("Parametric VaR", "Fat-tail gap")] + [l for l in pm["describe"] if l.startswith("Fat-tail gap")] + (pm.get("notes") or [])


def without(a: dict, *keys) -> dict:
    return {k: v for k, v in a.items() if k not in keys}


def test_old_against_new_on_the_demo_data(stack):
    for scope in ("BANK", "DESK:DESK-RATES", "NODE:MGMT~DESK-FX"):
        status, head = get(str(stack) + "/api/var/scope?" + urllib.parse.urlencode({"scope": scope, "parametric": "true"}))
        assert status == 200, scope
        x = explain(stack, kind="var", measure="var", scope=scope)
        assert x["value"] == head["var"] and x["asOf"] == head["businessDate"], scope
        rows = {r["label"]: r["value"] for r in x["inputs"]["rows"]}
        for label, value in old_how_calculated(head).items():
            assert rows.get(label) == value, (scope, label, rows.get(label), value)
        es = explain(stack, kind="var", measure="es", scope=scope, part="formula")
        assert es["value"] == head["es"]
        pm = head.get("parametric")
        if pm:
            for measure in ("pvar", "pes", "pgap"):
                p = explain(stack, kind="var", measure=measure, scope=scope, part="formula")
                assert p["formula"]["lines"] == old_question_mark(measure, pm), (scope, measure)
                assert p["value"] == pm[{"pvar": "var", "pes": "es", "pgap": "gap"}[measure]]
        # Why it moved: the engine's answer; against the removed endpoints when this server still has them (a parity build)
        new = explain(stack, kind="var", scope=scope, part="why")["why"]
        assert new["available"] and new["addsUp"]
        status, old = get(stack.server + "/api/v1/risk/var/explain?" + urllib.parse.urlencode({"scope": scope}))
        if status == 200:
            assert without(new["answer"], "computeMs") == without(old, "computeMs"), scope
            status, mr = get(stack.server + "/api/v1/risk/mr/explain?" + urllib.parse.urlencode({"scope": scope}))
            if status == 200:
                assert without(new["answer"], "computeMs") == without(mr, "computeMs", "factorOverlay"), scope
            m_new = explain(stack, kind="var", scope=scope, part="why", view="matrix")["why"]["answer"]["matrix"]
            status, m_old = get(stack.server + "/api/v1/risk/var/explain/matrix?" + urllib.parse.urlencode({"scope": scope}))
            assert status == 200 and m_new == m_old, scope
        else:                                                                  # removed: the answer still ends at each date's VaR
            a = new["answer"]
            _, h1 = get(str(stack) + "/api/var/scope?" + urllib.parse.urlencode({"scope": scope, "asOf": a["from"]}))
            assert abs(a["varFrom"] - h1["var"]) < 0.5 and abs(a["varTo"] - head["var"]) < 0.5, scope
        wf = [p for p in new["panels"] if p["id"] == "waterfall"][0]["data"]["steps"]
        assert [s["value"] for s in wf[1:-1]] == [l["effect"] for l in new["answer"]["lines"]]


def drawer_ready(pg, tab: str):
    wait(pg, "(() => { const d = document.querySelector('[data-explain-drawer]'); return d && !d.hidden && d.dataset.state === 'ready'"
             " && d.dataset.tab === %r; })()" % tab, 180)


def test_every_button_of_the_one_explain(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK", {"width": 1400, "height": 950})
    painted(pg, "BANK")
    tile = pg.locator('.vx-tile[data-tile="0"] .v')
    assert tile.get_attribute("data-explain") and "dx-number" in tile.get_attribute("class")
    # the preview: hover the VaR tile
    tile.hover()
    wait(pg, "(() => { const p = document.querySelector('[data-explain-preview]'); return p && !p.hidden && p.dataset.state === 'ready'; })()", 60)
    card = pg.locator("[data-explain-preview]")
    assert "worst of" in card.inner_text() and "VaR" in card.inner_text()
    assert card.locator("[data-explain-link]").count() == 4
    shot(pg, "explain-preview")
    # its link Inputs opens the drawer at Inputs & lineage
    card.locator('[data-explain-link="inputs"]').click()
    drawer_ready(pg, "inputs")
    drawer = pg.locator("[data-explain-drawer]")
    for label in ("Scope", "Positions", "Trades", "Scenario set", "Method", "Engine", "Lineage"):
        assert label.lower() in drawer.inner_text().lower(), label
    # every tab
    for tab in ("formula", "inputs", "why", "workbook"):
        pg.locator(f'[data-dx-tab="{tab}"]').click()
        drawer_ready(pg, tab)
        assert pg.get_attribute(f'[data-dx-tab="{tab}"]', "aria-selected") == "true"
    pg.locator('[data-dx-tab="formula"]').click()
    drawer_ready(pg, "formula")
    assert "VaR = −(k-th worst of" in drawer.inner_text() and "var()" in drawer.inner_text()
    assert "inputs complete" in drawer.inner_text()                          # the checks
    # Why it moved: painted by the renderer, its three views, its dates
    pg.locator('[data-dx-tab="why"]').click()
    drawer_ready(pg, "why")
    wait(pg, "document.querySelector('[data-dx-why]') && document.querySelector('[data-dx-why]').dataset.painted === 'waterfall'", 180)
    shot(pg, "explain-why-waterfall")
    for view in ("matrix", "sentences", "waterfall"):
        pg.locator(f'[data-dx-view="{view}"]').click()
        drawer_ready(pg, "why")
        wait(pg, "document.querySelector('[data-dx-why]').dataset.painted === %r" % view, 180)
        assert pg.get_attribute(f'[data-dx-view="{view}"]', "aria-pressed") == "true"
    frm = pg.input_value("[data-dx-from]")
    assert frm
    pg.locator(".dx-dates button[type=submit]").click()
    drawer_ready(pg, "why")
    # Open as a page sits in the panel's header beside the title (seen without scrolling) and carries the scope, the compare date and the tab
    btn = pg.locator(".dx-head [data-dx-page]")
    assert btn.is_visible() and pg.locator(".dx-foot [data-dx-page]").count() == 0
    box = btn.bounding_box()
    assert box and 0 <= box["y"] < 160, box
    href = btn.get_attribute("href")
    assert "scope=BANK" in href and "tab=why" in href and ("from=" + frm) in href, href
    # the Workbook: its links, the .xlsx answers
    pg.locator('[data-dx-tab="workbook"]').click()
    drawer_ready(pg, "workbook")
    hrefs = pg.eval_on_selector_all("[data-dx-workbook]", "els => els.map(e => e.getAttribute('href'))")
    assert any(h.startswith("/api/var/explain.xlsx?scope=BANK") for h in hrefs) and any(h.startswith("/play?scope=BANK") for h in hrefs)
    xlsx = [h for h in hrefs if h.startswith("/api/var/explain.xlsx")][0]
    with urllib.request.urlopen(str(stack) + xlsx, timeout=180) as r:
        assert r.status == 200 and "spreadsheetml" in r.headers.get("content-type", "") and r.read(2) == b"PK"
    # Esc closes, the focus goes back; E opens the page's main figure; the close button
    pg.keyboard.press("Escape")
    wait(pg, "document.querySelector('[data-explain-drawer]').hidden")
    pg.locator("body").click(position={"x": 5, "y": 300})
    pg.keyboard.press("e")
    drawer_ready(pg, "formula")
    pg.locator("[data-dx-close]").click()
    wait(pg, "document.querySelector('[data-explain-drawer]').hidden")
    # ? on a focused number: the preview
    pg.locator('.vx-tile[data-tile="1"] .v').focus()
    pg.keyboard.press("?")
    wait(pg, "(() => { const p = document.querySelector('[data-explain-preview]'); return !p.hidden && p.dataset.state === 'ready'"
             " && p.innerText.indexOf('ES') >= 0; })()", 60)
    pg.keyboard.press("Escape")
    # Explain › then Open as a page: the four parts at once, Print and Back
    pg.locator("[data-vx-explain]").click()
    drawer_ready(pg, "formula")
    href = pg.get_attribute("[data-dx-page]", "href")
    assert href.startswith("/explain?") and "kind=var" in href and "scope=BANK" in href
    pg.locator("[data-dx-page]").click()
    pg.wait_for_url("**/explain?**")
    wait(pg, "document.querySelector('[data-explain-page]').dataset.state === 'ready'", 180)
    assert pg.locator("[data-dx-part]").count() == 4
    assert pg.locator("[data-dx-print]").is_visible() and pg.locator("[data-dx-back]").get_attribute("href")
    shot(pg, "explain-page")
    # Back returns to the screen exactly as it was (its address), not a fresh one
    screen = "/var?scope=BANK"
    pg.locator("[data-dx-back]").click()
    pg.wait_for_url("**" + screen)
    painted(pg, "BANK")
    pg.close()


def test_the_parametric_question_mark_is_its_explain(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK&pm=1", {"width": 1400, "height": 950})
    painted(pg, "BANK")
    wait(pg, "document.querySelectorAll('[data-vp-tiles] .vp-q').length === 3", 60)
    q = pg.locator('[data-vp-tile="pgap"] .vp-q')
    assert json.loads(q.get_attribute("data-explain"))["measure"] == "pgap"
    q.click()
    drawer_ready(pg, "formula")
    assert "Fat-tail gap" in pg.locator("[data-explain-drawer]").inner_text()
    pg.close()


def test_var_explain_opens_the_page_at_why_it_moved_and_a_phone_gets_a_sheet(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR EXPLAIN BANK <GO>".replace(" ", "+"), {"width": 1280, "height": 900})
    assert "/explain?" in pg.url and "tab=why" in pg.url
    wait(pg, "document.querySelector('[data-explain-page]').dataset.state === 'ready'", 180)
    # Why it moved as a page is layout A (VAR_GUIDE.md 3.11): the waterfall drawn, a step's contributors beside it
    assert pg.evaluate("document.querySelector('[data-explain-page]').dataset.layout") == "why-a"
    wait(pg, "!!(document.querySelector('[data-dx-waterfall] drishti-chart') || {}).chart", 180)
    assert pg.locator("[data-dx-side] h2").inner_text().startswith("Biggest contributors")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.close()
    ph = open_var(browser, str(stack), "/var?scope=BANK", {"width": 390, "height": 844})
    painted(ph, "BANK")
    ph.locator('.vx-tile[data-tile="0"] .v').click()
    drawer_ready(ph, "formula")
    box = ph.locator("[data-explain-drawer]").bounding_box()
    assert box["width"] >= 389 and box["y"] > 100
    assert ph.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    shot(ph, "explain-phone")
    ph.close()
