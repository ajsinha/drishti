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

"""The top bar's Live control is one round toggle button at every width, right of the theme switcher and the same size as the person's initials (a 44 px hit area on touch): a green circle (a filled dot) while live, a red
circle (pause bars) once paused, no visible text, aria-pressed and a label that say what a click does. A click pauses (the
page becomes a static snapshot of the current business date, so no more updates arrive), the next click resumes; the choice
follows the user to the next page; Enter and Space work; both themes. Skipped when Playwright or its Chromium is missing."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _eventually, _ticks, browser, console_url  # noqa: E402,F401 - the shared fixtures

VIEW = "/v/trade/IRS-48213"
BTN = ".tbar .asof-live"
ON, OFF = "Live updates on — click to pause", "Live updates paused — click to resume"
STATE = """() => { const b = document.querySelector('.tbar .asof-live'), ring = b.querySelector('.ring'), r = ring.getBoundingClientRect(), c = getComputedStyle(ring);
  const av = document.querySelector('.tbar .avatar').getBoundingClientRect(), hit = b.getBoundingClientRect();
  const d = getComputedStyle(b.querySelector('.dot'), '::before');
  return {w: r.width, h: r.height, aw: av.width, ah: av.height, hit: Math.min(hit.width, hit.height), text: [...b.childNodes].filter(n => !(n.classList && n.classList.contains('visually-hidden'))).map(n => n.textContent).join('').trim(), stream: b.getAttribute('data-live-state'), visible: !!r.width, bg: c.backgroundColor, radius: c.borderRadius,
          pressed: b.getAttribute('aria-pressed'), label: b.getAttribute('aria-label'), title: b.title, tag: b.tagName,
          bars: d.content !== 'none', sw: document.documentElement.scrollWidth, vw: document.documentElement.clientWidth}; }"""


def _rgb(s):
    return tuple(int(x) for x in s[s.index("(") + 1:s.index(")")].split(",")[:3])


@pytest.mark.parametrize("width,mobile", [(1280, False), (390, True)])
@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_live_is_a_green_red_toggle(browser, console_url, width, mobile, scheme):
    ctx = browser.new_context(viewport={"width": width, "height": 800}, is_mobile=mobile, has_touch=mobile, color_scheme=scheme)
    page = ctx.new_page()
    try:
        page.goto(console_url + VIEW, wait_until="load")
        s = page.evaluate(STATE)
        assert s["tag"] == "BUTTON" and s["visible"] and s["text"] == "" and s["radius"] in ("50%", "22px")
        assert (round(s["w"]), round(s["h"])) == (round(s["aw"]), round(s["ah"])), s   # the same circle as the initials beside it
        assert s["hit"] >= (44 if mobile else 34), s                              # the button around it is the hit area
        assert s["pressed"] == "true" and s["label"] == ON and s["title"].startswith(ON) and not s["bars"]
        assert s["stream"] in ("live", "static") and (" · Live" in s["title"] or " · Static" in s["title"]), s   # the stream of this page, on the same button
        green = _rgb(s["bg"])
        assert green[1] > green[0] and green[1] > green[2], f"live should be green, got {s['bg']}"
        assert s["sw"] <= s["vw"]
        _eventually(lambda: _ticks(page) > 0, page, "live updates on a live view")

        page.click(BTN)                                                    # pause
        page.wait_for_selector(f"{BTN}[aria-pressed=false]")
        s = page.evaluate(STATE)
        red = _rgb(s["bg"])
        assert red[0] > red[1] and red[0] > red[2], f"paused should be red, got {s['bg']}"
        assert s["label"] == OFF and s["title"].startswith(OFF) and s["bars"] and s["text"] == "" and round(s["w"]) == round(s["aw"])
        before = _ticks(page)
        page.wait_for_timeout(1200)
        assert _ticks(page) == before, "updates kept arriving while paused"

        page.goto(console_url + "/t", wait_until="load")                   # the choice follows the user
        assert page.get_attribute(BTN, "aria-pressed") == "false"

        page.focus(BTN)                                                    # keyboard: Enter resumes
        page.keyboard.press("Enter")
        page.wait_for_selector(f"{BTN}[aria-pressed=true]")
        page.focus(BTN)                                                    # and Space pauses again
        page.keyboard.press("Space")
        page.wait_for_selector(f"{BTN}[aria-pressed=false]")
        page.focus(BTN)
        assert page.evaluate("getComputedStyle(document.querySelector('.tbar .asof-live')).outlineStyle") != "none"
        page.click(BTN)                                                    # resume: updates arrive again
        page.wait_for_selector(f"{BTN}[aria-pressed=true]")
        page.goto(console_url + VIEW, wait_until="load")
        _eventually(lambda: _ticks(page) > 0, page, "live updates after resuming")
    finally:
        ctx.close()
