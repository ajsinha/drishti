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

"""A pinned link opened signed out, in a real browser against a real server with sign-in on (COLLABORATION.md, build step 1): the
sign-in page keeps the link, signing in lands on the pinned date with the as-shared banner, the reader's own saved date is not
touched, and the next page they open is their own (live).

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_auth_console  # noqa: F401 - the fixture

LINK = "/v/trade/IRS-48213?asOf=2026-09-29&knownAt=2026-09-29T14:30:00Z&gen=1742"


def test_a_pinned_link_survives_sign_in_and_leaves_the_readers_date_alone(live_auth_console, page):
    base = str(live_auth_console)
    page.goto(base + LINK)
    page.locator("input[name=user]").wait_for()
    assert page.url.startswith(base + "/login?next=") and "asOf%3D2026-09-29" in page.url          # the whole link rides along
    page.fill("input[name=user]", "drishti-dev-admin")
    page.fill("input[name=password]", "drishti-dev-admin123")
    page.click("form button[type=submit], form input[type=submit]")
    page.wait_for_url(lambda u: "/v/trade/IRS-48213" in u)
    assert "asOf=2026-09-29" in page.url and "knownAt=2026-09-29T14:30:00Z" in page.url
    banner = page.locator(".pin-banner")
    banner.wait_for()
    assert "2026-09-29" in banner.inner_text() and "Go live" in banner.inner_text()
    names = {c["name"] for c in page.context.cookies()}
    assert "drishti_asof" not in names and "drishti_knownat" not in names                           # the pin set nothing saved
    page.goto(base + "/v/trade/IRS-48213")                                                          # the reader's own page: live
    page.locator(".vtitle .vid").wait_for()
    assert page.locator(".pin-banner").count() == 0
