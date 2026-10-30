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

import hashlib
import json
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
        saved = await request.app.state.backend.admin("PUT", f"/role-definitions/{quote(name)}", ident(request), body)
    except BackendError as e:
        return _problem(e)
    _role_changed(request)
    return saved


@router.post("/api/roles/{name}/delete")
async def delete_role(request: Request, name: str):
    try:
        await request.app.state.backend.admin("DELETE", f"/role-definitions/{quote(name)}", ident(request))
    except BackendError as e:
        return _problem(e)
    _role_changed(request)
    return {"ok": True}


def _role_changed(request: Request) -> None:
    """A role's definition changed (its BI powers, kinds, raw, layout ...): what every holder may do changes, on every worker at once (QA3B-06).

    The shared stamp (core/user_stamps.py) makes every per-user copy on every worker older than the change: BI powers, packs, settings, layouts and
    the session check are read again at their next use."""
    bi_me = getattr(request.app.state, "bi_me", None)
    if bi_me is not None:
        bi_me.forget_all()
    else:
        stamps = getattr(request.app.state, "user_stamps", None)
        if stamps is not None:
            from core.user_stamps import EVERYONE

            stamps.touch(EVERYONE)


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
    try:                                        # who sees the sample packs
        samples = await request.app.state.backend.admin("GET", "/packs/samples", me)
    except BackendError:
        samples = {"mode": "visible", "configured": "visible", "overridden": False, "samples": []}
    return render(request, "admin/packs.html", packs=rows, registry=registry, history=history, samples=samples,
                  deploy_max_mb=request.app.state.pack_upload_limit // 1048576)


@router.get("/packs/{name}")
async def pack_detail(request: Request, name: str):
    """One pack's page: its summary and state, what it extends and who needs it, its kinds (with an example record to open and the form),
    its Sutras, the connectors it names, its data loads, and its deploy history. Pieces the server cannot give (a pack that is not loaded
    has no kinds yet) are left out; only the pack itself being unknown is an error."""
    import asyncio
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    try:
        rows = await backend.admin("GET", "/packs", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    row = next((p for p in rows if p.get("name") == name), None)
    if row is None:
        return render(request, "admin/forbidden.html", status_code=404,
                      error=BackendError(404, "DRS-1001", f"no pack named '{name}' is on disk or loaded"))
    q = quote(name)

    async def best(coro, fallback):
        try:
            return await coro
        except BackendError:
            return fallback
    loaded = bool(row.get("loaded"))
    detail, overview, sources, expectations, loads, history, coverage, readme = await asyncio.gather(
        best(backend.admin("GET", f"/packs/{q}/detail", me), {}) if loaded else asyncio.sleep(0, {}),
        best(backend.pack_overview(name, me), {}) if loaded else asyncio.sleep(0, {}),
        best(backend.admin("GET", f"/packs/{q}/datasource", me), {}) if loaded else asyncio.sleep(0, {}),
        best(backend.admin("GET", f"/loads/{q}/expectations", me), {}) if loaded else asyncio.sleep(0, {}),
        best(backend.admin("GET", f"/loads/{q}", me, limit=3), {}) if loaded else asyncio.sleep(0, {}),
        best(backend.admin("GET", "/packs/history", me, limit=30, pack=name), {"history": [], "kept": {}}),
        best(backend.pack_coverage(name, me), {}) if loaded else asyncio.sleep(0, {}),
        _pack_readme(backend, name, me) if loaded else asyncio.sleep(0, {}))
    examples = {k.get("kind"): k.get("example") for k in (overview or {}).get("kinds", [])}
    every = {p.get("name") for p in rows}
    return render(request, "admin/pack_detail.html", p=row, d=detail or {}, examples=examples, sources=sources or {}, expectations=expectations or {},
                  loads=(loads or {}).get("loads", []), history=history.get("history", []), kept=(history.get("kept") or {}).get(name, []),
                  known_packs=every, coverage=(coverage or {}).get("kinds", []), readme=await _readme_html(request, name, readme or {}),
                  status=await _status(request))


async def _pack_readme(backend, name: str, me) -> dict:
    """The pack's README from the server; none (an older server, a pack without one) leaves the section out, never the page."""
    try:
        r = await backend.pack_readme(name, me)
    except Exception:                            # noqa: BLE001
        return {}
    return r if isinstance(r, dict) else {}


async def _readme_html(request: Request, name: str, readme: dict) -> dict:
    """The pack's README for its page: its front matter and its markdown as HTML (links into the help centre where they can go)."""
    if not readme.get("markdown"):
        return {}
    from core import readmes
    from routes.common import library
    lib = await library(request)
    return {"meta": readme.get("meta") or {}, "html": readmes.render(readme["markdown"], readmes.source_of(request.app.state.docs_dir, "pack", name), lib)}


@router.get("/api/packs/{name}/readme")
async def pack_readme(request: Request, name: str):
    """A pack's README as the server gives it (front matter and markdown): what Admin -> Packs shows, for tools and AI."""
    try:
        return await request.app.state.backend.pack_readme(name, ident(request))
    except BackendError as e:
        return JSONResponse({"error": e.detail, "code": e.code}, status_code=e.status)


@router.post("/api/pack-samples")
async def set_pack_samples(request: Request):
    """Hide the sample packs from business users (``developers``), from everyone (``hidden``), or show them (``visible``); ``default``
    removes the saved setting so the server's ``drishti.packs.samples`` decides. Saved and audited by the server."""
    body = await json_body(request)
    try:
        out = await request.app.state.backend.admin("PUT", "/packs/samples", ident(request), {"mode": str(body.get("mode", ""))})
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()        # every user's packs, examples and menus change
    return out


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


@router.get("/api/packs/{name}/data")
async def data_targets(request: Request, name: str):
    """Load data: the kinds of the pack the browser can load into its File connector, and where each goes."""
    try:
        return await request.app.state.backend.admin("GET", f"/packs/{quote(name)}/data", ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/packs/{name}/data")
async def data_load(request: Request, name: str):
    """Load data: the browser sends the whole file (a JSON array or JSON Lines) as the body; it is streamed on to the server as it arrives
    (never held here), which writes it into the pack's File connector and announces the load. Administrators only."""
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    kind = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if kind != "application/octet-stream":
        raise BodyError(415, "send the file as application/octet-stream")
    cap = int(request.app.state.settings.get("builder.pack_load_max_mb", 2048)) * 1048576
    size = int(request.headers.get("content-length") or 0)
    if size > cap:
        raise BodyError(413, f"the file is over {cap // 1048576} MB (builder.pack_load_max_mb)")
    seen = 0

    async def chunks():
        nonlocal seen
        async for c in request.stream():
            seen += len(c)
            if seen > cap:
                raise BodyError(413, f"the file is over {cap // 1048576} MB (builder.pack_load_max_mb)")
            yield c
    q = request.query_params
    try:
        return await request.app.state.backend.admin_stream_upload(f"/packs/{quote(name)}/data", me, chunks(),
                                                                   {"kind": q.get("kind"), "date": q.get("date"), "file": q.get("file")})
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


@router.get("/api/packs/stamp")
async def packs_stamp(request: Request):
    """What the Packs page polls every few seconds: one short row per pack and a digest of them (the page offers a refresh when it moves)."""
    try:
        listing = await request.app.state.backend.admin("GET", "/packs", ident(request))
    except BackendError as e:
        return _problem(e)
    rows = [{"name": p.get("name"), "version": p.get("version"), "loaded": p.get("loaded"), "enabled": p.get("enabled"), "problem": p.get("problem"),
             "sutraProblems": len(p.get("sutraProblems") or []), "updatedAt": p.get("updatedAt")} for p in listing]
    digest = hashlib.sha1(json.dumps(rows, sort_keys=True, default=str).encode()).hexdigest()
    return {"stamp": digest, "rows": rows}


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


@router.post("/api/packs/{name}/loads")
async def announce_load(request: Request, name: str):
    """Announce a load from Admin -> Packs -> Data loads (2.9.7, O4): the same POST /api/v1/packs/{pack}/loads an ETL makes, as this
    administrator, with the console named as its source."""
    body = await json_body(request)
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    allowed = {k: body[k] for k in ("kind", "businessDate", "status", "rows", "batchId", "note") if body.get(k) not in (None, "")}
    allowed["source"] = "console"
    try:
        return await request.app.state.backend.announce_load(name, allowed, me)
    except BackendError as e:
        return _problem(e)


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
    """Loads or unloads a pack: the server checks it, records it, and applies it now (no restart)."""
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


HEALTH_WAIT_SECONDS = 30.0


@router.get("/health")
async def health(request: Request, partial: int = 0):
    """Connector and pack health (admins): the page, or only its body for the page's own refresh."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        # Health is the page an administrator opens when the server is struggling, and it measures (the connectors, the temp folders): it is
        # given 30 s, not the console's usual 5 s, so a loaded server's answer is shown instead of a 504
        data = await request.app.state.backend.admin("GET", "/health", me, timeout=HEALTH_WAIT_SECONDS)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    # Only a connector (a file under config/connectors) has a page: a source a plugin runs on its own (file, demo) does not.
    try:
        rows = await request.app.state.backend.admin("GET", "/connectors", me)
        items = rows.get("connectors", rows) if isinstance(rows, dict) else rows
        connectors = {c.get("name") for c in items or [] if isinstance(c, dict)}
    except BackendError:
        connectors = set()
    from core import external_lib, tmpdir

    return render(request, "admin/_health_body.html" if partial else "admin/health.html", h=data, connectors=connectors, console_tmp=tmpdir.info(), console_ext=external_lib.info(),
                  watch=request.app.state.backend_watch.snapshot(),
                  console_cluster=(request.app.state.cluster.snapshot() if getattr(request.app.state, "cluster", None) is not None else None))


@router.get("/caches")
async def caches(request: Request):
    """Every cache on the server (2.9.7: VaR, exposure, BI and pack access too), with the memory and disk totals; purge one, all, or a date."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        data = await request.app.state.backend.admin("GET", "/caches", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    view = data if isinstance(data, dict) else {"caches": data}        # a server before 2.9.7 answers a list of caches
    return render(request, "admin/caches.html", view=_cache_view(view), caches=view.get("caches") or [])


_CACHE_KEYS = ("name", "owner", "type", "where", "path", "sizeBytes", "limitBytes", "entries", "limitEntries", "ttlSeconds",
               "oldestAgeSeconds", "hits", "misses", "freshness", "dates", "lastPurge", "stats")
_MEMORY_KEYS = ("budgetBytes", "usedBytes", "heapMaxBytes", "heapUsedBytes", "squeezed")
_DISK_KEYS = ("freeBytes", "guardBytes", "state", "folders")


def _cache_view(view: dict) -> dict:
    """Every key the page reads present (a missing one is None), whatever the server's version."""
    rows = [{k: (r or {}).get(k) for k in _CACHE_KEYS} for r in (view.get("caches") or [])]
    mem = view.get("memory") or {}
    disk = view.get("disk") or {}
    return {"caches": rows, "memory": {k: mem.get(k) for k in _MEMORY_KEYS} if mem else {},
            "disk": {**{k: disk.get(k) for k in _DISK_KEYS}, "folders": [{"name": f.get("name"), "usedBytes": f.get("usedBytes"),
                                                                       "limitBytes": f.get("limitBytes")} for f in (disk.get("folders") or [])]}
            if disk else {}}


@router.get("/caches/{name}/dates")
async def cache_dates(request: Request, name: str):
    """One cache's business dates, as an HTML fragment for the page's drill-down (2.9.7)."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        dates = await request.app.state.backend.admin("GET", f"/caches/{quote(name)}/dates", me)
        keys = ("date", "businessDate", "state", "sizeBytes", "entries", "files", "partitions", "versions", "digest", "data", "builtAt", "lastUsedAt")
        rows = [{k: (d or {}).get(k) for k in keys} for d in (dates or [])]
        return render(request, "admin/_cache_dates.html", name=name, dates=rows, error=None)
    except BackendError as e:
        return render(request, "admin/_cache_dates.html", name=name, dates=[], error=f"{e.code}: {e.message}" if hasattr(e, "message") else str(e))


@router.post("/api/caches/{name}/purge")
async def purge(request: Request, name: str, date: str = "", scope: str = "server"):
    """Purges one cache, all, or one business date of it; ``scope=cluster`` on every member too (2.9.7)."""
    params = {}
    if date:
        params["date"] = date
    if scope == "cluster":
        params["scope"] = "cluster"
    try:
        return await request.app.state.backend.admin("POST", f"/caches/{quote(name)}/purge", ident(request), **params)
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
