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

"""A page's Sutra opens in the designer at one click (a link from another site asks first), and it is that Sutra: from the "How this view was built" panel and from About this page's
"Why the page looks like this", against a REAL scratch server with the shipped packs (wb_live.py). The designer's YAML must be the
named Sutra (its name and version), not a blank starter or another Sutra. Skipped when Playwright, Chromium, the jar or a JDK is missing."""
import html
import re
import urllib.parse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from wb_live import BROWSER_WAIT_MS, live_console  # noqa: E402,F401

TRADE = "1000001"                                   # the first shipped sample trade: an ENDUR ComSwap


def designer_state(page):
    """(the open Design's id, its YAML) once the workbench has drawn it."""
    page.wait_for_function("() => window.drishtiWorkbench && window.drishtiWorkbench.store.state.id", timeout=BROWSER_WAIT_MS)
    page.wait_for_function("() => (window.drishtiWorkbench.store.state.yaml || '').length > 0", timeout=BROWSER_WAIT_MS)
    return page.evaluate("() => [window.drishtiWorkbench.store.state.id, window.drishtiWorkbench.store.state.yaml]")


def asked(href):
    """The name and version a /studio?sutra=name@version link asks for."""
    return tuple(urllib.parse.parse_qs(urllib.parse.urlparse(href).query)["sutra"][0].split("@"))


def follow_from_page(page, href):
    """Follows a link the way a click on one of Drishti's pages does (a same-site navigation): no confirmation page."""
    page.evaluate("h => { location.href = h; }", href)
    page.wait_for_url(lambda u: "/build/d/" in u, timeout=BROWSER_WAIT_MS)
    return designer_state(page)


def is_that_sutra(yaml_text, name, version):
    """The designer holds the named Sutra: its own name and version lines (a starter or another Sutra has different ones)."""
    return re.search(r"(?m)^sutra:\s*" + re.escape(name) + r"\s*$", yaml_text) and re.search(r"(?m)^version:\s*" + re.escape(version) + r"\s*$", yaml_text)


def test_the_sutra_named_on_a_page_opens_in_the_designer_and_it_is_that_sutra(browser, live_console):
    base = str(live_console)
    ctx = browser.new_context()
    page = ctx.new_page()
    page.set_default_timeout(BROWSER_WAIT_MS)

    # 1. the "How this view was built" panel on a deal page: one click, straight into the designer, on that Sutra
    page.goto(f"{base}/v/trade/{TRADE}")
    link = page.locator("a[data-prov-sutra]").first
    link.wait_for()
    href, shown = link.get_attribute("href"), link.inner_text()
    source = page.locator("a[data-prov-source]").first.get_attribute("href")
    assert source.startswith("/admin/connectors/") and len(source) > len("/admin/connectors/")
    name, version = asked(href)
    assert shown == f"{name} v{version}"
    link.click()
    page.wait_for_url(lambda u: "/build/d/" in u, timeout=BROWSER_WAIT_MS)
    first, text = designer_state(page)
    assert is_that_sutra(text, name, version), f"the designer opened something else for {name}@{version}: {text[:300]}"

    # 2. About this page, "Why the page looks like this": the same Sutra, and the same Design (no pile of copies)
    page.goto(f"{base}/v/trade/{TRADE}")
    about = page.request.get(f"{base}/v/trade/{TRADE}/about?generation=0").text()
    m = re.search(r'<a class="mono" data-about-sutra href="([^"]+)"', about)
    assert m, "About this page names no Sutra link"
    href2 = html.unescape(m.group(1))
    assert f"kind=trade&id={TRADE}" in href2 and asked(href2) == (name, version)
    again, text2 = follow_from_page(page, href2)
    assert again == first and is_that_sutra(text2, name, version)
    refs = [x.get("ref") for x in page.request.get(f"{base}/build/designs/{first}").json().get("samples", [])]
    assert {"kind": "trade", "id": TRADE} in refs, refs        # the record the page showed is now the Design's sample

    # 3. the same address typed or opened from another site still asks first, and says what it opens
    page.goto(base + href)
    assert page.locator("h1").inner_text() == f"Open the Sutra {name} v{version} in the designer"
    page.locator("[data-start-go]").click()
    third, text3 = designer_state(page)
    assert third == first and is_that_sutra(text3, name, version)
    ctx.close()
