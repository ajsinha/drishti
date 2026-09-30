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


"""W20: the console's half of single sign-on: redirect with PKCE, state checked, code exchanged, token handed to the server."""
import base64
import hashlib
import json
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from core.oidc import Oidc, OidcError

ISSUER = "https://login.bank.example"


class FakeSettings(dict):
    def get(self, key, default=None):
        return super().get(key, default)


class FakeAuth:
    session_secret = "s" * 40


def _provider(seen: dict) -> httpx.MockTransport:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/.well-known/openid-configuration":
            return httpx.Response(200, json={"issuer": ISSUER, "authorization_endpoint": ISSUER + "/authorize", "token_endpoint": ISSUER + "/token"})
        if request.url.path == "/token":
            seen["form"] = parse_qs(request.content.decode())
            seen["auth"] = request.headers.get("authorization")
            return httpx.Response(200, json={"id_token": "header.payload.sig", "token_type": "Bearer"})
        return httpx.Response(404)
    return httpx.MockTransport(handle)


def _oidc(seen, **extra):
    s = FakeSettings({"auth.oidc.enabled": True, "auth.oidc.issuer": ISSUER, "auth.oidc.client_id": "drishti", "auth.oidc.client_secret": "shh"})
    s.update(extra)
    return Oidc(s, FakeAuth(), transport=_provider(seen))


@pytest.mark.anyio
async def test_start_redirects_with_pkce_state_and_nonce_and_finish_exchanges_the_code():
    seen = {}
    o = _oidc(seen)
    url, sealed = await o.start("http://console.example/", "/v/trade/T-1")
    q = {k: v[0] for k, v in parse_qs(urlparse(url).query).items()}
    assert url.startswith(ISSUER + "/authorize?") and q["response_type"] == "code" and q["code_challenge_method"] == "S256"
    assert q["redirect_uri"] == "http://console.example/auth/oidc/callback" and q["client_id"] == "drishti" and q["state"] and q["nonce"]
    token, nonce, target = await o.finish("http://console.example/", sealed, "the-code", q["state"])
    assert token == "header.payload.sig" and nonce == q["nonce"] and target == "/v/trade/T-1"
    verifier = seen["form"]["code_verifier"][0]
    assert base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode() == q["code_challenge"]
    assert seen["form"]["code"] == ["the-code"] and seen["auth"].startswith("Basic ")        # client_secret_basic


@pytest.mark.anyio
async def test_a_wrong_state_a_forged_cookie_or_a_stale_sign_in_is_refused():
    seen = {}
    o = _oidc(seen)
    url, sealed = await o.start("http://c/", "/t")
    with pytest.raises(OidcError):
        await o.finish("http://c/", sealed, "code", "not-the-state")
    payload, sig = sealed.split(".")
    body = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    forged = base64.urlsafe_b64encode(json.dumps(dict(body, x="//evil.example")).encode()).rstrip(b"=").decode() + "." + sig
    with pytest.raises(OidcError):
        await o.finish("http://c/", forged, "code", body["s"])
    stale = o._seal(dict(body, t=0))
    with pytest.raises(OidcError):
        await o.finish("http://c/", stale, "code", body["s"])
    with pytest.raises(OidcError):
        await o.finish("http://c/", None, "code", body["s"])
    assert "form" not in seen                                                            # no code was ever exchanged


@pytest.mark.anyio
async def test_the_provider_must_use_https_and_name_itself():
    with pytest.raises(OidcError):
        await _oidc({}, **{"auth.oidc.issuer": "http://login.bank.example"}).discovery()


def test_the_callback_signs_the_user_in_through_the_server(client, backend, monkeypatch):
    app = client.app
    seen = {}
    app.state.oidc = _oidc(seen)
    monkeypatch.setattr(app.state.auth, "secure_cookie", False)              # the test client speaks http
    handed = {}

    async def oidc_login(id_token, nonce, service):
        handed.update(token=id_token, nonce=nonce)
        return {"username": "ana", "displayName": "Ana", "roles": ["trader"], "desk": ""}
    backend.oidc_login = oidc_login
    r = client.get("/auth/oidc/login", params={"next": "/v/trade/IRS-48213"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith(ISSUER + "/authorize?")
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    back = client.get("/auth/oidc/callback", params={"code": "c1", "state": state}, follow_redirects=False)
    assert back.status_code == 303 and back.headers["location"] == "/v/trade/IRS-48213"
    assert handed["token"] == "header.payload.sig" and any("drishti_session=" in c for c in back.headers.get_list("set-cookie"))
    denied = client.get("/auth/oidc/callback", params={"error": "access_denied", "error_description": "user cancelled"})
    assert denied.status_code == 401 and "user cancelled" in denied.text
    client.cookies.clear()
    app.state.oidc = Oidc(FakeSettings({}), FakeAuth())
