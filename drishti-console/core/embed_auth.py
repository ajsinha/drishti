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

"""Console-side verification of the server's embed tokens (docs/architecture/ELEMENTS.md, section 6; build step 7).

The server issues an ES256 JWT (header typ ``drishti-embed+jwt``) and publishes its key at ``GET /api/v1/embed/jwks``.
``EmbedAuth`` checks the signature against that key set, the expiry, the audience, the application and the browser's Origin
(against the token's own ``origins``), so a forged or foreign token is refused here, before any work. It is defence in depth:
the token is still passed to the server, which checks it again, and is the decision, on every call.

The key set is cached (``embed.jwks_ttl_seconds``); a token naming a key id the cache does not know makes ONE refetch (the key was
rotated), but never more often than ``embed.jwks_min_refetch_seconds``, so a stream of forged key ids cannot make the console
hammer the server.
"""
from __future__ import annotations

import asyncio
import json
import time
from typing import Awaitable, Callable

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from core.embed import EmbedError, TYP, _origin, _unb64, origin_matches

FetchKeys = Callable[[], Awaitable[dict]]


class EmbedAuth:
    """Verifies embed tokens against the server's key set."""

    def __init__(self, fetch_keys: FetchKeys, *, audiences=(), ttl: float = 300.0, min_refetch: float = 10.0,
                 leeway: float = 5.0, clock: Callable[[], float] = time.time):
        self._fetch, self._audiences = fetch_keys, tuple(str(a).rstrip("/") for a in audiences if a)
        self._ttl, self._min_refetch, self._leeway, self._clock = ttl, min_refetch, leeway, clock
        self._keys: dict[str, ec.EllipticCurvePublicKey] = {}
        self._fetched = float("-inf")                 # monotonic time of the last fetch
        self._lock = asyncio.Lock()

    # -- the key set ------------------------------------------------------------------------------------------------
    @staticmethod
    def _public(jwk: dict) -> ec.EllipticCurvePublicKey:
        x, y = int.from_bytes(_unb64(jwk["x"]), "big"), int.from_bytes(_unb64(jwk["y"]), "big")
        return ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()

    async def _refresh(self) -> None:
        got = await self._fetch()
        keys = {}
        for jwk in (got or {}).get("keys", []):
            if jwk.get("kty") == "EC" and jwk.get("crv") == "P-256" and jwk.get("kid"):
                try:
                    keys[str(jwk["kid"])] = self._public(jwk)
                except (KeyError, ValueError):
                    continue
        self._keys, self._fetched = keys, time.monotonic()

    async def key(self, kid: str) -> ec.EllipticCurvePublicKey:
        async with self._lock:
            age = time.monotonic() - self._fetched
            if age > self._ttl or (kid not in self._keys and age > self._min_refetch):
                try:
                    await self._refresh()
                except Exception:                      # the server is unreachable: keep the keys we have, try again later
                    self._fetched = time.monotonic() - self._ttl + min(self._min_refetch, self._ttl)
            if kid not in self._keys:
                raise EmbedError(401, "DRS-8001", "embed token signed with an unknown key")
            return self._keys[kid]

    # -- the token --------------------------------------------------------------------------------------------------
    async def verify(self, bearer: str | None, origin: str | None = None) -> dict:
        """The token's claims once the signature, expiry, audience, application and origin pass. Raises EmbedError."""
        parts = (bearer or "").split(".")
        if len(parts) != 3:
            raise EmbedError(401, "DRS-8001", "embed token missing or malformed")
        try:
            head, claims = json.loads(_unb64(parts[0])), json.loads(_unb64(parts[1]))
            raw = _unb64(parts[2])
            if not isinstance(head, dict) or not isinstance(claims, dict) or len(raw) != 64:
                raise ValueError("shape")
        except (ValueError, TypeError):
            raise EmbedError(401, "DRS-8001", "embed token malformed") from None
        if head.get("typ") != TYP or head.get("alg") != "ES256" or not head.get("kid"):
            raise EmbedError(401, "DRS-8001", "not an embed token")
        key = await self.key(str(head["kid"]))
        try:
            key.verify(encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")),
                       f"{parts[0]}.{parts[1]}".encode("ascii"), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError, UnicodeEncodeError):
            raise EmbedError(401, "DRS-8001", "embed token signature invalid") from None
        try:
            if not claims.get("sub") or not claims.get("azp") or float(claims["exp"]) <= self._clock() - self._leeway:
                raise ValueError("claims")
            if "nbf" in claims and float(claims["nbf"]) > self._clock() + self._leeway:
                raise ValueError("nbf")
        except (ValueError, KeyError, TypeError):
            raise EmbedError(401, "DRS-8001", "embed token invalid or expired") from None
        if self._audiences:
            aud = claims.get("aud")
            named = [str(a).rstrip("/") for a in (aud if isinstance(aud, list) else [aud])]
            if not any(a in self._audiences for a in named):
                raise EmbedError(401, "DRS-8001", "embed token is for another audience")
        if origin and claims.get("origins") is not None:
            if not any(origin_matches(_origin(o), _origin(origin)) for o in claims.get("origins") or []):
                raise EmbedError(403, "DRS-8002", "this origin is not one of the host application's origins")
        return claims
