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

"""Comment threads, the console's side (COLLABORATION.md, build step 6): a small client for the server's thread endpoints
(``/threads``, ``/comments``, ``/admin/collab/comments``) and the one piece the drawer needs from the console: the link
*Open as it was* follows, made from the comment's own pin (the same form a share's link has)."""
from __future__ import annotations

import re
from typing import Any
from urllib.parse import quote

from core.backend import entity_path
from core.collab import link_for

_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
_QUERY = ("anchor", "panel", "path", "state", "limit", "before")


def clean_id(value: str | None) -> str | None:
    """A thread or comment id as the server writes it (letters, digits, ``_`` and ``-``), else None: nothing else goes into a server path."""
    v = (value or "").strip()
    return v if _ID.match(v) else None


def pin_link(thread: dict, comment: dict) -> str:
    """Where *Open as it was* goes: the entity at the date, "known at" time and generation the comment was written against."""
    return link_for({"kind": thread.get("kind"), "entityId": thread.get("entityId"), "pin": comment.get("pin") or {}, "panel": thread.get("panel")})


def annotate(threads: list) -> list:
    """Adds ``href`` to every comment of every thread (its pinned link), in place."""
    for t in threads or []:
        for c in t.get("items") or []:
            c["href"] = pin_link(t, c)
    return threads


class Threads:
    """The server's thread API for the signed-in user. Stateless: built over whatever backend the request uses."""

    def __init__(self, backend: Any):
        self._b = backend

    async def list(self, kind: str, id_: str, ident, **query) -> list:
        where, q = entity_path(kind, id_)
        params = {**q, **{k: v for k, v in query.items() if k in _QUERY and v not in (None, "")}}
        return annotate(await self._b._send("GET", f"/threads/{where}", ident, params=params))

    async def counts(self, kind: str, id_: str, ident) -> dict:
        where, q = entity_path(kind, id_)
        return await self._b._send("GET", f"/threads/{where}/counts", ident, params=q)

    async def start(self, kind: str, id_: str, body: dict, ident) -> dict:
        where, q = entity_path(kind, id_)
        return await self._b._send("POST", f"/threads/{where}", ident, params=q, json=body)

    async def reply(self, tid: str, body: dict, ident) -> dict:
        return await self._b._send("POST", f"/threads/{quote(tid, safe='')}/comments", ident, json=body)

    async def edit(self, cid: str, body: dict, ident) -> dict:
        return await self._b._send("PATCH", f"/comments/{quote(cid, safe='')}", ident, json=body)

    async def retract(self, cid: str, ident) -> dict:
        return await self._b._send("POST", f"/comments/{quote(cid, safe='')}/retract", ident)

    async def revisions(self, cid: str, ident) -> list:
        return await self._b._send("GET", f"/comments/{quote(cid, safe='')}/revisions", ident)

    async def state(self, tid: str, state: str, ident) -> dict:
        return await self._b._send("POST", f"/threads/{quote(tid, safe='')}/state", ident, json={"state": state})

    async def follow(self, tid: str, muted: bool, ident) -> dict:
        return await self._b._send("PUT", f"/threads/{quote(tid, safe='')}/follow", ident, json={"muted": muted})

    async def unfollow(self, tid: str, ident) -> None:
        await self._b._send("DELETE", f"/threads/{quote(tid, safe='')}/follow", ident)

    async def hide(self, cid: str, reason: str, ident) -> dict:
        return await self._b._send("POST", f"/admin/collab/comments/{quote(cid, safe='')}/hide", ident, json={"reason": reason})

    async def unhide(self, cid: str, ident) -> dict:
        return await self._b._send("POST", f"/admin/collab/comments/{quote(cid, safe='')}/unhide", ident)
