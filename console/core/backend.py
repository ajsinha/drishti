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

"""Async client for the Drishti server REST API: one pooled HTTP client per console process."""
from __future__ import annotations

from typing import Any

import httpx


class BackendError(Exception):
    """The server answered with a problem (RFC 7807) or could not be reached."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(f"{code} {detail}")
        self.status = status
        self.code = code
        self.detail = detail


class BackendClient:
    """Thin, typed-by-convention wrapper over ``/api/v1``. Safe to share across requests."""

    def __init__(self, base_url: str, timeout: float = 5.0, pool: int = 64):
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"), timeout=timeout,
            limits=httpx.Limits(max_connections=pool, max_keepalive_connections=pool))

    async def _get(self, path: str, user: str | None = None, **params: Any) -> Any:
        return await self._send("GET", path, user, params=params)

    async def _send(self, method: str, path: str, user: str | None, **kw: Any) -> Any:
        headers = {"X-Drishti-User": user} if user else {}
        try:
            r = await self._client.request(method, "/api/v1" + path, headers=headers, **kw)
        except httpx.HTTPError as e:
            raise BackendError(503, "DRS-5003", f"backend unreachable: {e}") from e
        if r.status_code >= 400:
            try:
                body = r.json()
            except ValueError:
                body = {}
            raise BackendError(r.status_code, body.get("code", f"HTTP-{r.status_code}"), body.get("detail", r.text[:200]))
        return r.json()

    async def view(self, kind: str, id_: str, user: str) -> dict:
        return await self._get(f"/views/{kind}/{id_}", user)

    async def raw(self, kind: str, id_: str) -> dict:
        return await self._get(f"/entities/{kind}/{id_}/raw")

    async def suggest(self, q: str, user: str, limit: int = 10) -> list:
        return await self._get("/command/suggest", user, q=q, limit=limit)

    async def command(self, text: str) -> dict:
        return await self._send("POST", "/command", None, json={"text": text})

    async def sources(self) -> dict:
        return await self._get("/sources")

    async def sutras(self) -> list:
        return await self._get("/sutras")

    async def stream(self, kind: str, id_: str):
        """Yields ``(event, data)`` pairs from the server's SSE stream for a view, until it ends."""
        async with self._client.stream("GET", f"/api/v1/views/{kind}/{id_}/stream", timeout=None,
                                       headers={"Accept": "text/event-stream"}) as r:
            if r.status_code >= 400:
                raise BackendError(r.status_code, "DRS-5003", "stream refused")
            event, data = None, []
            async for line in r.aiter_lines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:])
                elif line == "" and event:
                    yield event, "\n".join(data)
                    event, data = None, []

    async def aclose(self) -> None:
        await self._client.aclose()
