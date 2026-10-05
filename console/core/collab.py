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

"""Share with a note, the console's side (COLLABORATION.md, build step 3): a small client for the server's collaboration
endpoints (``/collab``, ``/directory``, ``/shares``, ``/me/inbox``), and the pieces the pages share: the share id that
travels with a view call, and the link a share resolves to."""
from __future__ import annotations

import contextvars
import re
from typing import Any
from urllib.parse import quote

SHARE_HEADER = "X-Drishti-Share"
_ID = re.compile(r"^sh_[A-Za-z0-9]{8,64}$")
_share: contextvars.ContextVar[str | None] = contextvars.ContextVar("drishti_share", default=None)


def clean_share(value: str | None) -> str | None:
    """A share id as the server writes it (``sh_`` and letters or digits), else None: nothing else is ever sent as a header."""
    v = (value or "").strip()
    return v if _ID.match(v) else None


def set_share(value: str | None) -> str | None:
    v = clean_share(value)
    _share.set(v)
    return v


def headers() -> dict[str, str]:
    """The header that tells the server a view was opened through a share's link (it records it in the access log)."""
    v = _share.get()
    return {SHARE_HEADER: v} if v else {}


def link_for(share: dict) -> str:
    """Where a share opens: the view as it was pinned (business date, "known at", generation) or live, with the panel
    scrolled to. ``share`` is the server's ``GET /shares/{id}`` answer for someone who has access."""
    pin = share.get("pin") or {}
    q = []
    if not pin.get("live") and pin.get("businessDate"):
        q.append(f"asOf={quote(str(pin['businessDate']), safe='')}")
        known = re.sub(r"\.\d+Z$", "Z", str(pin.get("knownAt") or ""))     # the console's "known at" has whole seconds
        if known:
            q.append(f"knownAt={quote(known, safe='')}")
    if pin.get("generation"):
        q.append(f"gen={int(pin['generation'])}")
    if share.get("id"):
        q.append(f"share={quote(str(share['id']), safe='')}")
    path = f"/v/{quote(str(share.get('kind') or ''), safe='')}/{quote(str(share.get('entityId') or ''), safe='')}"
    frag = f"#p-{quote(str(share['panel']), safe='')}" if share.get("panel") else ""
    return path + ("?" + "&".join(q) if q else "") + frag


class Collab:
    """The server's collaboration API for the signed-in user. Stateless: built over whatever backend the request uses."""

    def __init__(self, backend: Any):
        self._b = backend

    async def config(self, ident) -> dict:
        return await self._b._send("GET", "/collab", ident)

    async def directory(self, q: str, ident, limit: int = 10, kind: str = "") -> list:
        params: dict[str, Any] = {"q": q, "limit": limit}
        if kind:
            params["kind"] = kind
        return await self._b._send("GET", "/directory", ident, params=params)

    async def send(self, body: dict, ident) -> dict:
        return await self._b._send("POST", "/shares", ident, json=body)

    async def share(self, share_id: str, ident) -> dict:
        return await self._b._send("GET", f"/shares/{quote(share_id, safe='')}", ident)

    async def inbox(self, ident, type_: str = "", unread: bool = False, limit: int = 50, before: int = 0) -> list:
        params: dict[str, Any] = {"unread": "true" if unread else "false", "limit": limit}
        if type_:
            params["type"] = type_
        if before:
            params["before"] = before
        return await self._b._send("GET", "/me/inbox", ident, params=params)

    async def unread(self, ident) -> int:
        return int((await self._b._send("GET", "/me/inbox/count", ident)).get("unread", 0))

    async def mark_read(self, body: dict, ident) -> dict:
        return await self._b._send("POST", "/me/inbox/read", ident, json=body)
