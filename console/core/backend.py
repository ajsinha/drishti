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

import re
from typing import Any

from urllib.parse import quote

import httpx

from core import asof


def drs_message(code: str, detail: str, sep: str = ": ") -> str:
    """``code`` once in front of ``detail``: no prefix when the detail already starts with it, and no repeat of it
    inside (the server writes "DRS-2101 alert expression: DRS-2101 ..."). A shared helper, not string glue (UX-10)."""
    code, detail = str(code or "").strip(), str(detail or "").strip()
    if code:
        detail = re.sub(rf"{re.escape(code)}[:\s]*", "", detail).strip()
    return f"{code}{sep}{detail}" if code and detail else (code or detail)


def drs_advice(table: dict | None, code: str, kind: str = "", id_: str = "") -> str:
    """What to do about an error, for its DRS code, from the configured ``ui.error_advice`` (UX-11): the advice must
    fit the code, so a timeout is not told to check the identifier. {kind} and {id} fill in."""
    table = table or {}
    text = str(table.get(str(code or "").strip()) or table.get("default") or "")
    return text.replace("{kind}", str(kind)).replace("{id}", str(id_))


class BackendError(Exception):
    """The server answered with a problem (RFC 7807) or could not be reached."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(drs_message(code, detail, " "))
        self.status = status
        self.code = code
        self.detail = detail

    @property
    def page_status(self) -> int:
        """The status a console page answers with: the server's own below 500; a timeout (the server's 504 DRS-1004, or the
        console giving up waiting) is a 504 gateway timeout, any other server fault a 502."""
        if self.status < 500:
            return self.status
        return 504 if self.status == 504 else 502


def entity_path(kind: str, id_: str) -> tuple[str, dict]:
    """``kind/id`` for a server path, and the query it needs. An id a path cannot carry (one with ``/`` or ``\\``, or
    ``.``, ``..`` or ``~``) goes in the query as ``id``, with ``~`` in its place in the path, which every ``{kind}/{id}``
    endpoint of the server accepts (QA 2026-10-01, DATA-21); any other id is percent-encoded into the path."""
    k = quote(kind, safe="")
    if "/" in id_ or "\\" in id_ or id_ in (".", "..", "~"):
        return f"{k}/~", {"id": id_}
    return f"{k}/{quote(id_, safe='')}", {}


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
        except httpx.TimeoutException as e:
            raise BackendError(504, "DRS-1004", f"the server did not answer in time ({type(e).__name__}); try again, "
                                                 "or raise the server's drishti.sources.fetch-timeout") from e
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
        where, q = entity_path(kind, id_)
        return await self._get(f"/views/{where}", ident, **q)

    async def search(self, q: str, ident=None) -> dict:
        """Structured search: TRD where mtm > 1m order by mtm desc limit 50."""
        return await self._get("/search", ident, q=q)

    async def series(self, kind: str, id_: str, path: str, days: int, ident=None) -> dict:
        """One field over the last ``days`` business days (oldest first)."""
        where, q = entity_path(kind, id_)
        return await self._get(f"/history/{where}/series", ident, path=path, days=days, **q)

    async def search_compare(self, q: str, from_: str, to: str, ident=None) -> dict:
        """A search on two business dates, side by side, with the change of every number."""
        return await self._get("/search/compare", ident, q=q, **{"from": from_}, **({"to": to} if to else {}))

    async def history_diff(self, kind: str, id_: str, ident=None, **params: str) -> dict:
        """What changed between two dates (or two "known at" times); blank parameters take the server's defaults."""
        where, q = entity_path(kind, id_)
        return await self._get(f"/history/{where}/diff", ident, **{k: v for k, v in params.items() if v}, **q)

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
        where, q = entity_path(kind, id_)
        return await self._get(f"/entities/{where}/raw", ident, **q)

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
        where, q = entity_path(kind, id_)
        return await self._get(f"/studio/inferred/{where}", ident, name=name, **q)

    async def builder_shape(self, samples: list, ident=None) -> dict:
        """Screen Builder: the shape (schema, roles, report) of sample documents, ``[{name, document}]``."""
        return await self._send("POST", "/builder/shape", ident, json={"samples": samples})

    async def builder_design(self, samples: list, kind: str, ident=None) -> dict:
        """Screen Builder, step 3: a drafted Sutra for the samples (yaml, reasons, alternatives, pruned, preview of the first)."""
        return await self._send("POST", "/builder/design", ident, json={"samples": samples, "kind": kind})

    async def designs(self, method: str, path: str, ident, body=None, **params):
        """Build workbench Designs (``/builder/designs``): the server keeps them per user (samples, Sutra, notes) and answers
        404 DRS-5006 for anyone else's. ``path`` is below the collection (``""``, ``"/{id}"``, ``"/{id}/samples"``...)."""
        kw = {"params": {k: v for k, v in params.items() if v is not None}} if params else {}
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/builder/designs" + path, ident, **kw)

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
        where, q = entity_path(kind, id_)
        return await self._get(f"/impact/{where}", ident, **q)

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

    # -- personal layouts (layout mode) ------------------------------------------------------------------
    async def layouts(self, ident) -> dict:
        """Whether the user may customise layouts (and promote them), and every layout they keep."""
        return await self._get("/me/layouts", ident)

    async def save_layout(self, sutra: str, kind: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}", ident, json=body)

    async def reset_layout(self, sutra: str, kind: str, ident) -> None:
        await self._send("DELETE", f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}", ident)

    async def layout_promotion(self, sutra: str, kind: str, drop_hidden: bool, ident) -> dict:
        """The next version of the Sutra the user's layout would make, with the latest version's text."""
        return await self._get(f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}/promotion", ident,
                               dropHidden=str(bool(drop_hidden)).lower())

    async def promote_layout(self, sutra: str, kind: str, note: str, drop_hidden: bool, ident) -> dict:
        return await self._send("POST", f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}/promotion", ident,
                                json={"note": note, "dropHidden": bool(drop_hidden)})

    # -- the Pivot tab ----------------------------------------------------------------------------
    async def panel_records(self, kind: str, id_: str, panel: str, ident) -> dict:
        """Every row of a table or ladder whose Sutra says pivot:, as raw values of its fields."""
        where, q = entity_path(kind, id_)
        return await self._send("GET", f"/views/{where}/panels/{quote(panel, safe='')}/records", ident, params=q, timeout=30.0)

    async def search_pivot(self, kind: str, body: dict, ident, part: str = "") -> dict:
        """The server engine of a search's Pivot tab: the cube (part ""), a cell's entities ("drill") or a field's values."""
        # the first read of a business day loads its columns (the server waits up to drishti.pivot.budget, 20 s)
        return await self._send("POST", f"/search/pivot/{quote(kind, safe='')}" + (f"/{part}" if part else ""), ident, json=body,
                                timeout=45.0)

    async def pivots(self, ident) -> dict:
        """The user's saved pivots, and whether they may promote one to a Sutra."""
        return await self._get("/me/pivots", ident)

    async def saved_pivot(self, method: str, scope: str, ident, body: dict | None = None) -> dict | None:
        """GET, PUT or DELETE a saved pivot: scope is ``panel/<sutra>/<panel>`` or ``search/<kind>`` (already quoted)."""
        return await self._send(method, f"/me/pivots/{scope}", ident, **({"json": body} if body is not None else {}))

    async def pivot_promotion(self, scope: str, ident, note: str | None = None) -> dict:
        """What promoting a panel's saved pivot would propose (note None), or the proposal itself."""
        if note is None:
            return await self._get(f"/me/pivots/{scope}/promotion", ident)
        return await self._send("POST", f"/me/pivots/{scope}/promotion", ident, json={"note": note})

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
        where, q = entity_path(kind, id_)
        return await self._get(f"/notes/{where}", ident, **q)

    async def add_note(self, kind: str, id_: str, body: str, path: str | None, ident) -> dict:
        where, q = entity_path(kind, id_)
        return await self._send("POST", f"/notes/{where}", ident, params=q, json={"body": body, "path": path})

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

    async def open_session(self, username: str, seconds: int, service) -> dict:
        """Opens a sign-in session on the server for a user it has just verified: ``{id, expiresAt, user}``."""
        return await self._send("POST", "/auth/sessions", service, json={"username": username, "seconds": seconds})

    async def session(self, session_id: str, service) -> dict:
        """Whether a session still stands, with its user as they are now (roles, password change due); 401 when it ended."""
        return await self._get(f"/auth/sessions/{quote(session_id, safe='')}", service)

    async def end_session(self, session_id: str, service) -> None:
        """Ends a session on the server (sign-out): its cookie no longer signs anyone in, on any console."""
        await self._send("DELETE", f"/auth/sessions/{quote(session_id, safe='')}", service)

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
        where, q = entity_path(kind, id_)
        async with self._client.stream("GET", f"/api/v1/views/{where}/stream", params=q, timeout=None, headers=headers) as r:
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
