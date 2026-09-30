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

from urllib.parse import quote

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

    async def _get(self, path: str, ident=None, **params: Any) -> Any:
        return await self._send("GET", path, ident, params=params)

    async def _send(self, method: str, path: str, ident, **kw: Any) -> Any:
        headers = dict(ident.headers()) if ident is not None else {}
        headers.update(kw.pop("headers", {}))
        try:
            r = await self._client.request(method, "/api/v1" + path, headers=headers, **kw)
        except httpx.HTTPError as e:
            raise BackendError(503, "DRS-5003", f"backend unreachable: {e}") from e
        if r.status_code >= 400:
            try:
                body = r.json()
            except ValueError:
                body = {}
            err = BackendError(r.status_code, body.get("code", f"HTTP-{r.status_code}"), body.get("detail", r.text[:200]))
            err.problems = body.get("problems", [])
            raise err
        if r.status_code == 204:
            return None
        return r.json() if "json" in r.headers.get("content-type", "") else r.text

    async def view(self, kind: str, id_: str, ident) -> dict:
        return await self._get(f"/views/{kind}/{id_}", ident)

    async def raw(self, kind: str, id_: str, ident=None) -> dict:
        return await self._get(f"/entities/{kind}/{id_}/raw", ident)

    async def suggest(self, q: str, ident, limit: int = 10) -> list:
        return await self._get("/command/suggest", ident, q=q, limit=limit)

    async def command(self, text: str, ident=None) -> dict:
        return await self._send("POST", "/command", ident, json={"text": text})

    async def sources(self, ident=None) -> dict:
        return await self._get("/sources", ident)

    async def sutras(self, ident=None) -> list:
        return await self._get("/sutras", ident)

    async def sutra_source(self, name: str, version: int, ident=None) -> str:
        return await self._get(f"/sutras/{name}/{version}/source", ident)

    async def preview(self, yaml_text: str, kind: str, id_: str, ident=None) -> dict:
        return await self._send("POST", "/studio/preview", ident, json={"yaml": yaml_text, "kind": kind, "id": id_})

    async def inferred(self, kind: str, id_: str, name: str, ident=None) -> str:
        return await self._get(f"/studio/inferred/{kind}/{id_}", ident, name=name)

    async def studio_settings(self, ident=None) -> dict:
        return await self._get("/studio/settings", ident)

    async def save_sutra(self, yaml_text: str, ident=None) -> dict:
        return await self._send("POST", "/sutras", ident, content=yaml_text.encode(), headers={"Content-Type": "text/yaml"})

    async def packs(self, ident=None) -> list:
        return await self._get("/packs", ident)

    async def about(self, ident=None) -> dict:
        return await self._get("/about", ident)

    # -- workspaces -------------------------------------------------------------------------------
    async def workspaces(self, ident) -> list:
        return await self._get("/me/workspaces", ident)

    async def workspace(self, name: str, ident) -> dict:
        return await self._get(f"/me/workspaces/{quote(name)}", ident)

    async def save_workspace(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/workspaces/{quote(name)}", ident, json=body)

    async def delete_workspace(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/workspaces/{quote(name)}", ident)

    # -- identity ---------------------------------------------------------------------------------
    async def login(self, username: str, password: str, service) -> dict:
        return await self._send("POST", "/auth/login", service, json={"username": username, "password": password})

    async def me(self, ident) -> dict:
        return await self._get("/auth/me", ident)

    async def change_password(self, current: str, new: str, ident) -> dict:
        return await self._send("POST", "/auth/password", ident, json={"current": current, "next": new})

    async def admin(self, method: str, path: str, ident, body: dict | None = None, **params):
        """Admin endpoints (``/admin/...``); the server enforces the admin role."""
        kw = {"params": params} if params else {}
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/admin" + path, ident, **kw)

    async def stream(self, kind: str, id_: str, ident=None):
        """Yields ``(event, data)`` pairs from the server's SSE stream for a view, until it ends."""
        headers = {"Accept": "text/event-stream", **(ident.headers() if ident is not None else {})}
        async with self._client.stream("GET", f"/api/v1/views/{kind}/{id_}/stream", timeout=None, headers=headers) as r:
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
