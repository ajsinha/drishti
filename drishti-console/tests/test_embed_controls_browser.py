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

"""Controls inside <drishti-view> (ELEMENTS.md, RUPAKA.md 8.1) in Chromium, Firefox and WebKit, against a host application on another origin and a
real scratch server (the stack of test_embed_elements_browser.py, which this file reuses: ports 18969/17969/17968 by default; set
DRISHTI_ELEMENTS_SERVER_PORT, DRISHTI_ELEMENTS_CONSOLE_PORT and DRISHTI_ELEMENTS_HOST_PORT to run it beside another run).

What is checked: the element draws the Sutra's controls in its shadow root; the `controls` attribute sets their values; a change asks the
console for only the panels that read the control (the others do not move); the host page's address is never touched; the change reaches the
host as a composed `drishti:controls` event and the live subscription is built with the values; the control works from the keyboard.

    drishti-console/.venv/bin/python -m pytest -q drishti-console/tests/test_embed_controls_browser.py"""
import json
import re

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect  # noqa: E402

from test_embed_elements_browser import SR, browser, host, stack  # noqa: E402,F401 - fixtures and helpers

IRS = "MX-20000001"
LEG = f"{SR}.querySelectorAll('#p-schedule tbody tr')"


def rows(h):
    return h.js(f"[...{LEG}].map(r => r.cells[1].textContent.trim())")


def legs(h):
    return h.js(f"[...{LEG}].map(r => r.cells[1].textContent.trim())")


def show_irs(h, controls=None):
    h.open()
    h.page.evaluate("""(args) => { const el = document.querySelector('#main'); window.__ctl = []; window.__done = [];
        document.addEventListener('drishti:controls', (e) => window.__ctl.push(e.detail));
        el.shadowRoot.addEventListener('drishti:controls-done', (e) => window.__done.push(e.detail));
        el.setAttribute('kind', 'trade'); el.setAttribute('entity', args.id); if (args.controls) { el.setAttribute('controls', args.controls); } }""", {"id": IRS, "controls": controls})
    h.until(lambda: h.js("!!document.querySelector('#main').shadowRoot.querySelector('#p-schedule') && document.querySelector('#main').getAttribute('data-state') === 'live'"), 30)


def test_the_element_draws_the_controls_and_the_attribute_sets_them(host):
    h = host()
    show_irs(h, "legs=2")
    h.until(lambda: h.js(f"!!{SR}.querySelector('[data-ctl=legs]')"), 30)
    assert h.js(f"{SR}.querySelector('#p-schedule .pnl-h [data-ctl=legs]') !== null")            # in the panel's header, inside the shadow root
    assert h.js(f"{SR}.querySelector('[data-ctl=when]') !== null")
    assert h.js("document.querySelectorAll('[data-ctl]').length") == 0                                # nothing leaked into the host page
    assert h.js(f"{SR}.querySelector('[data-ctl=legs] summary').textContent.trim()") == "2"
    got = h.js(f"[...{SR}.querySelectorAll('#p-schedule tbody tr')].map(r => r.cells[1].textContent.trim())")
    assert got and set(got) == {"2"}
    assert h.js("document.querySelector('#main').controls") == "legs=2"


def test_a_change_asks_for_only_the_panels_that_read_it_and_tells_the_host(host):
    h = host()
    show_irs(h)
    h.until(lambda: h.js(f"!!{SR}.querySelector('[data-ctl=legs]')"), 30)
    total = len(rows(h))
    assert total > 2
    h.js(f"{SR}.querySelector('#p-terms').setAttribute('data-mark', 'kept')")
    before = len(h.requests)
    h.page.locator("#main").locator("[data-ctl=legs] summary").click()
    h.page.locator("#main").locator("[data-ctl=legs] input[value='1']").uncheck()
    h.until(lambda: h.js("window.__done && window.__done.length > 0"), 30)
    got = h.js(f"[...{SR}.querySelectorAll('#p-schedule tbody tr')].map(r => r.cells[1].textContent.trim())")
    assert got and set(got) == {"2"} and len(got) < total
    asked = [r for r in h.requests[before:] if "/panels?" in r[1]]
    assert len(asked) == 1 and "panels=schedule" in asked[0][1] and "ctl.legs=2" in asked[0][1]
    assert h.js(f"{SR}.querySelector('#p-terms').getAttribute('data-mark')") == "kept"            # the other panels did not move
    assert h.js("location.search") == ""                                                                   # the host page's address is its own
    ev = h.js("window.__ctl")
    assert ev and ev[-1]["query"] == "ctl.legs=2"                                                         # a composed event the host may listen to
    assert h.js("document.querySelector('#main').controls") == "legs=2"
    # the live subscription is built with the values: the channel is told
    h.until(lambda: any("ctl.legs" in json.dumps(p) for p in h.channel_posts()) or any("ctl.legs" in r[1] for r in h.channel_gets()), what="a subscription with the controls")


def test_a_date_range_and_the_keyboard_work_inside_the_shadow_root(host):
    h = host()
    show_irs(h)
    h.until(lambda: h.js(f"!!{SR}.querySelector('[data-ctl=when]')"), 30)
    h.js(f"{SR}.querySelector('[data-ctl=legs] summary').focus()")
    h.page.keyboard.press("Enter")                                                                         # the multi-select opens by keyboard
    assert h.js(f"{SR}.querySelector('[data-ctl=legs] details').open")
    h.page.keyboard.press("Escape")
    assert not h.js(f"{SR}.querySelector('[data-ctl=legs] details').open")
    total = len(rows(h))
    h.page.locator("#main").locator("[data-ctl=when] .ctl-from").fill("2099-01-01")
    h.until(lambda: h.js("window.__done && window.__done.length > 0"), 30)
    assert len(rows(h)) == 0 and len(rows(h)) < total                                                      # after the last cashflow: no rows
    assert "2099-01-01.." in h.js("document.querySelector('#main').controls")
    assert re.search(r"Cashflows · paid 2099-01-01\.\.", h.js(f"{SR}.querySelector('#p-schedule h3').textContent"))
    assert h.js("document.querySelector('#main').getAttribute('data-state')") in ("live", "static")
