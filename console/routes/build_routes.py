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

"""Build workbench, step 4 (docs/architecture/BUILD_WORKBENCH.md): My designs (``/build``), New (``/build/new``) and a Design's
page (``/build/d/{id}``). A Design is kept by the server, per user; this console holds nothing, so a restart loses nothing and two
consoles show the same Designs. The JSON routes below are thin: they check what the browser sent (limits, shapes), forward it to
``/api/v1/builder/designs`` with the signed-in user's identity, and render the server's preview to HTML. Counts only are logged."""
from __future__ import annotations

import json
import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core import builder, designs, sutra_diff
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render
from routes.review_routes import pending_count

router = APIRouter(prefix="/build", include_in_schema=False)
LOG = logging.getLogger("drishti.console.build")


def _refuse(status: int, detail: str, **more) -> JSONResponse:
    return JSONResponse({"code": "DRS-5005" if status == 413 else "DRS-5001", "detail": detail, **more}, status_code=status)


def _error(e: BackendError, **more) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail, **more}, status_code=e.status)


def _studio_kind(request: Request) -> str:
    return str(request.app.state.settings.get("builder.studio_kind", "sample") or "sample")


async def _body(request: Request) -> dict:
    body = await json_body(request, limit=request.app.state.builder_limits.max_total_bytes * 2 + 65536)
    if not isinstance(body, dict):
        raise BodyError(400, "send a JSON object")
    return body


# ---- pages --------------------------------------------------------------------------------------------------------------

@router.get("")
async def my_designs(request: Request):
    """My designs: the signed-in user's Designs, newest first (the server keeps them; nobody else's are ever listed)."""
    try:
        data = await request.app.state.backend.designs("GET", "", ident(request))
    except BackendError as e:
        return render(request, "build/designs.html", status_code=e.status, designs=[], limits={}, error=e, screen="build")
    designs = sorted(data.get("designs") or [], key=lambda d: d.get("updated", 0), reverse=True)
    return render(request, "build/designs.html", designs=designs, limits=data.get("limits") or {}, error=None, screen="build")


@router.get("/new")
async def new_page(request: Request):
    """New: bring data (files, a folder, a schema, stored entities, an example, an existing Sutra), then start."""
    me = ident(request)
    examples = request.app.state.examples
    cards = []
    for name in examples.names():
        ex = examples.get(name)
        if ex:
            summary = next((ln.strip() for ln in ex.readme.splitlines()[1:] if ln.strip() and not ln.startswith("#")), "")
            cards.append({"name": ex.name, "title": ex.title, "kind": ex.kind, "summary": summary[:220]})
    try:
        sutras = await request.app.state.backend.sutras(me)
    except BackendError:
        sutras = []
    return render(request, "build/new.html", limits=request.app.state.builder_limits.as_dict(), examples=cards, sutras=sutras,
                  studio_kind=_studio_kind(request), screen="build")


@router.get("/shape")
async def shape_moved(request: Request):
    """The shape extractor is the Data step of New now; the old address keeps working (query kept)."""
    q = request.url.query
    return RedirectResponse("/build/new" + (f"?{q}" if q else "") + "#files", status_code=302)


@router.get("/d/{id_}")
async def design_page(request: Request, id_: str, tab: str = "", sample: str = "", share: str = ""):
    """The workbench: a Design's data, its canvas over the real preview, the YAML, the inspector, problems and tests.
    With ``?share=token`` it is the read-only view a share link opens (Sutra, operations, sample names)."""
    if share:
        from routes import ship_routes
        return await ship_routes.shared_page(request, id_, share)
    try:
        design = await request.app.state.backend.designs("GET", f"/{id_}", ident(request))
    except BackendError as e:
        return render(request, "build/design.html", status_code=e.status, design=None, error=e, screen="build",
                      limits=request.app.state.builder_limits.as_dict())
    review, pending = await pending_count(request)
    settings = await request.app.state.backend.studio_settings(ident(request))
    try:
        binding = await request.app.state.backend.designs("GET", "/binding", ident(request))
    except BackendError:
        binding = {}
    init = {"fileBinding": bool(binding.get("enabled")), "boundFile": design.get("boundFile") or "", "shared": bool(design.get("shared")),
            "bindDir": binding.get("dir") or "", "baseMoved": design.get("baseMoved") or None, "id": design["id"], "samples": design.get("samples") or [], "opsAt": design.get("opsAt", 0), "opsCount": len(design.get("ops") or []) if "opsCount" not in design else design["opsCount"],
            "status": design.get("status", "draft"),
            "tab": tab if tab in ("design", "yaml", "summary") else "", "sample": sample, "canSave": bool(settings.get("save")), "review": review,
            "base": design.get("base") or ""}
    return render(request, "build/design.html", design=design, init=init, error=None, screen="build", limits=request.app.state.builder_limits.as_dict(),
                  examples=request.app.state.examples.names(), pending=pending)


# ---- designs -------------------------------------------------------------------------------------------------------------

@router.post("/designs")
async def create_design(request: Request):
    """Body ``{name, kind, sutra, base, notes, empty}``; ``empty`` starts from a minimal Sutra for the kind."""
    body = await _body(request)
    send = {k: body[k] for k in ("name", "kind", "sutra", "base", "notes") if isinstance(body.get(k), str)}
    send.setdefault("kind", _studio_kind(request))
    if body.get("empty") and not send.get("sutra"):
        send["sutra"] = designs.new_sutra(send["kind"])
    backend, me = request.app.state.backend, ident(request)
    try:
        made = await backend.designs("POST", "", me, send)
        if send.get("base"):                               # Studio's test entities of that Sutra become samples of the Design
            made["migrated"] = await designs.migrate_tests(backend, me, made["id"], send["base"])
        return JSONResponse(made, status_code=201)
    except BackendError as e:
        return _error(e)


@router.get("/designs/{id_}")
async def read_design(request: Request, id_: str):
    """The Design as the server keeps it (revision, Sutra, samples, log position): what the workbench reloads after a 409."""
    try:
        return await request.app.state.backend.designs("GET", f"/{id_}", ident(request))
    except BackendError as e:
        return _error(e)


@router.patch("/designs/{id_}")
async def update_design(request: Request, id_: str):
    body = await _body(request)
    send = {k: body[k] for k in ("name", "kind", "notes", "sutra") if isinstance(body.get(k), str)}
    try:
        return await request.app.state.backend.designs("PATCH", f"/{id_}", ident(request), send)
    except BackendError as e:
        return _error(e)


@router.delete("/designs")
async def delete_scratch(request: Request):
    """My designs, "Delete scratch designs": every unnamed design of the signed-in user (the server counts how many)."""
    try:
        return await request.app.state.backend.designs("DELETE", "", ident(request), scratch="true")
    except BackendError as e:
        return _error(e)


@router.delete("/designs/{id_}")
async def delete_design(request: Request, id_: str):
    try:
        await request.app.state.backend.designs("DELETE", f"/{id_}", ident(request))
    except BackendError as e:
        return _error(e)
    return Response(status_code=204)


@router.post("/designs/{id_}/duplicate")
async def duplicate_design(request: Request, id_: str):
    body = await _body(request) if int(request.headers.get("content-length") or 0) else {}
    try:
        return JSONResponse(await request.app.state.backend.designs("POST", f"/{id_}/duplicate", ident(request),
                                                                    {"name": body.get("name")} if isinstance(body.get("name"), str) else {}),
                            status_code=201)
    except BackendError as e:
        return _error(e)


@router.post("/examples/{name}/open")
async def open_example(request: Request, name: str):
    """Every example opens as a new Design that is a copy: its Sutra, its JSON as a sample, its README as notes. The files in
    docs/guides/examples are only read."""
    ex = request.app.state.examples.get(name)
    if ex is None:
        return JSONResponse({"code": "NOT_FOUND", "detail": "no such example"}, status_code=404)
    backend, me = request.app.state.backend, ident(request)
    try:
        design = await designs.copy_example(backend, ex, me, _studio_kind(request))
    except BackendError as e:
        return _error(e)
    return JSONResponse({"id": design["id"], "url": f"/build/d/{design['id']}"}, status_code=201)


# ---- samples ---------------------------------------------------------------------------------------------------------------

@router.post("/designs/{id_}/files")
async def add_files(request: Request, id_: str):
    """Body ``{files: [{name, text}]}`` (.json: one document; .jsonl: one per line). 413 DRS-5005 over a whole-request limit; a
    file that cannot be used is listed with its problems and the rest are kept."""
    limits = request.app.state.builder_limits
    body = await json_body(request, limit=limits.max_total_bytes * 2 + 65536)      # JSON escaping can double text; chunked bodies too
    try:
        samples, report = builder.read_files(body.get("files") if isinstance(body, dict) else None, limits)
    except builder.TooBig as e:
        return _refuse(413, str(e))
    except ValueError as e:
        raise BodyError(400, str(e))
    if not samples:
        return _refuse(422, "no usable sample: every file had a problem", files=report)
    try:
        design = await request.app.state.backend.designs("POST", f"/{id_}/samples", ident(request), {"samples": samples})
    except BackendError as e:
        return _error(e, files=report)
    LOG.info("design %s: %d file(s), %d sample(s), %d with problems", id_, len(report), len(samples), sum(1 for f in report if f["problems"]))
    return {**design, "files": report}


@router.post("/designs/{id_}/samples")
async def add_samples(request: Request, id_: str):
    """Store references (``{refs: {kind, ids | count}}``) or synthetic samples (``{schema, count}``): forwarded as sent."""
    body = await _body(request)
    send = {k: body[k] for k in ("refs", "schema", "count") if k in body}
    if not send:
        raise BodyError(400, "send 'refs' ({kind, ids|count}) or 'schema' (with 'count')")
    try:
        return await request.app.state.backend.designs("POST", f"/{id_}/samples", ident(request), send)
    except BackendError as e:
        return _error(e)


@router.delete("/designs/{id_}/samples")
async def remove_sample(request: Request, id_: str, name: str = ""):
    try:
        return await request.app.state.backend.designs("DELETE", f"/{id_}/samples", ident(request), name=name)
    except BackendError as e:
        return _error(e)


# ---- data, preview, auto-design ----------------------------------------------------------------------------------------------

@router.get("/designs/{id_}/shape")
async def shape(request: Request, id_: str):
    try:
        return await request.app.state.backend.designs("POST", f"/{id_}/shape", ident(request), {})
    except BackendError as e:
        return _error(e)


@router.get("/designs/{id_}/shape/download")
async def download(request: Request, id_: str, kind: str = "shape"):
    """``kind=shape``: shape.json (the schema with its x-drishti annotations); ``kind=schema``: plain JSON Schema."""
    if kind not in ("shape", "schema"):
        return _refuse(400, "kind must be 'shape' or 'schema'")
    try:
        result = await request.app.state.backend.designs("POST", f"/{id_}/shape", ident(request), {})
    except BackendError as e:
        return _error(e)
    schema = result.get("schema") or {}
    text = json.dumps(schema if kind == "shape" else builder.plain_schema(schema), indent=2, ensure_ascii=False)
    name = "shape.json" if kind == "shape" else "schema.json"
    return Response(text, media_type="application/json", headers={"Content-Disposition": f'attachment; filename="{name}"', "Cache-Control": "no-store"})


def _html(request: Request, vm) -> str:
    return request.app.state.templates.get_template("studio/_preview.html").render(vm=vm) if vm else ""


@router.get("/designs/{id_}/preview")
async def preview(request: Request, id_: str, sample: str = ""):
    """The Design's Sutra against one of its samples, as Studio draws it. A stored entity the user may not open is a 403 'no access'."""
    try:
        vm = await request.app.state.backend.designs("GET", f"/{id_}/preview", ident(request), sample=sample or None)
    except BackendError as e:
        return _error(e)
    return {"previewHtml": _html(request, vm), "sample": sample}


@router.post("/designs/{id_}/autodesign")
async def autodesign(request: Request, id_: str):
    """Drafts a Sutra from the Design's samples and keeps it as the Design's Sutra (a new revision)."""
    try:
        result = await request.app.state.backend.designs("POST", f"/{id_}/autodesign", ident(request), {})
    except BackendError as e:
        return _error(e)
    result["previewHtml"] = _html(request, result.pop("preview", None))
    LOG.info("design %s auto-designed: %d sample(s), %d panel(s) pruned", id_, result.get("samples", 0), len(result.get("pruned") or []))
    return result



# ---- the workbench: operations, undo, check, suggestions, a file ---------------------------------------------------------------

def _drawn(request: Request, result: dict) -> dict:
    """An ops/undo/redo answer with its preview view model replaced by the HTML Studio draws (the server sends the model)."""
    result["previewHtml"] = _html(request, result.pop("preview", None))
    return result


@router.post("/designs/{id_}/ops")
async def apply_ops(request: Request, id_: str):
    """Body ``{baseRev, ops, sample?}``: the server's ``/ops``. A stale ``baseRev`` is its ``409 DRS-5007`` (nothing changed)."""
    body = await _body(request)
    send = {k: body[k] for k in ("baseRev", "ops", "sample") if k in body}
    try:
        return _drawn(request, await request.app.state.backend.designs("POST", f"/{id_}/ops", ident(request), send))
    except BackendError as e:
        return _error(e)


async def _step(request: Request, id_: str, move: str):
    body = await _body(request)
    try:
        return _drawn(request, await request.app.state.backend.designs("POST", f"/{id_}/{move}", ident(request), {k: body[k] for k in ("baseRev",) if k in body}))
    except BackendError as e:
        return _error(e)


@router.post("/designs/{id_}/undo")
async def undo(request: Request, id_: str):
    """Body ``{baseRev?}``: takes the last step of the Design's log back."""
    return await _step(request, id_, "undo")


@router.post("/designs/{id_}/redo")
async def redo(request: Request, id_: str):
    """Body ``{baseRev?}``: brings back the step undo took."""
    return await _step(request, id_, "redo")


@router.post("/designs/{id_}/check")
async def check(request: Request, id_: str):
    """The panel by sample matrix of the Design (the server's one checker)."""
    try:
        return await request.app.state.backend.designs("POST", f"/{id_}/check", ident(request), {})
    except BackendError as e:
        return _error(e)


@router.post("/designs/{id_}/suggest")
async def suggest(request: Request, id_: str):
    """Body ``{path, at?}``: the panel kinds that suit a field of the Design's shape, best first, each with options and a reason."""
    body = await _body(request)
    if not isinstance(body.get("path"), str) or not body["path"].startswith("$"):
        raise BodyError(400, "send 'path', the field to suggest panels for, for example \"$.profile\"")
    me, backend = ident(request), request.app.state.backend
    try:
        shape = await backend.designs("POST", f"/{id_}/shape", me, {})
        send = {"shape": {"schema": shape.get("schema"), "roles": shape.get("roles")}, "path": body["path"]}
        if isinstance(body.get("at"), str):
            send["at"] = body["at"]
        return await backend.builder_suggest(send, me)
    except BackendError as e:
        return _error(e)


@router.post("/designs/{id_}/preview-file")
async def preview_file(request: Request, id_: str):
    """Body ``{document}``: the Design's Sutra against any JSON document, drawn at once and kept nowhere ("try it on the spot")."""
    body = await _body(request)
    if "document" not in body:
        raise BodyError(400, "send 'document', the JSON to preview the design against")
    me, backend = ident(request), request.app.state.backend
    try:
        design = await backend.designs("GET", f"/{id_}", me)
        if not (design.get("sutra") or "").strip():
            return _refuse(400, "this design has no Sutra yet")
        vm = await backend.preview(design["sutra"], design.get("kind") or _studio_kind(request), "SAMPLE", me, document=body["document"])
    except BackendError as e:
        return _error(e)
    return {"previewHtml": _html(request, vm)}


@router.get("/designs/{id_}/sample")
async def sample_document(request: Request, id_: str, name: str = ""):
    """One brought sample's JSON (the YAML editor completes fields from it); a stored entity keeps no document."""
    try:
        return await request.app.state.backend.designs("GET", f"/{id_}/samples/document", ident(request), name=name)
    except BackendError as e:
        return _error(e)


# ---- versions, diff, saving -----------------------------------------------------------------------------------------------------

@router.get("/designs/{id_}/versions")
async def versions(request: Request, id_: str):
    """The texts the Design's log passes through (``{versions: [{n, at, ops, current}]}``) and what it edits (``base``, if any)."""
    backend, me = request.app.state.backend, ident(request)
    try:
        listing = await backend.designs("GET", f"/{id_}/versions", me)
        design = await backend.designs("GET", f"/{id_}", me)
    except BackendError as e:
        return _error(e)
    return {**listing, "base": design.get("base") or "", "rev": design.get("rev")}


@router.get("/designs/{id_}/versions/{n}")
async def version_text(request: Request, id_: str, n: int):
    """One version's Sutra text: what the workbench turns into a ``text`` operation to restore it."""
    try:
        return await request.app.state.backend.designs("GET", f"/{id_}/versions/{n}", ident(request))
    except BackendError as e:
        return _error(e)


async def _text_against(request: Request, design: dict, against: str) -> tuple[str, str]:
    """(label, text) of what a Design is compared with: ``base`` (the registry Sutra it came from) or ``N`` (a version of its log)."""
    backend, me = request.app.state.backend, ident(request)
    if against == "base":
        name, _, version = (design.get("base") or "").partition("@")
        if not name or not version.isdigit():
            raise BodyError(400, "this design was not made from a Sutra of the registry, so it has no base to compare with")
        return f"the live Sutra {name} v{version}", await backend.sutra_source(name, int(version), me)
    if against.isdigit():
        got = await backend.designs("GET", f"/{design['id']}/versions/{against}", me)
        return f"version {against}", got.get("yaml") or ""
    raise BodyError(400, "against is 'base' or the number of a version")


@router.get("/designs/{id_}/diff")
async def diff(request: Request, id_: str, against: str = "base"):
    """The Design's Sutra against its base or an earlier version, as the review page draws a diff (moves in words, then the lines)."""
    backend = request.app.state.backend
    try:
        design = await backend.designs("GET", f"/{id_}", ident(request))
        label, old = await _text_against(request, design, against)
    except BackendError as e:
        return _error(e)
    d = sutra_diff.review(old, design.get("sutra") or "")
    html = request.app.state.templates.get_template("build/_diff.html").render(diff=d)
    return {"html": html, "label": label, "same": not any(k != "ctx" for k, _ in d["full"]), "moves": len(d["moves"])}
