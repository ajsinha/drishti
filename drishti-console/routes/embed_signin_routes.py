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

"""The renderer's optional sign-in window, ``auth: 'popup'`` (docs/architecture/CLIENT_RENDERER.md section 10.1), for a host page WITHOUT a
backend of its own. Nothing here opens by itself: the renderer draws a "Sign in to see this" button, and its click opens one window here.

* ``GET  /embed/v2/sign-in/check``: may this page (its ``Origin``) sign in here? ``{allowed, origin, product, code, detail}``; readable by any
  page (no data, no credentials), so a page that is not registered can say why (``DRS-8002`` naming its origin) without opening a window;
* ``GET  /embed/v2/sign-in?origin=&state=&mode=popup|tab|redirect[&return=]``: the window: the bank's single sign-on through the console's
  ``auth.oidc`` (authorization code with PKCE; state, nonce, verifier, the page's origin sealed in a short-lived cookie on this path);
* ``GET  /embed/v2/sign-in/callback``: the provider comes back; the server verifies the ID token (``/api/v1/auth/oidc``, then
  ``/api/v1/embed/sign-in-window``, audited with the origin) and makes the user's embed token; the page posts it to the opener at the
  page's EXACT origin (``postMessage``), then closes. A window with no opener (a webview) goes back to ``return`` with a one-time code in the
  fragment instead;
* ``POST /embed/v2/sign-in/refresh``: a new embed token while the provider's SSO session lasts (its refresh token, sealed and encrypted in
  the page's memory, never readable there), refused once it has ended: the button comes back;
* ``POST /embed/v2/sign-in/redeem``: the one-time code of the same-tab flow, for the page's origin only, within two minutes.

Settings: ``embed.sign_in`` (CONFIGURATION.md); the server's ``drishti.embed.sign-in-window`` and ``drishti.security.oidc``.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import time
from urllib.parse import urlsplit

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from core.oidc import OidcError
from routes import embed_routes as v1

router = APIRouter(prefix="/embed/v2/sign-in", include_in_schema=False)
COOKIE = "drishti_embed_signin"
PATH = "/embed/v2/sign-in"
CODE_TTL = 120                                    # seconds a same-tab return's code may be redeemed
STATE = re.compile(r"[0-9a-f]{16,64}")
MODES = ("popup", "tab", "redirect")
NO_STORE = {"Cache-Control": "no-store", "Pragma": "no-cache"}


def _cfg(request: Request) -> dict:
    return (request.app.state.embed.settings or {}).get("sign_in") or {}


def _on(value, default=True) -> bool:
    return default if value is None else str(value).lower() in ("true", "1", "yes", "on")


def _origin(text: str | None) -> str:
    return str(text or "").strip().rstrip("/")


def _key(request: Request) -> bytes | None:
    auth = request.app.state.auth
    secret = getattr(auth, "session_secret", "") or getattr(auth, "token_secret", "")
    return hashlib.sha256(b"drishti-embed-sign-in\0" + secret.encode()).digest() if secret else None


def _seal(request: Request, body: dict) -> str:
    """Encrypted and authenticated (AES-GCM): the page holds it but can neither read nor change it."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    nonce = os.urandom(12)
    raw = nonce + AESGCM(_key(request)).encrypt(nonce, json.dumps(body, separators=(",", ":")).encode(), b"drishti-sign-in")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _unseal(request: Request, text: str) -> dict | None:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    try:
        raw = base64.urlsafe_b64decode(str(text) + "=" * (-len(str(text)) % 4))
        return json.loads(AESGCM(_key(request)).decrypt(raw[:12], raw[12:], b"drishti-sign-in"))
    except (InvalidTag, ValueError, TypeError):
        return None


def available(request: Request) -> tuple[bool, str]:
    """Whether the window can work on this console, and why not."""
    if not _on(_cfg(request).get("enabled"), True):
        return False, "the sign-in window is switched off on this console (embed.sign_in.enabled)"
    if not request.app.state.oidc.enabled:
        return False, "the sign-in window needs single sign-on, which is off on this console (auth.oidc.enabled)"
    if _key(request) is None:
        return False, "the sign-in window needs auth.token_secret on this console"
    return True, ""


def _not_registered(origin: str) -> str:
    return (f"the page {origin or '(no origin)'} is not one of a registered host application's origins, so it may not sign in here: an "
            "administrator registers it (Admin → Embedding)")


def _redirect_uri(request: Request) -> str:
    return str(_cfg(request).get("redirect_uri") or "") or str(request.base_url).rstrip("/") + PATH + "/callback"


def _console(request: Request) -> str:
    u = urlsplit(str(request.base_url))
    return f"{u.scheme}://{u.netloc}"


def _page(request: Request, status: int = 200, *, error: str = "", code: str = "", user: str = "", payload: dict | None = None) -> Response:
    templates = request.app.state.templates
    r = templates.TemplateResponse(request, "embed/sign_in.html", {"error": error, "code": code, "user": user, "payload": payload},
                                   status_code=status)
    r.headers.update(NO_STORE)
    return r


def _failed(request: Request, status: int, code: str, detail: str, origin: str = "", state: str = "") -> Response:
    """The window says why; a registered page that opened it is told too (and its button comes back), never another."""
    payload = {"mode": "popup", "origin": origin, "message": {"type": "drishti:sign-in-failed", "state": state, "code": code, "detail": detail}} if origin else None
    r = _page(request, status, error=detail, code=code, payload=payload)
    r.delete_cookie(COOKIE, path=PATH)
    return r


def _user(token: str) -> str:
    try:
        part = token.split(".")[1]
        return str(json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))).get("sub") or "")
    except (IndexError, ValueError):
        return ""


def _refusal(e: BackendError) -> tuple[int, str, str]:
    extra = getattr(e, "extra", None) or {}
    return (e.status if e.status < 500 else 503, extra.get("code") or e.code, extra.get("error_description") or e.detail)


@router.get("/check")
async def check(request: Request):
    origin = _origin(request.headers.get("origin"))
    on, why = available(request)
    body = {"allowed": False, "origin": origin, "product": request.app.state.settings.get("ui.product", ""), "signIn": on}
    if not on:
        body.update(code="DRS-8003", detail=why)
    elif not await request.app.state.embed.origin_allowed(request, origin):
        body.update(code="DRS-8002", detail=_not_registered(origin))
    else:
        body["allowed"] = True
    return JSONResponse(body, headers={**NO_STORE, "Access-Control-Allow-Origin": "*", "Vary": "Origin"})


@router.get("")
async def start(request: Request, origin: str = "", state: str = "", mode: str = "popup"):
    origin, ret = _origin(origin), request.query_params.get("return") or ""
    on, why = available(request)
    if not on:
        return _failed(request, 503, "DRS-8003", why)
    if not await request.app.state.embed.origin_allowed(request, origin):
        return _failed(request, 403, "DRS-8002", _not_registered(origin))         # no message: the page is not one this console talks to
    if not STATE.fullmatch(state or "") or mode not in MODES:
        return _failed(request, 400, "DRS-4000", "the sign-in window was opened without its state or with an unknown mode", origin, state)
    if mode == "redirect":
        u = urlsplit(ret)
        if u.scheme not in ("http", "https") or f"{u.scheme}://{u.netloc}".lower() != origin.lower():
            return _failed(request, 400, "DRS-8002", f"the page to return to must be on {origin}")
    try:
        url, sealed = await request.app.state.oidc.start(str(request.base_url), "/", redirect_uri=_redirect_uri(request),
                                                         extra={"o": origin, "st": state, "m": mode, "r": ret if mode == "redirect" else ""})
    except (OidcError, httpx.HTTPError) as e:
        return _failed(request, 503, "DRS-8003", f"single sign-on is unavailable: {e}", origin, state)
    r = RedirectResponse(url, status_code=303, headers=NO_STORE)
    r.set_cookie(COOKIE, sealed, httponly=True, samesite="lax", secure=request.app.state.auth.secure_cookie, max_age=600, path=PATH)
    return r


@router.get("/callback")
async def callback(request: Request, code: str = "", state: str = "", error: str = "", error_description: str = ""):
    oidc, auth = request.app.state.oidc, request.app.state.auth
    try:
        sealed = oidc.opened(request.cookies.get(COOKIE))
    except OidcError as e:
        return _failed(request, 401, "DRS-8001", str(e))
    origin, st, mode = sealed.get("o", ""), sealed.get("st", ""), sealed.get("m", "popup")
    if error:
        return _failed(request, 401, "DRS-8001", f"the sign-in provider said: {error_description or error}", origin, st)
    try:
        got, sealed = await oidc.finish_tokens(str(request.base_url), request.cookies.get(COOKIE), code, state, redirect_uri=_redirect_uri(request))
        profile = await request.app.state.backend.oidc_login(got["id_token"], sealed["n"], auth.service())   # the user, as single sign-on makes them
        out = await request.app.state.backend.embed_sign_in(got["id_token"], sealed["n"], origin, False, auth.service())
    except OidcError as e:
        return _failed(request, 401, "DRS-8001", str(e), origin, st)
    except httpx.HTTPError:
        return _failed(request, 503, "DRS-8003", "the sign-in provider is unavailable", origin, st)
    except BackendError as e:
        status, c, detail = _refusal(e)
        return _failed(request, status, c, detail, origin, st)
    user = _user(out.get("access_token", "")) or str(profile.get("username") or "")
    message = {"type": "drishti:sign-in", "state": st, "access_token": out.get("access_token"), "expires_in": out.get("expires_in"), "user": user,
               "refresh": _handle(request, got.get("refresh_token"), origin, user, sealed["n"])}
    if mode == "redirect":
        back = sealed.get("r", "").split("#")[0] + "#drishti-sign-in=" + _seal(request, {"msg": message, "o": origin, "exp": int(time.time()) + CODE_TTL}) \
            + "&drishti-server=" + _console(request)
        payload = {"mode": "redirect", "back": back}
    else:
        payload = {"mode": mode, "origin": origin, "message": message}
    r = _page(request, user=user, payload=payload)
    r.delete_cookie(COOKIE, path=PATH)
    return r


def _handle(request: Request, refresh_token: str | None, origin: str, user: str, nonce: str) -> str | None:
    """The page's way to renew silently: the provider's refresh token sealed with the origin and the user, never readable in the page."""
    if not refresh_token or not _on(_cfg(request).get("refresh"), True):
        return None
    hours = float(_cfg(request).get("max_hours") or request.app.state.settings.get("auth.session_hours") or 10)
    return _seal(request, {"rt": refresh_token, "o": origin, "u": user, "n": nonce, "until": int(time.time() + hours * 3600)})


async def _json(request: Request) -> dict:
    try:
        body = json.loads(await request.body() or b"{}")
        return body if isinstance(body, dict) else {}
    except ValueError:
        return {}


async def _answer(request: Request, body: dict, status: int = 200) -> Response:
    return await v1.cors(request, JSONResponse(body, status_code=status, headers=NO_STORE))


@router.post("/refresh")
async def refresh(request: Request):
    origin = _origin(request.headers.get("origin"))
    sealed = _unseal(request, (await _json(request)).get("refresh") or "") if _key(request) else None
    if not sealed or _origin(sealed.get("o")) != origin or not await request.app.state.embed.origin_allowed(request, origin):
        return await _answer(request, {"code": "DRS-8001", "detail": "this sign-in cannot be renewed here: sign in again"}, 401)
    if int(sealed.get("until", 0)) < time.time():
        return await _answer(request, {"code": "DRS-8001", "detail": "the sign-in reached its longest life (embed.sign_in.max_hours): sign in again"}, 401)
    try:
        got = await request.app.state.oidc.refresh(sealed["rt"])
        out = await request.app.state.backend.embed_sign_in(got["id_token"], "", origin, True, request.app.state.auth.service())
    except (OidcError, httpx.HTTPError):
        return await _answer(request, {"code": "DRS-8001", "detail": "the single sign-on session has ended: sign in again"}, 401)
    except BackendError as e:
        status, c, detail = _refusal(e)
        return await _answer(request, {"code": c, "detail": detail}, 401 if status < 500 else status)
    user = _user(out.get("access_token", "")) or sealed.get("u", "")
    if user != sealed.get("u"):
        return await _answer(request, {"code": "DRS-8001", "detail": "the single sign-on now names another user: sign in again"}, 401)
    sealed["rt"] = got.get("refresh_token") or sealed["rt"]
    return await _answer(request, {"access_token": out.get("access_token"), "expires_in": out.get("expires_in"), "user": user,
                                   "refresh": _seal(request, sealed)})


@router.post("/redeem")
async def redeem(request: Request):
    origin = _origin(request.headers.get("origin"))
    sealed = _unseal(request, (await _json(request)).get("code") or "") if _key(request) else None
    if not sealed or _origin(sealed.get("o")) != origin or int(sealed.get("exp", 0)) < time.time():
        return await _answer(request, {"code": "DRS-8001", "detail": "this sign-in code is not valid here (or is older than two minutes): sign in again"}, 401)
    return await _answer(request, sealed["msg"])
