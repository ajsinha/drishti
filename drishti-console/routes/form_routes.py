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

"""Query by form: ``FORM <pack> <GO>`` opens a pack's search form; submitting it compiles the criteria (on the server) to the ordinary
search grammar, shows that command, and runs it as any search: masks, kinds per role, packs per user and sample visibility apply."""
from __future__ import annotations

import asyncio
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import forms
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, packs, render
from routes.terminal_routes import _shown

router = APIRouter(include_in_schema=False)


@router.get("/form")
async def form_index(request: Request):
    """The old address of the forms home."""
    return RedirectResponse("/forms", status_code=303)


@router.get("/forms")
async def forms_home(request: Request):
    """The forms home: every switched-on pack with a declared ``form:`` section, as far as the person may open it (the server answers
    for the roles, the kinds and the sample-content policy), each kind a link to its form; a filter box, recent forms and saved searches."""
    me, backend = ident(request), request.app.state.backend
    current = [p for p in await packs(request) if p.get("active", True) and p.get("forms")]

    async def one(p):
        try:
            info = await backend.form(p["name"], "", me)
        except BackendError:
            return None                      # no access to any kind, or the pack is not visible to this person: not listed
        kinds = [{"kind": k["kind"], "label": k["label"], "mnemonic": k.get("mnemonic", ""), "fields": p["forms"][k["kind"]]}
                 for k in info["kinds"] if k["kind"] in p["forms"]]
        return {"name": p["name"], "title": p["title"], "description": p.get("description", ""), "kinds": kinds} if kinds else None

    rows = [r for r in await asyncio.gather(*(one(p) for p in current)) if r]
    saved = [s for s in (getattr(request.state, "settings", None) or {}).get("savedSearches") or [] if str(s.get("path", "")).startswith("/form/")]
    return render(request, "terminal/form_index.html", packs=rows, saved=saved, screen="form")


@router.post("/form/save")
async def save(request: Request):
    """Keeps a search (the form's address) under a name, among the person's own settings; the same name replaces."""
    try:
        body = await json_body(request, limit=4096)
    except BodyError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    me = ident(request)
    name, path = str(body.get("name", "")).strip(), str(body.get("path", ""))
    s = await request.app.state.user_settings.get(request.app.state.backend, me)
    kept = [x for x in s.get("savedSearches") or [] if x.get("name", "").lower() != name.lower()]
    if body.get("forget"):
        entry = []
    else:
        entry = [{"name": name, "path": path}]
    try:
        out = await request.app.state.backend.patch_settings({"savedSearches": (entry + kept)[:50]}, me)
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    request.app.state.user_settings.forget(me.user if me else "")
    return JSONResponse({"saved": out.get("savedSearches", [])})


@router.get("/form/{pack}")
async def form_page(request: Request, pack: str, kind: str = ""):
    backend, me = request.app.state.backend, ident(request)
    try:
        info = await backend.form(pack, kind, me)
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="form", id=f"FORM {pack}", error=e)
    params = list(request.query_params.multi_items())
    view = forms.prepare(info["form"], params)
    q = dict(request.query_params)
    out: dict = {"command": None, "data": None, "rows": [], "error": None, "titled": False, "address": None}
    if view["submitted"]:
        criteria = forms.criteria_from(params, info["form"]["fields"])
        order, desc = q.get("order", ""), q.get("desc") in ("1", "on", "true")
        settings = getattr(request.state, "settings", None) or {}
        limit = int(q["limit"]) if q.get("limit", "").isdigit() else int(settings.get("searchLimit") or 100)
        out["address"] = forms.address(pack, info["kind"], criteria, order, desc, limit if q.get("limit") else 0)
        try:
            body = {"criteria": criteria, "orderBy": order or None, "descending": desc, "limit": limit}
            out["command"] = (await backend.form_compile(pack, info["kind"], body, me))["command"]
            data = await backend.search(out["command"], me)
            out["data"] = data
            for r in data.get("rows", []):
                cells = [(_shown(r["values"].get(c)), isinstance(r["values"].get(c), (int, float)) and not isinstance(r["values"].get(c), bool))
                         for c in data.get("columns", [])]
                out["rows"].append({"ref": r["ref"], "title": r.get("title") or r["ref"]["id"], "cells": cells})
            out["titled"] = any(r["title"] != r["ref"]["id"] for r in out["rows"])
        except BackendError as e:
            out["error"] = e
    folded = q.get("form") == "hidden" or (q.get("form") != "shown" and out["data"] is not None and not out["error"]
                                           and bool(request.app.state.settings.get("form.collapse_on_submit", True)))
    reopen = "/form/" + quote(pack) + "?" + urlencode([(k, v) for k, v in params if k != "form"] + [("form", "shown")])
    saved = [s for s in (getattr(request.state, "settings", None) or {}).get("savedSearches") or [] if s.get("path", "").startswith(f"/form/{quote(pack)}")]
    fields = info["form"]["fields"]
    return render(request, "terminal/form.html", info=info, view=view, q=q, limits=forms.LIMITS, presets=forms.PRESETS, saved=saved,
                  sortable=[f for f in fields if not f.get("hidden") and f["type"] in ("text", "number", "amount", "date", "enum", "id")],
                  any_indexed=any(f.get("indexed") for f in fields), screen="form",
                  folded=folded, brief=forms.summary(view), reopen=reopen, collapse=bool(request.app.state.settings.get("form.collapse_on_submit", True)), **out)
