"""Admin → Artifacts: every installed pack, connector file and shipped BI file in one list, what depends on each, and the safe ways out of
one (disable, uninstall to an archive, restore, delete for good), plus uploading a shipped BI file with a dry run first.

The server decides everything (the impact, the refusals, the audit); this module lists, and carries the administrator's requests as
same-origin JSON. A refusal because other things depend on the artifact is passed on with its ``impact`` so the page can show the list
and offer "disable instead"."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import BodyError, json_body, limited_body
from routes.common import ident, render

router = APIRouter(prefix="/admin/artifacts", include_in_schema=False)

# what an action may carry; anything else in the body is dropped, not passed on
_ACTION = ("kind", "subtype", "name", "cascade", "confirm")
_BI_TYPES = ("application/octet-stream", "text/plain", "application/x-yaml", "text/yaml")


def _problem(e: BackendError) -> JSONResponse:
    out = {"code": e.code, "detail": e.detail}
    if isinstance(getattr(e, "extra", None), dict) and e.extra.get("impact"):
        out["impact"] = e.extra["impact"]
    return JSONResponse(out, status_code=e.page_status)


async def _server(request: Request, method: str, path: str, body: dict | None = None, **params):
    return await request.app.state.backend.admin(method, "/artifacts" + path, ident(request), body, **params)


def _admin(request: Request):
    return ident(request).is_admin


@router.get("")
async def page(request: Request, tab: str = "installed"):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        listing = await _server(request, "GET", "")
        archived = await _server(request, "GET", "/uninstalled")
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    rows = listing.get("artifacts", [])
    counts = {k: sum(1 for r in rows if r["kind"] == k) for k in ("pack", "connector", "bi")}
    attention = sum(1 for r in rows if r.get("problems") or r.get("state") == "problem")
    return render(request, "admin/artifacts.html", artifacts=rows, counts=counts, attention=attention, archives=archived.get("archives", []),
                  uninstalled_dir=archived.get("directory", ""), keep_days=archived.get("keepDays", 0), tab="uninstalled" if tab == "uninstalled" else "installed",
                  status=status)


@router.get("/api/impact")
async def impact(request: Request, kind: str, name: str, subtype: str = ""):
    if not _admin(request):
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    try:
        return await _server(request, "GET", "/impact", kind=kind, subtype=subtype, name=name)
    except BackendError as e:
        return _problem(e)


@router.post("/api/uninstalled/{archive_id}/restore")
async def restore(request: Request, archive_id: str):
    body = await json_body(request)
    try:
        out = await _server(request, "POST", f"/uninstalled/{quote(archive_id, safe='')}/restore", {k: v for k, v in body.items() if k in ("replace", "confirm")})
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()
    return out


@router.delete("/api/uninstalled/{archive_id}")
async def purge(request: Request, archive_id: str, confirm: str = ""):
    try:
        return await _server(request, "DELETE", f"/uninstalled/{quote(archive_id, safe='')}", confirm=confirm)
    except BackendError as e:
        return _problem(e)


async def _bi_body(request: Request) -> bytes:
    kind = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if kind not in _BI_TYPES:
        raise BodyError(415, "send the file as application/octet-stream or text/plain")
    data = await limited_body(request, request.app.state.pack_upload_limit, "the BI file")
    if not data:
        raise BodyError(400, "choose a file (<name>.dataset.yaml, .report.yaml, .board.yaml, .notebook.yaml or .python.yaml)")
    return data


@router.post("/api/bi/check")
async def bi_check(request: Request, file: str):
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    data = await _bi_body(request)
    try:
        return await request.app.state.backend.admin_upload(f"/artifacts/bi/check?file={quote(file, safe='')}", me, data, {})
    except BackendError as e:
        return _problem(e)


@router.post("/api/bi")
async def bi_install(request: Request, file: str, replace: bool = False, confirm: str = ""):
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    data = await _bi_body(request)
    path = f"/artifacts/bi?file={quote(file, safe='')}&replace={'true' if replace else 'false'}&confirm={quote(confirm, safe='')}"
    try:
        return await request.app.state.backend.admin_upload(path, me, data, {})
    except BackendError as e:
        return _problem(e)


@router.post("/api/{action}")
async def act(request: Request, action: str):
    if action not in ("disable", "enable", "uninstall"):
        return JSONResponse({"code": "DRS-5001", "detail": "disable, enable or uninstall"}, status_code=404)
    body = await json_body(request)
    try:
        out = await _server(request, "POST", "/" + action, {k: v for k, v in body.items() if k in _ACTION})
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()           # a pack on or off changes every user's menus and examples
    return out
