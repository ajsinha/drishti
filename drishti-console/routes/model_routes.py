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

"""The model layer's pages (docs/architecture/ARCHITECTURE.md §28 › The model layer: the specification of wave W1, section 9; docs/guides/REPORTS_GUIDE.md "Models"): *Start from your data* (the pack-generated
datasets and the models), one dataset's page (grain, keys, meanings, time mode), the model designer, and the new-dataset wizard for what a pack does not
give (a connector query, a Python table, a model's answer, a narrowed pack dataset).

The pages carry no data; their scripts ask ``/bi/model-api/...`` here, which reads the server as the signed-in person (``/api/v1/bi/pack-datasets``,
``/models/{id}/describe``, ``/models/validate``, ``/items``) and adds only shape: YAML written from the designer's document and read back, relationships
suggested from the packs' references, default measures, the Fields pane of a report on a model. The server checks every model and decides every access."""
from __future__ import annotations

import asyncio
import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

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


# The model designer and the dataset wizards are gone: tables and models are written in Build › Tables & models (docs/architecture/SHARED_SCREENS.md), one way per job.
# Their old addresses lead there.

@router.get("/bi/md/{item:path}")
async def designer_page(request: Request, item: str):
    """The model designer's old address: the model in Build › Tables & models."""
    return RedirectResponse("/build/tables?kind=model&item=" + quote(item, safe=":"), status_code=301)


@router.get("/bi/datasets/new-layer")
@router.get("/bi/datasets/new")
async def dataset_wizard_page(request: Request):
    """The dataset wizards' old addresses: a new table in Build › Tables & models (``?edit=<item>`` opens that table)."""
    edit = request.query_params.get("edit", "")
    return RedirectResponse("/build/tables?kind=table&item=" + quote(edit, safe=":") if edit else "/build/tables?new=table", status_code=301)


# ---- datasets -----------------------------------------------------------------------------------------------------------

async def _dataset_info(request: Request, name: str) -> dict:
    """A dataset by name: a pack-generated one (read-only) or a ``rachana: 2`` item the person may read (by item id or name)."""
    if name.startswith(ml.PACK_PREFIX):
        got = await _bi(request, "GET", "/pack-datasets/" + quote(name, safe=""))
        return ml.dataset_info(got.get("yaml") or "", read_only=True)
    item = await _bi(request, "GET", "/items/" + quote(name, safe=":"))
    body = item.get("body") or ""
    if "extends:" in body or re.search(r"^table:", body, flags=re.M):   # a narrowed dataset, or a table of the table layer: as the server resolves it
        try:
            body = (await _bi(request, "GET", "/layer-datasets/" + quote(item.get("id", name), safe=":"))).get("resolved") or body
        except BackendError:
            pass
    info = ml.dataset_info(body, read_only=not ((item.get("can") or {}).get("edit")), item=item.get("id", ""))
    info["status"] = item.get("status")
    return info


@router.get("/bi/model-api/datasets")
async def datasets(request: Request):
    """Every dataset a model may use, as the person sees them: the packs' (certified, read-only) first, then published ``rachana: 2`` items; and the models."""
    try:
        packs, models = await asyncio.gather(_bi(request, "GET", "/pack-datasets"), _bi(request, "GET", "/models"))
    except BackendError as e:
        return _problem(e)
    items = [m for m in models.get("models") or [] if m.get("kind") == "implicit"]
    return _ok({"packs": packs.get("datasets") or [], "items": items, "models": [m for m in models.get("models") or [] if m.get("kind") in ("model", "compat")]})


@router.get("/bi/model-api/roles")
async def roles(request: Request):
    """The roles a security rule may name, for the designer's role picker: the server's roles list when it has one (``GET /models/roles``), else the roles the signed-in
    identity carries (the wildcard left out). ``source`` says which, so the page can say that other roles exist."""
    try:
        got = await _bi(request, "GET", "/models/roles")
        names = sorted({str(r.get("name") if isinstance(r, dict) else r) for r in (got.get("roles") if isinstance(got, dict) else got) or []})
        if names:
            return _ok({"roles": names, "source": "server"})
    except BackendError:
        pass                                                      # an older server has no roles list: the identity's own roles are what the console knows
    mine = sorted(r for r in getattr(ident(request), "roles", ()) if r and r != "*")
    return _ok({"roles": mine, "source": "identity"})


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


@router.post("/bi/model-api/dataset-refresh")
async def dataset_refresh(request: Request):
    """``{item, refresh}``: writes ``refresh:`` (a schedule in words) into a new saved state of a dataset item's draft, every other line, comment and key
    order kept (the dataset page's Set a schedule). The server checks the text and who may save it."""
    body = await json_body(request)
    item, words = str(body.get("item") or ""), str(body.get("refresh") or "").strip()
    if not item or not words or len(words) > 120:
        return _refuse("send {item, refresh}: the dataset item and a schedule in words (daily 06:30, business-days 07:00, hourly, after-load)")
    try:
        got = await _bi(request, "GET", "/items/" + quote(item, safe=":"))
        original = got.get("body") or ""
        doc = ml.load(original)
        if not isinstance(doc, dict) or doc.get("rachana") != 2 or "dataset" not in doc:
            return _refuse("not a dataset of the model layer (rachana: 2 with dataset:)")
        old = doc.get("refresh")
        new = {"schedule": words, "on-load": True} if isinstance(old, dict) and old.get("on-load") and words != "after-load" else words   # a rebuild on load is kept
        text = ml.to_yaml_keeping(original, {**doc, "refresh": new}, ml.DATASET_ORDER)
        saved = await _bi(request, "PUT", "/items/" + quote(item, safe=":"), {"body": text, "message": "Set the refresh schedule: " + words})
    except BackendError as e:
        return _problem(e)
    return _ok({"item": item, "refresh": words, "status": (saved or {}).get("status")})


# ---- the model document -------------------------------------------------------------------------------------------------


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


@router.post("/bi/model-api/report-measures")
async def report_measures(request: Request):
    """``{yaml}`` of a report to ``{measures}``: its own measures (``measures:``, spec 4.2) as ``{name: {expr, format?, description?}}``, which the designer and the
    report page send along as the model query's ``define``. Shape only: the server compiles each one (as the reader) when it is asked."""
    body = await json_body(request)
    try:
        text = str(body.get("yaml") or "")
        return _ok({"measures": ml.report_measures(text), "perspective": str(ml.load(text).get("perspective") or "")})
    except ml.LayerError as e:
        return _refuse(str(e))


@router.get("/bi/model-api/fields")
async def fields(request: Request):
    """The Fields pane of a report on a model: the model as the person sees it (masked fields marked), in the designer's dataset shape."""
    ref = request.query_params.get("ref", "")
    if not ref:
        return _refuse("?ref=<model>@<major> or dataset:<name>", "DRS-9140")
    perspective = request.query_params.get("perspective", "")
    try:
        described = await _bi(request, "GET", "/models/" + quote(ref, safe="") + "/describe", **({"perspective": perspective} if perspective else {}))
    except BackendError as e:
        return _problem(e)
    doc = None
    if not ref.startswith("dataset:") and ml.needs_model_doc(described):                  # an older server: the properties come from the model's YAML
        try:
            listing = (await _bi(request, "GET", "/models")).get("models") or []
            hit = next((m for m in listing if m.get("kind") == "model" and m.get("name") == ref.split("@", 1)[0]), None)
            if hit:
                doc = ml.load((await _bi(request, "GET", "/items/" + quote(str(hit["id"]), safe=":"))).get("body") or "")
        except (BackendError, ml.LayerError):
            doc = None
    shape = ml.fields_shape(described, ref, doc)
    await _entities(request, shape)
    return _ok(shape)


async def _entities(request: Request, shape: dict) -> None:
    """Each table's entity kind (the pack kind its dataset reads: ``trade``), so a key column of a table visual opens that entity in the terminal. A pack
    dataset says it in its name; another dataset in its source (read once each; one that cannot be read keeps no entity)."""
    for t in shape.get("tables") or []:
        name = str(t.get("dataset") or "")
        if name.startswith(ml.PACK_PREFIX):
            t["entity"] = name.rsplit(".", 1)[-1]
            continue
        try:
            got = await _bi(request, "GET", "/layer-datasets/" + quote(name if ":" in name else "file:dataset:" + name, safe=":"))
            src = (ml.load(got.get("yaml") or "") or {}).get("source")
            if isinstance(src, dict) and src.get("kind"):
                t["entity"] = str(src["kind"])
        except (BackendError, ml.LayerError):
            pass


@router.get("/bi/model-api/consumers")
async def consumers(request: Request):
    """Who uses a model (reports, boards, alerts, subscriptions, notebooks), from the server's consumers list (``GET /models/{ref}/consumers``). A report references a model,
    it never owns it: many consumers share one. ``known`` is false when the server cannot say yet (an older server): the pages then say nothing rather than a wrong count."""
    ref = request.query_params.get("ref", "")
    if not ref:
        return _refuse("?ref=<model>@<major> or dataset:<name>", "DRS-9140")
    try:
        # the server takes a model item id, <name>@<major> or dataset:<name>
        got = await _bi(request, "GET", "/models/" + quote(ref, safe="") + "/consumers")
    except BackendError:
        return _ok({"known": False, "consumers": []})
    rows = got.get("consumers") if isinstance(got, dict) else got
    out = []
    for c in rows or []:
        if isinstance(c, dict):
            kind, cid = str(c.get("kind") or c.get("type") or "item"), str(c.get("id") or "")
            href = {"report": "/bi/r/", "board": "/bi/r/", "notebook": "/bi/i/", "alert": "/alerts/measure/", "subscription": "/reports"}.get(kind, "/bi/i/")      # a board is a report (2.4.0)
            out.append({"kind": kind, "id": cid, "title": str(c.get("title") or c.get("name") or cid), "href": href + (quote(cid, safe=":") if kind != "subscription" else "")})
    return _ok({"known": True, "consumers": out})


@router.post("/bi/model-api/rebind")
async def rebind(request: Request):
    """``{ref, reports: [id...]}``: points each report (one the person may edit) at the model ``ref``, as *Make it a model* does for the first. Reports are only
    re-pointed: nothing is copied and the model stays shared."""
    body = await json_body(request)
    ref, ids = str(body.get("ref") or ""), [str(x) for x in body.get("reports") or []][:50]
    if not ref or not ids:
        return _refuse("send {ref, reports: [report ids]}", "DRS-9140")
    done, failed = [], []
    for rid in ids:
        try:
            item = await _bi(request, "GET", "/items/" + quote(rid, safe=":"))
            await _bi(request, "PUT", "/items/" + quote(rid, safe=":"), {"body": ml.rebind_report(item.get("body") or "", ref)})
            done.append(rid)
        except BackendError as e:
            failed.append({"id": rid, "detail": e.detail})
    return _ok({"rebound": done, "failed": failed})


@router.post("/bi/model-api/promote")
async def promote(request: Request):
    """*Make it a model* (spec 3, rule 5): ``{report}`` on an implicit model: writes the implicit model out as a draft model item in the report's workspace
    and rebinds the report to it, in one step the report's history can undo (its previous saved state)."""
    body = await json_body(request)
    report = str(body.get("report") or "")
    try:
        item = await _bi(request, "GET", "/items/" + quote(report, safe=":"))
        source = ml.implicit_source(item.get("body") or "")
        if not source:
            return _refuse("this report already reads a model of its own (or none): Make it a model only writes out the implicit model of a report made "
                           "straight from a dataset (model: dataset:<name>). Open the model it reads to change it.", "DRS-9140")
        title = str(body.get("title") or "").strip() or (item.get("title") or "Report") + " model"
        ws = item.get("workspace") or "me"
        # a model is shared, never a per-report copy: a model already written out from this dataset in the report's workspace is reused
        listing = (await _bi(request, "GET", "/models")).get("models") or []
        same = next((m for m in listing if m.get("kind") == "model" and m.get("implicitFrom") == source
                     and m.get("workspace") == ws), None)
        if same:
            made, ref, reused = {"id": same["id"]}, f"{same['name']}@{(str(same.get('version') or '1').split('.')[0])}", True
        else:
            doc = await _implicit(request, source, title)
            made = await _bi(request, "POST", "/workspaces/" + quote(ws, safe="") + "/items", {"kind": "model", "title": title, "description": "Made from " + source, "body": ml.to_yaml(doc)})
            ref, reused = doc["model"] + "@1", False
        new_body = ml.rebind_report(item.get("body") or "", ref)
        saved = await _bi(request, "PUT", "/items/" + quote(report, safe=":"), {"body": new_body, "baseEtag": body.get("etag")} if body.get("etag") else {"body": new_body})
        try:                                                        # the other reports on the same dataset: offered to move onto the model too
            used = await _bi(request, "GET", "/models/" + quote("dataset:" + source, safe="") + "/consumers")
            others = [c for c in used.get("consumers") or [] if c.get("kind") == "report" and c.get("id") != report]
        except BackendError:
            others = []
    except BackendError as e:
        return _problem(e)
    except ml.LayerError as e:
        return _refuse(str(e))
    return _ok({"dataset": source, "model": made["id"], "ref": ref, "reused": reused, "href": "/bi/md/" + made["id"], "report": saved.get("id", report),
                "etag": saved.get("etag"), "others": others})



