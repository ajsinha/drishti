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

"""Sign-in, sessions and server tokens.

* Users are managed by the server (``drishti-identity``). The console verifies a sign-in by calling
  ``POST /api/v1/auth/login`` with its own short-lived *service* token; it never sees password hashes.
* A session is a signed, expiring cookie (HMAC-SHA256 with ``auth.session_secret``) carrying the user's
  name, display name, desk and roles; nothing is stored in the console, so any instance serves any user.
* For every backend call the console mints a short-lived HS256 token (``auth.token_secret``, shared with
  the server) carrying the user and roles.

With ``auth.enabled`` false (local development) everyone is the configured desk user with every role.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass, field

COOKIE = "drishti_session"


@dataclass(frozen=True)
class Identity:
    user: str
    display: str
    desk: str
    roles: tuple = field(default=("*",))
    token: str | None = None
    must_change: bool = False

    def headers(self) -> dict:
        h = {"X-Drishti-User": self.user}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    @property
    def is_admin(self) -> bool:
        return "*" in self.roles or "admin" in self.roles


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def mint_token(sub: str, roles, secret: str, ttl: int) -> str:
    header = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = _b64(json.dumps({"sub": sub, "roles": list(roles), "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), f"{header}.{claims}".encode(), hashlib.sha256).digest()
    return f"{header}.{claims}.{_b64(sig)}"


class Auth:
    """Sessions and tokens for one console process."""

    def __init__(self, settings, server=None):
        """``server``: the :class:`core.servers.Server` this Auth signs in to (its own token secret and session cookie);
        None for the one server of a console without ``servers:``."""
        self.enabled = bool(settings.get("auth.enabled", False))
        self.session_secret = str(settings.get("auth.session_secret") or "")
        self.token_secret = str(settings.get("auth.token_secret") or "") if server is None else server.token_secret
        self.server = "default" if server is None else server.id
        # one session per server, side by side: signing in to one server leaves the others as they were
        self.cookie = COOKIE if self.server == "default" else f"{COOKIE}_{self.server}"
        self.token_ttl = int(settings.get("auth.token_ttl_seconds", 300))
        self.session_ttl = int(settings.get("auth.session_hours", 10)) * 3600
        self.secure_cookie = bool(settings.get("auth.secure_cookie", True))
        self.default = Identity(settings.get("ui.user", "ash"), settings.get("ui.user_display", "Ash"),
                                settings.get("ui.desk", "Rates desk"))
        if self.enabled and len(self.session_secret) < 32:
            raise RuntimeError("auth.session_secret must be at least 32 characters when auth is enabled")

    def service(self) -> Identity:
        """The console itself, allowed only to verify sign-ins."""
        return self._with_token(Identity("console", "Console", "", ("service",)))

    # -- sessions ------------------------------------------------------------------------------
    def _sign(self, payload: str) -> str:
        return _b64(hmac.new(self.session_secret.encode(), payload.encode(), hashlib.sha256).digest())

    def session_for(self, user: dict) -> str:
        """A signed cookie value for a user the server has just verified."""
        body = {"u": user["username"], "d": user.get("displayName") or user["username"], "k": user.get("desk") or "",
                "r": sorted(user.get("roles") or []), "m": bool(user.get("mustChangePassword")),
                "x": int(time.time()) + self.session_ttl, "s": self.server}
        payload = _b64(json.dumps(body, separators=(",", ":")).encode())
        return f"{payload}.{self._sign(payload)}"

    def identity(self, cookie: str | None) -> Identity | None:
        if not self.enabled:
            return self._with_token(self.default)
        if not cookie or cookie.count(".") != 1:
            return None
        payload, sig = cookie.split(".")
        if not hmac.compare_digest(sig, self._sign(payload)):
            return None
        try:
            body = json.loads(_unb64(payload))
        except ValueError:
            return None
        if int(body.get("x", 0)) < time.time() or body.get("s", "default") != self.server:
            return None                                  # expired, or a session for another server
        return self._with_token(Identity(body["u"], body["d"], body["k"], tuple(body["r"]), must_change=bool(body.get("m"))))

    def _with_token(self, ident: Identity) -> Identity:
        if not self.token_secret:
            return ident
        return Identity(ident.user, ident.display, ident.desk, ident.roles,
                        mint_token(ident.user, ident.roles, self.token_secret, self.token_ttl), ident.must_change)
