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

TYP = "drishti-embed+jwt"
_ORIGINS_TTL = 60.0


class EmbedError(Exception):
    """A refused embed call, with the DRS code and HTTP status it is answered with (ELEMENTS.md, section 6.7)."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(detail)
        self.status, self.code, self.detail = status, code, detail


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


class EmbedHosts:
    """The embed settings of one console and the CORS origins its server's host applications registered (cached 60 s)."""

    def __init__(self, settings):
        cfg = settings.get("embed") or {}
        self.enabled = str(cfg.get("enabled", False)).lower() in ("true", "1", "yes", "on")
        self._ttl = float(cfg.get("origins_ttl_seconds", _ORIGINS_TTL))
        self._origins: frozenset = frozenset()
        self._fetched = 0.0

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
        for a in await self.origins(request):
            if a == o:
                return True
            if "://*." in a:
                scheme, _, rest = a.partition("://*.")
                if o.startswith(scheme + "://") and o.endswith("." + rest):
                    return True
        return False

    @staticmethod
    def claims(bearer: str | None) -> dict:
        """The token's claims, read to route the call (NOT trusted: the server verifies the signature on every call)."""
        parts = (bearer or "").split(".")
        if len(parts) != 3:
            raise EmbedError(401, "DRS-8001", "embed token missing or malformed")
        try:
            head, claims = json.loads(_unb64(parts[0])), json.loads(_unb64(parts[1]))
            if head.get("typ") != TYP or not claims.get("sub") or int(claims["exp"]) <= time.time():
                raise ValueError("claims")
        except (ValueError, KeyError, TypeError):
            raise EmbedError(401, "DRS-8001", "embed token invalid or expired") from None
        return claims

    async def identity(self, request, bearer: str | None, origin: str | None) -> tuple[Identity, dict]:
        """The caller of an /embed/ call and the token's claims. Raises EmbedError (the server's own checks come with the first call)."""
        claims = self.claims(bearer)
        if origin and not await self.origin_allowed(request, origin):
            raise EmbedError(403, "DRS-8002", "this origin is not one of the host application's origins")
        return EmbedIdentity(str(claims["sub"]), str(claims["sub"]), "", ("viewer",), token=bearer, origin=origin or None), claims
