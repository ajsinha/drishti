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

"""The model layer's pages (docs/architecture/BI_MODEL_SPEC.md section 9; docs/guides/BI_GUIDE.md "Models"): *Start from your data* (the pack-generated
datasets and the models), one dataset's page (grain, keys, meanings, time mode), the model designer, and the new-dataset wizard for what a pack does not
give (a connector query, a Python table, a model's answer, a narrowed pack dataset).

The pages carry no data; their scripts ask ``/bi/model-api/...`` here, which reads the server as the signed-in person (``/api/v1/bi/pack-datasets``,
``/models/{id}/describe``, ``/models/validate``, ``/items``) and adds only shape: YAML written from the designer's document and read back, relationships
suggested from the packs' references, default measures, the Fields pane of a report on a model. The server checks every model and decides every access."""
from __future__ import annotations

import asyncio
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import model_layer as ml
from core.backend import BackendError
from core.csrf import json_body
from routes.bi_routes import _page
from routes.common import ident

router = APIRouter(include_in_schema=False)

NO_STORE = {"Cache-Control": "no-store"}


def _ok(body) -> JSONResponse:
    return JSONResponse(body, headers=NO_STORE)


def _refuse(detail: str, code: str = "DRS-9100", status: int = 422) -> JSONResponse:
    return JSONResponse({"code": code, "detail": detail}, status_code=status)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail, **{k: v for k, v in (e.extra or {}).items() if k in ("path", "fix", "problems")}}, status_code=e.status)


async def _bi(request: Request, method: str, path: str, body=None, **params):
    return await request.app.state.backend.bi(method, path, ident(request), body, **params)


# ---- pages --------------------------------------------------------------------------------------------------------------

@router.get("/bi/models")
async def models_page(request: Request):
    """Start from your data: the datasets the packs give (no one builds them), the models, and the two next steps (a new dataset, a new model)."""
    return _page(request, "bi/models.html", "models")


@router.get("/bi/ds/{name:path}")
async def dataset_page(request: Request, name: str):
    """One model-layer dataset (pack-generated or an item): its grain, keys, columns with meanings, time mode, security, YAML; Start a report on it."""
    return _page(request, "bi/layer_dataset.html", "models", item=name)


@router.get("/bi/md/{item:path}")
async def designer_page(request: Request, item: str):
    """The model designer on one model item (spec 9.3): canvas, measures, security, contract, YAML."""
    return _page(request, "bi/model_designer.html", "models", item=item)


@router.get("/bi/datasets/new-layer")
async def dataset_wizard_page(request: Request):
    """The new-dataset wizard of the model layer (spec 9.2): three steps, no YAML typing."""
    return _page(request, "bi/dataset_layer_wizard.html", "models", workspace=request.query_params.get("workspace", ""))


# ---- datasets -----------------------------------------------------------------------------------------------------------

async def _dataset_info(request: Request, name: str) -> dict:
    """A dataset by name: a pack-generated one (read-only) or a ``rachana: 2`` item the person may read (by item id or name)."""
    if name.startswith(ml.PACK_PREFIX):
        got = await _bi(request, "GET", "/pack-datasets/" + quote(name, safe=""))
        return ml.dataset_info(got.get("yaml") or "", read_only=True)
    item = await _bi(request, "GET", "/items/" + quote(name, safe=":"))
    info = ml.dataset_info(item.get("body") or "", read_only=not ((item.get("can") or {}).get("edit")), item=item.get("id", ""))
    info["status"] = item.get("status")
    return info


async def _infos(request: Request, doc: dict) -> dict:
    """The datasets a model document names, read once each, by alias (one that cannot be read is left out; Check names it)."""
    uses = ml._uses(doc)
    names = sorted({u["dataset"] for u in uses.values() if u["dataset"]})

    async def one(n):
        try:
            return n, await _dataset_info(request, n)
        except (BackendError, ml.LayerError):
            return n, None

    got = dict(await asyncio.gather(*(one(n) for n in names)))
    out = {}
    for alias, u in uses.items():
        info = got.get(u["dataset"])
        if info is None and not u["dataset"].startswith(ml.PACK_PREFIX):
            info = await _item_dataset_by_name(request, u["dataset"])
        if info:
            out[alias] = info
    return out


async def _item_dataset_by_name(request: Request, name: str):
    try:
        listing = await _bi(request, "GET", "/models")
    except BackendError:
        return None
    hit = next((m for m in listing.get("models") or [] if m.get("kind") == "implicit" and m.get("name") == name), None)
    if not hit:
        return None
    try:
        return await _dataset_info(request, hit["id"])
    except (BackendError, ml.LayerError):
        return None


@router.get("/bi/model-api/datasets")
async def datasets(request: Request):
    """Every dataset a model may use, as the person sees them: the packs' (certified, read-only) first, then published ``rachana: 2`` items; and the models."""
    try:
        packs, models = await asyncio.gather(_bi(request, "GET", "/pack-datasets"), _bi(request, "GET", "/models"))
    except BackendError as e:
        return _problem(e)
    items = [m for m in models.get("models") or [] if m.get("kind") == "implicit"]
    return _ok({"packs": packs.get("datasets") or [], "items": items, "models": [m for m in models.get("models") or [] if m.get("kind") in ("model", "compat")]})


@router.get("/bi/model-api/dataset")
async def dataset(request: Request):
    name = request.query_params.get("name", "")
    if not name:
        return _refuse("name the dataset: ?name=pack.trading.trade")
    try:
        info = await _dataset_info(request, name)
        yaml_text = (await _bi(request, "GET", "/pack-datasets/" + quote(name, safe="")))["yaml"] if name.startswith(ml.PACK_PREFIX) else \
            (await _bi(request, "GET", "/items/" + quote(name, safe=":"))).get("body", "")
    except BackendError as e:
        return _problem(e)
    except ml.LayerError as e:
        return _refuse(str(e))
    return _ok({"info": info, "yaml": yaml_text})


# ---- the model document -------------------------------------------------------------------------------------------------

@router.post("/bi/model-api/compose")
async def compose(request: Request):
    """``{doc}`` (the designer's model document) to ``{yaml, check, suggestions, datasets}``: the YAML in the spec's order, the server's Check of it (problems
    with codes and paths, and the security safeguard's findings), the relationships the packs suggest, and the datasets' columns by alias."""
    body = await json_body(request)
    doc = body.get("doc")
    if not isinstance(doc, dict):
        return _refuse("doc is the model as an object")
    try:
        text = ml.to_yaml(doc)
    except ml.LayerError as e:
        return _refuse(str(e))
    try:
        check, infos = await asyncio.gather(_bi(request, "POST", "/models/validate", {"yaml": text}), _infos(request, doc))
    except BackendError as e:
        return _problem(e)
    return _ok({"yaml": text, "check": check, "suggestions": ml.suggestions(doc, infos), "datasets": infos})


@router.post("/bi/model-api/parse")
async def parse(request: Request):
    """``{yaml}`` (the YAML tab, edited by an expert) back to ``{doc}`` for the canvas."""
    body = await json_body(request)
    try:
        doc = ml.load(str(body.get("yaml") or ""))
    except ml.LayerError as e:
        return _refuse(str(e))
    if "model" not in doc:
        return _refuse("a model says `model: <name>`", "DRS-9120")
    return _ok({"doc": doc})


@router.post("/bi/model-api/measure")
async def measure(request: Request):
    """``{alias, column, aggregate?}`` with the model ``doc``: the default measure for that column (name and definition), from its dataset."""
    body = await json_body(request)
    doc, alias, column = body.get("doc") or {}, str(body.get("alias") or ""), str(body.get("column") or "")
    infos = await _infos(request, doc)
    info = infos.get(alias)
    if not info:
        return _refuse(f"no dataset '{alias}' in the model")
    if column in ("", "*"):
        name, m = ml.count_measure(alias)
    else:
        col = next((c for c in info["columns"] if c["name"] == column), None)
        if not col:
            return _refuse(f"'{alias}' has no column '{column}'")
        name, m = ml.default_measure(alias, col, str(body.get("aggregate") or ""))
    return _ok({"name": name, "measure": m})


@router.post("/bi/model-api/new")
async def new_model(request: Request):
    """``{title, workspace?, datasets?: [names], from?: <dataset>}``: a draft model item. From a dataset it is that dataset's implicit model written out
    (its pack dimensions and default measures); else the datasets given, each with its inferred role."""
    body = await json_body(request)
    title = str(body.get("title") or "").strip() or "New model"
    workspace = str(body.get("workspace") or "me")
    try:
        if body.get("from"):
            doc = await _implicit(request, str(body["from"]), title)
        else:
            doc = ml.new_doc(await _free_name(request, ml.slug(title)), title)
            infos = [await _dataset_info(request, str(name)) for name in body.get("datasets") or []]
            referenced = {c["references"] for i in infos for c in i["columns"] if c.get("references")}
            for info in infos:                                  # a dataset another one references is a dimension (spec 2.2), whatever amounts it carries
                alias = info["alias"] if info["alias"] not in doc["datasets"] else info["alias"] + "_2"
                role = "dimension" if ml.kind_of(info) in referenced else info["role"]
                doc["datasets"][alias] = {"dataset": f"{info['name']}@^{(info['version'] or '1').split('.')[0]}", "role": role}
            doc["layout"] = {"positions": {a: [24 + (i % 3) * 270, 24 + (i // 3) * 240] for i, a in enumerate(doc["datasets"])}}
        text = ml.to_yaml(doc)
        it = await _bi(request, "POST", "/workspaces/" + quote(workspace, safe="") + "/items", {"kind": "model", "title": title, "description": "", "body": text})
    except BackendError as e:
        return _problem(e)
    except ml.LayerError as e:
        return _refuse(str(e))
    return _ok({"id": it["id"], "href": "/bi/md/" + it["id"]})


async def _free_name(request: Request, name: str) -> str:
    """A model name nobody the person can see uses yet (``trades``, else ``trades-2``...): a query names a model by name, so two must not share one."""
    try:
        taken = {m.get("name") for m in (await _bi(request, "GET", "/models")).get("models") or []}
    except BackendError:
        return name
    out, n = name, 2
    while out in taken:
        out, n = f"{name}-{n}", n + 1
    return out


async def _implicit(request: Request, name: str, title: str) -> dict:
    info = await _dataset_info(request, name)
    dims = []
    if info["generated"]:
        packs = (await _bi(request, "GET", "/pack-datasets")).get("datasets") or []
        wanted = {c["references"] for c in info["columns"] if c.get("references")}
        for p in packs:
            if p.get("kind") in wanted and p.get("name") != info["name"]:
                try:
                    dims.append(await _dataset_info(request, p["name"]))
                except (BackendError, ml.LayerError):
                    continue
    return ml.implicit_doc(info, dims, name=await _free_name(request, ml.slug(title)), title=title)


# ---- reports on a model -------------------------------------------------------------------------------------------------

@router.post("/bi/model-api/report")
async def new_report(request: Request):
    """``{model | dataset, title, workspace?}``: a draft report bound to one model (a dataset gives its implicit model, ``dataset:<name>``)."""
    body = await json_body(request)
    ref = str(body.get("model") or "")
    if body.get("dataset"):
        ref = "dataset:" + str(body["dataset"])
    if not ref:
        return _refuse("name the model or the dataset the report reads", "DRS-9140")
    title = str(body.get("title") or "").strip() or "New report"
    workspace = str(body.get("workspace") or "me")
    try:
        await _bi(request, "GET", "/models/" + quote(ref, safe="") + "/describe")      # the person may read it, before a draft names it
        it = await _bi(request, "POST", "/workspaces/" + quote(workspace, safe="") + "/items",
                       {"kind": "report", "title": title, "description": "", "body": ml.report_body(ml.slug(title, "report"), title, ref)})
    except BackendError as e:
        return _problem(e)
    return _ok({"id": it["id"], "href": "/bi/design/" + it["id"]})


@router.get("/bi/model-api/fields")
async def fields(request: Request):
    """The Fields pane of a report on a model: the model as the person sees it (masked fields marked), in the designer's dataset shape."""
    ref = request.query_params.get("ref", "")
    if not ref:
        return _refuse("?ref=<model>@<major> or dataset:<name>", "DRS-9140")
    try:
        described = await _bi(request, "GET", "/models/" + quote(ref, safe="") + "/describe")
    except BackendError as e:
        return _problem(e)
    return _ok(ml.fields_shape(described, ref))


@router.post("/bi/model-api/promote")
async def promote(request: Request):
    """*Make it a model* (spec 3, rule 5): ``{report}`` on an implicit model: writes the implicit model out as a draft model item in the report's workspace
    and rebinds the report to it, in one step the report's history can undo (its previous saved state)."""
    body = await json_body(request)
    report = str(body.get("report") or "")
    try:
        item = await _bi(request, "GET", "/items/" + quote(report, safe=":"))
        import re
        m = re.search(r"(?m)^model:\s*dataset:(\S+)", item.get("body") or "")
        if not m:
            return _refuse("this report does not read an implicit model (model: dataset:<name>)", "DRS-9140")
        title = str(body.get("title") or "").strip() or (item.get("title") or "Report") + " model"
        doc = await _implicit(request, m.group(1), title)
        ws = item.get("workspace") or "me"
        made = await _bi(request, "POST", "/workspaces/" + quote(ws, safe="") + "/items", {"kind": "model", "title": title, "description": "Made from " + m.group(1), "body": ml.to_yaml(doc)})
        ref = doc["model"] + "@1"
        new_body = ml.rebind_report(item.get("body") or "", ref)
        saved = await _bi(request, "PUT", "/items/" + quote(report, safe=":"), {"body": new_body, "baseEtag": body.get("etag")} if body.get("etag") else {"body": new_body})
    except BackendError as e:
        return _problem(e)
    except ml.LayerError as e:
        return _refuse(str(e))
    return _ok({"model": made["id"], "ref": ref, "href": "/bi/md/" + made["id"], "report": saved.get("id", report), "etag": saved.get("etag")})


# ---- the new-dataset wizard ---------------------------------------------------------------------------------------------

@router.post("/bi/model-api/dataset-yaml")
async def dataset_yaml(request: Request):
    """``{doc}`` (the wizard's dataset document) to ``{yaml, check}``: the ``rachana: 2`` YAML and the server's Check of it."""
    body = await json_body(request)
    doc = body.get("doc")
    if not isinstance(doc, dict) or not doc:
        return _refuse("doc is the dataset as an object")
    try:
        text = ml.dataset_yaml(doc)
        check = await _bi(request, "POST", "/models/validate", {"yaml": text})
    except ml.LayerError as e:
        return _refuse(str(e))
    except BackendError as e:
        return _problem(e)
    return _ok({"yaml": text, "check": check})
