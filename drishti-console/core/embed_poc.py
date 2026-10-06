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

"""Embedded views, PROOF OF CONCEPT (docs/architecture/ELEMENTS.md, build step 0).

Host applications (config ``embed.poc.apps``) are declared with their exact CORS origins and a secret. The host's BACKEND
exchanges the secret for a short embed token naming its signed-in user (:meth:`EmbedPoc.mint`, the DEV-ONLY stand-in for
the server's RFC 8693 exchange, build step 1); the host's page hands the token to ``<drishti-view>``. The console
verifies it on every ``/embed/`` call (:meth:`EmbedPoc.verify`), checks the request's ``Origin`` against the app's origin
list, and calls the server as the user with their roles mapped to the MASKED role: embedded views always mask (Decision 5).

Everything is off unless ``embed.poc.enabled`` is true; nothing here touches the console's own pages.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass

AUDIENCE = "drishti-embed"
TYP = "drishti-embed+jwt-poc"


class EmbedError(Exception):
    """A refused embed call, with the DRS code and HTTP status it is answered with (ELEMENTS.md, section 6.7)."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(detail)
        self.status, self.code, self.detail = status, code, detail


@dataclass(frozen=True)
class App:
    id: str
    secret: str
    origins: frozenset
    role_map: dict


@dataclass(frozen=True)
class Claims:
    user: str
    app: App
    roles: tuple


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _origin(text: str) -> str:
    return str(text or "").strip().rstrip("/").lower()


class EmbedPoc:
    """The host applications, the dev token and the CORS allow-list of one console."""

    def __init__(self, settings):
        cfg = settings.get("embed.poc") or {}
        self.enabled = str(cfg.get("enabled", False)).lower() in ("true", "1", "yes", "on")
        self.key = str(cfg.get("signing_key") or "")
        self.ttl = int(cfg.get("token_ttl_seconds", 300))
        self.masked_role = str(cfg.get("masked_role") or "viewer")
        self.apps: dict[str, App] = {}
        for app_id, a in (cfg.get("apps") or {}).items():
            origins = a.get("origins") or []
            if isinstance(origins, str):
                origins = origins.split(",")
            self.apps[app_id] = App(app_id, str(a.get("secret") or ""), frozenset(_origin(o) for o in origins if _origin(o)),
                                    dict(a.get("role_map") or {}))
        if self.enabled and len(self.key) < 32:
            raise RuntimeError("embed.poc.signing_key must be at least 32 characters when embed.poc.enabled is true")

    # -- CORS ------------------------------------------------------------------------------------------------------
    def origin_allowed(self, origin: str | None) -> bool:
        """An exact match against some host application's origins: never a wildcard, never ``null``."""
        o = _origin(origin)
        return bool(o) and any(o in a.origins for a in self.apps.values())

    # -- the dev token ---------------------------------------------------------------------------------------------
    def _sign(self, payload: str) -> str:
        return _b64(hmac.new(self.key.encode(), payload.encode(), hashlib.sha256).digest())

    def mint(self, app_id: str, secret: str, user: str, roles) -> dict:
        """DEV ONLY (server to server): the app proves itself with its secret and names the user it signed in."""
        app = self.apps.get(app_id)
        if app is None or not app.secret or not hmac.compare_digest(app.secret, str(secret or "")):
            raise EmbedError(401, "DRS-8003", "unknown host application or wrong secret")
        user = str(user or "").strip()
        if not user:
            raise EmbedError(400, "DRS-8001", "the token names no user")
        head = _b64(json.dumps({"alg": "HS256", "typ": TYP}, separators=(",", ":")).encode())
        exp = int(time.time()) + self.ttl
        body = _b64(json.dumps({"sub": user, "roles": [str(r) for r in (roles or [])], "azp": app.id, "aud": AUDIENCE, "exp": exp},
                               separators=(",", ":")).encode())
        return {"access_token": f"{head}.{body}.{self._sign(head + '.' + body)}", "token_type": "Bearer", "expires_in": self.ttl}

    def verify(self, bearer: str | None, origin: str | None) -> Claims:
        """The caller of an /embed/ call: signature, expiry, audience, and the request's Origin against the app's list."""
        parts = (bearer or "").split(".")
        if len(parts) != 3:
            raise EmbedError(401, "DRS-8001", "embed token missing or malformed")
        try:
            if not hmac.compare_digest(self._sign(parts[0] + "." + parts[1]), parts[2]):
                raise ValueError("signature")
            claims = json.loads(_unb64(parts[1]))
            if json.loads(_unb64(parts[0])).get("typ") != TYP or claims.get("aud") != AUDIENCE or int(claims["exp"]) < time.time():
                raise ValueError("claims")
        except (ValueError, KeyError, TypeError):
            raise EmbedError(401, "DRS-8001", "embed token invalid or expired") from None
        app = self.apps.get(claims.get("azp"))
        if app is None:
            raise EmbedError(403, "DRS-8003", "the host application is unknown or disabled")
        if origin and _origin(origin) not in app.origins:
            raise EmbedError(403, "DRS-8002", "this origin is not one of the host application's origins")
        return Claims(str(claims["sub"]), app, tuple(claims.get("roles") or ()))

    def masked_roles(self, claims: Claims) -> tuple:
        """The roles embedded calls run as: each of the user's roles mapped through the app's role_map; whatever does not map
        (and every role with ``raw``, which the map never names) becomes the masked role. Embedded views always mask."""
        mapped = {claims.app.role_map.get(r, self.masked_role) for r in claims.roles} or {self.masked_role}
        return tuple(sorted(mapped))
