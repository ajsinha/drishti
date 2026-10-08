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

"""Where to go after sign-in (or after choosing a date): a path on this console and nothing else. A pinned link keeps its
query (``/v/trade/X?asOf=2026-09-29&knownAt=...``) through the sign-in page, so the target may carry one, but it must be
same-origin: it starts with a single ``/``, never ``//host`` or ``/\\host`` (browsers read a backslash as a slash), and has
no backslash, control character or line break anywhere (header splitting, scheme tricks)."""
from __future__ import annotations

from urllib.parse import quote


def safe_next(target: str | None, default: str = "/t") -> str:
    t = target or ""
    if not t.startswith("/") or t.startswith("//") or "\\" in t or any(ord(c) < 0x20 or ord(c) == 0x7F for c in t):
        return default
    return t


def to_login(path: str, query: str = "") -> str:
    """The sign-in URL that returns to this exact page, query and all."""
    return "/login?next=" + quote(f"{path}?{query}" if query else path, safe="/")


def login_url(path: str, query: str = "") -> str:
    """The sign-in URL for a page, returning to it afterwards; a target that is not a path on this console is dropped."""
    here = f"{path}?{query}" if query else path
    return to_login(path, query) if safe_next(here, default="") else "/login"
