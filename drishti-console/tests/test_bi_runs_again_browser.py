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

"""Run again from the Runs tab's drawer in a real browser (Chromium), against the stand-in server of bi_runs_backend.py: a labelled choice of how ("Run
again as") and a primary "Run again" button; changing the choice does nothing; the button spins and says "Running…" while the new run goes, the new run
is at the top of the list highlighted, the drawer switches to it and follows it to Done, and the button is itself again; an answer that was there says
"Done at once: reused the answer from <time>" with Open result; the old failed row says "superseded by" the new run. Every theme, a phone, reduced
motion. Skipped when Playwright's Chromium is not installed."""
import os

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from bi_runs_backend import RunsBackend, serve  # noqa: E402

THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
SHOTS = os.environ.get("DRISHTI_SHOTS")


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(RunsBackend(run_seconds=2.5))
    yield url, backend
    stop()


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


def failed_run(backend, title):
    r = backend._new(title, "failed", source={"type": "sql", "model": "trading", "id": "trading"})
    r.update(code="DRS-9025", reason="the run waited too long in admission", startedAt=r["submittedAt"], finishedAt=r["submittedAt"], actual={"seconds": 2},
             asOf={"from": "2026-10-16", "to": "2026-10-16", "dates": 1})
    return r


def open_drawer(browser, stack, rid, **ctx):
    c = browser.new_context(**({"viewport": {"width": 1366, "height": 900}, "bypass_csp": True} | ctx))
    page = c.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(stack[0] + "/bi/w/me?tab=runs", wait_until="load")
    page.wait_for_selector(f'[data-run-row="{rid}"]', timeout=15000)
    page.click(f'[data-run-open="{rid}"]')
    page.wait_for_selector(f"[data-run-drawer]:not([hidden]) [data-run-again]")
    return c, page


def test_run_again_of_a_failed_run_follows_the_new_run_with_a_busy_button(browser, stack):
    url, backend = stack
    backend.reuse_of = None
    old = failed_run(backend, "Raw trades again")
    c, page = open_drawer(browser, stack, old["id"])
    try:
        group = page.locator("[data-run-drawer] [data-run-again]")
        assert "Run again as" in group.inner_text()
        assert page.eval_on_selector("[data-run-again-as]", "s => [...s.options].map(o => o.text)") == ["Same as before (as of 2026-10-16)", "With the latest data"]
        assert page.eval_on_selector("[data-run-again-as]", "s => document.querySelector('label[for=\"' + s.id + '\"]') !== null")
        go = page.locator('[data-run-drawer] [data-run-act="rerun"]')
        assert go.inner_text().strip() == "Run again" and "btn-accent" in go.get_attribute("class")
        sizes = page.evaluate("[...document.querySelectorAll('[data-run-again] select, [data-run-again] button')].map(e => Math.round(e.getBoundingClientRect().height))")
        assert abs(sizes[0] - sizes[1]) <= 1, sizes                       # one control group: one height
        before = len([x for x in backend.asked if x[0].endswith("/rerun")])
        page.select_option("[data-run-again-as]", "latest")              # choosing does nothing on its own
        page.wait_for_timeout(300)
        assert len([x for x in backend.asked if x[0].endswith("/rerun")]) == before
        go.click()
        page.wait_for_function("['queued', 'running'].includes((document.querySelector('[data-run-drawer] [data-run-drawer-state]') || {getAttribute: () => ''}).getAttribute('data-run-drawer-state'))", timeout=5000)
        page.wait_for_selector("[data-run-drawer] [data-run-spinner]", timeout=5000)
        busy = page.locator('[data-run-drawer] [data-run-act="rerun"]')
        assert busy.is_disabled() and "Running…" in busy.inner_text()
        new = next(r for r in backend.runs.values() if r.get("rerunOf") == old["id"])
        assert "DRS-9025" not in page.inner_text("[data-run-drawer]")          # the drawer shows the new run, not the old failure
        first = page.locator("[data-run-row]").first
        assert first.get_attribute("data-run-row") == new["id"] and "bi-run-new" in first.get_attribute("class")
        assert page.locator(f'[data-run-row="{old["id"]}"] [data-run-superseded="{new["id"]}"]').count() == 1
        assert "superseded by" in page.inner_text(f'[data-run-row="{old["id"]}"]')
        page.wait_for_selector("[data-run-drawer] [data-run-drawer-state='done']", timeout=15000)
        assert page.locator("[data-run-drawer] [data-run-spinner]").count() == 0
        assert page.locator('[data-run-drawer] [data-run-act="rerun"]').inner_text().strip() == "Run again"
        assert not page.locator('[data-run-drawer] [data-run-act="rerun"]').is_disabled()
        assert any(x[1] == {"data": "latest"} for x in backend.asked if x[0].endswith("/rerun"))
        assert not page.errors, page.errors
    finally:
        c.close()


def test_an_answer_that_was_there_says_it_reused_and_offers_the_result(browser, stack):
    url, backend = stack
    done = next(r for r in backend.runs.values() if r["title"] == "Notional by counterparty")
    backend.reuse_of = done["id"]
    old = failed_run(backend, "Raw trades reused")
    c, page = open_drawer(browser, stack, old["id"])
    try:
        page.click('[data-run-drawer] [data-run-act="rerun"]')
        page.wait_for_function(f"(document.querySelector('[data-run-drawer-title]')||{{}}).textContent && document.querySelector('[data-run-drawer] [data-run-drawer-state]').getAttribute('data-run-drawer-state') === 'reused'", timeout=10000)
        new = next(r for r in backend.runs.values() if r.get("rerunOf") == old["id"])
        msg = page.inner_text("[data-run-drawer] [data-run-drawer-msg]")
        assert msg.startswith("Done at once: reused the answer from ") and done["finishedAt"].replace("T", " ")[:16] in msg
        assert page.get_attribute("[data-run-drawer] [data-run-open-result]", "href") == f"/bi/run/{new['id']}"
        assert page.locator(f'[data-run-row="{old["id"]}"] [data-run-superseded="{new["id"]}"]').count() == 1
        page.click(f'[data-run-row="{old["id"]}"] [data-run-superseded] a')
        page.wait_for_selector(f'[data-run-drawer]:not([hidden]) [data-run-drawer-state="reused"]')
        assert not page.errors, page.errors
    finally:
        backend.reuse_of = None
        c.close()


@pytest.mark.parametrize("data", ["latest", "same"])
def test_a_run_again_answered_at_once_shows_the_new_run_not_something_went_wrong(browser, stack, data):
    """The real server answers a quick rerun 200 {run, answer} and keeps no row of it, so the reloaded list lacks it (the cause of a bare "Something went
    wrong." in 2.6.7): the drawer shows the new run from the reply and says what happened; the old row says superseded by it."""
    url, backend = stack
    backend.reuse_of, backend.at_once = None, True
    old = failed_run(backend, "Cashflow ladder at once " + data)
    c, page = open_drawer(browser, stack, old["id"])
    try:
        page.select_option("[data-run-again-as]", data)
        page.click('[data-run-drawer] [data-run-act="rerun"]')
        page.wait_for_selector("[data-run-drawer] [data-run-drawer-state='done']", timeout=10000)
        new = next(r for r in backend.runs.values() if r.get("rerunOf") == old["id"])
        msg = page.inner_text("[data-run-drawer] [data-run-drawer-msg]")
        assert msg == "Done at once: asked again " + ("with the latest data" if data == "latest" else "as before") + ", 1 row.", msg
        assert "went wrong" not in page.inner_text("[data-run-drawer]") and "failed in the page" not in msg
        assert page.locator(f'[data-run-row="{old["id"]}"] [data-run-superseded="{new["id"]}"]').count() == 1
        assert page.locator('[data-run-drawer] [data-run-act="rerun"]').inner_text().strip() == "Run again"
        assert not page.errors, page.errors
    finally:
        backend.at_once = False
        c.close()


@pytest.mark.parametrize("theme", THEMES)
def test_every_theme_draws_the_run_again_group(browser, stack, theme):
    old = failed_run(stack[1], "Raw trades " + theme)
    c, page = open_drawer(browser, stack, old["id"])
    try:
        page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
        k = page.evaluate("""(() => { const b = getComputedStyle(document.querySelector('[data-run-drawer] [data-run-act="rerun"]'));
            const s = getComputedStyle(document.querySelector('[data-run-again-as]')), l = getComputedStyle(document.querySelector('[data-run-again] > label'));
            return { bg: b.backgroundImage !== 'none' ? b.backgroundImage : b.backgroundColor, fg: b.color, sel: s.backgroundColor, label: l.color }; })()""")
        assert k["bg"] != "rgba(0, 0, 0, 0)" and k["bg"] != k["fg"] and k["label"]
        if SHOTS and theme == "terminal":
            page.screenshot(path=os.path.join(SHOTS, "bi-run-again-dark.png"))
        assert not page.errors, page.errors
    finally:
        c.close()


def test_a_phone_stacks_the_group_and_reduced_motion_stills_the_spinner(browser, stack):
    stack[1].reuse_of = None
    old = failed_run(stack[1], "Raw trades phone")
    c, page = open_drawer(browser, stack, old["id"], viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, reduced_motion="reduce")
    try:
        sel, go = page.locator("[data-run-again-as]").bounding_box(), page.locator('[data-run-drawer] [data-run-act="rerun"]').bounding_box()
        assert go["y"] >= sel["y"] + sel["height"] - 1 and go["width"] >= 390 - 32 - 2 and sel["width"] >= 390 - 32 - 2 and go["height"] >= 44
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        page.click('[data-run-drawer] [data-run-act="rerun"]')
        page.wait_for_selector("[data-run-drawer] [data-run-spinner]", timeout=5000)
        # the drawer redraws as the run moves: read the spinner that is there now (a still dot, no animation)
        page.wait_for_function("(() => { const e = document.querySelector('[data-run-drawer] [data-run-spinner]'); return e && e.isConnected && getComputedStyle(e).animationName === 'none'; })()", timeout=5000)
        assert not page.errors, page.errors
    finally:
        c.close()
