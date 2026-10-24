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

"""The Sutra designer in a real browser (docs/guides/SCREEN_DESIGNER.md section 31), ui.designer on, a real server behind the console:

* Customize on a trade page: reorder, hide, a used table opened up and given other columns, "when I click a row in the cash flows show the
  row's details beside it" (a click fills the child), a kit fragment added, Done; every step redraws only the panels it changed, with no
  navigation; the page opens with it again; Reset gives the pack's page;
* Customize on a book page: "when I click a row show its counterparty's credit limits", a record of another pack, fills on a click; an
  existing panel of the page moved under the table;
* Describe builds a book-desk-like screen from one line, its live result fills on a click, and it is saved to the workspace; a line with
  errors clears the result and says what to do;
* a phone, three themes; the screenshots for the review (docs/guides/img/designer/).

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py). ``DRISHTI_DZ_BASE`` points the tests at a console
already running with ui.designer on (development)."""
import os
from urllib.parse import quote
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

TRADE = "/v/trade/MX-20000001"
BOOK = "/v/portfolio-trades/BOOK-RATES-2"
IMG = Path(__file__).resolve().parents[2] / "docs" / "guides" / "img" / "designer"
LINE = "TRD BOOK-RATES-2 · click a trade → cash flows + CPTY limits · refresh 30s"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    if os.environ.get("DRISHTI_DZ_BASE"):
        yield os.environ["DRISHTI_DZ_BASE"]
        return
    old = os.environ.get("DRISHTI_UI_DESIGNER")
    os.environ["DRISHTI_UI_DESIGNER"] = "true"             # read when the console's settings load: this console only
    try:
        yield from (str(s) for s in _stack(tmp_path_factory, {}))
    finally:
        if old is None:
            os.environ.pop("DRISHTI_UI_DESIGNER", None)
        else:
            os.environ["DRISHTI_UI_DESIGNER"] = old


def page_in(browser, theme, size):
    pg = browser.new_page(viewport=size)
    pg.add_init_script("document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '%s'))" % theme)
    return pg


def customizing(pg, base, path=TRADE):
    pg.goto(base + path)
    with pg.expect_navigation():
        pg.click("[data-customize-open]")
    wait(pg, "document.querySelector('[data-dz-ready]')")
    pg.navigations = []
    pg.on("framenavigated", lambda f: f == pg.main_frame and pg.navigations.append(f.url))


def part_titles(pg):
    return pg.evaluate("[...document.querySelectorAll('.dz-tools')].map(t => t.closest('section').querySelector('h3, h2')?.textContent.trim() || '')")


def reloaded(pg, action):
    """Runs a step and waits for it to be drawn: only the panels it changed, never a navigation (no reload, the scroll kept)."""
    rev = pg.locator("[data-view]").get_attribute("data-dz-rev")
    before = len(pg.navigations)
    action()
    y = pg.evaluate("window.scrollY")                                                       # where the click left the page
    wait(pg, "document.querySelector('[data-view]').getAttribute('data-dz-rev') !== %r" % rev)
    assert len(pg.navigations) == before, "a Customize step reloaded the page"
    top = pg.evaluate("document.documentElement.scrollHeight - window.innerHeight")
    assert abs(pg.evaluate("window.scrollY") - min(y, top)) < 60, "the scroll position was lost"


def test_customize_a_trade_page_then_reopen_and_reset(stack, browser):
    pg = page_in(browser, "terminal", {"width": 1440, "height": 900})
    customizing(pg, stack)
    assert pg.locator(".dz-bar").inner_text().strip().startswith("Customizing")
    first = pg.locator(".dz-tools").first.get_attribute("data-dz-part")
    ids = pg.evaluate("[...document.querySelectorAll('.dz-tools')].map(t => t.closest('section').id)")
    assert first == "0" and ids[0] == "p-ticket"

    reloaded(pg, lambda: pg.locator("#p-ticket [data-dz-down]").click())                   # reorder
    ids = pg.evaluate("[...document.querySelectorAll('.vmain section.pnl[id], .vright section.pnl[id]')].map(s => s.id)")
    assert ids.index("p-legs") < ids.index("p-ticket")

    reloaded(pg, lambda: pg.locator("#p-built [data-dz-hide]").click())                    # hide
    assert pg.locator("#p-built").count() == 0
    assert pg.locator("[data-dz-show='How this view was built']").count() == 1

    reloaded(pg, lambda: pg.locator("#p-flows [data-dz-columns]").click())                 # a used table is opened up ...
    pg.locator("#p-flows [data-dz-columns]").click()                                        # ... and takes other columns
    before = pg.locator("#p-flows thead th").count()
    box = pg.locator("#p-flows .dz-cols")
    box.locator("input:checked").last.uncheck()
    reloaded(pg, lambda: box.locator("[data-dz-cols-apply]").click())
    assert pg.locator("#p-flows thead th").count() == before - 1

    pg.select_option("[data-dz-click-from]", "t:flows")                                     # when I click a row in the flows ...
    pg.select_option("[data-dz-click-place]", "detail-right")
    reloaded(pg, lambda: pg.locator("[data-dz-click-apply]").click())
    pg.locator("#p-flows tbody tr[data-ev]").first.locator("td").nth(1).click()
    wait(pg, "document.querySelector('#p-flows-row dd')")                                   # ... the row's details fill beside it
    assert pg.locator("[data-wire-kids='flows'][data-place='detail-right'] #p-flows-row").count() == 1

    pg.evaluate("window.__ticket = document.querySelector('#p-ticket')")
    pg.fill("[data-dz-search]", "note")                                                    # add a kit fragment
    wait(pg, "document.querySelector('[data-dz-add=\"explainer-note@1\"]')")
    reloaded(pg, lambda: pg.locator("[data-dz-add='explainer-note@1']").click())
    assert pg.locator("#p-note").count() == 1

    kept = pg.evaluate("document.querySelector('#p-ticket') === window.__ticket")
    pg.locator(".dz-yaml summary").click()
    assert "children:" in pg.locator(".dz-yaml pre").inner_text()                           # the Sutra, only when asked
    assert kept, "a panel the step did not change was drawn again"
    with pg.expect_navigation():
        pg.locator("[data-dz-done]").click()
    wait(pg, "document.querySelector('#p-note')")
    assert pg.locator(".dz-bar").count() == 0 and pg.locator(".dz-own").count() == 1        # the page opens with it, for this user

    pg.goto(stack + TRADE)                                                                  # reopened: still mine
    assert pg.locator("#p-note").count() == 1 and pg.locator("#p-built").count() == 0

    customizing(pg, stack)
    pg.on("dialog", lambda d: d.accept())
    with pg.expect_navigation():
        pg.locator("[data-dz-reset]").click()
    wait(pg, "document.querySelector('#p-built')")
    assert pg.locator("#p-note").count() == 0 and pg.locator(".dz-own").count() == 0        # the pack's page again
    pg.close()


def test_customize_screenshot_dark_and_on_a_phone_in_blue(stack, browser):
    pg = page_in(browser, "terminal", {"width": 1440, "height": 900})
    customizing(pg, stack)
    IMG.mkdir(parents=True, exist_ok=True)
    pg.screenshot(path=str(IMG / "customize.jpg"), type="jpeg", quality=84)
    pg.locator("[data-dz-cancel]").click()
    pg.close()
    phone = page_in(browser, "blue", {"width": 390, "height": 844})
    customizing(phone, stack)
    assert phone.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"
    assert phone.locator(".dz-side").bounding_box()["width"] > 300                           # under the page, full width
    phone.locator("[data-dz-cancel]").click()
    phone.close()


def test_describe_builds_a_book_desk_and_saves_it(stack, browser):
    pg = page_in(browser, "terminal", {"width": 1440, "height": 900})
    pg.goto(stack + "/designer/new?q=" + quote(LINE))
    wait(pg, "document.querySelector('[data-result]') && !document.querySelector('[data-result]').hidden")
    chips = pg.locator("[data-token]").all_inner_texts()
    assert any("Trades of book BOOK-RATES-2" in c for c in chips) and any("refresh every 30 s" in c for c in chips)
    frame = pg.frame_locator("[data-preview]")
    frame.locator("#p-trades tbody tr[data-ev]").first.locator("td").nth(1).click()
    frame.locator("tr.wire-kids #p-flows").wait_for()
    frame.locator("#p-limit dd").first.wait_for()
    IMG.mkdir(parents=True, exist_ok=True)
    pg.evaluate("window.scrollTo(0, 0)")
    pg.wait_for_timeout(300)
    pg.screenshot(path=str(IMG / "describe.jpg"), type="jpeg", quality=84)
    pg.locator("[data-show-sutra]").click()
    assert "refresh: 30" in pg.locator("[data-yaml]").inner_text()                         # the Sutra, read only, when asked
    pg.locator("[data-save]").click()
    wait(pg, "document.querySelector('[data-saved] a')")
    assert "Saved to your workspace" in pg.locator("[data-saved]").inner_text()
    pg.close()


def test_describe_says_what_it_did_not_understand_in_the_light_theme(stack, browser):
    pg = page_in(browser, "light", {"width": 1280, "height": 860})
    pg.goto(stack + "/designer/new?q=" + quote("TRD BOOK-RATES-2"))
    wait(pg, "document.querySelector('[data-result]') && !document.querySelector('[data-result]').hidden")
    pg.fill("#describe", "TRD BOOK-RATES-2 · click a trade → limits · pizza")
    pg.locator("[data-describe-form] button[type=submit]").click()
    wait(pg, "document.querySelectorAll('[data-token]').length === 3")
    assert pg.locator(".dz-token.dz-unsure").count() == 1 and pg.locator(".dz-token.dz-error").count() == 1
    assert "I did not understand 'pizza'" in pg.locator("[data-messages]").inner_text()
    assert pg.locator("[data-result]").is_hidden() and pg.locator("[data-fix]").is_visible()     # no stale screen: the last good one is gone
    assert "Fix the red part" in pg.locator("[data-fix]").inner_text()
    assert pg.locator("[data-preview]").get_attribute("src") == "about:blank"
    pg.screenshot(path=str(IMG / "describe-light.jpg"), type="jpeg", quality=84)
    pg.close()


def test_a_click_shows_a_linked_record_of_another_pack_and_a_moved_panel(stack, browser):
    pg = page_in(browser, "light", {"width": 1440, "height": 900})
    customizing(pg, stack, BOOK)
    pick = pg.locator("[data-dz-click-from] option").first.get_attribute("value")
    pg.select_option("[data-dz-click-from]", pick)
    if pick.startswith("u:"):                                                               # a used table: opened up by the first sentence
        reloaded(pg, lambda: pg.locator("[data-dz-click-apply]").click())
    pid = pg.locator("[data-dz-click-from] option").first.get_attribute("value")[2:]
    labels = pg.locator("[data-dz-click-show] option").all_inner_texts()
    limits = next(x for x in labels if "credit limits" in x)
    assert "its counterparty (core)" in labels and "its events" in labels, labels                  # linked across packs, in plain words
    pg.select_option("[data-dz-click-show]", label=limits)
    pg.select_option("[data-dz-click-place]", "detail-right")
    reloaded(pg, lambda: pg.locator("[data-dz-click-apply]").click())
    pg.locator(f"#p-{pid} tbody tr[data-ev]").first.locator("td").nth(1).click()
    wait(pg, f"document.querySelector('[data-wire-kids=\"{pid}\"] #p-{pid}-credit-limit tbody tr')")   # filled from counterparty-risk
    IMG.mkdir(parents=True, exist_ok=True)
    pg.screenshot(path=str(IMG / "customize-linked.jpg"), type="jpeg", quality=84)

    moved = next(x for x in pg.locator("[data-dz-click-show] option").all_inner_texts() if "(moved here)" in x)
    title = moved.split("“")[1].split("”")[0]
    tops = pg.locator(".dz-tools strong").all_inner_texts()
    pg.select_option("[data-dz-click-show]", label=moved)
    reloaded(pg, lambda: pg.locator("[data-dz-click-apply]").click())
    assert pg.locator(".dz-tools strong").all_inner_texts() == [t for t in tops if t != title]       # no longer a panel of the page ...
    pg.locator(f"#p-{pid} tbody tr[data-ev]").first.locator("td").nth(1).click()
    wait(pg, f"document.querySelector('[data-wire-kids=\"{pid}\"] section.pnl')")          # ... it opens under the table on a click
    pg.locator("[data-dz-cancel]").click()
    pg.close()


def _figures(pg):
    return pg.evaluate("[...document.querySelectorAll('section.pnl[id]')].filter(s => /^p-figure(-\\d+)?$/.test(s.id)).length")


def _figure_card(pg):
    pg.fill("[data-dz-search]", "key figure")
    wait(pg, "document.querySelector('[data-dz-add=\"key-figure@1\"]')")
    return pg.locator("[data-dz-add='key-figure@1']")


def test_a_key_figure_opens_its_settings_shows_the_chosen_number_and_is_removed_with_undo(stack, browser):
    pg = page_in(browser, "terminal", {"width": 1440, "height": 900})
    customizing(pg, stack)
    assert pg.locator("#p-ticket [data-dz-remove]").count() == 0 and pg.locator("#p-ticket [data-dz-hide]").count() == 1   # the pack's: Hide
    reloaded(pg, lambda: _figure_card(pg).click())
    assert _figures(pg) == 1
    wait(pg, "document.querySelector('[data-dz-settings-box] [data-dz-setting=\"value\"]')")       # its settings open at once
    box = pg.locator("[data-dz-settings-box]")
    assert "Which number?" in box.inner_text()
    numbers = box.locator("[data-dz-setting='value'] option").evaluate_all("os => os.map(o => o.value)")
    pick = "$.QUANTITY" if "$.QUANTITY" in numbers else numbers[-1]
    box.locator("[data-dz-setting='value']").select_option(pick)
    box.locator("[data-dz-setting='label']").fill("The chosen number")
    reloaded(pg, lambda: box.locator("[data-dz-settings-apply]").click())
    figure = pg.locator("#p-figure")
    text = figure.inner_text()
    assert "The chosen number" in text and "No data" not in text and any(ch.isdigit() for ch in text), text
    assert pg.locator("#p-figure [data-dz-need]").count() == 0

    index = int(pg.locator("#p-figure .dz-tools").get_attribute("data-dz-part"))              # a number the record does not hold ...
    pg.evaluate("""([d, i]) => fetch('/designer/op/' + d, {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json'},
                   body: JSON.stringify({op: 'settings', index: i, values: {value: '$.NOT_IN_THIS_TRADE'}, kind: 'trade', id: 'MX-20000001'})})""",
                [pg.locator("[data-view]").get_attribute("data-design"), index])
    pg.reload()
    wait(pg, "document.querySelector('[data-dz-ready]')")
    pg.navigations = []
    need = pg.locator("#p-figure [data-dz-need]")
    assert "Choose which number" in need.inner_text()                                           # ... is said on the panel, never silently empty
    need.locator("[data-dz-need-open]").click()
    wait(pg, "document.querySelector('[data-dz-settings-box] [data-dz-settings-need]')")

    reloaded(pg, lambda: pg.locator("#p-figure [data-dz-remove]").click())                    # Remove, then Undo
    assert _figures(pg) == 0 and pg.locator("[data-dz-toast]").is_visible()
    reloaded(pg, lambda: pg.locator("[data-dz-undo]").click())
    assert _figures(pg) == 1 and pg.locator("[data-dz-toast]").count() == 0
    pg.locator("[data-dz-cancel]").click()
    pg.close()


def test_a_double_click_or_clicks_in_a_burst_add_one_key_figure(stack, browser):
    pg = page_in(browser, "terminal", {"width": 1440, "height": 900})
    customizing(pg, stack)
    card = _figure_card(pg)
    reloaded(pg, lambda: card.dblclick())
    pg.wait_for_timeout(600)                                                                    # a second add would have landed by now
    wait(pg, "document.querySelector('[data-dz-settings-box]')")
    assert _figures(pg) == 1
    card = _figure_card(pg)
    reloaded(pg, lambda: card.evaluate("c => { c.click(); c.click(); c.click(); }"))
    pg.wait_for_timeout(600)
    assert _figures(pg) == 2                                                                    # three clicks in one burst: one more panel
    pg.locator("[data-dz-cancel]").click()
    pg.close()
