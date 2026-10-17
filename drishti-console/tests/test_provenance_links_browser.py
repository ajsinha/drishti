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

"""A page's Sutra opens in the designer, and it is that Sutra: from the "How this view was built" panel and from About this page's
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


def opens_in_designer(page, base, href):
    """Follows a /studio?sutra=name@version link the way a person does (the confirmation page, then Start) and returns
    (the asked name, the asked version, the designer's YAML)."""
    asked = urllib.parse.parse_qs(urllib.parse.urlparse(href).query)["sutra"][0]
    name, version = asked.split("@")
    page.goto(base + href)
    page.locator("[data-start-go]").click()
    page.wait_for_function("() => window.drishtiWorkbench && window.drishtiWorkbench.store.state.id", timeout=BROWSER_WAIT_MS)
    page.wait_for_function("() => (window.drishtiWorkbench.store.state.yaml || '').length > 0", timeout=BROWSER_WAIT_MS)
    return name, version, page.evaluate("() => window.drishtiWorkbench.store.state.yaml")


def is_that_sutra(yaml_text, name, version):
    """The designer holds the named Sutra: its own name and version lines (a starter or another Sutra has different ones)."""
    return re.search(r"(?m)^sutra:\s*" + re.escape(name) + r"\s*$", yaml_text) and re.search(r"(?m)^version:\s*" + re.escape(version) + r"\s*$", yaml_text)


def test_the_sutra_named_on_a_page_opens_in_the_designer_and_it_is_that_sutra(browser, live_console):
    base = str(live_console)
    ctx = browser.new_context()
    page = ctx.new_page()
    page.set_default_timeout(BROWSER_WAIT_MS)

    # 1. the "How this view was built" panel on a deal page
    page.goto(f"{base}/v/trade/{TRADE}")
    link = page.locator("a[data-prov-sutra]").first
    link.wait_for()
    href, shown = link.get_attribute("href"), link.inner_text()
    source = page.locator("a[data-prov-source]").first.get_attribute("href")
    assert source.startswith("/admin/connectors/") and len(source) > len("/admin/connectors/")
    name, version, text = opens_in_designer(page, base, href)
    assert shown == f"{name} v{version}"
    assert is_that_sutra(text, name, version), f"the designer opened something else for {name}@{version}: {text[:300]}"

    # 2. About this page, "Why the page looks like this": the same Sutra, with this trade as the sample
    about = page.request.get(f"{base}/v/trade/{TRADE}/about?generation=0").text()
    m = re.search(r'<a class="mono" data-about-sutra href="([^"]+)"', about)
    assert m, "About this page names no Sutra link"
    href2 = html.unescape(m.group(1))
    assert f"kind=trade&id={TRADE}" in href2
    name2, version2, text2 = opens_in_designer(page, base, href2)
    assert (name2, version2) == (name, version)
    assert is_that_sutra(text2, name2, version2), text2[:300]
    ctx.close()
