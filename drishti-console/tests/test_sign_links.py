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

"""Sign in / Sign out links: on the public pages, in the top bar, on the login page, and after signing out."""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402
from core.nextpath import login_url  # noqa: E402

FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def _secure(backend):
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60,
                    "session_hours": 1, "secure_cookie": False}
    app = create_app(Settings(data))
    app.state.backend = backend
    return TestClient(app)


def _sign_in(c):
    r = c.post("/login", content="user=drishti-dev-admin&password=drishti-dev-admin123&next=/t", headers=FORM, follow_redirects=False)
    assert r.status_code == 303


def test_sign_in_off_says_so_and_adds_nothing_to_the_public_nav(client):
    assert "data-sign-in" not in client.get("/").text and "data-public-user" not in client.get("/").text
    menu = client.get("/t").text
    assert "Sign-in is off on this server" in menu and "/help/user-management#signing-in-and-out" in menu and "Sign out" not in menu
    login = client.get("/login").text
    assert "data-sign-in-off" in login and 'href="/t"' in login and 'name="password"' not in login and "data-login-back" in login
    assert client.post("/logout", follow_redirects=False).headers["location"] == "/"


def test_sign_in_on_public_pages_offer_sign_in_with_a_checked_next(backend):
    c = _secure(backend)
    for path in ("/", "/help", "/about"):
        page = c.get(path).text
        assert "data-sign-in" in page and "data-public-user" not in page
    assert 'href="/login?next=/help"' in c.get("/help").text
    assert 'href="/login?next=/about"' in c.get("/about").text
    assert "data-sign-in-off" not in c.get("/login").text and "data-login-back" in c.get("/login").text
    # an open redirect is refused whatever the page path says
    assert login_url("//evil.example") == "/login" and login_url("/\\evil") == "/login"
    assert login_url("/help", "q=a b") == "/login?next=/help%3Fq%3Da%20b"
    r = c.post("/login", content="user=drishti-dev-admin&password=drishti-dev-admin123&next=//evil.example", headers=FORM, follow_redirects=False)
    assert r.headers["location"] == "/t"


def test_signed_in_public_pages_show_the_account_menu_and_sign_out_lands_home_with_a_notice(backend):
    c = _secure(backend)
    _sign_in(c)
    page = c.get("/").text
    assert "data-public-user" in page and "data-sign-in>" not in page and 'action="/logout"' in page and "My account" in page
    assert "Sign out" in c.get("/t").text and "Sign-in is off" not in c.get("/t").text
    r = c.post("/logout", headers={"Origin": "http://testserver"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/?signedout=1"
    home = c.get("/?signedout=1").text
    assert "data-signed-out" in home and "data-sign-in" in home
    assert "data-signed-out" not in c.get("/").text


def test_sign_out_needs_the_console_s_own_origin(backend):
    c = _secure(backend)
    _sign_in(c)
    assert c.post("/logout", headers={"Origin": "https://evil.example"}, follow_redirects=False).status_code == 403
    assert "Sign out" in c.get("/t").text                                           # still signed in
