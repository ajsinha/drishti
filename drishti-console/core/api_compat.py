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

"""The console and the server speak a versioned API: the console declares the range it supports and refuses an incompatible server.

The server reports its API version in ``GET /api/v1/version`` (``{"api": "1.0", "minSupported": "1.0", "product", "version"}``, open, asked
before any sign-in) and in ``/public/about`` (``api``). ``major.minor``: a minor step only adds; a major step may remove or change.
This console was written for the API versions :data:`SUPPORTED_MIN` to :data:`SUPPORTED_MAX` (inclusive) and works against any server
whose API is inside that range. :func:`problem` is the one rule (one line of words, or ``None`` when compatible); :func:`preflight` asks a
server once and is what the console's start command calls before it opens its port: a server that is away is not a problem (start order
does not matter; the backend watch checks again at every probe), a server that answers outside the range is.
"""
from __future__ import annotations

import re

#: the oldest and the newest server API this console is written for (inclusive). Raise SUPPORTED_MAX when a server minor is tested against.
SUPPORTED_MIN = "1.0"
SUPPORTED_MAX = "1.99"
PATH = "/api/v1/version"

_VERSION = re.compile(r"^(\d+)\.(\d+)$")


def parse(text) -> tuple[int, int] | None:
    """``"1.4"`` as ``(1, 4)``; None for anything that is not ``major.minor``."""
    m = _VERSION.match(str(text or "").strip())
    return (int(m.group(1)), int(m.group(2))) if m else None


def supported() -> str:
    """The range in words: ``1.0 to 1.99``."""
    return f"{SUPPORTED_MIN} to {SUPPORTED_MAX}"


def problem(api, url: str = "the server") -> str | None:
    """Why a server that reports API version ``api`` cannot be used by this console, in one line; None when it can. ``api`` None or empty:
    a server older than the versioned API."""
    got = parse(api)
    if got is None:
        if api in (None, ""):
            return (f"the server at {url} reports no API version (it is older than API {SUPPORTED_MIN}): this console supports API "
                    f"{supported()}; start the console of the server's release, or upgrade the server")
        return f"the server at {url} reports the API version {api!r}, which is not major.minor: this console supports API {supported()}"
    if parse(SUPPORTED_MIN) <= got <= parse(SUPPORTED_MAX):
        return None
    side = "newer" if got > parse(SUPPORTED_MAX) else "older"
    return (f"the server at {url} speaks API {api}, {side} than this console supports (API {supported()}): "
            f"use the console of the server's release" + (", or upgrade this console" if side == "newer" else ", or upgrade the server"))


def preflight(url: str, timeout: float = 3.0) -> str | None:
    """Asks the server once (``/api/v1/version``, then ``/public/about`` for a server whose API version is not answered): the problem line
    when it answers outside this console's range, else None, also when the server cannot be reached (it may start after the console)."""
    import httpx

    base = url.rstrip("/")
    try:
        with httpx.Client(timeout=timeout, trust_env=False) as client:
            r = client.get(base + PATH)
            if r.status_code == 200:
                return problem((r.json() or {}).get("api"), base)
            r = client.get(base + "/public/about")          # a server before the versioned API: no such route, but it answers
            if r.status_code == 200:
                return problem((r.json() or {}).get("api"), base)
    except (httpx.HTTPError, ValueError):
        return None
    return None
