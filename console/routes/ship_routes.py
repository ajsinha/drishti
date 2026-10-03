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

"""Build workbench, step 8 (docs/architecture/BUILD_WORKBENCH.md): shipping a Design. Propose with evidence, pack fragment export and
import, development file binding and read-only share links. Thin: the server decides (rights, limits, what travels); this forwards with
the signed-in user's identity and draws the answers. A share link shows the Sutra, the operations and the sample NAMES; the recipient
previews it against their own data or a stored entity they may read, never the owner's samples."""
from __future__ import annotations

import io
import json
import logging
import zipfile

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(prefix="/build", include_in_schema=False)
LOG = logging.getLogger("drishti.console.ship")

MAX_FOLDER_FILES = 1000
FOLDER_SUFFIXES = (".yaml", ".yml", ".json", ".md")


def _error(e: BackendError, **more) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail, **more}, status_code=e.status)


async def _body(request: Request) -> dict:
    body = await json_body(request)
    if not isinstance(body, dict):
        raise BodyError(400, "send a JSON object")
    return body


# ---- propose with evidence --------------------------------------------------------------------------------------------------------

@router.post("/designs/{id_}/propose")
async def propose(request: Request, id_: str):
    """Submit for review from the workbench: the server attaches the check matrix, the sample names, the notes and the design's identity.
    Body ``{note?}``. With review off the Sutra is saved and the design is live at once."""
    body = await _body(request) if int(request.headers.get("content-length") or 0) else {}
    try:
        return await request.app.state.backend.designs("POST", f"/{id_}/propose", ident(request), {"note": str(body.get("note", ""))[:300]})
    except BackendError as e:
        return _error(e, problems=getattr(e, "problems", []))


# ---- pack fragments -----------------------------------------------------------------------------------------------------------------

@router.get("/designs/{id_}/export")
async def export_fragment(request: Request, id_: str):
    """The design as a pack fragment (zip): pack.yaml stub, the Sutra, tests with expect.yaml, three samples, a README."""
    try:
        data = await request.app.state.backend.designs_raw("GET", f"/{id_}/export", ident(request))
    except BackendError as e:
        return _error(e)
    return Response(data, media_type="application/zip", headers={"Content-Disposition": f'attachment; filename="fragment-{id_}.zip"', "Cache-Control": "no-store"})


def folder_zip(files: list) -> bytes:
    """A zip of ``[{path, text}]`` (a folder the browser read), refusing odd paths. Only text kinds of a pack are kept."""
    if len(files) > MAX_FOLDER_FILES:
        raise BodyError(413, f"the folder holds more than {MAX_FOLDER_FILES} files")
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            if not isinstance(f, dict) or not isinstance(f.get("path"), str) or not isinstance(f.get("text"), str):
                raise BodyError(400, "files are [{path, text}]")
            path = f["path"].replace("\\", "/").lstrip("/")
            if ".." in path.split("/") or not path.lower().endswith(FOLDER_SUFFIXES):
                continue
            z.writestr(path, f["text"])
    return out.getvalue()


@router.post("/import")
async def import_fragment(request: Request):
    """Import a pack folder or zip as Designs, one per Sutra, with the samples found beside it. Send a zip (``application/zip``)
    or a folder as JSON ``{files: [{path, text}]}``. Answers the designs made and what was skipped."""
    kind = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if kind == "application/zip":
        data = await request.body()
    elif kind == "application/json":
        body = await _body(request)
        files = body.get("files")
        if not isinstance(files, list):
            raise BodyError(400, "send {files: [{path, text}]} or a zip")
        data = folder_zip(files)
    else:
        raise BodyError(415, "send a zip (application/zip) or a folder as JSON")
    try:
        got = await request.app.state.backend.designs_raw("POST", "/import", ident(request), data, "application/zip")
    except BackendError as e:
        return _error(e)
    return JSONResponse(json.loads(got), status_code=201)


# ---- file binding (development) -------------------------------------------------------------------------------------------------------

@router.get("/binding")
async def binding(request: Request):
    try:
        return await request.app.state.backend.designs("GET", "/binding", ident(request))
    except BackendError as e:
        return _error(e)


@router.post("/designs/{id_}/bind")
async def bind(request: Request, id_: str):
    """Body ``{file}``: a path under a Sutra directory. An existing file's text becomes the Sutra (undo brings the old one back)."""
    body = await _body(request)
    try:
        return await request.app.state.backend.designs("POST", f"/{id_}/bind", ident(request), {"file": str(body.get("file", ""))[:300]})
    except BackendError as e:
        return _error(e)


@router.delete("/designs/{id_}/bind")
async def unbind(request: Request, id_: str):
    try:
        return await request.app.state.backend.designs("DELETE", f"/{id_}/bind", ident(request))
    except BackendError as e:
        return _error(e)


@router.post("/designs/{id_}/save-file")
async def save_file(request: Request, id_: str):
    try:
        return await request.app.state.backend.designs("POST", f"/{id_}/save-file", ident(request))
    except BackendError as e:
        return _error(e, problems=getattr(e, "problems", []))


@router.get("/designs/{id_}/sync")
async def sync(request: Request, id_: str):
    """Reads the bound file: an edit made in an IDE becomes a step of the design. ``changed`` says so (the workbench then reloads)."""
    try:
        got = await request.app.state.backend.designs("GET", f"/{id_}/sync", ident(request))
    except BackendError as e:
        return _error(e)
    return {k: got.get(k) for k in ("changed", "missing", "rev", "status", "boundFile")}


# ---- read-only sharing ------------------------------------------------------------------------------------------------------------------

@router.post("/designs/{id_}/share")
async def share(request: Request, id_: str):
    """Makes (or renews) the read-only link; the token is shown once. Sutra, operations and sample names only; revocable."""
    try:
        got = await request.app.state.backend.designs("POST", f"/{id_}/share", ident(request))
    except BackendError as e:
        return _error(e)
    return {"token": got["token"], "path": got["path"], "url": str(request.base_url).rstrip("/") + got["path"]}


@router.delete("/designs/{id_}/share")
async def unshare(request: Request, id_: str):
    try:
        return await request.app.state.backend.designs("DELETE", f"/{id_}/share", ident(request))
    except BackendError as e:
        return _error(e)


async def shared_page(request: Request, id_: str, token: str):
    """``/build/d/{id}?share=token``: what a link holder sees. Never the owner's samples; the preview runs on data of the viewer's own."""
    try:
        got = await request.app.state.backend.designs("GET", f"/shared/{id_}", ident(request), token=token)
    except BackendError as e:
        return render(request, "build/shared.html", status_code=e.status, shared=None, token="", error=e, screen="build")
    return render(request, "build/shared.html", shared=got, token=token, error=None, screen="build")


@router.post("/shared/{id_}/preview")
async def shared_preview(request: Request, id_: str):
    """Body ``{token, document}`` or ``{token, kind, id}``: the shared Sutra against the viewer's own JSON or a stored entity the
    viewer may open (the server checks the kind rights and masks as for any preview)."""
    body = await _body(request)
    backend, me = request.app.state.backend, ident(request)
    try:
        got = await backend.designs("GET", f"/shared/{id_}", me, token=str(body.get("token", "")))
        kind = str(body.get("kind") or got.get("kind") or "sample")
        if body.get("document") is not None:
            vm = await backend.preview(got["sutra"], kind, "SAMPLE", me, document=body["document"])
        else:
            vm = await backend.preview(got["sutra"], kind, str(body.get("id", "")), me)
    except BackendError as e:
        return _error(e)
    html = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm) if vm else ""
    return {"previewHtml": html}
