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


"""Personal settings (W21). The server keeps them (so they follow the user to any browser) and validates them; the
console reads them once per user for a short while, so a page costs no extra backend call, and forgets them when
the user changes them."""
from __future__ import annotations

import time

DEFAULTS = {"theme": None, "landing": "/t", "clockZone": None, "density": "comfortable", "flash": True, "searchLimit": 100, "pinned": []}


class UserSettings:
    def __init__(self, ttl: float = 30.0):
        self.ttl = ttl
        self._cache: dict[str, tuple[float, dict]] = {}

    async def get(self, backend, ident) -> dict:
        if ident is None:
            return dict(DEFAULTS)
        hit = self._cache.get(ident.user)
        if hit and time.monotonic() - hit[0] < self.ttl:
            return hit[1]
        try:
            data = {**DEFAULTS, **(await backend.settings(ident))}
        except Exception:  # noqa: BLE001 - settings are a convenience; a page never fails for them
            return dict(DEFAULTS)
        self._cache[ident.user] = (time.monotonic(), data)
        return data

    def forget(self, user: str) -> None:
        self._cache.pop(user, None)


def is_pinned(settings: dict, kind: str, id_: str) -> bool:
    return any(p.get("kind") == kind and p.get("id") == id_ for p in settings.get("pinned") or [])
