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

"""Forms in a real browser (RACHANA_REFERENCE.md 10), a real server behind the console, on the pack screens that carry a form:

* A, the book desk's trades: a click opens the form beside the table; a locked field says why; a warning ("a second approver is
  needed") and an error ("say why") in words; Draft · not saved; the draft saved, the page reloaded, "You have 1 draft", resumed;
  leaving with changes not saved asks first; a masked field says Hidden for you and offers nothing;
* B, a credit limit's buckets: a pencil on the editable column; Enter, Esc and Tab; a changed cell tinted with "was …"; a bad value
  red with the reason under the row; "N changes not saved", Review, Save draft, Undo all; a masked cell is Hidden for you;
* C, New trade: a drawer over the dimmed page in steps, Next refusing a missing field, the limit after the trade computed from the
  chosen counterparty, Save as draft;
* the richer fields: the booking entity picked by search (its id kept, its name shown, Open beside it), money typed as 1.5m with the
  currency the currency field holds beside it, a fee worked out from the notional until it is typed over, a date and time with its
  time zone;
* two themes and a phone; the screenshots for the guide (docs/guides/img/designer/form-beside.jpg, form-cells.jpg, form-drawer.jpg).

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack
from docshots import docs_img

IMG = docs_img("guides/img/designer")                 # docs/guides/img/designer only when DRISHTI_SHOTS is set (docshots.py)
DESK = "/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk"
LIMIT = "/v/credit-limit/LIM-ALDERSHOT"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from (str(s) for s in _stack(tmp_path_factory, {}))


def page(browser, base, path, theme="terminal", size=None):
    pg = browser.new_page(viewport=size or {"width": 1500, "height": 950})
    pg.add_init_script("document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '%s'))" % theme)
    pg.dialogs = []

    def on_dialog(d):
        pg.dialogs.append((d.type, d.message))
        d.accept()
    pg.on("dialog", on_dialog)
    pg.goto(base + path)
    wait(pg, "window.drishtiForms && Object.keys(drishtiForms.state()).length > 0")
    return pg


def forget_drafts(pg, base):
    for d in pg.request.get(base + "/api/drafts").json():
        pg.request.delete(base + "/api/drafts/" + d["draft"])


def overflow(pg):
    return pg.evaluate("document.documentElement.scrollWidth > window.innerWidth + 1")


def test_a_form_beside_the_table_with_words_a_draft_and_resuming_it(stack, browser):
    pg = page(browser, stack, DESK)
    forget_drafts(pg, stack)
    pg.reload()
    wait(pg, "window.drishtiForms && Object.keys(drishtiForms.state()).length > 0")
    pg.click("#p-trades tbody tr[data-frow='1'] td:nth-child(2)")
    side = pg.locator("[data-fm-side]")
    side.wait_for()
    assert "locked: you can view, not change" in side.locator("[data-fm-field='counterparty'] .fm-why").inner_text()
    assert "read-only: set by the booking system" in side.locator("[data-fm-field='trade_id']").inner_text()
    assert "· LE-" in side.locator("[data-fm-field='legal_entity'] .fm-ro").inner_text()      # an entity: its name, then its id
    assert side.locator("[data-fm-field='notional'] .fm-unit").inner_text() == pg.evaluate(
        "JSON.parse(document.querySelector('#p-trades').getAttribute('data-form')).rows[1].values.currency")
    assert side.locator("[data-fm-badge]").count() == 0
    pg.fill("[data-fm-side] [data-field='notional']", "40,000,000")
    warn = side.locator("[data-msgs='notional'] .fm-warn")
    assert warn.inner_text().strip() == "Above the desk limit of 30,000,000: a second approver is needed."
    assert "Say why the trade is above the desk limit" in side.locator("[data-msgs='comment'] .fm-error").inner_text()
    assert side.locator("[data-fm-badge]").inner_text() == "Draft · not saved"
    pg.click("[data-fm-side] .fm-save")                          # an error: not saved, the field it names is focused
    assert pg.evaluate("document.activeElement.getAttribute('data-field')") == "comment"
    assert pg.evaluate("drishtiForms.state().trades.dirty")
    pg.fill("[data-fm-side] [data-field='comment']", "Client hedge for the Q4 roll")
    assert side.locator(".fm-error").count() == 0 and warn.count() == 1
    pg.fill("[data-fm-side] [data-field='executed_at']", "2026-10-22T09:41")      # a date and time, in its time zone
    IMG.mkdir(parents=True, exist_ok=True)
    wait(pg, "document.querySelector('#p-trades tr.wire-kids')")
    pg.evaluate("document.querySelectorAll('#p-trades tr.wire-kids').forEach(n => n.remove())")   # the cash flows the click opened: not in the picture
    pg.evaluate("document.querySelector('#p-trades').scrollIntoView({ block: 'start' }); window.scrollBy(0, -70); document.activeElement.blur(); document.querySelector('[data-fm-side]').scrollTop = 0")
    pg.screenshot(path=str(IMG / "form-beside.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 900})
    pg.click("[data-fm-side] .fm-save")
    wait(pg, "document.querySelector('[data-fm-side] .fm-badge-saved')")
    assert pg.locator("[data-fm-drafts]").inner_text().count("draft") >= 1
    pg.reload()
    wait(pg, "document.querySelector('[data-fm-drafts]')")
    assert "You have 1 draft on this page" in pg.locator("[data-fm-drafts]").inner_text()
    pg.click("[data-fm-drafts] .fm-link:text('Resume')")
    side.wait_for()
    assert pg.input_value("[data-fm-side] [data-field='comment']") == "Client hedge for the Q4 roll"
    assert pg.input_value("[data-fm-side] [data-field='notional']") == "40,000,000"
    pg.fill("[data-fm-side] [data-field='notional']", "41,000,000")     # changes not saved: leaving asks first
    leaving = "(() => { const e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); return e.defaultPrevented; })()"
    assert pg.evaluate(leaving)                                   # the browser asks "Leave site?"
    pg.click("[data-fm-side] .fm-x")                              # closing the form asks too (accepted here)
    assert any("not saved as a draft" in m for _, m in pg.dialogs)
    assert not pg.evaluate(leaving)
    forget_drafts(pg, stack)
    pg.close()


def test_a_masked_field_is_hidden_and_offers_nothing(stack, browser):
    pg = page(browser, stack, DESK)
    pg.evaluate("""() => {
      const s = document.querySelector('#p-trades'); const f = JSON.parse(s.getAttribute('data-form'));
      f.fields.forEach(x => { if (x.id === 'counterparty') { x.masked = true; delete x.options; } if (x.id === 'trade_status') { x.locked = 'locked: you can view, not change'; } });
      f.rows.forEach(r => { delete r.values.counterparty; r.masked = ['counterparty']; });
      drishtiForms.reset('trades', f);
    }""")
    pg.click("#p-trades tbody tr[data-frow='0'] td:nth-child(2)")
    side = pg.locator("[data-fm-side]")
    side.wait_for()
    cp = side.locator("[data-fm-field='counterparty']")
    assert "Hidden for you" in cp.inner_text() and cp.locator("select, input").count() == 0
    assert "locked: you can view, not change" in side.locator("[data-fm-field='trade_status']").inner_text()
    assert side.locator("[data-fm-field='trade_status'] select").count() == 0
    pg.click("[data-fm-new]")                                     # the drawer never asks for a masked or locked field
    assert pg.locator("[data-fm-drawer] [data-fm-field='counterparty']").count() == 0
    pg.close()


def test_cells_edited_like_a_spreadsheet_with_reasons_under_the_row(stack, browser):
    pg = page(browser, stack, LIMIT)
    forget_drafts(pg, stack)
    head = pg.locator("#p-buckets thead th")
    assert "✎" in head.nth(1).inner_text() and "✎" not in head.nth(0).inner_text() and "✎" not in head.nth(2).inner_text()
    cell = "#p-buckets tr[data-frow='{}'] td[data-fcell='limit']"
    pg.click(cell.format(0))
    pg.keyboard.press("Control+A")
    pg.keyboard.type("100")
    pg.keyboard.press("Enter")
    assert "fm-bad" in pg.get_attribute(cell.format(0), "class")
    assert "The new limit is below what's already used: raise it, or ask the owner first." in pg.locator("#p-buckets tr.fm-why").first.inner_text()
    assert pg.locator("#p-buckets .fm-toolbar [data-fm-badge]").inner_text() == "1 change not saved"
    assert "was " in pg.locator(cell.format(0) + " .fm-was").inner_text()
    pg.click(cell.format(1))
    pg.keyboard.press("Control+A")
    pg.keyboard.type("400m")
    pg.keyboard.press("Tab")                                     # kept, and on to the next editable cell
    assert pg.evaluate("document.activeElement.getAttribute('data-fcell')") == "limit"
    assert pg.evaluate("document.activeElement.closest('tr').getAttribute('data-frow')") == "2"
    pg.keyboard.press("Enter")
    pg.keyboard.type("1")
    pg.keyboard.press("Escape")                                  # undone
    assert "fm-changed" not in (pg.get_attribute(cell.format(2), "class") or "")
    assert "fm-changed" in pg.get_attribute(cell.format(1), "class")
    assert pg.locator("#p-buckets .fm-toolbar [data-fm-badge]").inner_text() == "2 changes not saved"
    pg.evaluate("""() => { const s = document.querySelector('#p-buckets'); const f = JSON.parse(s.getAttribute('data-form'));
      f.rows[4].masked = ['limit']; delete f.rows[4].values.limit; window.__f = f; }""")
    pg.screenshot(path=str(IMG / "form-cells.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    pg.click(".fm-review-open")
    assert "Fix 1 problem first" in pg.locator(".fm-review-box").inner_text()
    pg.keyboard.press("Escape")
    pg.click(cell.format(0))
    pg.keyboard.press("Control+A")
    pg.keyboard.type("250,000,000")
    pg.keyboard.press("Enter")
    assert pg.locator("#p-buckets tr.fm-why").count() == 0          # fixed: no reason left under any row
    pg.click(".fm-review-open")
    pg.click(".fm-review-box .fm-primary")
    wait(pg, "document.querySelector('#p-buckets .fm-badge-saved')")
    assert "Draft saved · 2 changes" in pg.locator("#p-buckets .fm-toolbar").inner_text()
    drafts = pg.request.get(stack + "/api/drafts?kind=credit-limit&id=LIM-ALDERSHOT").json()
    assert len(drafts) == 1 and drafts[0]["mode"] == "cells" and drafts[0]["changes"] == 2
    pg.click(".fm-undo")
    assert pg.locator("#p-buckets td.fm-changed").count() == 0
    pg.evaluate("drishtiForms.reset('buckets', window.__f)")
    hidden = pg.locator("#p-buckets tr[data-frow='4'] td[data-fcell='limit']")
    assert hidden.inner_text() == "Hidden for you" and "fm-editable" not in hidden.get_attribute("class")
    pg.close()


def test_new_in_a_guided_drawer_with_a_live_hint(stack, browser):
    pg = page(browser, stack, DESK)
    forget_drafts(pg, stack)
    pg.click("[data-fm-new]")
    dr = pg.locator("[data-fm-drawer]")
    dr.wait_for()
    assert pg.locator("[data-fm-scrim]").is_visible()
    assert dr.locator(".fm-step").all_inner_texts() == ["1 · Who", "2 · What", "3 · Check"]
    dr.locator(".fm-next").click()                               # nothing chosen: Next says what is missing
    assert "Counterparty is required" in dr.locator("[data-msgs='counterparty']").inner_text()
    pg.select_option("[data-fm-drawer] [data-field='counterparty']", index=1)
    dr.locator(".fm-next").click()
    assert dr.locator(".fm-step").nth(0).inner_text() == "1 · Who ✓"
    assert "So far: Counterparty" in dr.locator(".fm-sofar").inner_text()
    assert "fill in the fields above" in dr.locator("[data-fm-hint]").inner_text()
    pg.select_option("[data-fm-drawer] [data-field='product_type']", "IRS")
    pg.fill("[data-fm-drawer] [data-field='notional']", "25m")
    pg.select_option("[data-fm-drawer] [data-field='currency']", "USD")
    pg.fill("[data-fm-drawer] [data-field='maturity_date']", "2031-06-30")
    hint = dr.locator("[data-fm-hint]").inner_text()
    assert "PFE limit after this trade:" in hint and " of " in hint and "%)" in hint
    assert dr.locator("[data-fm-badge]").inner_text() == "Draft · not saved"
    pg.screenshot(path=str(IMG / "form-drawer.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 860})
    dr.locator(".fm-save").click()
    wait(pg, "document.querySelector('[data-fm-drawer] .fm-badge-saved')")
    dr.locator(".fm-next").click()
    assert dr.locator(".fm-summary").inner_text().count("IRS") == 1
    pg.keyboard.press("Escape")                                  # saved: closes without asking
    assert pg.locator("[data-fm-drawer]").count() == 0 and not pg.dialogs
    pg.reload()
    wait(pg, "document.querySelector('[data-fm-drafts]')")
    pg.click("[data-fm-drafts] .fm-link:text('Resume')")
    assert pg.locator("[data-fm-drawer] .fm-step.on").inner_text() == "2 · What"
    forget_drafts(pg, stack)
    pg.close()


def test_an_entity_by_search_money_a_worked_out_default_and_a_time(stack, browser):
    pg = page(browser, stack, DESK)
    forget_drafts(pg, stack)
    pg.click("[data-fm-new]")
    dr = pg.locator("[data-fm-drawer]")
    dr.wait_for()
    pg.select_option("[data-fm-drawer] [data-field='counterparty']", index=1)
    ent = "[data-fm-drawer] [data-field='legal_entity']"
    pg.fill(ent, "europe")                                       # search by name: the list narrows as people type
    opts = dr.locator(".fm-entity-list li[role='option']")
    assert opts.count() >= 1 and all("europe" in t.lower() for t in opts.all_inner_texts())
    assert pg.get_attribute(ent, "aria-expanded") == "true"
    pg.keyboard.press("Enter")
    kept = pg.get_attribute(ent, "data-value")
    assert kept.startswith("LE-") and pg.input_value(ent).endswith("· " + kept)
    assert dr.locator("[data-fm-open='legal_entity']").get_attribute("href") == "/v/legal-entity/" + kept
    dr.locator(".fm-next").click()
    pg.fill("[data-fm-drawer] [data-field='notional']", "lots")
    assert dr.locator("[data-msgs='notional']").inner_text().strip() == "Notional is an amount (1,000,000, 1.5m or 250k)"
    pg.fill("[data-fm-drawer] [data-field='notional']", "1.5m")
    assert dr.locator("[data-fm-field='notional'] .fm-unit").inner_text() == "—"
    pg.select_option("[data-fm-drawer] [data-field='currency']", "EUR")
    assert dr.locator("[data-fm-field='notional'] .fm-unit").inner_text() == "EUR"           # the currency the currency field holds
    fee = "[data-fm-drawer] [data-field='upfront_fee']"
    assert pg.input_value(fee) == "1,500" and "Worked out: notional * 0.001" in dr.locator("[data-fm-auto]").inner_text()
    pg.fill("[data-fm-drawer] [data-field='notional']", "2m")
    assert pg.input_value(fee) == "2,000"                       # worked out again as people type
    pg.fill(fee, "2.5k")
    assert dr.locator("[data-fm-auto]").count() == 0             # typed over: no longer worked out
    pg.locator("[data-fm-drawer] [data-field='executed_at']").focus()
    assert pg.input_value(fee) == "2,500"                        # grouped once the field is left
    assert dr.locator("[data-fm-field='executed_at'] .fm-unit").inner_text() == "Europe/London"
    pg.fill("[data-fm-drawer] [data-field='executed_at']", "2026-10-23T14:05")
    pg.fill("[data-fm-drawer] [data-field='maturity_date']", "2031-06-30")
    pg.select_option("[data-fm-drawer] [data-field='product_type']", "IRS")
    dr.locator(".fm-next").click()
    summary = dr.locator(".fm-summary").inner_text()
    assert "2,000,000 EUR" in summary and "2,500 EUR" in summary and "2026-10-23 14:05 Europe/London" in summary and kept in summary
    dr.locator(".fm-save").click()
    wait(pg, "document.querySelector('[data-fm-drawer] .fm-badge-saved')")
    [d] = pg.request.get(stack + "/api/drafts?kind=portfolio-trades&id=BOOK-RATES-2").json()
    v = d["records"][0]["values"]
    assert (v["notional"], v["upfront_fee"], v["legal_entity"], v["executed_at"]) == (2000000, 2500, kept, "2026-10-23T14:05")
    forget_drafts(pg, stack)
    pg.close()


def test_an_entity_field_searches_the_server_as_people_type(stack, browser):
    pg = page(browser, stack, DESK)
    forget_drafts(pg, stack)
    pg.click("[data-fm-new]")
    dr = pg.locator("[data-fm-drawer]")
    dr.wait_for()
    pg.select_option("[data-fm-drawer] [data-field='counterparty']", index=1)
    ent = "[data-fm-drawer] [data-field='legal_entity']"
    with pg.expect_response(lambda r: "/api/forms/lookup" in r.url) as got:
        pg.fill(ent, "bank")                                      # one ask, 250 ms after the last key, read as the viewer
    assert "kind=legal-entity" in got.value.url and "q=bank" in got.value.url and got.value.ok
    results = got.value.json()["results"]
    assert results and all("bank" in (r["label"] + r["id"]).lower() for r in results)
    wait(pg, "!document.querySelector('[data-fm-drawer] .fm-entity-status')")
    assert dr.locator(".fm-entity-list li[role='option']").count() >= 1
    with pg.expect_response(lambda r: "/api/forms/lookup" in r.url):
        pg.fill(ent, "zzqx-nothing")
    wait(pg, "document.querySelector('[data-fm-drawer] .fm-entity-none')")
    assert dr.locator(".fm-entity-none").inner_text() == "No match for “zzqx-nothing”"
    pg.route("**/api/forms/lookup**", lambda route: route.abort())  # offline: the choices the page brought still answer
    pg.fill(ent, "europe")
    wait(pg, "!document.querySelector('[data-fm-drawer] .fm-entity-status')")
    assert dr.locator(".fm-entity-list li[role='option']").count() >= 1
    pg.unroute("**/api/forms/lookup**")
    with pg.expect_response(lambda r: "/api/forms/lookup" in r.url):
        pg.fill(ent, "bank")
    wait(pg, "!document.querySelector('[data-fm-drawer] .fm-entity-status')")
    pg.keyboard.press("ArrowDown")
    pg.keyboard.press("ArrowUp")
    pg.keyboard.press("Enter")                                   # picked with the keys
    kept = pg.get_attribute(ent, "data-value")
    assert kept == results[0]["id"] and pg.get_attribute(ent, "aria-expanded") == "false"
    dr.locator(".fm-save").click()
    wait(pg, "document.querySelector('[data-fm-drawer] .fm-badge-saved')")
    [d] = pg.request.get(stack + "/api/drafts?kind=portfolio-trades&id=BOOK-RATES-2").json()
    assert d["records"][0]["values"]["legal_entity"] == kept
    forget_drafts(pg, stack)
    pg.close()


@pytest.mark.parametrize("theme", ["terminal", "light"])
def test_two_themes_draw_the_three_forms(stack, browser, theme):
    pg = page(browser, stack, DESK, theme=theme)
    pg.click("#p-trades tbody tr[data-frow='0'] td:nth-child(2)")
    pg.locator("[data-fm-side]").wait_for()
    colours = pg.evaluate("[getComputedStyle(document.querySelector('.fm-side')).backgroundColor, getComputedStyle(document.body).color]")
    assert colours[0] != colours[1]
    assert not overflow(pg)
    pg.close()


def test_a_phone_stacks_the_form_and_the_drawer_fills_the_screen(stack, browser):
    pg = page(browser, stack, DESK, theme="light", size={"width": 390, "height": 844})
    pg.click("#p-trades tbody tr[data-frow='0'] td:nth-child(2)")
    box = pg.locator("[data-fm-side]").bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 390
    pg.locator("[data-fm-side] .fm-x").click()
    pg.click("[data-fm-new]")
    d = pg.locator("[data-fm-drawer]").bounding_box()
    assert d["x"] <= 1 and d["width"] >= 388
    assert pg.locator("[data-fm-drawer] .fm-in").first.bounding_box()["height"] >= 36
    pg.close()
