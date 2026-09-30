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

* Users live in a YAML file (``auth.users_file``) with PBKDF2-SHA256 password hashes
  (``python console/tools/hash_password.py``). The file is git-ignored; ``config/users.example.yaml``
  shows the shape.
* A session is a signed, expiring cookie (HMAC-SHA256 with ``auth.session_secret``); nothing is stored
  server-side, so any console instance can serve any user.
* For every backend call the console mints a short-lived HS256 token (``auth.token_secret``, shared with
  the server) carrying the user and roles.

With ``auth.enabled`` false (local development) everyone is the configured desk user with every role.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path

import yaml

COOKIE = "drishti_session"


@dataclass(frozen=True)
class Identity:
    user: str
    display: str
    desk: str
    roles: tuple = field(default=("*",))
    token: str | None = None

    def headers(self) -> dict:
        h = {"X-Drishti-User": self.user}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h


def hash_password(password: str, iterations: int = 240_000, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    return f"pbkdf2_sha256${iterations}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iterations, salt, digest = stored.split("$")
    except ValueError:
        return False
    if algo != "pbkdf2_sha256":
        return False
    candidate = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), int(iterations))
    return hmac.compare_digest(candidate.hex(), digest)


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mint_token(sub: str, roles, secret: str, ttl: int) -> str:
    header = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = _b64(json.dumps({"sub": sub, "roles": list(roles), "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), f"{header}.{claims}".encode(), hashlib.sha256).digest()
    return f"{header}.{claims}.{_b64(sig)}"


class Auth:
    """Users, sessions and tokens for one console process."""

    def __init__(self, settings, base: Path):
        self.enabled = bool(settings.get("auth.enabled", False))
        self.session_secret = str(settings.get("auth.session_secret") or "")
        self.token_secret = str(settings.get("auth.token_secret") or "")
        self.token_ttl = int(settings.get("auth.token_ttl_seconds", 300))
        self.session_ttl = int(settings.get("auth.session_hours", 10)) * 3600
        self.secure_cookie = bool(settings.get("auth.secure_cookie", True))
        self.default = Identity(settings.get("ui.user", "ash"), settings.get("ui.user_display", "Ash"),
                                settings.get("ui.desk", "Rates desk"))
        self.users: dict = {}
        if self.enabled:
            if len(self.session_secret) < 32:
                raise RuntimeError("auth.session_secret must be at least 32 characters when auth is enabled")
            users_file = Path(settings.get("auth.users_file", "config/users.yaml"))
            path = users_file if users_file.is_absolute() else base / users_file
            self.users = (yaml.safe_load(path.read_text()) or {}).get("users", {}) if path.exists() else {}

    # -- sessions ------------------------------------------------------------------------------
    def _sign(self, payload: str) -> str:
        return _b64(hmac.new(self.session_secret.encode(), payload.encode(), hashlib.sha256).digest())

    def login(self, user: str, password: str) -> str | None:
        """A session cookie value for valid credentials, else None. Timing does not reveal unknown users."""
        u = self.users.get(user)
        stored = u.get("password", "") if u else hash_password("x", iterations=1000, salt=b"0" * 16)
        ok = verify_password(password, stored) and u is not None
        if not ok:
            return None
        payload = f"{user}|{int(time.time()) + self.session_ttl}"
        return f"{payload}|{self._sign(payload)}"

    def identity(self, cookie: str | None) -> Identity | None:
        if not self.enabled:
            return self._with_token(self.default)
        if not cookie or cookie.count("|") != 2:
            return None
        user, exp, sig = cookie.split("|")
        if not hmac.compare_digest(sig, self._sign(f"{user}|{exp}")) or int(exp) < time.time():
            return None
        u = self.users.get(user)
        if not u:
            return None
        return self._with_token(Identity(user, u.get("display", user), u.get("desk", ""), tuple(u.get("roles", []))))

    def _with_token(self, ident: Identity) -> Identity:
        if not self.token_secret:
            return ident
        return Identity(ident.user, ident.display, ident.desk, ident.roles,
                        mint_token(ident.user, ident.roles, self.token_secret, self.token_ttl))
