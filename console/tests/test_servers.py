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

"""Sutra Studio pages and the sign-in flow."""
"""One console, many servers (ADR-016): a session per server, never shared; only catalogued servers are reachable."""
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402
from core.servers import Servers, Switch  # noqa: E402
from tests.conftest import FakeBackend  # noqa: E402

FORM = {"Content-Type": "application/x-www-form-urlencoded"}
LOGIN = "user=drishti-dev-admin&password=drishti-dev-admin123&next=/t"


@pytest.fixture()
def two():
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40,
                    "token_ttl_seconds": 60, "session_hours": 1, "secure_cookie": False}
    data["servers"] = [{"id": "open", "name": "Open", "url": "http://open.invalid", "color": "#2a7"},
                       {"id": "desk", "name": "Rates desk", "url": "http://desk.invalid", "token_secret": "d" * 40},
                       {"id": "lab", "name": "Lab", "url": "http://lab.invalid", "listed": False}]
    app = create_app(Settings(data))
    fakes = {"open": FakeBackend(), "desk": FakeBackend(), "lab": FakeBackend()}
    app.state.backend = Switch(fakes, "open")

    async def states(timeout=2.0):
        return {"open": {"up": True, "version": "1.12.0", "signIn": {"required": True, "password": True}},
                "desk": {"up": False, "error": "ConnectError"}}
    app.state.servers.states = states
    return TestClient(app), app, fakes


def test_each_server_has_its_own_sign_in(two):
    c, app, fakes = two
    assert c.get("/t", follow_redirects=False).headers["location"].startswith("/login")
    assert "to <b>Open</b>" in c.get("/login").text
    c.post("/login", content=LOGIN, headers=FORM, follow_redirects=False)
    assert c.cookies.get("drishti_session_open") and c.get("/t").status_code == 200
    page = c.get("/t").text
    assert "srv-chip" in page and "Rates desk" in page and "Lab" not in page       # unlisted: not in the picker
    r = c.get("/connect/desk?next=/t", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/t" and c.cookies.get("drishti_server") == "desk"
    assert c.get("/t", follow_redirects=False).headers["location"].startswith("/login")   # no session on desk yet
    c.cookies.set("drishti_session_desk", c.cookies.get("drishti_session_open"))             # a session moved across
    assert c.get("/t", follow_redirects=False).status_code == 303                           # is refused
    c.cookies.delete("drishti_session_desk")
    fakes["desk"].calls.clear()
    c.post("/login", content=LOGIN, headers=FORM, follow_redirects=False)
    assert c.get("/v/trade/IRS-48213").status_code == 200
    assert any(x[0] == "view" for x in fakes["desk"].calls)                              # desk's backend answered
    c.get("/logout")
    assert c.cookies.get("drishti_session_open")                                          # signing out of desk keeps open


def test_only_catalogued_servers_and_links_name_theirs(two):
    c, app, fakes = two
    assert c.get("/connect/http:%2F%2Fevil.example").status_code == 404
    assert c.get("/connect/nope").status_code == 404
    assert c.get("/connect/lab?next=//evil.example", follow_redirects=False).headers["location"] == "/"   # unlisted: by link
    c.get("/connect/open")
    c.post("/login", content=LOGIN, headers=FORM)
    c.get("/connect/desk")
    r = c.get("/t?srv=open", follow_redirects=False)                                       # a shared link names its server
    assert r.status_code == 200 and c.cookies.get("drishti_server") == "open"
    c.get("/t?srv=elsewhere")
    assert c.cookies.get("drishti_server") == "open"
    page = c.get("/servers").text
    assert "Rates desk" in page and "DOWN" in page and "1.12.0" in page and "signed in" in page and ">Lab<" not in page


def test_without_a_catalogue_there_is_one_default_server():
    s = Servers(Settings({"backend": {"url": "http://x:1/"}, "ui": {"product": "P"}}))
    assert len(s) == 1 and s.default == "default" and s.get("default").url == "http://x:1"
    with pytest.raises(RuntimeError):
        Servers(Settings({"servers": [{"id": "Bad Id", "url": "http://x"}]}))
    with pytest.raises(RuntimeError):
        Servers(Settings({"servers": [{"id": "a", "url": "http://x"}, {"id": "a", "url": "http://y"}]}))
