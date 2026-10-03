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

"""Sutra Studio, folded into the Build workbench (docs/architecture/BUILD_WORKBENCH.md, step 7). The page is gone: every
``/studio`` address is a 302 into the workbench (``/build/d/{id}``) or the review pages (``/build/reviews``). What stays here is
the JSON the workbench still asks of this prefix: preview, test, summary, schema, source, inference, save, an example, and the
test entities Studio kept (read once, when a Design starts from a Sutra)."""
from __future__ import annotations

from urllib.parse import parse_qs, quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse

from core import designs, sutra_summary
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(prefix="/studio", include_in_schema=False)



async def _preview_body(request: Request) -> dict:
    """A preview request as the server takes it: a JSON object with the Sutra text, the kind, and an id (or a pasted
    document). Anything else is a 400 DRS-5001 problem here, not a 500 further on (QA 2026-10-01 GRAM-08)."""
    body = await json_body(request)
    if not isinstance(body, dict):
        raise BodyError(400, "send a JSON object: {yaml, kind, id} (or a pasted document instead of the id)")
    if not isinstance(body.get("yaml"), str):
        raise BodyError(400, "'yaml' is required: the Sutra text to preview")
    if not isinstance(body.get("kind"), str) or not body["kind"].strip():
        raise BodyError(400, "'kind' is required: the kind of entity to preview against")
    if body.get("document") is None and not isinstance(body.get("id"), str):
        raise BodyError(400, "'id' is required: the entity to preview against (or a pasted document)")
    return body


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail, "problems": getattr(e, "problems", [])}, status_code=e.status)


@router.get("")
async def studio(request: Request, sutra: str | None = None, kind: str = "", id: str = "", example: str = "", design: str = "",
                 sample: str = "", build: str = ""):
    """Studio's page is retired (BUILD_WORKBENCH.md, step 7). ``design=`` (with ``sample=``) opens that Design of yours, a 302 into the
    workbench. Every other address would *start* something (an example as a copy, a Design that edits a Sutra, a stored entity, a
    scratch Design): a GET must not do that, or any page on the web could fill your quota with a link (QA 2026-10-03 S2-04), so it
    answers a small confirmation page whose button POSTs the same address (``POST /studio``, protected by the same-origin check)."""
    backend, me = request.app.state.backend, ident(request)
    tab = "design" if build and build != "0" else "yaml"
    target = await _existing(backend, me, design)
    if target is None:
        fields = {k: v for k, v in (("sutra", sutra or ""), ("kind", kind.strip()), ("id", id.strip()), ("example", example), ("build", build),
                                    ("sutra_given", "1" if sutra is not None else "")) if v}
        return render(request, "build/start.html", fields=fields, what=_describe(sutra, kind.strip(), id.strip(), example), screen="build")
    query = {"tab": tab}
    if design and sample and target == design:
        query["sample"] = sample
    return RedirectResponse(f"/build/d/{target}?{urlencode(query)}", status_code=302)


def _describe(sutra: str | None, kind: str, id_: str, example: str) -> str:
    if example:
        return f"the example \u201c{example}\u201d as your own copy"
    if sutra and "@" in sutra:
        return f"a design that edits the Sutra {sutra}"
    if id_:
        return f"a design with {kind or 'the'} entity {id_} as its sample"
    return "a new scratch design"


@router.post("")
async def studio_open(request: Request):
    """The button of the confirmation page: starts the Design the address describes and 303s into the workbench."""
    form = {k: v[0] for k, v in parse_qs((await request.body()).decode("utf-8", "replace"), keep_blank_values=True).items()}
    backend, me = request.app.state.backend, ident(request)
    sutra = form.get("sutra", "") if form.get("sutra_given") or form.get("sutra") else None
    tab = "design" if form.get("build") and form["build"] != "0" else "yaml"
    try:
        target = (await _start(request, me, sutra, form.get("kind", "").strip(), form.get("id", "").strip(), form.get("example", "")))["id"]
    except BackendError as e:
        return render(request, "build/designs.html", status_code=e.status, designs=[], limits={}, error=e, screen="build")
    return RedirectResponse(f"/build/d/{target}?{urlencode({'tab': tab})}", status_code=303)


async def _existing(backend, me, design: str) -> str | None:
    """The Design asked for when it is the user's; else None (not theirs or gone: Studio opened as usual, so a new one is made)."""
    if not design:
        return None
    try:
        await backend.designs("GET", f"/{design}", me)
    except BackendError:
        return None
    return design


async def _start(request: Request, me, sutra: str | None, kind: str, id_: str, example: str) -> dict:
    """A new Design for the address: see ``studio``. An example that does not exist is not the default one: a blank Design."""
    backend, examples = request.app.state.backend, request.app.state.examples
    fallback = str(request.app.state.settings.get("builder.studio_kind", "sample") or "sample")
    if example:
        ex = examples.get(example)
        if ex:
            return await designs.copy_example(backend, ex, me, fallback)
        return await backend.designs("POST", "", me, {"kind": fallback, "sutra": designs.new_sutra(fallback)})
    if sutra and "@" in sutra:
        name = sutra.partition("@")[0]
        known = next((s for s in await backend.sutras(me) if s.get("name") == name), None)
        d = await backend.designs("POST", "", me, {"kind": (known or {}).get("kind") or kind or fallback, "base": sutra})
        await designs.migrate_tests(backend, me, d["id"], sutra)
        if kind and id_:
            await designs.add_entity(backend, me, d["id"], kind, id_)
        return d
    if id_:
        d = await backend.designs("POST", "", me, {"kind": kind or fallback, "sutra": designs.new_sutra(kind or fallback)})
        await designs.add_entity(backend, me, d["id"], kind or fallback, id_)
        return d
    ex = examples.get(examples.default) if sutra is None and not kind else None
    if ex:
        return await designs.copy_example(backend, ex, me, fallback, scratch=True)
    return await backend.designs("POST", "", me, {"kind": kind or fallback, "sutra": designs.new_sutra(kind or fallback)})


@router.get("/example/{name}")
async def example(request: Request, name: str):
    """One example as JSON: {name, title, kind, yaml, json}. A name that is not an example's is a 404, whatever it contains."""
    ex = request.app.state.examples.get(name)
    if ex is None:
        return JSONResponse({"code": "NOT_FOUND", "detail": "no such example"}, status_code=404)
    return {"name": ex.name, "title": ex.title, "kind": ex.kind, "yaml": ex.yaml, "json": ex.json}


def _moved(request: Request, path: str, status: int = 302) -> RedirectResponse:
    q = request.url.query
    return RedirectResponse(f"/build/reviews{path}" + (f"?{q}" if q else ""), status_code=status)


@router.get("/reviews")
async def reviews(request: Request):
    """The review pages live in the workbench's Govern menu now (``/build/reviews``)."""
    return _moved(request, "")


@router.get("/reviews/{id_}")
async def review(request: Request, id_: str):
    return _moved(request, f"/{quote(id_, safe='')}")


@router.post("/reviews/{id_}/{action}")
async def decide(request: Request, id_: str, action: str):
    """A decision posted to the old address is sent on with its form (307 keeps the method and the body)."""
    return _moved(request, f"/{quote(id_, safe='')}/{quote(action, safe='')}", status=307)


@router.get("/source/{name}/{version}")
async def source(request: Request, name: str, version: int):
    try:
        return PlainTextResponse(await request.app.state.backend.sutra_source(name, version, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/preview")
async def preview(request: Request):
    body = await _preview_body(request)
    try:
        vm = await request.app.state.backend.preview(body["yaml"], body["kind"], body.get("id") or "", ident(request),
                                                     body.get("document"))
    except BackendError as e:
        return _problem(e)
    html = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm)
    return HTMLResponse(html)


@router.get("/tests/{sutra}")
async def tests(request: Request, sutra: str):
    """The entities this author keeps for trying the Sutra."""
    try:
        return await request.app.state.backend.studio_tests(sutra, ident(request))
    except BackendError as e:
        return _problem(e)


@router.put("/tests/{sutra}")
async def save_tests(request: Request, sutra: str):
    try:
        return await request.app.state.backend.set_studio_tests(sutra, await json_body(request, []), ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/test")
async def run_test(request: Request):
    """Previews the Sutra on one entity and says how it went: problems in the Sutra, or panels that could not bind.
    The server's one checker answers (``/builder/check``); this only reshapes its matrix for Studio's test list."""
    import time

    body = await _preview_body(request)
    t0 = time.perf_counter()
    sample = {"name": body.get("id") or "sample"}
    if body.get("document") is not None:
        sample["document"] = body["document"]
    else:
        sample["ref"] = {"kind": body["kind"], "id": body["id"]}
    try:
        m = await request.app.state.backend.check(body["yaml"], body["kind"], [sample], ident(request))
    except BackendError as e:
        return {"ok": False, "code": e.code, "detail": e.detail, "problems": getattr(e, "problems", []),
                "ms": round((time.perf_counter() - t0) * 1000, 1)}
    ms = round((time.perf_counter() - t0) * 1000, 1)
    one = (m.get("samples") or [{}])[0]
    if one.get("status") == "noAccess":
        return {"ok": False, "code": "DRS-5002", "detail": one.get("message") or "no access", "problems": [], "ms": ms}
    if one.get("status") == "error":
        return {"ok": False, "code": "DRS-4002", "detail": one.get("message") or "the view could not be built", "problems": [], "ms": ms}
    panels = m.get("panels", [])
    failed = [{"panel": p["id"], "error": p["cells"][0].get("message")} for p in panels if p["cells"][0]["status"] == "error"]
    empty = [p["id"] for p in panels if p["cells"][0]["status"] == "empty"]
    return {"ok": not failed, "panels": len(panels), "failed": failed, "empty": empty, "layout": one.get("layout"), "ms": ms}


@router.post("/summary")
async def summary(request: Request):
    """The Summary tab: the YAML Sutra read back as a page (derived, read-only)."""
    body = await json_body(request)
    tpl = request.app.state.templates.get_template("studio/_summary.html")
    try:
        return JSONResponse({"html": tpl.render(s=sutra_summary.summarize(str(body.get("yaml", ""))), error=None)})
    except ValueError as e:
        return JSONResponse({"html": tpl.render(s=None, error=str(e)), "error": str(e)})


@router.get("/schema")
async def schema(request: Request):
    """The Rachana language's JSON Schema with this server's kinds, formats and functions (for completion and checks)."""
    try:
        return JSONResponse(await request.app.state.backend.rachana_schema(ident(request)), headers={"Cache-Control": "private, max-age=60"})
    except BackendError as e:
        return _problem(e)


@router.get("/inferred/{kind}/{id_:path}")
async def inferred(request: Request, kind: str, id_: str, name: str = ""):
    try:
        return PlainTextResponse(await request.app.state.backend.inferred(kind, id_, name, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/inferred")
async def inferred_from_sample(request: Request):
    body = await json_body(request)
    try:
        return PlainTextResponse(await request.app.state.backend.inferred_from(
            body.get("kind", ""), body.get("id", ""), body.get("name", ""), body.get("document"), ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/save")
async def save(request: Request):
    body = await json_body(request)
    try:
        return await request.app.state.backend.save_sutra(body.get("yaml", ""), ident(request), note=str(body.get("note", ""))[:300])
    except BackendError as e:
        return _problem(e)
