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

"""Pinned links (COLLABORATION.md, build step 1): the sign-in redirect keeps the whole link, the pin is read per request,
the recipient's saved date is never touched, and the banner says how the pin was honoured."""
import sys
from pathlib import Path
from urllib.parse import quote

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402
from core.nextpath import safe_next, to_login  # noqa: E402

FORM = {"Content-Type": "application/x-www-form-urlencoded"}
QUERY = "asOf=2026-09-29&knownAt=2026-09-29T14:30:00Z&gen=1742"
PIN = "/v/trade/IRS-48213?" + QUERY


def _secure(backend):
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60,
                    "session_hours": 1, "secure_cookie": False}
    app = create_app(Settings(data))
    app.state.backend = backend
    return TestClient(app)


def _banner(html):
    import re
    m = re.search(r"<p class=\"asof-banner pin-banner\".*?</p>", html, re.S)
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", m.group(0))) if m else ""


def _sign_in(c, nxt):
    return c.post("/login", content=f"user=drishti-dev-admin&password=drishti-dev-admin123&next={quote(nxt, safe='')}",
                  follow_redirects=False, headers=FORM)


def test_the_sign_in_redirect_keeps_path_and_query(backend):
    c = _secure(backend)
    r = c.get(PIN, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == to_login("/v/trade/IRS-48213", QUERY)
    login = c.get(r.headers["location"]).text
    assert f'name="next" value="/v/trade/IRS-48213?{QUERY.replace("&", "&amp;")}"' in login
    done = _sign_in(c, PIN)
    assert done.status_code == 303 and done.headers["location"] == PIN
    assert "drishti_asof" not in c.cookies and "drishti_knownat" not in c.cookies       # signing in to a pin saves no date
    page = c.get(PIN)
    assert page.status_code == 200 and "pin-banner" in page.text and 'data-pin="asit"' in page.text


def test_the_redirect_target_is_same_origin_only(backend):
    for bad in ("//evil.example", "/\\evil.example", "https://evil.example/x", "evil", "", None, "/a\r\nSet-Cookie: x=1", "/a\\b", "/x\x00"):
        assert safe_next(bad) == "/t", bad
    assert safe_next("/v/trade/X?asOf=2026-09-29&a=//b") == "/v/trade/X?asOf=2026-09-29&a=//b"
    c = _secure(backend)
    for bad in ("//evil.example/x", "/\\evil.example", "https://evil.example"):
        assert _sign_in(c, bad).headers["location"] == "/t", bad
    assert 'value="//evil.example"' not in c.get("/login?next=//evil.example").text


def test_a_pinned_link_never_sets_the_readers_date(client):
    page = client.get(PIN)
    assert page.status_code == 200 and not page.headers.get("set-cookie")
    assert 'data-share="http://testserver/v/trade/IRS-48213?asOf=2026-09-29&amp;knownAt=2026-09-29T14%3A30%3A00Z&amp;gen=1742"' in page.text
    assert "drishti_asof" not in client.cookies and "drishti_knownat" not in client.cookies
    assert "pin-banner" not in client.get("/v/trade/IRS-48213").text                      # the next page is the reader's own: live
    assert "/static/js/live.js" in client.get("/v/trade/IRS-48213").text


def test_a_pin_overrides_but_does_not_replace_the_readers_cookie(client):
    client.cookies.set("drishti_asof", "2026-09-26")
    client.cookies.set("drishti_knownat", "2026-09-26T10:00:00Z")
    try:
        r = client.get("/v/trade/IRS-48213?asOf=2026-09-29")
        assert "pin-banner" in r.text and "as known at" not in _banner(r.text) and "2026-09-26T10" not in _banner(r.text)
        assert not r.headers.get("set-cookie")
        assert client.cookies.get("drishti_asof") == "2026-09-26"
    finally:
        client.cookies.delete("drishti_asof")
        client.cookies.delete("drishti_knownat")


def test_the_banner_says_how_the_pin_was_honoured(client, backend, monkeypatch):
    from core import asof

    shown = client.get(PIN).text
    assert 'data-pin="asit"' in shown and "as known at" in shown and "as it was shared" in shown and "Go live" in shown
    changed = client.get(PIN.replace("gen=1742", "gen=1600")).text
    assert 'data-pin="changed"' in changed and "generation 1600" in changed and "What changed" in changed

    real = backend.view

    async def no_versions(kind, id_, user):                                  # a dated store with no earlier versions
        if asof.known_at():
            raise BackendError(400, "DRS-1007", "keeps no earlier versions")
        return await real(kind, id_, user)
    monkeypatch.setattr(backend, "view", no_versions)
    fallback = client.get(PIN).text
    assert 'data-pin="retry"' in fallback and "keeps no earlier versions" in _banner(fallback) and "as known at" not in _banner(fallback)
    assert "pin-banner" not in client.get("/v/trade/IRS-48213").text


def test_the_old_asof_form_still_works(client):
    r = client.get("/asof?d=2026-09-29&ki=2026-09-29T14:30:00Z&next=/v/trade/IRS-48213", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/v/trade/IRS-48213"
    assert client.get("/asof?d=live&next=//evil.example", follow_redirects=False).headers["location"] == "/t"
