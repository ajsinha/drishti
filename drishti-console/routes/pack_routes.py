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

"""Build -> New pack (``/build/pack/new``): schema files and sample documents in, a pack out (docs/guides/SCHEMA_TO_PACK.md).

The page reads the user's files in the browser and sends only schemas, a sample of the documents and statistics. These routes plan the pack
(``core/packwizard``, ``core/schemakit``), draft every Sutra with the server's auto-designer as a background job (progress, cancel), render
any Sutra on its samples, open one in the workbench, build the bundle (the format of ``pack bundle``) and keep the wizard's state as a Design
(a draft the user resumes). Deploying is Admin -> Packs -> Deploy archive: the bundle is handed to that page, which previews and confirms."""
from __future__ import annotations

import asyncio
import base64
import gzip
import json
import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core import packwizard as W
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, packs as user_packs, render

router = APIRouter(prefix="/build/pack", include_in_schema=False)
LOG = logging.getLogger("drishti.console.build.pack")
DRAFT_PREFIX = "New pack: "
DRAFT_MARK = "drishti-pack-draft/1\n"


def _problem(status: int, detail: str, code: str | None = None, **more) -> JSONResponse:
    return JSONResponse({"code": code or ("DRS-5005" if status == 413 else "DRS-5001"), "detail": detail, **more}, status_code=status)


def _wiz(request: Request) -> tuple[W.WizardLimits, W.Jobs]:
    st = request.app.state
    if not hasattr(st, "pack_jobs"):
        lim = W.WizardLimits.from_settings(st.settings)
        tools = st.settings.get("builder.tools_dir", "../tools")
        from pathlib import Path
        d = Path(str(tools))
        console_dir = Path(__file__).resolve().parent.parent
        st.pack_wizard_limits = lim
        st.pack_jobs = W.Jobs(lim, d if d.is_absolute() else (console_dir / d).resolve())
    return st.pack_wizard_limits, st.pack_jobs


async def _body(request: Request) -> dict:
    lim, _ = _wiz(request)
    mb = lim.max_docs * 60 + lim.max_files * lim.max_schema_kb * 1024 + 65536          # the documents are small samples, not whole files
    body = await json_body(request, limit=max(mb, 8 * 1048576))
    if not isinstance(body, dict):
        raise BodyError(400, "send a JSON object")
    return body


async def _may_author(request: Request) -> bool:
    try:
        s = await request.app.state.backend.studio_settings(ident(request))
    except BackendError:
        return False
    return bool(s.get("author", s.get("save")))


@router.get("/new")
async def page(request: Request):
    """The five steps: bring sources, kinds, pack, preview, output."""
    lim, _ = _wiz(request)
    me = ident(request)
    return render(request, "build/pack_new.html", limits=lim.as_dict(), can_author=await _may_author(request), is_admin=bool(me.is_admin),
                  screen="build", read_rows=lim.read_rows, read_mb=lim.read_mb)


# ---- plan ----------------------------------------------------------------------------------------------------------------------

@router.post("/api/plan")
async def plan(request: Request):
    """Body ``{schemas:[{name,text}], data:[{name,rows,bytes,docs}], kindOf, overrides, pack, samples, strip}`` -> the plan, per kind."""
    lim, _ = _wiz(request)
    body = await _body(request)
    try:
        return await W.make_plan(body, lim, request.app.state.backend, ident(request), request.app.state.packs)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)


# ---- jobs ----------------------------------------------------------------------------------------------------------------------

@router.post("/api/jobs")
async def start_job(request: Request):
    """Starts drafting every Sutra of the plan in the background; answers ``{id}``. Poll ``GET /api/jobs/{id}``."""
    lim, jobs = _wiz(request)
    body = await _body(request)
    me = ident(request)
    try:
        plan_obj, *_ = await W.plan_object(body, lim, request.app.state.backend, me, request.app.state.packs)
        if not W.NAME_RE.fullmatch(plan_obj.pack["name"]):
            return _problem(400, "the pack name is lower-case letters, digits and '-'")
        if not plan_obj.kinds:
            return _problem(400, "no kind could be read from these files")
        nokey = [k.kind for k in plan_obj.kinds if not k.key]
        if nokey:
            return _problem(400, "choose the key field of " + ", ".join(nokey) + " (step 2)")
        if plan_obj.sutra_count > lim.max_sutras:
            return _problem(413, f"{plan_obj.sutra_count} Sutras; at most {lim.max_sutras} per pack (builder.pack_max_sutras)")
        job = jobs.start(me.user, plan_obj, request.app.state.backend, me)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)
    LOG.info("new-pack job %s started: %d Sutra(s)", job.id, job.total)
    return JSONResponse({"id": job.id, "total": job.total}, status_code=202)


@router.get("/api/jobs/{job_id}")
async def job_status(request: Request, job_id: str):
    _, jobs = _wiz(request)
    try:
        return jobs.get(job_id, ident(request).user).view()
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)


@router.delete("/api/jobs/{job_id}")
async def job_cancel(request: Request, job_id: str):
    _, jobs = _wiz(request)
    try:
        job = jobs.get(job_id, ident(request).user)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)
    jobs.cancel(job)
    return job.view()


@router.get("/api/jobs/{job_id}/preview")
async def job_preview(request: Request, job_id: str, sutra: str, sample: str = ""):
    """One Sutra rendered on one of its samples (the first by default), as the workbench draws it."""
    _, jobs = _wiz(request)
    me = ident(request)
    try:
        job = jobs.get(job_id, me.user)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)
    item = next((i for i in job.items if i.name == sutra), None)
    res = job.results.get(sutra)
    if item is None or not res or not res.get("yaml"):
        return _problem(404, "no such Sutra in this job", "DRS-5006")
    s = next((x for x in item.samples if x.name == sample), item.samples[0])
    try:
        vm = await request.app.state.backend.preview(res["yaml"], item.kp.kind, "SAMPLE", me, document=s.doc)
    except BackendError as e:
        return _problem(e.status, e.detail, e.code, problems=getattr(e, "problems", []))
    html = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm) if vm else ""
    return {"previewHtml": html, "sample": s.name, "source": s.source}


@router.post("/api/jobs/{job_id}/open")
async def job_open(request: Request, job_id: str):
    """Opens one Sutra of the job in the workbench: a Design with the Sutra and its samples; answers ``{id, url}``."""
    _, jobs = _wiz(request)
    body = await _body(request)
    me = ident(request)
    try:
        job = jobs.get(job_id, me.user)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)
    item = next((i for i in job.items if i.name == body.get("sutra")), None)
    res = job.results.get(item.name) if item else None
    if item is None or not res or not res.get("yaml"):
        return _problem(404, "no such Sutra in this job", "DRS-5006")
    backend = request.app.state.backend
    try:
        d = await backend.designs("POST", "", me, {"name": f"{job.name}: {item.name}"[:120], "kind": item.kp.kind, "sutra": res["yaml"],
                                                   "notes": f"From Build -> New pack ({job.name}). Samples named sample-synthetic-N were generated from the schema."})
        await backend.designs("POST", f"/{d['id']}/samples", me, {"samples": [{"name": s.name, "document": s.doc} for s in item.samples]})
    except BackendError as e:
        return _problem(e.status, e.detail, e.code)
    return {"id": d["id"], "url": f"/build/d/{d['id']}"}


@router.get("/api/jobs/{job_id}/bundle")
async def job_bundle(request: Request, job_id: str, part: str = "tar"):
    """The bundle of a finished job: ``part=tar`` (the .tar.gz, default), ``sha256`` or ``manifest``. Needs the author power."""
    _, jobs = _wiz(request)
    me = ident(request)
    if not await _may_author(request):
        return _problem(403, "downloading a pack needs a role with the author power", "DRS-5002")
    try:
        job = jobs.get(job_id, me.user)
        bundle = await asyncio.to_thread(jobs.build_bundle, job)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)
    except Exception as e:           # noqa: BLE001 - a bundling failure is shown, with the reason
        LOG.warning("new-pack bundle failed: %s", e)
        return _problem(500, f"the bundle could not be built: {e}")
    if part == "sha256":
        return Response(f"{bundle['sha256']}  {bundle['name']}\n", media_type="text/plain", headers={"Content-Disposition": f'attachment; filename="{bundle["name"]}.sha256"'})
    if part == "manifest":
        return Response(bundle["manifest"], media_type="application/json", headers={"Content-Disposition": f'attachment; filename="{bundle["pack"]}-{bundle["version"]}.manifest.json"'})
    return Response(bundle["tar"], media_type="application/gzip", headers={"Content-Disposition": f'attachment; filename="{bundle["name"]}"',
                                                                         "X-Drishti-Sha256": bundle["sha256"], "Cache-Control": "no-store"})


@router.get("/api/jobs/{job_id}/summary")
async def job_summary(request: Request, job_id: str):
    """What the output step shows: the bundle's name, size, checksum and what is synthetic; builds the bundle."""
    _, jobs = _wiz(request)
    me = ident(request)
    try:
        job = jobs.get(job_id, me.user)
        b = await asyncio.to_thread(jobs.build_bundle, job)
    except W.WizardError as e:
        return _problem(e.status, e.detail, e.code)
    except Exception as e:           # noqa: BLE001
        return _problem(500, f"the bundle could not be built: {e}")
    return {"file": b["name"], "bytes": len(b["tar"]), "sha256": b["sha256"], "files": b["files"], "pack": b["pack"], "version": b["version"],
            "synthetic": b["synthetic"], "todo": b["todo"], "canAuthor": await _may_author(request), "canDeploy": bool(me.is_admin)}


# ---- drafts: the wizard's state, kept as a Design ---------------------------------------------------------------------------------

def encode_state(state: dict) -> str:
    return DRAFT_MARK + base64.b64encode(gzip.compress(json.dumps(state, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))).decode("ascii")


def decode_state(notes: str) -> dict | None:
    if not isinstance(notes, str) or not notes.startswith(DRAFT_MARK):
        return None
    try:
        return json.loads(gzip.decompress(base64.b64decode(notes[len(DRAFT_MARK):])).decode("utf-8"))
    except (ValueError, OSError):
        return None


@router.get("/api/drafts")
async def drafts(request: Request):
    try:
        data = await request.app.state.backend.designs("GET", "", ident(request))
    except BackendError as e:
        return _problem(e.status, e.detail, e.code)
    rows = [{"id": d["id"], "name": d.get("name", "")[len(DRAFT_PREFIX):], "updated": d.get("updated", 0)} for d in data.get("designs") or []
            if str(d.get("name") or "").startswith(DRAFT_PREFIX)]
    return {"drafts": sorted(rows, key=lambda r: r["updated"], reverse=True), "maxNotesKb": (data.get("limits") or {}).get("maxNotesKb", 64)}


@router.post("/api/drafts")
async def save_draft(request: Request):
    """Body ``{id?, name, state}``: creates the draft (a Design named 'New pack: <name>') or updates it; the state is compressed into the notes."""
    body = await _body(request)
    me = ident(request)
    backend = request.app.state.backend
    state, name = body.get("state"), str(body.get("name") or "").strip()[:100]
    if not isinstance(state, dict) or not name:
        return _problem(400, "send {name, state}")
    notes = encode_state(state)
    try:
        data = await backend.designs("GET", "", me)
        cap = int((data.get("limits") or {}).get("maxNotesKb", 64)) * 1024
        if len(notes.encode("ascii")) > cap:
            return _problem(413, f"the draft is {len(notes) // 1024} KB, over the {cap // 1024} KB a design's notes may hold: remove a large schema, or download the bundle now")
        if body.get("id"):
            d = await backend.designs("PATCH", f"/{body['id']}", me, {"name": DRAFT_PREFIX + name, "notes": notes})
        else:
            d = await backend.designs("POST", "", me, {"name": DRAFT_PREFIX + name, "kind": request.app.state.settings.get("builder.studio_kind", "sample"), "notes": notes})
    except BackendError as e:
        return _problem(e.status, e.detail, e.code)
    return {"id": d["id"], "name": name}


@router.get("/api/drafts/{draft_id}")
async def open_draft(request: Request, draft_id: str):
    try:
        d = await request.app.state.backend.designs("GET", f"/{draft_id}", ident(request))
    except BackendError as e:
        return _problem(e.status, e.detail, e.code)
    state = decode_state(d.get("notes") or "")
    if state is None:
        return _problem(422, "this design is not a New pack draft")
    return {"id": d["id"], "name": str(d.get("name", ""))[len(DRAFT_PREFIX):], "state": state}


@router.delete("/api/drafts/{draft_id}")
async def delete_draft(request: Request, draft_id: str):
    try:
        await request.app.state.backend.designs("DELETE", f"/{draft_id}", ident(request))
    except BackendError as e:
        return _problem(e.status, e.detail, e.code)
    return {"deleted": draft_id}
