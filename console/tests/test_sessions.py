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

"""Console sessions follow the user (QA 2026-10-01: SEC-01, SEC-05, SEC-06, SEC-08).

The server keeps each sign-in session; the console checks it (and the user's current roles and state) per request,
cached for ``auth.recheck_seconds``; sign-out ends it on the server; a password change asked for first is enforced;
state-changing requests from other sites and JSON sent as text are refused.
"""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.auth import COOKIE  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402
from tests.conftest import FakeBackend  # noqa: E402

FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def _users():
    base = {"enabled": True, "mustChangePassword": False, "locked": False, "email": "", "lastLoginAt": None}
    return {"drishti-dev-admin": {**base, "username": "drishti-dev-admin", "displayName": "Admin", "desk": "Ops", "roles": ["admin"]},
            "qa-trader": {**base, "username": "qa-trader", "displayName": "Trader", "desk": "Rates", "roles": ["trader"]},
            "qa-risk": {**base, "username": "qa-risk", "displayName": "Risk", "desk": "Risk", "roles": ["admin"]},
            "qa-new": {**base, "username": "qa-new", "displayName": "New", "desk": "Rates", "roles": ["trader"], "mustChangePassword": True}}


def _app(recheck=0, origins=None):
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60,
                    "session_hours": 1, "secure_cookie": False, "recheck_seconds": recheck}
    if origins is not None:
        data["auth"]["allowed_origins"] = origins
    app = create_app(Settings(data))
    fake = FakeBackend()
    fake.users = _users()
    fake.passwords = {u: u + "-pass-1" for u in fake.users}
    fake.tokens_made = []
    app.state.backend = fake
    return app, fake


def _signed_in(app, user):
    c = TestClient(app)
    r = c.post("/login", content=f"user={user}&password={user}-pass-1&next=/t", headers=FORM, follow_redirects=False)
    assert r.status_code == 303 and c.cookies.get(COOKIE)
    return c


def test_disabling_a_user_ends_their_console_session():
    """SEC-01: a disabled user's existing cookie stops working (the session is checked against the server)."""
    app, fake = _app()
    c = _signed_in(app, "qa-trader")
    assert c.get("/v/trade/IRS-48213").status_code == 200
    fake.users["qa-trader"]["enabled"] = False
    assert c.get("/v/trade/IRS-48213", follow_redirects=False).status_code == 303
    assert c.get("/api/suggest", params={"q": "IRS"}).status_code == 401
    assert c.post("/api/tokens", json={"name": "after disable"}).status_code == 401      # no API token either


def test_a_demoted_user_loses_the_role_at_once():
    """SEC-01: roles come from the server per request, not from the cookie."""
    app, fake = _app()
    c = _signed_in(app, "qa-risk")
    assert c.get("/admin/users").status_code == 200
    fake.users["qa-risk"]["roles"] = ["viewer"]
    assert c.get("/admin/users").status_code == 403
    fake.calls.clear()
    c.get("/v/trade/IRS-48213")
    ident = next(x[3] for x in fake.calls if x[0] == "view")
    assert ident.roles == ("viewer",)                  # the token minted for the server carries the current roles


def test_the_check_is_cached_for_the_configured_time_and_admin_changes_clear_it():
    app, fake = _app(recheck=300)
    c = _signed_in(app, "qa-trader")
    fake.calls.clear()
    for _ in range(3):
        assert c.get("/v/trade/IRS-48213").status_code == 200
    assert sum(1 for x in fake.calls if x[0] == "session") <= 1               # one server check per recheck interval
    admin = _signed_in(app, "drishti-dev-admin")
    fake.users["qa-trader"]["enabled"] = False
    assert admin.post("/admin/api/users/qa-trader/enabled", json={"enabled": False}).status_code == 200
    assert c.get("/v/trade/IRS-48213", follow_redirects=False).status_code == 303   # this console forgot it at once


def test_sign_out_ends_the_session_on_the_server():
    """SEC-05: a copy of the cookie is useless after sign-out."""
    app, fake = _app(recheck=300)
    c = _signed_in(app, "qa-trader")
    copied = c.cookies.get(COOKIE)
    r = c.post("/logout", follow_redirects=False)
    assert r.status_code == 303 and any(x[0] == "end_session" for x in fake.calls)
    other = TestClient(app)
    other.cookies.set(COOKIE, copied)
    assert other.get("/t", follow_redirects=False).status_code == 303
    assert other.get("/api/suggest", params={"q": "IRS"}).status_code == 401


def test_sign_out_is_a_post_and_get_only_asks():
    """SEC-08: a cross-site link to /logout no longer signs anyone out; the menu posts a form."""
    app, fake = _app()
    c = _signed_in(app, "qa-trader")
    page = c.get("/logout")
    assert page.status_code == 200 and 'method="post"' in page.text and 'action="/logout"' in page.text
    assert c.get("/t").status_code == 200                                        # still signed in
    assert 'action="/logout"' in c.get("/t").text and 'href="/logout"' not in c.get("/t").text


def test_a_password_change_asked_for_is_enforced():
    """SEC-06: until the password is changed only the account page, the change and sign-out answer."""
    app, fake = _app()
    c = _signed_in(app, "qa-new")
    r = c.get("/t", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/account?must=1"
    assert c.get("/v/trade/IRS-48213", follow_redirects=False).headers["location"] == "/account?must=1"
    api = c.get("/api/view/trade/IRS-48213")
    assert api.status_code == 403 and api.json()["code"] == "DRS-6010"
    assert c.post("/api/tokens", json={"name": "x"}).status_code == 403
    assert c.post("/account/settings", content="theme=light", headers=FORM, follow_redirects=False).status_code == 403
    page = c.get("/account")
    assert page.status_code == 200 and "choose a new password" in page.text and "data-tokens" not in page.text
    fake.change_password = _changes(fake, "qa-new")
    assert c.post("/account/password", json={"current": "qa-new-pass-1", "next": "better-pass-99"}).json()["ok"]
    assert c.get("/t").status_code == 200 and c.get("/api/view/trade/IRS-48213").status_code == 200


def _changes(fake, user):
    async def change_password(current, new, ident):
        fake.users[user]["mustChangePassword"] = False
        return fake.users[user]
    return change_password


def test_cross_site_writes_and_json_as_text_are_refused():
    """SEC-08: an Origin (or Referer) of another site is refused on state-changing routes; JSON must say so."""
    app, fake = _app()
    c = _signed_in(app, "qa-trader")
    evil = c.post("/api/tokens", json={"name": "csrf"}, headers={"Origin": "https://evil.example"})
    assert evil.status_code == 403 and evil.json()["code"] == "DRS-5002"
    assert c.post("/api/tokens", json={"name": "csrf"}, headers={"Referer": "https://evil.example/page"}).status_code == 403
    assert c.post("/api/tokens", json={"name": "csrf"}, headers={"Origin": "null"}).status_code == 403
    assert c.post("/logout", headers={"Origin": "https://evil.example"}).status_code == 403
    text = c.post("/api/tokens", content='{"name": "csrf"}', headers={"Content-Type": "text/plain"})
    assert text.status_code == 415
    assert fake.tokens_made == []
    ok = c.post("/api/tokens", json={"name": "mine"}, headers={"Origin": "http://testserver"})
    assert ok.status_code == 200 and fake.tokens_made == [("mine", None)]
    assert c.get("/t", headers={"Origin": "https://evil.example"}).status_code == 200      # reads are not affected


def test_configured_origins_are_allowed():
    app, fake = _app(origins=["https://drishti.bank.example"])
    c = _signed_in(app, "qa-trader")
    assert c.post("/api/tokens", json={"name": "proxy"}, headers={"Origin": "https://drishti.bank.example"}).status_code == 200
    assert c.post("/api/tokens", json={"name": "x"}, headers={"Origin": "https://other.example"}).status_code == 403


def test_a_cookie_without_a_server_session_is_refused():
    """Cookies from before server-side sessions (or forged without one) do not sign anyone in."""
    app, fake = _app()
    c = TestClient(app)
    c.cookies.set(COOKIE, app.state.auth.session_for(fake.users["qa-trader"]))
    assert c.get("/t", follow_redirects=False).status_code == 303
