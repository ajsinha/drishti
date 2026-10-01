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


"""One console, many servers (ADR-016). The console's configuration lists the servers (``servers:``); the person
picks one and signs in to it. Each server keeps its own users, roles, packs and audit: a session on one gives nothing
on another. The chosen server is per browser (a cookie) and every request runs against it: ``app.state.backend`` and
``app.state.auth`` are switches that answer for the current request's server, so routes need not know there are
several. Caches key by (server, user) through :func:`scoped`.

Without ``servers:`` the console has one server, ``default``, from ``backend.url`` and ``auth.token_secret``:
installations that never heard of this keep working unchanged.
"""
from __future__ import annotations

import asyncio
import contextvars
import re
from dataclasses import dataclass

import httpx

DEFAULT = "default"
COOKIE = "drishti_server"
_ID = re.compile(r"^[a-z][a-z0-9-]{0,31}$")
CURRENT: contextvars.ContextVar[str] = contextvars.ContextVar("drishti_server", default=DEFAULT)


def current() -> str:
    """The server the current request runs against."""
    return CURRENT.get()


def scoped(key: str) -> str:
    """A cache key for this server: the same user on two servers is two people."""
    return f"{CURRENT.get()}\x1f{key}"


@dataclass(frozen=True)
class Server:
    id: str
    name: str
    url: str
    description: str = ""
    color: str = ""
    listed: bool = True
    token_secret: str = ""


class Servers:
    """The catalogue: only these servers can be reached (a server id is never a URL: no request forgery through it)."""

    def __init__(self, settings):
        entries = settings.get("servers") or []
        servers: list[Server] = []
        for e in entries:
            sid = str(e.get("id", "")).strip()
            if not _ID.match(sid):
                raise RuntimeError(f"servers: id '{sid}' must be lower-case letters, digits and dashes (at most 32)")
            if not e.get("url"):
                raise RuntimeError(f"servers: '{sid}' has no url")
            servers.append(Server(sid, str(e.get("name") or sid), str(e["url"]).rstrip("/"), str(e.get("description") or ""),
                                  str(e.get("color") or ""), bool(e.get("listed", True)),
                                  str(e.get("token_secret") or settings.get("auth.token_secret") or "")))
        if not servers:
            servers = [Server(DEFAULT, str(settings.get("backend.name") or settings.get("ui.product", "") or "Server"),
                              str(settings.get("backend.url")).rstrip("/"), token_secret=str(settings.get("auth.token_secret") or ""))]
        if len({s.id for s in servers}) != len(servers):
            raise RuntimeError("servers: every id must be different")
        self._by_id = {s.id: s for s in servers}
        self.default = servers[0].id

    def __len__(self) -> int:
        return len(self._by_id)

    def get(self, sid: str | None) -> Server | None:
        return self._by_id.get(sid or "")

    def all(self) -> list[Server]:
        return list(self._by_id.values())

    def listed(self) -> list[Server]:
        return [s for s in self._by_id.values() if s.listed]

    def choose(self, cookie: str | None) -> str:
        """The server a request runs against: the person's choice if it is in the catalogue, else the first."""
        return cookie if cookie in self._by_id else self.default

    async def states(self, timeout: float = 2.0) -> dict[str, dict]:
        """Each listed server's public state for the picker (name, version, how to sign in), read concurrently."""
        async def one(client: httpx.AsyncClient, s: Server) -> tuple[str, dict]:
            try:
                r = await client.get(f"{s.url}/public/about")
                r.raise_for_status()
                return s.id, {"up": True, **r.json()}
            except (httpx.HTTPError, ValueError) as e:
                return s.id, {"up": False, "error": type(e).__name__}
        async with httpx.AsyncClient(timeout=timeout) as client:
            return dict(await asyncio.gather(*(one(client, s) for s in self.all())))


class Switch:
    """Stands in for one per-server object (a backend client, an Auth): every attribute is the current server's."""

    def __init__(self, by_server: dict, fallback: str):
        object.__setattr__(self, "_by_server", by_server)
        object.__setattr__(self, "_fallback", fallback)

    def _target(self):
        return self._by_server.get(CURRENT.get()) or self._by_server[self._fallback]

    def __getattr__(self, name):
        return getattr(self._target(), name)

    def __setattr__(self, name, value):          # tests replace methods on the backend: apply it to the current one
        setattr(self._target(), name, value)

    def __delattr__(self, name):
        delattr(self._target(), name)

    def each(self):
        return list(self._by_server.values())
