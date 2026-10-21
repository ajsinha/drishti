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


def test_a_run_that_ended_before_its_card_listens_still_draws(ctx, stack):
    """BIR-01 (the real server, 2026-10-21): a quick run is done before the card subscribes, so no event comes for it and hello lists only unfinished runs;
    the card asks once (at hello, or at once when the stream is already open) and draws the result instead of saying Queued for ever."""
    page = open_page(ctx, stack, REPORT, HEAVY)
    draw = """(a) => { const host = document.createElement('div'); host.setAttribute('data-early', a.tag); document.body.prepend(host);
        drishtiBi.runs.card(host, { id: a.id, state: 'queued', can: { cancel: true } }, { onAnswer: (r) => host.setAttribute('data-answered', String((r.rows || []).length)) }); }"""
    for tag in ("first", "streaming"):
        if tag == "streaming":
            page.wait_for_function("drishtiBi.runs.streaming === true", timeout=15000)
        done = stack[1]._new("Done before the card", "done")
        done.update(progress=1.0, startedAt=done["submittedAt"], finishedAt=done["submittedAt"], actual={"rowsOut": 4, "seconds": 0})
        page.evaluate(draw, {"id": done["id"], "tag": tag})
        page.wait_for_selector(f'[data-early="{tag}"][data-answered]', timeout=10000)
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
    page.wait_for_function("document.querySelector('[data-run-drawer]').innerText.includes('re-masked')", timeout=10000)
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
    # the drawer's share modes and schedule form draw from the same tokens: each mode has a colour of its own, never the drawer's background
    done = next(r for r in stack[1].runs.values() if r["title"] == "Notional by counterparty")
    done["shared"]["with"] = [{"recipient": "bob", "mode": "re-masked", "recipientRun": None}, {"recipient": "dave", "mode": "same", "recipientRun": None}]
    page.click(f'[data-run-open="{done["id"]}"]')
    page.wait_for_selector('[data-run-share="bob"] .bi-run-mode')
    page.click('[data-run-act="schedule"]')
    marks = page.evaluate("""(() => { const bg = getComputedStyle(document.querySelector('[data-run-drawer]')).backgroundColor;
        const c = (s) => getComputedStyle(document.querySelector(s)).color;
        return { bg, masked: c('.bi-run-mode-re-masked'), same: c('.bi-run-mode-same'), form: getComputedStyle(document.querySelector('[data-run-sched-form]')).backgroundColor }; })()""")
    assert marks["masked"] != marks["bg"] and marks["same"] != marks["bg"] and marks["masked"] != marks["same"] and marks["form"] != "rgba(0, 0, 0, 0)"
    done["shared"]["with"] = []
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
        # the share and schedule forms fit the phone: nothing wider than the drawer
        done = next(r for r in stack[1].runs.values() if r["title"] == "Notional by counterparty")
        page.goto(stack[0] + f"/bi/w/me?tab=runs&run={done['id']}")
        page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-act='share']")
        for act, form in (("share", "[data-run-share-form]"), ("schedule", "[data-run-sched-form]")):
            page.click(f"[data-run-act='{act}']")
            page.wait_for_selector(form)
            assert page.evaluate("(() => { const d = document.querySelector('[data-run-drawer]'); return d.scrollWidth <= d.clientWidth + 1; })()"), act
            assert page.locator(form).bounding_box()["width"] <= 390 - 32 + 1, act
    finally:
        c.close()


def done_run(backend, title):
    r = backend._new(title, "done")
    r.update(progress=1.0, startedAt=r["submittedAt"], finishedAt=r["submittedAt"], actual={"rowsOut": 4, "seconds": 60}, expiresAt="2026-11-19T09:14:00Z")
    return r


def open_drawer(ctx, stack, rid):
    page = open_page(ctx, stack, "/bi/w/me?tab=runs", f'[data-run-row="{rid}"]')
    page.click(f'[data-run-open="{rid}"]')
    page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-drawer-state='done']")
    return page


def test_share_with_the_picker_shows_each_mode_unshare_and_the_recipients_view(ctx, stack):
    url, backend = stack
    run = done_run(backend, "Desk P&L, for sharing")
    page = open_drawer(ctx, stack, run["id"])
    page.click('[data-run-act="share"]')
    page.fill("[data-run-share-form] input[name='to']", "car")
    page.wait_for_selector("#runSharePick:not([hidden]) [role=option]", timeout=5000)
    page.keyboard.press("Enter")                                                   # the picker's choice becomes a chip
    page.wait_for_selector('[data-run-share-chip="carol"]')
    page.fill("[data-run-share-form] input[name='to']", "bob, eve")
    page.click("[data-run-share-form] [type=submit]")
    page.wait_for_selector('[data-run-share="carol"]', timeout=10000)
    assert page.get_attribute('[data-run-share="bob"]', "data-run-share-mode") == "re-masked"
    assert page.get_attribute('[data-run-share="carol"]', "data-run-share-mode") == "recomputed"
    text = page.inner_text("[data-run-drawer]")
    assert "re-masked" in text and "recomputed as them" in text and "Not shared with eve" in text and "DRS-9021" in text
    assert page.locator('[data-run-share="eve"]').count() == 0
    page.click('[data-run-unshare="carol"]')
    page.wait_for_function("!document.querySelector('[data-run-share=\"carol\"]')", timeout=10000)
    assert "No longer shared with carol" in page.inner_text("[data-run-drawer]")
    assert not page.errors, page.errors
    # the recipient: the run in their Runs tab, marked as shared by whom, with their own export
    backend.user = "bob"
    viewer = open_page(ctx, stack, "/bi/w/me?tab=runs", f'[data-run-row="{run["id"]}"]')
    assert "shared by ajsinha" in viewer.inner_text(f'[data-run-row="{run["id"]}"]')
    viewer.click(f'[data-run-open="{run["id"]}"]')
    viewer.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-export='csv']")
    assert "Shared with you by ajsinha" in viewer.inner_text("[data-run-drawer]") and viewer.locator('[data-run-act="share"]').count() == 0
    assert not viewer.errors, viewer.errors


def test_a_schedule_is_made_from_a_preset_edited_paused_and_removed(ctx, stack):
    url, backend = stack
    run = done_run(backend, "Desk P&L, scheduled")
    page = open_drawer(ctx, stack, run["id"])
    page.click('[data-run-act="schedule"]')
    form = "[data-run-sched-form]"
    page.select_option(form + " select[name=kind]", "weekly")
    page.select_option(form + " select[name=day]", "wed")
    page.fill(form + " input[name=time]", "09:15")
    assert page.inner_text("[data-run-sched-preview]") == "weekly wed 09:15"
    page.click(form + " [type=submit]")
    page.wait_for_selector("[data-run-schedule]:not([data-run-schedule=''])", timeout=10000)
    words = page.inner_text("[data-run-schedule]")
    assert "weekly wed 09:15 on the newest data" in words and "next 2026-10-22 09:15" in words
    assert backend.schedules and list(backend.schedules.values())[-1]["schedule"] == "weekly wed 09:15"
    page.click("[data-run-sched-edit]")
    assert page.input_value(form + " select[name=kind]") == "weekly"                  # the form opens on the schedule it changes
    page.select_option(form + " select[name=kind]", "monthly-business")
    page.fill(form + " input[name=time]", "18:00")
    page.click(form + " [type=submit]")
    page.wait_for_function("document.querySelector('[data-run-schedule]').innerText.includes('monthly last 18:00 business')", timeout=10000)
    page.click("[data-run-sched-toggle]")
    page.wait_for_function("document.querySelector('[data-run-schedule]').innerText.includes('paused')", timeout=10000)
    page.click("[data-run-sched-remove]")
    page.click("[data-confirm-go]")
    page.wait_for_selector("[data-run-schedule='']", timeout=10000)
    assert not [s for s in backend.schedules.values() if s["runId"] == run["id"]]
    assert not page.errors, page.errors


def test_export_downloads_csv_and_excel_from_the_drawer_and_the_stored_result(ctx, stack):
    url, backend = stack
    run = done_run(backend, "Desk P&L, exported")
    page = open_drawer(ctx, stack, run["id"])
    with page.expect_download() as got:
        page.click("[data-run-drawer] [data-run-export='xlsx']")
    assert got.value.suggested_filename == run["id"] + ".xlsx"
    with page.expect_download() as got:
        page.click("[data-run-drawer] [data-run-export='csv']")
    assert got.value.suggested_filename == run["id"] + ".csv"
    page.wait_for_function("document.querySelector('[data-run-drawer]').innerText.includes('Masked cells stay masked')", timeout=5000)
    report = open_page(ctx, stack, f"{REPORT}/overview?run={run['id']}", "[data-run-banner] [data-run-export='csv']")
    with report.expect_download() as got:
        report.click("[data-run-banner] [data-run-export='csv']")
    assert got.value.suggested_filename.endswith(".csv")
    assert not page.errors and not report.errors, page.errors + report.errors


def test_a_rollup_suggestion_is_accepted_and_another_dismissed(ctx, stack):
    url, backend = stack
    backend.suggestions[:] = [dict(s) for s in __import__("bi_runs_backend").SUGGESTIONS]
    page = open_page(ctx, stack, "/admin/bi/runs", '[data-sugg="sg-1"] [data-sugg-accept]')
    row = page.inner_text('[data-sugg="sg-1"]')
    assert "desk × month" in row and "37" in row and "4 min" in row and "asked 37 times this week" in row
    page.click('[data-sugg="sg-1"] summary')
    assert "by_desk_month" in page.inner_text('[data-sugg="sg-1"] .bi-sugg-yaml')
    page.click('[data-sugg-accept="sg-1"]')
    page.wait_for_selector('[data-sugg="sg-1"][data-sugg-status="accepted"]', timeout=10000)
    assert "Accepted" in page.inner_text("[data-admin-suggest-msg]") and page.locator('[data-sugg-open="bi-model-trading"]').count() == 1
    page.click('[data-sugg-dismiss="sg-2"]')
    page.wait_for_selector('[data-sugg="sg-2"][data-sugg-status="dismissed"]', timeout=10000)
    assert not page.errors, page.errors


def test_a_run_says_what_the_server_said_kept_reason_data_and_title(ctx, stack):
    """BIR-12: a done run without a date is not "kept (promoted or pinned)"; BIR-13: a reason ending with the fix is not told it twice; BIR-09, BIR-10:
    no data words and no tables draw a dash, and a report visual's run titled with the report's id reads by the visual's and the report's names."""
    url, backend = stack
    done = backend._new("Exposure by tenor", "done")
    done.update(progress=1.0, startedAt=done["submittedAt"], finishedAt=done["submittedAt"], expiresAt=None, actual={"rowsOut": 3, "seconds": 9})
    stopped = backend._new("Cash ladder by currency", "failed")
    stopped.update(code="DRS-9085", reason="the server stopped while the run was running and it could not be started again: re-run it", finishedAt=stopped["submittedAt"])
    src = {"type": "report", "id": "file:report:counterparty-risk", "title": None, "page": "p1", "visual": "exposure-by-name"}
    bare = backend._new("file:report:counterparty-risk", "done", source=src)
    bare.update(progress=1.0, startedAt=bare["submittedAt"], finishedAt=bare["submittedAt"], data={"version": None, "words": None, "tables": []}, actual={"rowsOut": 1, "seconds": 3})
    page = open_page(ctx, stack, "/bi/w/me?tab=runs", f'[data-run-row="{bare["id"]}"]')
    table = page.inner_text("[data-runs-table]")
    assert "re-run it: re-run it" not in table and table.count("re-run it; if it happens again, narrow it") == 1, table
    row = page.inner_text(f'[data-run-row="{bare["id"]}"]')
    assert "Exposure by name · Counterparty risk" in row and "file:report:" not in row and "–" in row, row
    page.click(f'[data-run-open="{done["id"]}"]')
    page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-expires]")
    assert page.inner_text("[data-run-expires]") == "30 days after it was last opened"
    page.click("[data-run-drawer-close]")
    page.wait_for_selector("[data-run-drawer]", state="hidden")
    page.click(f'[data-run-open="{bare["id"]}"]')
    page.wait_for_function("document.querySelector('[data-run-drawer-title]').textContent === 'Exposure by name · Counterparty risk'")
    assert 'Report "Counterparty risk"' in page.inner_text("[data-run-drawer]")
    R = "window.drishtiBi.runs"
    assert page.evaluate(f"{R}.keptUntil({{state: 'done', promotedTo: 'desk-pnl@1'}}, 30)") == "kept (promoted)"
    assert page.evaluate(f"{R}.keptUntil({{state: 'done', pinned: true}}, 30)") == "kept (pinned)"
    assert page.evaluate(f"{R}.keptUntil({{state: 'done', retentionDays: 7}}, 30)") == "7 days after it was last opened"
    assert page.evaluate(f"{R}.keptUntil({{state: 'failed'}}, 30)") == ""
    assert page.evaluate(f"{R}.words('DRS-9028', 'the run ran out of its memory budget (4 GB)')") == "The run ran out of its memory budget (4 GB): narrow the dates or add a rollup. (DRS-9028)"
    assert not page.errors, page.errors


def test_promote_asks_name_title_and_kind_then_links_the_drafts(ctx, stack):
    url, backend = stack
    mq = backend._new("Notional by desk", "done", source={"type": "mq", "model": "trading"})
    mq.update(progress=1.0, startedAt=mq["submittedAt"], finishedAt=mq["submittedAt"], expiresAt="2026-11-19T09:14:00Z", actual={"rowsOut": 4, "seconds": 20})
    visual = backend._new("MTM by desk, to promote", "done")
    visual.update(progress=1.0, startedAt=visual["submittedAt"], finishedAt=visual["submittedAt"], actual={"rowsOut": 4, "seconds": 20})
    page = open_page(ctx, stack, "/bi/w/me?tab=runs", f'[data-run-row="{mq["id"]}"]')
    page.click(f'[data-run-open="{visual["id"]}"]')                                        # a report visual's run: the server says no (can.promote)
    page.wait_for_selector("[data-run-drawer]:not([hidden]) [data-run-drawer-state]")
    assert page.locator('[data-run-act="promote"]').count() == 0
    page.click("[data-run-drawer-close]")
    page.wait_for_selector("[data-run-drawer]", state="hidden")
    page.click(f'[data-run-open="{mq["id"]}"]')
    page.click('[data-run-act="promote"]')
    form = '[data-run-form][aria-label="Promote this result"]'
    assert page.input_value(form + ' [name="name"]') == "notional-by-desk" and page.input_value(form + ' [name="title"]') == "Notional by desk"
    page.fill(form + ' [name="name"]', "taken")
    page.click(form + ' [type="submit"]')
    page.wait_for_function("document.querySelector('[data-run-drawer]').innerText.includes('(DRS-9004)')", timeout=10000)
    assert "taken by another person's promotion: choose another name." in page.inner_text("[data-run-drawer]")
    page.fill(form + ' [name="name"]', "desk-notional")
    page.fill(form + ' [name="title"]', "Desk notional, end of September")
    page.select_option(form + ' [name="to"]', "report")
    page.click(form + ' [type="submit"]')
    page.wait_for_selector("[data-run-promoted-report]", timeout=10000)
    assert backend.promotions[-1] == {"name": "desk-notional", "title": "Desk notional, end of September", "to": "report", "run": mq["id"]}
    text = page.inner_text("[data-run-drawer]")
    assert "desk-notional@1" in text and "draft dataset and a draft report" in text
    assert page.get_attribute("[data-run-promoted-dataset]", "href") == "/bi/i/bi-ds-desk-notional"
    assert page.get_attribute("[data-run-promoted-report]", "href") == "/bi/i/bi-rpt-desk-notional"
    assert "read only by the dataset it was promoted to" in page.evaluate("window.drishtiBi.runs.words('DRS-9108')")
    assert not page.errors, page.errors
