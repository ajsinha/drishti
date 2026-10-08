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

"""Sign in and Sign out in a real browser, with sign-in on: the public nav's Sign in goes to the login page and back to
the page it came from; Sign out works from the public menu and from the top bar and lands on the landing page with a
notice; the public nav fits a phone. Skipped when Playwright or its Chromium is not installed."""
import re
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import _free_port, browser  # noqa: E402,F401 - the shared browser fixture


@pytest.fixture(scope="module")
def secure_url():
    import uvicorn

    from core.app import create_app
    from core.config import Settings, load_settings

    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60,
                    "session_hours": 1, "secure_cookie": False}
    app = create_app(Settings(data))
    app.state.backend = FakeBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


def _sign_in(page, url, path="/about"):
    page.goto(url + path)
    page.click("[data-sign-in]")
    page.wait_for_url(re.compile(r"/login\?next="))
    page.fill("#lu", "drishti-dev-admin")
    page.fill("#lp", "drishti-dev-admin123")
    page.click(".login-box button[type=submit]")


def test_sign_in_from_the_public_nav_returns_to_the_page_and_sign_out_works_from_both_menus(browser, secure_url):
    page = browser.new_page()
    _sign_in(page, secure_url)
    page.wait_for_url("**/about")                                   # back where it started
    page.click(".tbar-tools .user")                                 # the top bar's menu (the about page shows it when signed in)
    page.click(".user-menu button:has-text('Sign out')")
    page.wait_for_url("**/?signedout=1")
    assert page.locator("[data-signed-out]").is_visible()
    assert page.locator("[data-sign-in]").first.is_visible()

    page.goto(secure_url + "/login")                                # the public menu: sign in, go home, sign out from there
    page.fill("#lu", "drishti-dev-admin")
    page.fill("#lp", "drishti-dev-admin123")
    page.click(".login-box button[type=submit]")
    page.wait_for_url("**/t")
    page.goto(secure_url + "/")
    page.click("[data-public-user]")
    page.click(".pub-user button:has-text('Sign out')")
    page.wait_for_url("**/?signedout=1")
    assert page.locator("[data-signed-out]").is_visible()
    page.goto(secure_url + "/login")
    page.click("[data-login-back]")                                 # the login page's way home
    page.wait_for_url(secure_url + "/")
    page.close()


def test_the_public_nav_sign_in_fits_a_phone(browser, secure_url):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    page.goto(secure_url + "/")
    box = page.locator("[data-sign-in]").bounding_box()
    assert box["height"] >= 43.5 and box["width"] >= 43.5 and box["x"] + box["width"] <= 390
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    ctx.close()
