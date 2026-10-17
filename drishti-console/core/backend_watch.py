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

"""The console watches its server(s): start order does not matter, a restarted server is noticed.

A background task asks each server of the catalogue ``/public/about`` every ``backend.check_seconds`` (short timeout),
keeps ``reachable`` / ``since`` / ``lastError`` / ``version`` per server and logs each change once. The state backs
``GET /healthz/backend`` (which the browser's server-watch.js polls) and Admin → Health. Nothing here holds a secret.
"""
from __future__ import annotations

import asyncio
import logging
import time

import httpx

LOG = logging.getLogger("drishti.console.backend")


def _brief(e: Exception) -> str:
    """What went wrong, short: the error's class and its first line."""
    text = str(e).strip().splitlines()[0] if str(e).strip() else ""
    return (f"{type(e).__name__}: {text}" if text else type(e).__name__)[:160]


class BackendWatch:
    """Reachability of each server, kept fresh by :meth:`run`; :meth:`check` is one probe (tests replace :meth:`probe`)."""

    def __init__(self, servers, seconds: float = 5.0, timeout: float = 2.0):
        self._servers = servers
        self.seconds = float(seconds)
        self.timeout = float(timeout)
        self._state: dict[str, dict] = {}

    @property
    def enabled(self) -> bool:
        return self.seconds > 0

    async def probe(self, url: str) -> dict:
        """The server's public about (its version); raises when it cannot be reached or does not answer 2xx."""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            r = await client.get(f"{url}/public/about")
            r.raise_for_status()
            try:
                return r.json()
            except ValueError:
                return {}

    async def check(self, server) -> dict:
        """Probes one server and records the result; a change of state is logged once."""
        now = time.time()
        old = self._state.get(server.id)
        try:
            about = await self.probe(server.url)
            up, error, version = True, "", str(about.get("version") or "")
        except Exception as e:  # noqa: BLE001 - any failure means not reachable
            up, error, version = False, _brief(e), (old or {}).get("version", "")
        if old is None or old["reachable"] != up:
            since = now
            if old is not None and up:
                LOG.warning("server %s reachable again after %d s", server.url, round(now - old["since"]))
            elif not up:
                LOG.warning("server %s unreachable: %s", server.url, error)
        else:
            since = old["since"]
        self._state[server.id] = {"reachable": up, "since": since, "lastError": error, "version": version, "checked": now}
        return self._state[server.id]

    async def check_all(self) -> None:
        await asyncio.gather(*(self.check(s) for s in self._servers.all()))

    async def run(self) -> None:
        while True:
            try:
                await self.check_all()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - the watch itself never dies
                LOG.exception("backend watch failed")
            await asyncio.sleep(self.seconds)

    async def state(self, sid: str) -> dict:
        """One server's state; probed now when nothing has been checked yet (the watch not started, or its first pass pending)."""
        if sid not in self._state:
            server = self._servers.get(sid)
            if server is None:
                return {"reachable": False, "since": time.time(), "lastError": "unknown server", "version": ""}
            await self.check(server)
        return dict(self._state[sid])

    def snapshot(self) -> list[dict]:
        """Every server's last state, for Admin → Health."""
        out = []
        for s in self._servers.all():
            st = self._state.get(s.id)
            out.append({"id": s.id, "name": s.name, **(st or {"reachable": None, "since": None, "lastError": "", "version": ""})})
        return out
