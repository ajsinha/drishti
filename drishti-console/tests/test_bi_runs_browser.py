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

"""BI runs in a real browser (Chromium; docs/architecture/BI_RUNS.md section 19.6), against the stand-in server of bi_runs_backend.py with synthetic data:
a visual answered in time draws with no card; a heavy one asks to confirm with its estimate and basis, Run in background shows the run card with progress,
and the card turns into the chart when the run finishes; the bell counts the finished run on another page and its notice opens the report with ?run=,
drawn from the stored result; a slicer redraws a covered visual at once and offers Run in background for one that would need a run; the Runs tab lists the
run with as-of, data version, estimate and actual, re-run on the same data reuses it, a cancelled run says Cancelled by you; a run shared with a viewer
shows the viewer's mask; the tabs, rows and drawer work by keyboard and say each change in a live region; every theme draws the tab; a phone at 16 px
gutters. Skipped when Playwright's Chromium is not installed."""
import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from bi_runs_backend import REPORT_ID, RunsBackend, serve  # noqa: E402

THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
REPORT = f"/bi/r/{REPORT_ID}"
HEAVY = '[data-visual="mtm-by-desk-month"]'


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(RunsBackend(run_seconds=3.0))
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


@pytest.fixture
def ctx(browser, stack):
    stack[1].user = "ajsinha"
    c = browser.new_context(viewport={"width": 1366, "height": 900}, accept_downloads=True, bypass_csp=True)
    yield c
    c.close()


def open_page(ctx, stack, path, wait):
    page = ctx.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(stack[0] + path, wait_until="load")
    page.wait_for_selector(wait, timeout=15000)
    return page


def run_in_background(page):
    page.wait_for_selector(HEAVY + " [data-run-ask='DRS-9082']", timeout=15000)
    page.click(HEAVY + " [data-run-go]")
    page.wait_for_selector(HEAVY + " [data-run-card]", timeout=10000)
    return page.get_attribute(HEAVY + " [data-run-card]", "data-run-card")


def test_inline_visuals_draw_with_no_card_and_a_heavy_one_asks_first(ctx, stack):
    page = open_page(ctx, stack, REPORT, '[data-visual="mtm"] .bi-vis-body *')
    assert page.locator('[data-visual="mtm"] [data-run-card]').count() == 0
    ask = page.wait_for_selector(HEAVY + " [data-run-ask='DRS-9082']", timeout=15000)
    text = ask.inner_text()
    assert "Confirm a background run" in text and "3 to 6 min" in text and "Delta statistics" in text and "182 business days" in text
    assert page.locator(HEAVY + " [data-run-narrow]").count() == 1 and page.locator(HEAVY + " [data-run-not]").count() == 1
    page.click(HEAVY + " [data-run-not]")
    assert "Nothing was read" in page.inner_text(HEAVY)
    assert not page.errors, page.errors


def test_run_in_background_shows_progress_then_draws_the_chart(ctx, stack):
    page = open_page(ctx, stack, REPORT, HEAVY)
    rid = run_in_background(page)
    assert rid.startswith("r-java-2-")
    page.wait_for_selector(HEAVY + " [role=progressbar]", timeout=8000)
    card = page.inner_text(HEAVY + " [data-run-card]")
    assert "Running" in card and "bell" in card and "Open in Runs" in card
    page.wait_for_selector(HEAVY + " .bi-chart", timeout=15000)
    assert page.locator(HEAVY + " [data-run-card]").count() == 0
    assert not page.errors, page.errors


def test_the_bell_counts_the_finished_run_and_its_notice_opens_the_stored_result(ctx, stack):
    url, backend = stack
    before = len(backend.notices)
    page = open_page(ctx, stack, REPORT, HEAVY)
    rid = run_in_background(page)
    page.click('[data-page="notes"]')
    page.wait_for_function(f"Number((document.querySelector('[data-bell-count]')||{{}}).textContent||0) >= {before + 1}", timeout=15000)
    page.goto(url + "/inbox?tab=run")
    link = page.locator(f'a[href="/bi/run/{rid}"]')
    assert link.count() == 1
    link.click()
    page.wait_for_url(f"**/overview?run={rid}", timeout=10000)
    page.wait_for_selector(HEAVY + " .bi-chart", timeout=15000)
    assert "Drawn from the run" in page.inner_text("[data-run-banner]")
    assert page.locator(HEAVY + " [data-run-ask]").count() == 0                      # drawn from the result: nothing was asked again
    assert not page.errors, page.errors


def test_a_slicer_redraws_a_covered_visual_at_once_and_offers_a_run_for_a_heavy_one(ctx, stack):
    page = open_page(ctx, stack, REPORT, '[data-visual="desk"] [data-slicer-value]')
    page.wait_for_selector(HEAVY + " [data-run-ask]", timeout=15000)
    page.locator('[data-visual="desk"] [data-slicer-value="DESK-FX"]').check()
    page.wait_for_selector(HEAVY + " [data-run-ask='DRS-9089']", timeout=10000)
    assert "This selection needs a background run" in page.inner_text(HEAVY) and "3 to 6 min" in page.inner_text(HEAVY)
    assert page.locator('[data-visual="mtm"] [data-run-card], [data-visual="mtm"] [data-run-ask]').count() == 0
    asked = [b for p, b, _ in stack[1].asked if b and b.get("visual") == "mtm-by-desk-month"]
    assert asked[-1]["interaction"] is True and asked[-1]["supersedes"] == f"{REPORT_ID}/overview/mtm-by-desk-month"
    page.click(HEAVY + " [data-run-go]")
    page.wait_for_selector(HEAVY + " [data-run-card]", timeout=10000)
    assert not page.errors, page.errors


def test_the_runs_tab_lists_reuses_on_the_same_data_and_cancels(ctx, stack):
    url, backend = stack
    done = next(r for r in backend.runs.values() if r["state"] == "done" and r["title"] == "Notional by counterparty")
    page = open_page(ctx, stack, "/bi/w/me?tab=runs", f'[data-run-row="{done["id"]}"]')
    row = page.inner_text(f'[data-run-row="{done["id"]}"]')
    for want in ("Notional by counterparty", "Done", "2026-09-30", "delta v412", "41 s", "1,204", "30–90 s"):
        assert want in row, want
    assert "Failed" in page.inner_text("[data-runs-table]") and "ran out of its memory budget" in page.inner_text("[data-runs-table]")
    page.click(f'[data-run-open="{done["id"]}"]')
    page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-drawer-state]")
    text = page.inner_text("[data-run-drawer]")
    assert "Estimate" in text and "actual 41 s" in text and "Delta statistics" in text and "DESK in (DESK-FX, DESK-FI)" in text
    page.select_option('[data-run-act="rerun"]', "same")
    page.wait_for_function("document.querySelector('[data-runs-table]').innerText.includes('Reused')", timeout=10000)
    page.keyboard.press("Escape")
    # a second run, cancelled from its row's drawer
    other = backend._new("Cash ladder 30 days")
    page.wait_for_selector(f'[data-run-row="{other["id"]}"]', timeout=10000)
    page.click(f'[data-run-open="{other["id"]}"]')
    page.click('[data-run-drawer] [data-run-act="cancel"]')
    page.wait_for_function(f"(document.querySelector('[data-run-row=\"{other['id']}\"]')||{{}}).getAttribute && document.querySelector('[data-run-row=\"{other['id']}\"]').getAttribute('data-run-state') === 'cancelled'", timeout=10000)
    assert "Cancelled: cancelled by you" in page.inner_text("[data-runs-table]")
    assert page.is_visible("[data-run-drawer]") and not page.errors, page.errors


def test_a_shared_run_shows_the_viewers_mask(ctx, stack):
    url, backend = stack
    done = backend._new("MTM by desk, shared", "done")
    done.update(progress=1.0, startedAt=done["submittedAt"], finishedAt=done["submittedAt"], actual={"rowsOut": 4, "seconds": 60})
    page = open_page(ctx, stack, "/bi/w/me?tab=runs", f'[data-run-row="{done["id"]}"]')
    page.click(f'[data-run-open="{done["id"]}"]')
    page.click('[data-run-act="share"]')
    page.fill('[data-run-form] input[name="to"]', "bob")
    page.click('[data-run-form] [type="submit"]')
    page.wait_for_function("document.querySelector('[data-run-drawer]').innerText.includes('sees it with their masks')", timeout=10000)
    backend.share_as_viewer(done["id"], "bob")
    viewer = open_page(ctx, stack, f"{REPORT}/overview?run={done['id']}", HEAVY + " .bi-chart")
    shown = viewer.evaluate("fetch('/bi/api/runs/" + done["id"] + "/result?format=json').then(r => r.json())")
    assert all(row[1] is None for row in shown["rows"]) and shown["columns"][1]["masked"] is True       # the mask, never a value
    assert not viewer.errors, viewer.errors


def test_keyboard_only_tabs_rows_and_drawer_with_live_announcements(ctx, stack):
    page = open_page(ctx, stack, "/bi/w/me", '[data-ws-tab="items"][aria-selected="true"]')
    page.focus('[data-ws-tab="items"]')
    page.keyboard.press("ArrowRight")
    page.wait_for_selector('[data-ws-tab="runs"][aria-selected="true"]')
    assert "tab=runs" in page.url and page.is_visible('[data-ws-panel="runs"]') and not page.is_visible('[data-ws-panel="items"]')
    page.wait_for_selector("[data-run-row]")
    page.focus("[data-run-row]")
    first = page.evaluate("document.activeElement.getAttribute('data-run-row')")
    page.keyboard.press("ArrowDown")
    second = page.evaluate("document.activeElement.getAttribute('data-run-row')")
    assert first and second and first != second
    page.keyboard.press("Enter")
    page.wait_for_selector("[data-run-drawer]:not([hidden])")
    page.wait_for_function("document.activeElement && document.activeElement.hasAttribute('data-run-drawer-close')", timeout=3000)
    page.keyboard.press("Escape")
    assert page.is_hidden("[data-run-drawer]") and page.evaluate("document.activeElement.getAttribute('data-run-row')") == second
    assert page.get_attribute("[data-runs-say]", "aria-live") == "polite"
    run = stack[1]._new("Announced")
    page.wait_for_selector(f'[data-run-row="{run["id"]}"]', timeout=10000)
    page.wait_for_function("document.querySelector('[data-runs-say]').textContent.includes('Announced')", timeout=10000)
    assert not page.errors, page.errors


@pytest.mark.parametrize("theme", THEMES)
def test_every_theme_draws_the_runs_tab_from_its_tokens(ctx, stack, theme):
    page = open_page(ctx, stack, "/bi/w/me?tab=runs", "[data-run-row]")
    page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
    colours = page.evaluate("""(() => { const s = getComputedStyle(document.querySelector('[data-ws-tab="runs"]'));
        const row = getComputedStyle(document.querySelector('.bi-run-done') || document.body);
        return { tab: s.color, border: s.borderBottomColor, done: row.color }; })()""")
    assert colours["tab"] and colours["border"] != "rgba(0, 0, 0, 0)" and colours["done"]
    assert not page.errors, page.errors


def test_a_phone_at_16_px_gutters(browser, stack):
    c = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, bypass_csp=True)
    try:
        page = c.new_page()
        page.goto(stack[0] + "/bi/w/me?tab=runs")
        page.wait_for_selector("[data-run-row]")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        page.click("[data-run-open]")
        box = page.locator("[data-run-drawer]").bounding_box()
        assert box["width"] <= 391 and box["x"] >= -1
        pad = page.evaluate("parseFloat(getComputedStyle(document.querySelector('[data-run-drawer]')).paddingLeft)")
        assert pad == 16
    finally:
        c.close()
