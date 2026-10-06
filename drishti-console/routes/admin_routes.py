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

"""User administration: the users page, the audit page, and same-origin JSON actions. The server enforces
the admin role on every call; the console also hides these pages from non-admins."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.csrf import BodyError, json_body, limited_body
from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)


def _forbidden(request: Request):
    return render(request, "admin/forbidden.html", status_code=403)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _status(request: Request) -> dict:
    try:
        return await request.app.state.backend.admin("GET", "/status", ident(request))
    except BackendError:
        return {}


@router.get("/users")
async def users(request: Request, q: str = ""):
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    try:
        rows = await backend.admin("GET", "/users", me, q=q)
        roles = sorted(await backend.admin("GET", "/roles", me))
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/users.html", users=rows, roles=roles, q=q, status=await _status(request))


@router.get("/roles")
async def roles(request: Request):
    """Every role and what it allows; administrators add their own (built-in roles come from configuration and packs)."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    try:
        rows = await backend.admin("GET", "/role-definitions", me)
        packs = await backend.packs(me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    kinds = sorted({k for p in packs for k in (p.get("kinds") or [])})
    return render(request, "admin/roles.html", roles=rows, kinds=kinds, packs=packs)


@router.post("/api/roles/{name}")
async def save_role(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.admin("PUT", f"/role-definitions/{quote(name)}", ident(request), body)
    except BackendError as e:
        return _problem(e)


@router.post("/api/roles/{name}/delete")
async def delete_role(request: Request, name: str):
    try:
        await request.app.state.backend.admin("DELETE", f"/role-definitions/{quote(name)}", ident(request))
    except BackendError as e:
        return _problem(e)
    return {"ok": True}


@router.get("/packs")
async def packs(request: Request):
    """Every pack on disk: loaded or not, switched on or off for everyone."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        rows = await request.app.state.backend.admin("GET", "/packs", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    try:                                        # the signed registry, when one is configured
        registry = await request.app.state.backend.admin("GET", "/registry", me)
    except BackendError as e:
        registry = {"configured": True, "error": e.detail, "packs": []}
    try:                                        # deployments and rollbacks, and the versions kept for a rollback
        history = await request.app.state.backend.admin("GET", "/packs/history", me, limit=30)
    except BackendError:
        history = {"history": [], "kept": {}}
    return render(request, "admin/packs.html", packs=rows, registry=registry, history=history,
                  deploy_max_mb=request.app.state.pack_upload_limit // 1048576)


@router.post("/api/packs/deploy")
async def deploy_upload(request: Request):
    """Sends a pack archive (the raw body) to the server to be verified and previewed; nothing changes until it is confirmed."""
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    kind = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if kind != "application/octet-stream":
        raise BodyError(415, "send the archive as application/octet-stream")
    data = await limited_body(request, request.app.state.pack_upload_limit, "the pack archive")
    if not data:
        raise BodyError(400, "choose an archive (.tar.gz or .zip)")
    headers = {k: v for k, v in ((h, request.headers.get(h)) for h in ("X-Drishti-Filename", "X-Drishti-Sha256", "X-Drishti-Signature", "X-Drishti-Publisher")) if v}
    try:
        return await request.app.state.backend.admin_upload("/packs/deploy", me, data, headers)
    except BackendError as e:
        return _problem(e)


@router.post("/api/packs/deploy/{upload_id}")
async def deploy_confirm(request: Request, upload_id: str):
    """Deploys a verified upload; the server restarts in place to read it (and puts the old files back if it cannot start)."""
    body = await json_body(request)
    try:
        out = await request.app.state.backend.admin("POST", f"/packs/deploy/{quote(upload_id)}", ident(request),
                                                     timeout=60.0, acceptBreaking="true" if body.get("acceptBreaking") else "false")
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.delete("/api/packs/deploy/{upload_id}")
async def deploy_discard(request: Request, upload_id: str):
    try:
        return await request.app.state.backend.admin("DELETE", f"/packs/deploy/{quote(upload_id)}", ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/api/packs/history")
async def deploy_history(request: Request, pack: str = ""):
    try:
        return await request.app.state.backend.admin("GET", "/packs/history", ident(request), limit=100, **({"pack": pack} if pack else {}))
    except BackendError as e:
        return _problem(e)


@router.post("/api/packs/{name}/rollback")
async def deploy_rollback(request: Request, name: str):
    """Goes back to a kept version of a pack (``{version}``: one of the kept versions, or ``shipped``; none: the newest kept)."""
    body = await json_body(request)
    params = {"version": str(body["version"])} if body.get("version") else {}
    try:
        out = await request.app.state.backend.admin("POST", f"/packs/{quote(name)}/rollback", ident(request), timeout=60.0, **params)
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


LOAD_STATES = {"on-time": "ok", "landed-late": "warn", "pending": "", "late": "bad", "missing": "bad", "failed": "bad"}


@router.get("/loads")
async def all_loads(request: Request):
    """Admin → Data loads: every loaded pack at a glance, with today's expectations (on time, late, missing) and its latest loads;
    each pack links to its own Data loads page."""
    import asyncio
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    try:
        rows = await backend.admin("GET", "/packs", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    packs = [p for p in (rows if isinstance(rows, list) else rows.get("packs", [])) if p.get("loaded")]

    async def one(p):
        name = p.get("name")
        try:
            history, expectations = await asyncio.gather(backend.admin("GET", f"/loads/{quote(name)}", me, limit=5),
                                                          backend.admin("GET", f"/loads/{quote(name)}/expectations", me))
            return {"name": name, "title": p.get("title") or name, "loads": history.get("loads", []),
                    "expectations": expectations.get("expectations", []), "error": None}
        except BackendError as e:
            return {"name": name, "title": p.get("title") or name, "loads": [], "expectations": [], "error": str(e)}
    summary = await asyncio.gather(*(one(p) for p in packs))
    attention = sum(1 for s in summary for x in s["expectations"] if x.get("state") in ("late", "missing", "failed"))
    return render(request, "admin/loads_all.html", packs=summary, attention=attention, states=LOAD_STATES, status=await _status(request))


@router.get("/packs/{name}/loads")
async def pack_loads(request: Request, name: str, kind: str = "", status: str = "", date: str = ""):
    """Admin → Packs → Data loads: the pack's load history (filters: kind, status, date), what it expects and today's state, and the
    expectation settings. Everything is read from the server; the page works without scripts (filters are a plain GET form)."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    params = {k: v for k, v in (("kind", kind), ("status", status), ("date", date)) if v}
    try:
        history = await backend.admin("GET", f"/loads/{quote(name)}", me, limit=200, **params)
        expectations = await backend.admin("GET", f"/loads/{quote(name)}/expectations", me)
        config = await backend.admin("GET", f"/loads/{quote(name)}/config", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    return render(request, "admin/loads.html", pack=name, loads=history.get("loads", []), kinds=history.get("kinds", []), expectations=expectations,
                  config=config, states=LOAD_STATES, f_kind=kind, f_status=status, f_date=date, status=await _status(request))


@router.put("/api/loads/{name}/config")
async def loads_config_save(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.admin("PUT", f"/loads/{quote(name)}/config", ident(request), body)
    except BackendError as e:
        return _problem(e)


@router.delete("/api/loads/{name}/config")
async def loads_config_reset(request: Request, name: str):
    try:
        return await request.app.state.backend.admin("DELETE", f"/loads/{quote(name)}/config", ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/api/packs/{name}/datasource")
async def datasource_get(request: Request, name: str):
    try:
        return await request.app.state.backend.admin("GET", f"/packs/{quote(name)}/datasource", ident(request))
    except BackendError as e:
        return _problem(e)


@router.put("/api/packs/{name}/datasource")
async def datasource_save(request: Request, name: str):
    """Saves the administrator's override of the pack's data source and applies it (restart in place, undone if it cannot start)."""
    body = await json_body(request)
    try:
        out = await request.app.state.backend.admin("PUT", f"/packs/{quote(name)}/datasource", ident(request), {"connectors": body.get("connectors") or {}}, timeout=60.0)
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.delete("/api/packs/{name}/datasource")
async def datasource_reset(request: Request, name: str, connector: str = ""):
    try:
        out = await request.app.state.backend.admin("DELETE", f"/packs/{quote(name)}/datasource", ident(request), timeout=60.0, **({"connector": connector} if connector else {}))
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.post("/api/packs/{name}/datasource/test")
async def datasource_test(request: Request, name: str):
    """Tries the settings in force, or an edit not yet saved: dates and row counts per kind. Changes nothing."""
    body = await json_body(request)
    try:
        return await request.app.state.backend.admin("POST", f"/packs/{quote(name)}/datasource/test", ident(request),
                                                      {"connector": body.get("connector") or None, "connectors": body.get("connectors")}, timeout=90.0)
    except BackendError as e:
        return _problem(e)


@router.post("/api/packs/{name}")
async def switch_pack(request: Request, name: str):
    body = await json_body(request)
    try:
        out = await request.app.state.backend.admin("PUT", f"/packs/{quote(name)}", ident(request), {"enabled": bool(body.get("enabled"))})
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()                # every user's pack switcher changes
    return out


@router.post("/api/registry/{name}/{version}/install")
async def install_pack(request: Request, name: str, version: str):
    """Installs a pack version from the signed registry (the server verifies it), then loads or reloads it."""
    try:
        out = await request.app.state.backend.admin("POST", f"/registry/{quote(name)}/{quote(version)}/install", ident(request))
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.post("/api/registry/{name}/rollback")
async def rollback_pack(request: Request, name: str):
    try:
        out = await request.app.state.backend.admin("POST", f"/registry/{quote(name)}/rollback", ident(request))
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.post("/api/packs/{name}/{action}")
async def load_pack(request: Request, name: str, action: str):
    """Loads or unloads a pack: the server checks it, records it, and restarts in place."""
    if action not in ("load", "unload"):
        return JSONResponse({"code": "DRS-5001", "detail": "unknown action"}, status_code=400)
    try:
        out = await request.app.state.backend.admin("POST", f"/packs/{quote(name)}/{action}", ident(request))
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.get("/access")
async def access(request: Request, user: str = "", action: str = "", kind: str = "", id: str = "", to: str = "", limit: int = 200):
    """Who looked at what: views, raw documents, history, searches and CSV exports, newest first."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    f = {"user": user, "action": action, "kind": kind, "id": id, "from": request.query_params.get("from", ""), "to": to,
         "limit": max(1, min(limit, 5000))}
    params = {k: v for k, v in f.items() if v not in ("", None)}
    error, rows, stats = None, [], {}
    try:
        rows = await request.app.state.backend.admin("GET", "/access", me, **params)
        stats = await request.app.state.backend.admin("GET", "/access/stats", me)
    except BackendError as e:
        error = e
    return render(request, "admin/access.html", rows=rows, f=f, stats=stats, error=error)


@router.get("/tokens")
async def tokens(request: Request):
    """Every personal API token (never a secret), with revoke."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        rows = await request.app.state.backend.admin("GET", "/tokens", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/tokens.html", tokens=rows)


@router.post("/api/tokens/{id_}/revoke")
async def revoke_any_token(request: Request, id_: str):
    try:
        await request.app.state.backend.admin("DELETE", f"/tokens/{quote(id_)}", ident(request))
    except BackendError as e:
        return _problem(e)
    return {"ok": True}


@router.get("/audit")
async def audit(request: Request, subject: str = "", limit: int = 200):
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        events = await request.app.state.backend.admin("GET", "/audit", me, limit=limit, subject=subject)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/audit.html", events=events, subject=subject)


@router.get("/health")
async def health(request: Request, partial: int = 0):
    """Connector and pack health (admins): the page, or only its body for the page's own refresh."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        data = await request.app.state.backend.admin("GET", "/health", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/_health_body.html" if partial else "admin/health.html", h=data)


@router.get("/caches")
async def caches(request: Request):
    """Every cache (the engine's and each connector's), with a purge for any of them or all."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        rows = await request.app.state.backend.admin("GET", "/caches", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/caches.html", caches=rows)


@router.post("/api/caches/{name}/purge")
async def purge(request: Request, name: str):
    try:
        return await request.app.state.backend.admin("POST", f"/caches/{quote(name)}/purge", ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/users")
async def create(request: Request):
    body = await json_body(request)
    try:
        return JSONResponse(await request.app.state.backend.admin("POST", "/users", ident(request), body), status_code=201)
    except BackendError as e:
        return _problem(e)


@router.post("/api/users/{username}/{action}")
async def act(request: Request, username: str, action: str):
    """``update`` (profile), ``enabled``, ``password`` (reset) or ``delete``. The user's sessions on this console are
    re-checked at their next request (other consoles within ``auth.recheck_seconds``)."""
    body = await json_body(request)
    me, backend, u = ident(request), request.app.state.backend, quote(username)
    try:
        if action == "update":
            return await backend.admin("PUT", f"/users/{u}", me, body)
        if action == "enabled":
            return await backend.admin("POST", f"/users/{u}/enabled", me, {"enabled": bool(body.get("enabled"))})
        if action == "password":
            return await backend.admin("POST", f"/users/{u}/password", me, {"password": body.get("password", "")})
        if action == "delete":
            await backend.admin("DELETE", f"/users/{u}", me)
            return {"ok": True}
    except BackendError as e:
        return _problem(e)
    finally:
        request.app.state.auth.forget(user=username)
    return JSONResponse({"code": "DRS-5001", "detail": "unknown action"}, status_code=400)
