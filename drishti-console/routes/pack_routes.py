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

"""Build -> New pack: the Pack designer (``/build/pack/designer``; docs/guides/BUILD_GUIDE.md section 8).

JSON Lines files go up to a workspace on the server; the server reads every record (the code of ``drishti.py pack new``, run as a job:
core/packdesigner.py), the page shows the combinations, the Sutras and their live screens, and the finished module (.dmod) is deployed through
the one module deploy (single node: install; cluster: plan, then apply). ``/build/pack/new``, the old page, redirects here."""
from __future__ import annotations

import json
import logging
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response

from core import packdesigner as PD
from core.backend import BackendError
from core.csrf import BodyError, json_body
from core.servers import scoped
from routes.common import ident, render

router = APIRouter(prefix="/build/pack", include_in_schema=False)
LOG = logging.getLogger("drishti.console.build.pack")


def _problem(status: int, detail: str, code: str | None = None, **more) -> JSONResponse:
    return JSONResponse({"code": code or ("DRS-5005" if status == 413 else "DRS-5001"), "detail": detail, **more}, status_code=status)


def _ws(request: Request) -> PD.Workspaces:
    st = request.app.state
    if not hasattr(st, "pack_designer"):
        from core.app import _console_data_dir
        tools = Path(str(st.settings.get("builder.tools_dir", "../tools")))
        console_dir = Path(__file__).resolve().parent.parent
        st.pack_designer = PD.Workspaces(_console_data_dir(st.settings) / "pack-designer", tools if tools.is_absolute() else (console_dir / tools).resolve(),
                                         PD.DesignerLimits.from_settings(st.settings))
    return st.pack_designer


async def _may_author(request: Request) -> bool:
    try:
        s = await request.app.state.backend.studio_settings(ident(request))
    except BackendError:
        return False
    return bool(s.get("author", s.get("save")))


async def _body(request: Request) -> dict:
    body = await json_body(request, limit=8 * 1048576)
    if not isinstance(body, dict):
        raise BodyError(400, "send a JSON object")
    return body


def _dir(request: Request, wid: str) -> Path:
    return _ws(request).path(scoped(ident(request).user), wid)


@router.get("/new")
async def old_page():
    """The old Build -> New pack page is gone: the designer is its replacement."""
    return RedirectResponse("/build/pack/designer", status_code=308)


@router.get("/designer")
async def page(request: Request):
    """The workspace: six steps (Data, Kind and keys, Source, Design, Finalize, Deploy) with their summaries on the left."""
    ws = _ws(request)
    me = ident(request)
    return render(request, "build/pack_designer.html", limits=ws.limits.as_dict(), can_author=await _may_author(request), is_admin=bool(me.is_admin),
                  screen="build")


# ---- the workspace and its files ----------------------------------------------------------------------------------------------

@router.post("/api/ws")
async def create(request: Request):
    if not await _may_author(request):
        return _problem(403, "starting a pack needs a role with the author power", "DRS-5002")
    return JSONResponse({"id": _ws(request).create(scoped(ident(request).user))}, status_code=201)


@router.get("/api/ws/{wid}")
async def state(request: Request, wid: str):
    ws = _ws(request)
    try:
        d = ws.path(scoped(ident(request).user), wid)
        req = json.loads((d / "request.json").read_text(encoding="utf-8")) if (d / "request.json").is_file() else {}
        return {"id": wid, "files": ws.files(d), "request": req, "status": ws.status(d), "analysed": (d / "analysis.json").is_file(),
                "result": ws.result(d)}
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)


@router.delete("/api/ws/{wid}")
async def remove(request: Request, wid: str):
    try:
        _ws(request).remove(scoped(ident(request).user), wid)
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)
    return Response(status_code=204)


@router.post("/api/ws/{wid}/file")
async def upload(request: Request, wid: str, path: str):
    """One file's bytes as the body (streamed to the workspace's in/ folder, never held whole); ``path`` is its path below the dropped folder."""
    ws = _ws(request)
    try:
        d = ws.path(scoped(ident(request).user), wid)
        if ws.running(d):
            return _problem(409, "a job is running in this workspace: wait for it")
        f = ws.target(d, path)
        cap = ws.limits.max_mb * 1048576
        have = ws.used(d) - (f.stat().st_size if f.exists() else 0)
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and have + int(declared) > cap:
            return _problem(413, f"over {ws.limits.max_mb} MB in one workspace (builder.pack_designer_max_mb)")
        f.parent.mkdir(parents=True, exist_ok=True)
        tmp = f.with_name(f.name + ".part")
        size = 0
        try:
            with open(tmp, "wb") as w:
                async for chunk in request.stream():
                    size += len(chunk)
                    if have + size > cap:
                        raise PD.DesignerError(413, f"over {ws.limits.max_mb} MB in one workspace (builder.pack_designer_max_mb)", "DRS-5005")
                    w.write(chunk)
            tmp.replace(f)
        finally:
            tmp.unlink(missing_ok=True)
        ws.touch(d)
        (d / "analysis.json").unlink(missing_ok=True)                  # the files changed: the last analysis no longer holds
        return {"path": PD.clean_path(path), "bytes": size}
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)


@router.delete("/api/ws/{wid}/file")
async def remove_file(request: Request, wid: str, path: str):
    ws = _ws(request)
    try:
        d = ws.path(scoped(ident(request).user), wid)
        ws.remove_file(d, path)
        (d / "analysis.json").unlink(missing_ok=True)
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)
    return Response(status_code=204)


# ---- the jobs ----------------------------------------------------------------------------------------------------------------

def _job_env(request: Request) -> dict:
    """Extra environment for a job: none (the job runs with the console's own, so its data folder is the console's: DRISHTI_DATA_DIR)."""
    return {}


@router.post("/api/ws/{wid}/analyse")
async def analyse(request: Request, wid: str):
    """Reads every record of the workspace's files and drafts the Sutras: the choices in the body ({name, kind, kindPrefix, match, key, date, ...})."""
    if not await _may_author(request):
        return _problem(403, "designing a pack needs a role with the author power", "DRS-5002")
    ws = _ws(request)
    try:
        body = await _body(request)
        d = ws.path(scoped(ident(request).user), wid)
        req = ws.request_for({**body, "edits": None})
        ws.start(scoped(ident(request).user), d, "analyse", req, _job_env(request))
    except (PD.DesignerError, BodyError) as e:
        return _problem(e.status, e.detail, getattr(e, "code", None))
    return JSONResponse({"id": wid}, status_code=202)


@router.get("/api/ws/{wid}/status")
async def status(request: Request, wid: str):
    ws = _ws(request)
    try:
        return ws.status(ws.path(scoped(ident(request).user), wid))
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)


@router.post("/api/ws/{wid}/cancel")
async def cancel(request: Request, wid: str):
    ws = _ws(request)
    try:
        return {"cancelled": ws.cancel(ws.path(scoped(ident(request).user), wid))}
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)


@router.get("/api/ws/{wid}/analysis")
async def analysis(request: Request, wid: str):
    ws = _ws(request)
    try:
        a = ws.analysis(ws.path(scoped(ident(request).user), wid))
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)
    return a if a is not None else _problem(404, "no analysis yet: read the files first", "DRS-5006")


@router.post("/api/ws/{wid}/finalize")
async def finalize(request: Request, wid: str):
    """Writes the module with the edited Sutras, binds it, packs the .dmod and runs the fifteen checks: ({..choices, edits: {sutra name: YAML}})."""
    if not await _may_author(request):
        return _problem(403, "making a module needs a role with the author power", "DRS-5002")
    ws = _ws(request)
    try:
        body = await _body(request)
        d = ws.path(scoped(ident(request).user), wid)
        if ws.analysis(d) is None:
            return _problem(409, "read the files first (step 1)")
        req = ws.request_for(body)
        if req.get("mode") == "source" and ws.mapping(d) is None:
            return _problem(409, "propose the field map first (step 2)")
        ws.start(scoped(ident(request).user), d, "source" if req.get("mode") == "source" else "finalize", req, _job_env(request))
    except (PD.DesignerError, BodyError) as e:
        return _problem(e.status, e.detail, getattr(e, "code", None))
    return JSONResponse({"id": wid}, status_code=202)


@router.post("/api/ws/{wid}/map")
async def propose_map(request: Request, wid: str):
    """A source of an existing kind: every record read again, the field map proposed native -> canonical with a confidence each
    ({mode: source, sourceFor, system, mapEdits, ...}; `drishti.py pack new --source-for`)."""
    if not await _may_author(request):
        return _problem(403, "designing a source needs a role with the author power", "DRS-5002")
    ws = _ws(request)
    try:
        body = await _body(request)
        d = ws.path(scoped(ident(request).user), wid)
        req = ws.request_for({**body, "mode": "source", "name": body.get("name") or "source-" + str(body.get("system") or "x").lower().replace("_", "-")})
        ws.start(scoped(ident(request).user), d, "map", req, _job_env(request))
    except (PD.DesignerError, BodyError) as e:
        return _problem(e.status, e.detail, getattr(e, "code", None))
    return JSONResponse({"id": wid}, status_code=202)


@router.get("/api/ws/{wid}/mapping")
async def mapping(request: Request, wid: str):
    ws = _ws(request)
    try:
        m = ws.mapping(ws.path(scoped(ident(request).user), wid))
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)
    return m if m is not None else _problem(404, "no field map yet: choose the kind and the system first", "DRS-5006")


@router.get("/api/ws/{wid}/dmod")
async def dmod(request: Request, wid: str):
    ws = _ws(request)
    try:
        f = ws.dmod(ws.path(scoped(ident(request).user), wid))
    except PD.DesignerError as e:
        return _problem(e.status, e.detail, e.code)
    if f is None:
        return _problem(404, "the module is not made yet", "DRS-5006")
    return FileResponse(f, media_type="application/octet-stream", filename=f.name, headers={"Cache-Control": "no-store"})


# ---- the live screen -------------------------------------------------------------------------------------------------------------

def _sutra_of(a: dict, name: str) -> dict | None:
    return next((s for s in a.get("sutras", []) if s["name"] == name), None)


async def _sutra_call(request: Request, wid: str):
    """(person, the analysed Sutra the body names, the body) for the live screen's calls, or the problem to answer with."""
    ws, me = _ws(request), ident(request)
    try:
        body = await _body(request)
        a = ws.analysis(ws.path(scoped(me.user), wid))
    except (PD.DesignerError, BodyError) as e:
        return _problem(e.status, e.detail, getattr(e, "code", None))
    return me, _sutra_of(a or {}, str(body.get("sutra") or "")), body


@router.post("/api/ws/{wid}/preview")
async def preview(request: Request, wid: str):
    """{sutra, yaml, sample}: the Sutra's YAML (as edited) drawn by the server on one of the combination's real rows (sample is its index)."""
    got = await _sutra_call(request, wid)
    if isinstance(got, JSONResponse):
        return got
    me, s, body = got
    text = body.get("yaml")
    if s is None or not isinstance(text, str) or not s["samples"]:
        return _problem(404, "no such Sutra in this analysis", "DRS-5006")
    n = int(body.get("sample") or 0)
    doc = s["samples"][n if 0 <= n < len(s["samples"]) else 0]
    backend = request.app.state.backend
    try:
        vm = await backend.preview(text, s["kind"], "SAMPLE", me, document=doc)
    except BackendError as e:
        return _problem(e.status, e.detail, e.code, problems=getattr(e, "problems", []))
    html = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm) if vm else ""
    return {"previewHtml": html, "sample": n, "samples": len(s["samples"])}


@router.post("/api/ws/{wid}/check")
async def check(request: Request, wid: str):
    """{sutra, yaml}: the server's one checker over the combination's real rows: {ok, empty, error} cells and the problems."""
    got = await _sutra_call(request, wid)
    if isinstance(got, JSONResponse):
        return got
    me, s, body = got
    text = body.get("yaml")
    if s is None or not isinstance(text, str):
        return _problem(404, "no such Sutra in this analysis", "DRS-5006")
    samples = [{"name": f"row-{i + 1}", "document": d} for i, d in enumerate(s["samples"])]
    try:
        res = await request.app.state.backend.check(text, s["kind"], samples, me)
    except BackendError as e:
        return {"ok": 0, "empty": 0, "error": 0, "problems": getattr(e, "problems", None) or [{"message": e.detail}], "refused": True}
    return {"counts": res.get("counts") or {}, "panels": [{"id": p.get("id"), "cells": p.get("cells")} for p in res.get("panels") or []],
            "problems": res.get("problems") or []}


async def _propose_binding(b, me, d: Path, reason: str) -> dict:
    """The workspace's binding (binding.json, written by the job) goes through the one bindings path: the file connector is saved on the server
    (Admin -> Connectors) and each kind's binding is proposed to Admin -> Data bindings, where a second administrator approves it (four eyes)."""
    f = d / "binding.json"
    if not f.is_file():
        return {"proposed": [], "note": "the workspace has no binding: bind the kinds in Admin -> Data bindings"}
    import json
    plan = json.loads(f.read_text(encoding="utf-8"))
    out: dict = {"connector": plan.get("connector"), "proposed": [], "errors": []}
    try:
        if plan.get("file"):
            await b.admin("PUT", f"/connectors/{quote(plan['file']['name'], safe='')}", me, {"text": plan["file"]["text"]})
            out["connectorSaved"] = plan["file"]["name"]
    except BackendError as e:
        out["errors"].append(f"connector {plan['file']['name']}: {e.detail}")
        return out
    why = (reason or "Pack designer: the data of the new module")[:500]
    for kind in plan.get("kinds") or []:
        try:
            p = await b.admin("POST", "/bindings/proposals", me, {"kind": kind, "binding": {"read": [plan["connector"]]}, "reason": why})
            out["proposed"].append({"kind": kind, "id": (p or {}).get("id")})
        except BackendError as e:
            out["errors"].append(f"{kind}: {e.detail}")
    return out


# ---- deploy: the one module deploy -------------------------------------------------------------------------------------------------

@router.post("/api/ws/{wid}/deploy/{action}")
async def deploy(request: Request, wid: str, action: str):
    """``install``: one node, the .dmod is put in the modules folder (auto-deploy installs it); ``plan``: the .dmod goes to the module store (the server checks it once) and a plan to install it is made; ``apply`` {id, comment}: the
    plan is applied (a single node installs at once; a cluster needs a second administrator by four eyes when the server says so)."""
    me = ident(request)
    if not me.is_admin:
        return _problem(403, "deploying a module is for administrators", "DRS-5002")
    ws = _ws(request)
    b = request.app.state.backend
    try:
        d = ws.path(scoped(me.user), wid)
        body = await _body(request)
        if action == "install":                       # one node: the package goes to the modules folder, auto-deploy checks, installs and loads it
            import os
            import shutil
            f = ws.dmod(d)
            if f is None:
                return _problem(409, "make the module first (step 5)")
            target = Path(str(request.app.state.settings.get("builder.pack_designer_modules_dir", "") or os.environ.get("DRISHTI_MODULES_PATH")
                              or Path(os.environ.get("DRISHTI_DATA_DIR") or Path(__file__).resolve().parents[2] / "data") / "modules"))
            target.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, target / f.name)
            return {"installed": str(target / f.name), "binding": await _propose_binding(b, me, d, str(body.get("reason") or ""))}
        if action == "plan":
            f, r = ws.dmod(d), ws.result(d)
            if f is None or r is None:
                return _problem(409, "make the module first (step 5)")
            stored = await b.admin_upload("/modules/store", me, f.read_bytes(), {}, timeout=600.0)
            plan = await b.admin("POST", "/modules/deployments/plans", me, {"action": "install", "module": r["name"], "version": r["version"],
                                                                         "reason": str(body.get("reason") or "Pack designer")[:500]})
            return {"stored": stored, "plan": plan, "binding": await _propose_binding(b, me, d, str(body.get("reason") or ""))}
        if action == "apply":
            out = await b.admin("POST", f"/modules/deployments/{quote(str(body.get('id') or ''), safe='')}/apply", me,
                                {"comment": str(body.get("comment") or "")[:500]}, timeout=600.0)
            from core import modules as _modules
            await request.app.state.module_parts.refresh(b, _modules.state().loaded, force=True)
            return out
    except (PD.DesignerError, BodyError) as e:
        return _problem(e.status, e.detail, getattr(e, "code", None))
    except BackendError as e:
        return _problem(e.status, e.detail, e.code)
    return _problem(400, "unknown action")
