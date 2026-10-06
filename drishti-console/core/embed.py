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

"""Embedded views for other web applications (docs/architecture/ELEMENTS.md; build steps 1 to 3 on the server, 7 here).

A host application's BACKEND exchanges its credentials and its user's identity for an embed token at the SERVER
(``POST /api/v1/embed/token``, RFC 8693); the host's page hands the token to ``<drishti-view>``. The server checks the token on
EVERY call (signature, audience, expiry, host application enabled, user enabled, origin, scopes, kinds, rates) and always masks;
this module only reads what routing needs, and asks the server which origins its host applications registered, for CORS.
Everything is off unless ``embed.enabled``; nothing here touches the console's own pages.
"""
from __future__ import annotations

import base64
import json
import time
from dataclasses import dataclass

from core.auth import Identity
from core.embed_limits import EmbedLimits

TYP = "drishti-embed+jwt"
_ORIGINS_TTL = 60.0


class EmbedError(Exception):
    """A refused embed call, with the DRS code and HTTP status it is answered with (ELEMENTS.md, section 6.7)."""

    def __init__(self, status: int, code: str, detail: str, retry_after: str | None = None):
        super().__init__(detail)
        self.status, self.code, self.detail, self.retry_after = status, code, detail, retry_after


@dataclass(frozen=True)
class EmbedIdentity(Identity):
    """The caller of an embed request: calls the server with the embed token itself (never a console token), and passes the
    browser's Origin on so the server checks it against the host application's origins too."""

    origin: str | None = None

    def headers(self) -> dict:
        h = {"Authorization": f"Bearer {self.token}"}
        if self.origin:
            h["Origin"] = self.origin
        return h


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _origin(text) -> str:
    return str(text or "").strip().rstrip("/").lower()


def origin_matches(allowed: str, origin: str) -> bool:
    """An exact match, or a registered ``https://*.suffix`` by suffix (both already normalised by :func:`_origin`)."""
    if allowed == origin:
        return True
    if "://*." in allowed:
        scheme, _, rest = allowed.partition("://*.")
        return origin.startswith(scheme + "://") and origin.endswith("." + rest)
    return False


class EmbedHosts:
    """The embed settings of one console and the CORS origins its server's host applications registered (cached 60 s)."""

    def __init__(self, settings):
        cfg = settings.get("embed") or {}
        self.enabled = str(cfg.get("enabled", False)).lower() in ("true", "1", "yes", "on")
        self._ttl = float(cfg.get("origins_ttl_seconds", _ORIGINS_TTL))
        self._origins: frozenset = frozenset()
        self._fetched = 0.0
        self.settings = cfg
        self.auth = None                       # EmbedAuth, built on first use (it needs the backend): see :meth:`verifier`
        self.limits = EmbedLimits(cfg)
        self.guards: dict = {}                 # open channel id -> its StreamGuard (core/embed_stream.py)

    def verifier(self, request):
        """The token verifier: keys from the server's ``/embed/jwks`` (cached), audiences from ``embed.audiences``."""
        if self.auth is None:
            from core.embed_auth import EmbedAuth

            async def keys():
                return await request.app.state.backend._get("/embed/jwks", request.app.state.auth.service())

            cfg = self.settings
            aud = cfg.get("audiences") or []
            self.auth = EmbedAuth(keys, audiences=[aud] if isinstance(aud, str) else aud, ttl=float(cfg.get("jwks_ttl_seconds", 300)),
                                  min_refetch=float(cfg.get("jwks_min_refetch_seconds", 10)))
        return self.auth

    async def origins(self, request) -> frozenset:
        if time.monotonic() - self._fetched > self._ttl:
            try:
                got = await request.app.state.backend._get("/embed/apps/origins", request.app.state.auth.service())
                self._origins = frozenset(_origin(o) for o in got.get("origins", []))
            except Exception:                       # the server is unreachable: keep what we knew
                pass
            self._fetched = time.monotonic()
        return self._origins

    async def origin_allowed(self, request, origin: str | None) -> bool:
        """An exact match against a registered origin (a registered ``https://*.suffix`` also by suffix): never ``null``."""
        o = _origin(origin)
        if not o or o == "null":
            return False
        return any(origin_matches(a, o) for a in await self.origins(request))

    async def identity(self, request, bearer: str | None, origin: str | None, *, count: bool = True) -> tuple[Identity, dict]:
        """The caller of an /embed/ call and the verified claims. Raises EmbedError. The server checks the token again on every call."""
        self.check_contract(request.headers.get("drishti-embed-api"))
        if origin and not await self.origin_allowed(request, origin):
            raise EmbedError(403, "DRS-8002", "this origin is not one of the host application's origins")
        claims = await self.verifier(request).verify(bearer, origin)
        if count:
            self.limits.take(str(claims["azp"]))
        return EmbedIdentity(str(claims["sub"]), str(claims["sub"]), "", ("viewer",), token=bearer, origin=origin or None), claims

    @staticmethod
    def check_contract(value: str | None) -> None:
        """The element's contract version (``Drishti-Embed-Api: 1.0``): a major this console no longer serves is ``410 DRS-8006``."""
        if value and value.strip().split(".")[0] != "1":
            raise EmbedError(410, "DRS-8006", f"the element's contract version {value.strip()} is not served (this console serves 1.x): upgrade the element")
