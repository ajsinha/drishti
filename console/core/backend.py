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

from core import asof


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

    async def _get(self, url: str, ident=None, **params: Any) -> Any:
        return await self._send("GET", url, ident, params=params)

    async def _send(self, method: str, path: str, ident, **kw: Any) -> Any:
        headers = dict(ident.headers()) if ident is not None else {}
        headers.update(asof.headers())
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

    async def search(self, q: str, ident=None) -> dict:
        """Structured search: TRD where mtm > 1m order by mtm desc limit 50."""
        return await self._get("/search", ident, q=q)

    async def series(self, kind: str, id_: str, path: str, days: int, ident=None) -> dict:
        """One field over the last ``days`` business days (oldest first)."""
        return await self._get(f"/history/{quote(kind)}/{quote(id_)}/series", ident, path=path, days=days)

    async def search_compare(self, q: str, from_: str, to: str, ident=None) -> dict:
        """A search on two business dates, side by side, with the change of every number."""
        return await self._get("/search/compare", ident, q=q, **{"from": from_}, **({"to": to} if to else {}))

    async def history_diff(self, kind: str, id_: str, ident=None, **params: str) -> dict:
        """What changed between two dates (or two "known at" times); blank parameters take the server's defaults."""
        return await self._get(f"/history/{kind}/{id_}/diff", ident, **{k: v for k, v in params.items() if v})

    async def calc_settings(self, ident) -> dict:
        """Whether the user may use Calc (a role with calc), and its limits."""
        return await self._get("/calc/settings", ident)

    async def calc_snippets(self, ident) -> list:
        return await self._get("/me/calc-snippets", ident)

    async def save_calc_snippet(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/calc-snippets/{quote(name, safe='')}", ident, json=body)

    async def delete_calc_snippet(self, name: str, ident) -> None:
        await self._send("DELETE", f"/me/calc-snippets/{quote(name, safe='')}", ident)

    async def columns(self, kind: str, paths: str, limit: int | None, ident=None) -> dict:
        """Whole columns of a kind on the business date (Calc's drishti.columns())."""
        params = {"paths": paths} if paths else {}
        if limit:
            params["limit"] = limit
        # the first read of a business day loads its columns (the server waits up to drishti.calc.columns-budget, 20 s)
        return await self._send("GET", f"/search/columns/{quote(kind, safe='')}", ident, params=params, timeout=45.0)

    async def raw(self, kind: str, id_: str, ident=None) -> dict:
        return await self._get(f"/entities/{kind}/{id_}/raw", ident)

    async def suggest(self, q: str, ident, limit: int | None = None) -> list:
        return await self._get("/command/suggest", ident, q=q, **({"limit": limit} if limit else {}))

    async def command(self, text: str, ident=None) -> dict:
        return await self._send("POST", "/command", ident, json={"text": text})

    async def sources(self, ident=None) -> dict:
        return await self._get("/sources", ident)

    async def sutras(self, ident=None) -> list:
        return await self._get("/sutras", ident)

    async def sutra_source(self, name: str, version: int, ident=None) -> str:
        return await self._get(f"/sutras/{name}/{version}/source", ident)

    async def preview(self, yaml_text: str, kind: str, id_: str, ident=None, document=None) -> dict:
        body = {"yaml": yaml_text, "kind": kind, "id": id_}
        if document is not None:
            body["document"] = document
        return await self._send("POST", "/studio/preview", ident, json=body)

    async def inferred_from(self, kind: str, id_: str, name: str, document, ident=None) -> str:
        return await self._send("POST", "/studio/inferred", ident, json={"kind": kind, "id": id_, "name": name, "document": document})

    async def inferred(self, kind: str, id_: str, name: str, ident=None) -> str:
        return await self._get(f"/studio/inferred/{kind}/{id_}", ident, name=name)

    async def rachana_schema(self, ident=None) -> dict:
        """The Rachana JSON Schema, generated from the grammar with this server's kinds, formats and functions."""
        return await self._get("/rachana/schema", ident)

    async def studio_settings(self, ident=None) -> dict:
        return await self._get("/studio/settings", ident)

    async def save_sutra(self, yaml_text: str, ident=None, note: str = "") -> dict:
        """Saves a Sutra; with review on, the answer is {"proposal": {...}} and the Sutra is not live yet."""
        return await self._send("POST", "/sutras", ident, content=yaml_text.encode(), headers={"Content-Type": "text/yaml"},
                                params={"note": note} if note else None)

    async def proposals(self, ident=None, status: str = "", name: str = "") -> dict:
        return await self._get("/sutras/proposals", ident, **{k: v for k, v in {"status": status, "name": name}.items() if v})

    async def proposal(self, id_: str, ident=None) -> dict:
        return await self._get(f"/sutras/proposals/{id_}", ident)

    async def decide(self, id_: str, action: str, ident=None, comment: str = "") -> dict:
        return await self._send("POST", f"/sutras/proposals/{id_}/{action}", ident, json={"comment": comment})

    async def impact(self, kind: str, id_: str, ident=None) -> dict:
        return await self._get(f"/impact/{kind}/{quote(id_)}", ident)

    async def studio_tests(self, sutra: str, ident) -> list:
        return await self._get(f"/me/studio-tests/{quote(sutra)}", ident)

    async def set_studio_tests(self, sutra: str, entities: list, ident) -> list:
        return await self._send("PUT", f"/me/studio-tests/{quote(sutra)}", ident, json=entities)

    async def my_tokens(self, ident) -> list:
        return await self._get("/me/tokens", ident)

    async def create_token(self, name: str, days, ident) -> dict:
        return await self._send("POST", "/me/tokens", ident, json={"name": name, "days": days})

    async def revoke_token(self, id_: str, ident) -> None:
        return await self._send("DELETE", f"/me/tokens/{quote(id_)}", ident)

    async def command_history(self, ident) -> list:
        return await self._get("/command/history", ident)

    async def aliases(self, ident) -> dict:
        return await self._get("/command/aliases", ident)

    async def set_aliases(self, aliases: dict, ident) -> dict:
        return await self._send("PUT", "/command/aliases", ident, json=aliases)

    async def pack_overview(self, name: str, ident=None) -> dict:
        """A pack's kinds with their mnemonics, counts, an example and key columns (MKT <GO>)."""
        return await self._get(f"/packs/{quote(name)}/overview", ident)

    async def packs(self, ident=None) -> list:
        return await self._get("/packs", ident)

    async def choose_packs(self, active: list, ident) -> dict:
        return await self._send("PUT", "/me/packs", ident, json={"active": active})

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

    async def workspace_share(self, name: str, ident) -> dict | None:
        """Who this workspace is shared with, or None when it is not shared."""
        try:
            return await self._get(f"/me/workspaces/{quote(name)}/share", ident)
        except BackendError as e:
            if e.status == 404:
                return None
            raise

    async def share_workspace(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/workspaces/{quote(name)}/share", ident, json=body)

    async def unshare_workspace(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/workspaces/{quote(name)}/share", ident)

    async def shared_workspaces(self, ident) -> list:
        return await self._get("/workspaces/shared", ident)

    async def shared_workspace(self, owner: str, name: str, ident) -> dict:
        return await self._get(f"/workspaces/shared/{quote(owner)}/{quote(name)}", ident)

    async def phrase(self, text: str, ident=None) -> dict:
        """A phrase in plain words as a structured search, with how each part was read (it does not run it)."""
        return await self._get("/phrase", ident, text=text)

    # -- scheduled reports ------------------------------------------------------------------------
    async def reports(self, ident) -> list:
        return await self._get("/me/reports", ident)

    async def save_report(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/reports/{quote(name)}", ident, json=body)

    async def run_report(self, name: str, ident) -> dict:
        return await self._send("POST", f"/me/reports/{quote(name)}/run", ident)

    async def delete_report(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/reports/{quote(name)}", ident)

    # -- notes ------------------------------------------------------------------------------------
    async def notes(self, kind: str, id_: str, ident) -> list:
        return await self._get(f"/notes/{quote(kind)}/{quote(id_)}", ident)

    async def add_note(self, kind: str, id_: str, body: str, path: str | None, ident) -> dict:
        return await self._send("POST", f"/notes/{quote(kind)}/{quote(id_)}", ident, json={"body": body, "path": path})

    async def edit_note(self, note_id: int, body: str, ident) -> dict:
        return await self._send("PUT", f"/notes/{int(note_id)}", ident, json={"body": body})

    async def delete_note(self, note_id: int, ident) -> None:
        return await self._send("DELETE", f"/notes/{int(note_id)}", ident)

    # -- monitors and alerts ----------------------------------------------------------------------
    async def mine(self, method: str, path: str, ident, body=None, **params):
        """Calls under /me (monitors, alerts) for the signed-in user."""
        kw = {"params": params} if params else {}
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/me" + path, ident, **kw)

    async def business_date(self, ident=None) -> dict:
        """The server's business date: current, selected (rolled back to a business day), live, holidays."""
        return await self._get("/business-date", ident)

    async def sse(self, path: str, ident=None, opened: list | None = None):
        """Yields ``(event, data)`` from any server SSE endpoint under /api/v1. The open response is appended to
        ``opened`` when given, so a caller can close it from another task to end the stream."""
        headers = {"Accept": "text/event-stream", **(ident.headers() if ident is not None else {}), **asof.headers()}
        async with self._client.stream("GET", "/api/v1" + path, timeout=None, headers=headers) as r:
            if opened is not None:
                opened.append(r)
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

    # -- identity ---------------------------------------------------------------------------------
    async def login(self, username: str, password: str, service) -> dict:
        return await self._send("POST", "/auth/login", service, json={"username": username, "password": password})

    async def settings(self, ident) -> dict:
        return await self._get("/me/settings", ident)

    async def patch_settings(self, changes: dict, ident) -> dict:
        return await self._send("PATCH", "/me/settings", ident, json=changes)

    async def oidc_login(self, id_token: str, nonce: str, service) -> dict:
        """The server verifies a provider's ID token and signs the user in (single sign-on)."""
        return await self._send("POST", "/auth/oidc", service, json={"idToken": id_token, "nonce": nonce})

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

    async def stream(self, kind: str, id_: str, ident=None, opened: list | None = None):
        """Yields ``(event, data)`` pairs from the server's SSE stream for a view, until it ends (see ``sse`` for ``opened``)."""
        headers = {"Accept": "text/event-stream", **(ident.headers() if ident is not None else {}), **asof.headers()}
        async with self._client.stream("GET", f"/api/v1/views/{kind}/{id_}/stream", timeout=None, headers=headers) as r:
            if opened is not None:
                opened.append(r)
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
