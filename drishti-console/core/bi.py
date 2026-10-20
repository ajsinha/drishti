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

"""Business intelligence (Rupaka phase 1), the console's side: what the signed-in user may do in BI, kept for a short while so the top bar
can show the BI menu (and the person's three most recent items) without a server call on every page. The server decides everything; this
only decides what to *show*. An unreachable or switched-off server means no BI menu, never an error page."""
from __future__ import annotations

import time

from core.servers import scoped

NONE = {"enabled": False, "powers": [], "recent": [], "mayCreateWorkspace": False}
RECENT_IN_MENU = 3


class BiMe:
    def __init__(self, ttl: float = 10.0, stamps=None):
        from core.user_stamps import NONE

        self.ttl = ttl
        self.stamps = stamps or NONE    # core/user_stamps.py: a change through another worker is seen at once
        self._cache: dict[str, tuple[float, dict, float]] = {}

    async def get(self, backend, ident) -> dict:
        if ident is None:
            return dict(NONE)
        key = scoped(ident.user)
        hit = self._cache.get(key)
        if hit and time.monotonic() - hit[0] < self.ttl and self.stamps.fresh(key, hit[2]):
            return hit[1]
        began = time.time()
        try:
            me = await backend.bi("GET", "/me", ident)
            data = {**NONE, **me}
            if data.get("enabled") and "bi" in (data.get("powers") or []):
                home = await backend.bi("GET", "/home", ident)
                data["recent"] = [{"id": i["id"], "title": i["title"], "kind": i["kind"]} for i in (home.get("recent") or [])[:RECENT_IN_MENU]]
            else:
                data["enabled"] = False
        except Exception:  # noqa: BLE001 - the menu is a convenience; a page never fails for it
            return dict(NONE)
        self._cache[key] = (time.monotonic(), data, began)
        return data

    def forget(self, user: str) -> None:
        self._cache.pop(scoped(user), None)
        self.stamps.touch(scoped(user))
