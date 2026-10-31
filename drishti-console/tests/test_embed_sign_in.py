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

"""The renderer's sign-in window on the console's side (CLIENT_RENDERER.md 10.1; routes/embed_signin_routes.py), without a browser: the
check a page makes before any window, the window's start (registered origins only, PKCE to the provider, back to its own callback), the
callback (the server verifies the ID token and makes the embed token for the origin; the page gets it by postMessage at its exact origin),
the silent renewal while the provider's session lasts, and the same-tab flow's one-time code. The browser half is in
test_render_sign_in_browser.py."""
import base64
import html
import json
import re
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from core.backend import BackendError
from core.oidc import Oidc
from test_embed import HOST, make_client
from test_oidc import ISSUER, FakeAuth, FakeSettings

STATE = "0123456789abcdef0123"


def provider(seen: dict, refresh_ok=True) -> httpx.MockTransport:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/.well-known/openid-configuration":
            return httpx.Response(200, json={"issuer": ISSUER, "authorization_endpoint": ISSUER + "/authorize", "token_endpoint": ISSUER + "/token"})
        if request.url.path == "/token":
            form = parse_qs(request.content.decode())
            seen.setdefault("grants", []).append(form["grant_type"][0])
            if form["grant_type"][0] == "refresh_token":
                if not refresh_ok:
                    return httpx.Response(400, json={"error": "invalid_grant", "error_description": "Session not active"})
                return httpx.Response(200, json={"id_token": "renewed.id.token", "refresh_token": "rt-2"})
            return httpx.Response(200, json={"id_token": "header.payload.sig", "refresh_token": "rt-1", "token_type": "Bearer"})
        return httpx.Response(404)
    return httpx.MockTransport(handle)


def jwt(sub: str) -> str:
    body = base64.urlsafe_b64encode(json.dumps({"sub": sub}).encode()).rstrip(b"=").decode()
    return "e30." + body + ".sig"


@pytest.fixture
def win(backend, monkeypatch):
    c = make_client(backend)
    app = c.app
    seen = {"handed": []}
    app.state.oidc = Oidc(FakeSettings({"auth.oidc.enabled": True, "auth.oidc.issuer": ISSUER, "auth.oidc.client_id": "drishti"}), FakeAuth(),
                          transport=provider(seen))
    monkeypatch.setattr(app.state.auth, "token_secret", "t" * 40)
    monkeypatch.setattr(app.state.auth, "secure_cookie", False)

    async def oidc_login(id_token, nonce, service):
        return {"username": "ana", "displayName": "Ana", "roles": ["viewer"]}

    async def embed_sign_in(id_token, nonce, origin, refreshed, service):
        seen["handed"].append({"token": id_token, "nonce": nonce, "origin": origin, "refreshed": refreshed})
        return {"access_token": jwt("ana"), "expires_in": 300, "scope": "embed:view", "token_type": "Bearer"}
    monkeypatch.setattr(backend, "oidc_login", oidc_login, raising=False)
    monkeypatch.setattr(backend, "embed_sign_in", embed_sign_in, raising=False)
    c.seen = seen
    return c


def payload(r) -> dict:
    m = re.search(r'data-sign-in="([^"]*)"', r.text)
    return json.loads(html.unescape(m.group(1))) if m else {}


def sign_in(c, mode="popup", ret=""):
    params = {"origin": HOST, "state": STATE, "mode": mode}
    if ret:
        params["return"] = ret
    r = c.get("/embed/v2/sign-in", params=params, follow_redirects=False)
    assert r.status_code == 303, r.text
    q = parse_qs(urlparse(r.headers["location"]).query)
    return c.get("/embed/v2/sign-in/callback", params={"code": "c1", "state": q["state"][0]}, follow_redirects=False), q


def test_a_page_asks_first_and_an_unregistered_one_learns_why_without_a_window(win):
    ok = win.get("/embed/v2/sign-in/check", headers={"Origin": HOST}).json()
    assert ok["allowed"] is True and ok["origin"] == HOST and ok["signIn"] is True
    bad = win.get("/embed/v2/sign-in/check", headers={"Origin": "https://intranet.evil.example"})
    assert bad.headers["access-control-allow-origin"] == "*" and bad.headers["cache-control"].startswith("no-store")
    assert bad.json()["allowed"] is False and bad.json()["code"] == "DRS-8002" and "https://intranet.evil.example" in bad.json()["detail"]
    win.app.state.oidc = Oidc(FakeSettings({}), FakeAuth())
    off = win.get("/embed/v2/sign-in/check", headers={"Origin": HOST}).json()
    assert off["allowed"] is False and off["code"] == "DRS-8003" and "auth.oidc" in off["detail"]


def test_the_window_goes_to_single_sign_on_with_pkce_for_a_registered_page_only(win):
    r = win.get("/embed/v2/sign-in", params={"origin": HOST, "state": STATE, "mode": "popup"}, follow_redirects=False)
    q = parse_qs(urlparse(r.headers["location"]).query)
    assert r.status_code == 303 and r.headers["location"].startswith(ISSUER + "/authorize?") and q["code_challenge_method"] == ["S256"]
    assert q["redirect_uri"] == ["http://testserver/embed/v2/sign-in/callback"]
    assert any(c.startswith("drishti_embed_signin=") and "Path=/embed/v2/sign-in" in c and "HttpOnly" in c for c in r.headers.get_list("set-cookie"))
    stranger = win.get("/embed/v2/sign-in", params={"origin": "https://evil.example", "state": STATE}, follow_redirects=False)
    assert stranger.status_code == 403 and "DRS-8002" in stranger.text and "https://evil.example" in stranger.text
    assert payload(stranger) == {}, "nothing is posted to a page this console does not talk to"
    assert win.get("/embed/v2/sign-in", params={"origin": HOST, "state": "x"}, follow_redirects=False).status_code == 400
    away = win.get("/embed/v2/sign-in", params={"origin": HOST, "state": STATE, "mode": "redirect", "return": "https://evil.example/p"}, follow_redirects=False)
    assert away.status_code == 400


def test_the_callback_hands_the_token_to_the_page_s_exact_origin(win):
    back, q = sign_in(win)
    assert back.status_code == 200 and back.headers["cache-control"].startswith("no-store")
    p = payload(back)
    assert p["origin"] == HOST and p["mode"] == "popup"
    m = p["message"]
    assert m["type"] == "drishti:sign-in" and m["state"] == STATE and m["user"] == "ana" and m["expires_in"] == 300 and m["access_token"]
    assert m["refresh"] and "rt-1" not in m["refresh"], "the provider's refresh token is sealed, never readable in the page"
    assert win.seen["handed"] == [{"token": "header.payload.sig", "nonce": q["nonce"][0], "origin": HOST, "refreshed": False}]
    assert any(c.startswith('drishti_embed_signin=""') or "drishti_embed_signin=;" in c or "Max-Age=0" in c for c in back.headers.get_list("set-cookie"))
    assert '/static/js/embed-sign-in.js' in back.text and "<script>" not in back.text


def test_a_refusal_by_the_server_is_said_in_the_window_and_to_the_page(win, backend, monkeypatch):
    async def refused(*a, **k):
        e = BackendError(403, "DRS-8003", "x")
        e.extra = {"code": "DRS-8003", "error_description": "the sign-in window is switched off on this server (drishti.embed.sign-in-window)"}
        raise e
    monkeypatch.setattr(backend, "embed_sign_in", refused, raising=False)
    back, _ = sign_in(win)
    assert back.status_code == 403 and "drishti.embed.sign-in-window" in back.text
    m = payload(back)["message"]
    assert m["type"] == "drishti:sign-in-failed" and m["code"] == "DRS-8003" and m["state"] == STATE


def test_renewal_while_the_session_lasts_then_the_button_comes_back(win):
    back, _ = sign_in(win)
    handle = payload(back)["message"]["refresh"]
    r = win.post("/embed/v2/sign-in/refresh", json={"refresh": handle}, headers={"Origin": HOST})
    assert r.status_code == 200 and r.headers["access-control-allow-origin"] == HOST
    got = r.json()
    assert got["access_token"] and got["user"] == "ana" and got["refresh"] and got["refresh"] != handle
    assert win.seen["handed"][-1] == {"token": "renewed.id.token", "nonce": "", "origin": HOST, "refreshed": True}
    assert win.post("/embed/v2/sign-in/refresh", json={"refresh": handle}, headers={"Origin": "https://evil.example"}).status_code == 401
    assert win.post("/embed/v2/sign-in/refresh", json={"refresh": handle[:-4] + "AAAA"}, headers={"Origin": HOST}).status_code == 401
    win.app.state.oidc._transport = provider(win.seen, refresh_ok=False)          # the SSO session ended at the provider
    ended = win.post("/embed/v2/sign-in/refresh", json={"refresh": got["refresh"]}, headers={"Origin": HOST})
    assert ended.status_code == 401 and ended.json()["code"] == "DRS-8001"


def test_the_same_tab_flow_returns_with_a_one_time_code_for_the_page_s_origin(win):
    back, _ = sign_in(win, mode="redirect", ret=HOST + "/intranet/risk.html?x=1")
    p = payload(back)
    assert p["mode"] == "redirect" and p["back"].startswith(HOST + "/intranet/risk.html?x=1#drishti-sign-in=") and "&drishti-server=http://testserver" in p["back"]
    code = re.search(r"drishti-sign-in=([^&]+)", p["back"]).group(1)
    assert "access_token" not in p["back"]
    got = win.post("/embed/v2/sign-in/redeem", json={"code": code}, headers={"Origin": HOST})
    assert got.status_code == 200 and got.json()["state"] == STATE and got.json()["access_token"]
    assert win.post("/embed/v2/sign-in/redeem", json={"code": code}, headers={"Origin": "https://evil.example"}).status_code == 401
