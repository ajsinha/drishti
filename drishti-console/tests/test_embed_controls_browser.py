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
real scratch server (the stack of test_embed_elements_browser.py, which this file reuses: ports 18966/17966/17965 by default; set
DRISHTI_ELEMENTS_SERVER_PORT, DRISHTI_ELEMENTS_CONSOLE_PORT and DRISHTI_ELEMENTS_HOST_PORT to run it beside another run).

What is checked: the element draws the Sutra's controls in its shadow root; the `controls` attribute sets their values; a change asks the
console for only the panels that read the control (the others do not move); the host page's address is never touched; the change reaches the
host as a composed `drishti:controls` event; the control works from the keyboard. The trade (static since 2.3.0) shows the controls; a live netting set
shows that the live subscription is built with the values.

    drishti-console/.venv/bin/python -m pytest -q drishti-console/tests/test_embed_controls_browser.py"""
import json
import os
import re
import urllib.parse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect  # noqa: E402

from test_embed_elements_browser import SR, browser, host, stack  # noqa: E402,F401 - fixtures and helpers
from scratch_ports import worker_ports  # noqa: E402

# this file's own (server, console, host) ports plus the pytest-xdist worker offset: the stack of test_embed_elements_browser.py switches to them
PORTS = worker_ports(*(os.environ.get(k, d) for k, d in (("DRISHTI_ELEMENTS_SERVER_PORT", 18966), ("DRISHTI_ELEMENTS_CONSOLE_PORT", 17966), ("DRISHTI_ELEMENTS_HOST_PORT", 17965))))

IRS = "MX-20000001"
LEG = f"{SR}.querySelectorAll('#p-event-list tbody tr')"
# the panel the controls read since 2.3.0: the trade's events, the leg in the first column ("deal" for an event of the whole deal, which every
# leg filter keeps)
ROWS = f"[...{LEG}].filter(r => r.cells.length > 1).map(r => r.cells[0].textContent.trim())"


def rows(h):
    return h.js(ROWS)


def legs(h):
    return [x for x in rows(h) if x != "deal"]


def show_irs(h, controls=None):
    h.open()
    h.page.evaluate("""(args) => { const el = document.querySelector('#main'); window.__ctl = []; window.__done = [];
        document.addEventListener('drishti:controls', (e) => window.__ctl.push(e.detail));
        el.shadowRoot.addEventListener('drishti:controls-done', (e) => window.__done.push(e.detail));
        el.setAttribute('kind', 'trade'); el.setAttribute('entity', args.id); if (args.controls) { el.setAttribute('controls', args.controls); } }""", {"id": IRS, "controls": controls})
    # a trade is static since 2.3.0 (it changes when its source books a version): the view settles as `static`, with no live subscription
    h.until(lambda: h.js("!!document.querySelector('#main').shadowRoot.querySelector('#p-event-list') && document.querySelector('#main').getAttribute('data-state') === 'static'"), 30)


def test_the_element_draws_the_controls_and_the_attribute_sets_them(host):
    h = host()
    show_irs(h, "legs=2")
    h.until(lambda: h.js(f"!!{SR}.querySelector('[data-ctl=legs]')"), 30)
    assert h.js(f"{SR}.querySelector('#p-event-list .pnl-h [data-ctl=legs]') !== null")            # in the panel's header, inside the shadow root
    assert h.js(f"{SR}.querySelector('[data-ctl=when]') !== null")
    assert h.js("document.querySelectorAll('[data-ctl]').length") == 0                                # nothing leaked into the host page
    assert h.js(f"{SR}.querySelector('[data-ctl=legs] summary').textContent.trim()") == "2"
    got = legs(h)
    assert got and set(got) == {"2"}
    assert h.js("document.querySelector('#main').controls") == "legs=2"


def test_a_change_asks_for_only_the_panels_that_read_it_and_tells_the_host(host):
    h = host()
    show_irs(h)
    h.until(lambda: h.js(f"!!{SR}.querySelector('[data-ctl=legs]')"), 30)
    total = len(legs(h))
    assert total > 2
    h.js(f"{SR}.querySelector('#p-ticket').setAttribute('data-mark', 'kept')")
    before = len(h.requests)
    h.page.locator("#main").locator("[data-ctl=legs] summary").click()
    h.page.locator("#main").locator("[data-ctl=legs] input[value='1']").uncheck()
    h.until(lambda: h.js("window.__done && window.__done.length > 0"), 30)
    got = legs(h)
    assert got and set(got) == {"2"} and len(got) < total
    asked = [r for r in h.requests[before:] if "/panels?" in r[1]]
    assert len(asked) == 1 and "panels=event-list" in asked[0][1] and "ctl.legs=2" in asked[0][1]
    assert h.js(f"{SR}.querySelector('#p-ticket').getAttribute('data-mark')") == "kept"            # the other panels did not move
    assert h.js("location.search") == ""                                                                   # the host page's address is its own
    ev = h.js("window.__ctl")
    assert ev and ev[-1]["query"] == "ctl.legs=2"                                                         # a composed event the host may listen to
    assert h.js("document.querySelector('#main').controls") == "legs=2"


def test_a_live_views_subscription_is_built_with_the_control_values(host):
    """A live kind with controls (a netting set: its member trades by asset class, product, notional, status): a change reaches the channel."""
    h = host()
    h.open()
    h.page.evaluate("""() => { const el = document.querySelector('#main'); window.__done = [];
        el.shadowRoot.addEventListener('drishti:controls-done', (e) => window.__done.push(e.detail));
        el.setAttribute('kind', 'netting-set'); el.setAttribute('entity', 'NS-ALDERSHOT-FRA'); }""")
    h.until(lambda: h.js(f"!!{SR}.querySelector('[data-ctl=asset]') && document.querySelector('#main').getAttribute('data-state') === 'live'"), 30, "the live netting set")
    asset = h.js(f"[...{SR}.querySelectorAll('[data-ctl=asset] option')].map(o => o.value).filter(Boolean)[0]")
    assert asset, "the asset-class control has no option"
    h.page.locator("#main").locator("[data-ctl=asset] select").select_option(asset)
    h.until(lambda: h.js("window.__done && window.__done.length > 0"), 30, "the controls applied")
    assert urllib.parse.unquote(h.js("document.querySelector('#main').controls")) == f"asset={asset}"
    # the live subscription is built with the values: the channel is told
    h.until(lambda: any("ctl.asset" in json.dumps(p) for p in h.channel_posts()) or any("ctl.asset" in r[1] for r in h.channel_gets()), what="a subscription with the controls")


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
    assert re.search(r"Events · 2099-01-01\.\.", h.js(f"{SR}.querySelector('#p-event-list h3').textContent"))
    assert h.js("document.querySelector('#main').getAttribute('data-state')") == "static"
