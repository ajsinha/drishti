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


"""Single sign-on with an OpenID Connect provider (W20): the browser half. The console sends the user to the provider
with the authorization code flow and PKCE, keeps state, nonce and verifier in a short-lived signed cookie, exchanges
the code for an ID token, and hands the token and nonce to the server, which verifies it and signs the user in. The
console never trusts the token itself, so a compromised console cannot mint users."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from urllib.parse import urlencode

import httpx

COOKIE = "drishti_oidc"
TTL = 600                                   # seconds a sign-in may take at the provider


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class OidcError(Exception):
    """A sign-in that cannot continue; the message is safe to show."""


class Oidc:
    def __init__(self, settings, auth, transport: httpx.AsyncBaseTransport | None = None):
        self.enabled = bool(settings.get("auth.oidc.enabled", False))
        self.issuer = str(settings.get("auth.oidc.issuer") or "").rstrip("/")
        self.client_id = str(settings.get("auth.oidc.client_id") or "")
        self.client_secret = str(settings.get("auth.oidc.client_secret") or "")
        self.scopes = str(settings.get("auth.oidc.scopes") or "openid profile email")
        self.label = str(settings.get("auth.oidc.label") or "Sign in with single sign-on")
        self.redirect_uri = str(settings.get("auth.oidc.redirect_uri") or "")
        self.token_auth = str(settings.get("auth.oidc.token_auth") or "client_secret_basic")
        self.auth = auth
        self._transport = transport
        self._discovery: tuple[float, dict] | None = None
        if self.enabled and not (self.issuer and self.client_id):
            raise RuntimeError("auth.oidc.issuer and auth.oidc.client_id are required when single sign-on is on")

    def _client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=10.0, transport=self._transport, follow_redirects=False)

    @staticmethod
    def _checked(url: str) -> str:
        if not (url.startswith("https://") or url.startswith(("http://localhost", "http://127.0.0.1"))):
            raise OidcError("the provider must be reached over https")
        return url

    async def discovery(self) -> dict:
        if self._discovery and time.monotonic() - self._discovery[0] < 3600:
            return self._discovery[1]
        async with self._client() as c:
            r = await c.get(self._checked(self.issuer + "/.well-known/openid-configuration"))
        if r.status_code != 200:
            raise OidcError("the sign-in provider is unavailable")
        doc = r.json()
        if doc.get("issuer", "").rstrip("/") != self.issuer:
            raise OidcError("the sign-in provider describes another issuer")
        self._discovery = (time.monotonic(), doc)
        return doc

    def redirect(self, base_url: str) -> str:
        return self.redirect_uri or base_url.rstrip("/") + "/auth/oidc/callback"

    # -- the signed cookie that carries state, nonce and the PKCE verifier across the round trip ---------------------
    def _seal(self, body: dict) -> str:
        payload = _b64(json.dumps(body, separators=(",", ":")).encode())
        sig = _b64(hmac.new(self.auth.session_secret.encode(), ("oidc:" + payload).encode(), hashlib.sha256).digest())
        return f"{payload}.{sig}"

    def _open(self, cookie: str | None) -> dict:
        if not cookie or cookie.count(".") != 1:
            raise OidcError("the sign-in took too long or was started elsewhere; try again")
        payload, sig = cookie.split(".")
        good = _b64(hmac.new(self.auth.session_secret.encode(), ("oidc:" + payload).encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(sig, good):
            raise OidcError("the sign-in could not be verified; try again")
        body = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        if int(body.get("t", 0)) + TTL < time.time():
            raise OidcError("the sign-in took too long; try again")
        return body

    async def start(self, base_url: str, next_path: str) -> tuple[str, str]:
        """The provider URL to send the browser to, and the cookie to set."""
        doc = await self.discovery()
        state, nonce, verifier = secrets.token_urlsafe(24), secrets.token_urlsafe(24), secrets.token_urlsafe(48)
        challenge = _b64(hashlib.sha256(verifier.encode()).digest())
        query = {"response_type": "code", "client_id": self.client_id, "redirect_uri": self.redirect(base_url), "scope": self.scopes,
                 "state": state, "nonce": nonce, "code_challenge": challenge, "code_challenge_method": "S256"}
        url = self._checked(doc["authorization_endpoint"]) + "?" + urlencode(query)
        return url, self._seal({"s": state, "n": nonce, "v": verifier, "x": next_path, "t": int(time.time())})

    async def finish(self, base_url: str, cookie: str | None, code: str, state: str) -> tuple[str, str, str]:
        """(ID token, nonce, next path) for a callback, after checking state; the server verifies the token."""
        body = self._open(cookie)
        if not state or not hmac.compare_digest(state, body["s"]):
            raise OidcError("the sign-in could not be verified; try again")
        doc = await self.discovery()
        form = {"grant_type": "authorization_code", "code": code, "redirect_uri": self.redirect(base_url), "code_verifier": body["v"]}
        auth = None
        if self.token_auth == "client_secret_post":
            form.update(client_id=self.client_id, client_secret=self.client_secret)
        elif self.client_secret:
            auth = (self.client_id, self.client_secret)
        else:
            form["client_id"] = self.client_id                     # a public client: PKCE alone
        async with self._client() as c:
            r = await c.post(self._checked(doc["token_endpoint"]), data=form, auth=auth, headers={"Accept": "application/json"})
        if r.status_code != 200 or "id_token" not in r.json():
            raise OidcError("the sign-in provider did not confirm the sign-in")
        return r.json()["id_token"], body["n"], body.get("x") or "/t"
