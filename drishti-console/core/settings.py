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
from core.servers import scoped
from core.user_stamps import NONE as NO_STAMPS

DEFAULTS = {"theme": None, "landing": "/t", "clockZone": None, "locale": None, "density": "comfortable", "flash": True, "searchLimit": 100, "pinned": [], "savedSearches": []}


#: the cookie that tells every worker (and every console behind one address) that this browser's user changed a setting:
#: a worker's cached settings older than it are read again, so the next page shows the change whichever process serves it
CHANGED_COOKIE = "drishti_settings_at"


class UserSettings:
    """A user's settings for a short while (``ttl``), per console process. A console runs several worker processes, each
    with its own cache: a change forgets the cache of the worker that made it only, so the change also stamps the browser
    (CHANGED_COOKIE) and every worker reads the settings again for a request whose stamp is newer than its copy (QA
    2026-10-20: a theme picked on one page came back as the old one on the next page another worker served)."""

    def __init__(self, ttl: float = 10.0, stamps=NO_STAMPS):
        self.ttl = ttl
        self.stamps = stamps            # the console's user stamps (core/user_stamps.py): every worker, every browser of the user
        self._cache: dict[str, tuple[float, float, dict]] = {}   # user → (monotonic time, wall time the read began, settings)

    async def get(self, backend, ident, changed_at: float = 0.0) -> dict:
        if ident is None:
            return dict(DEFAULTS)
        key = scoped(ident.user)
        hit = self._cache.get(key)
        if hit and time.monotonic() - hit[0] < self.ttl and hit[1] > changed_at and self.stamps.fresh(key, hit[1]):
            return hit[2]
        began = time.time()             # before the call: a change landing while it runs is newer than this copy
        try:
            data = {**DEFAULTS, **(await backend.settings(ident))}
        except Exception:  # noqa: BLE001 - settings are a convenience; a page never fails for them
            return dict(DEFAULTS)
        self._cache[key] = (time.monotonic(), began, data)
        return data

    def forget(self, user: str, request=None) -> None:
        """Drops this worker's copy and stamps the user (every worker, every browser of theirs, reads again); with the request, the
        response also stamps the browser (the fast path, and consoles behind one address that share no data folder)."""
        self._cache.pop(scoped(user), None)
        stamp = self.stamps.touch(scoped(user))
        if request is not None:
            request.state.settings_changed_at = stamp


def changed_at(request) -> float:
    """The browser's stamp of its user's last settings change (0 when none, or not a number)."""
    try:
        return float(request.cookies.get(CHANGED_COOKIE) or 0)
    except ValueError:
        return 0.0


def is_pinned(settings: dict, kind: str, id_: str) -> bool:
    return any(p.get("kind") == kind and p.get("id") == id_ for p in settings.get("pinned") or [])
