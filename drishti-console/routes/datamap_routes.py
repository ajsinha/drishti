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

"""The data map (``/datamap``, ``DATAMAP [domain|kind] <GO>``; docs/guides/USER_GUIDE.md "The data map"), under Views, for everyone: the
user's packs as domains, their kinds with counts from the lake, and the joins their data contracts declare, each checked against the data.
One page, two views: **Map** (domain regions, kind nodes, join lines; the selected kind's joins lit, a side panel with its join fields
and their coverage, the commands that open it and its contract, guide and WHAT links) and **Cards** (one card per domain; the open one a
table of its kinds, counts, joins and open commands, where its data comes from and the domains it crosses into). Selection, the domain
chips and Find are shared; the view and the selection are in the address. The model is core/datamap.py; ``/datamap/data`` serves it as
JSON for the page to follow while the server is still counting and checking."""
from __future__ import annotations

import asyncio
from pathlib import Path
from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import datamap
from core.backend import BackendError
from routes.common import ident, library, packs, render

router = APIRouter(include_in_schema=False)

CONFIG = Path(__file__).resolve().parent.parent / "config" / "datamap.yaml"


async def model(request: Request) -> dict:
    """The page's model for the signed-in user (core/datamap.py): the server's map, the user's commands, packs and help links."""
    me = ident(request)
    backend = request.app.state.backend
    server, functions, current, lib = await asyncio.gather(backend.datamap(me), _functions(backend, me), packs(request), library(request))
    knowledge = request.app.state.templates.env.globals.get("KNOWLEDGE") or {}
    return datamap.build(server, functions, datamap.load_config(CONFIG), current, lib, getattr(request.app.state, "glossary", None), knowledge,
                         request.app.state.docs_dir)


async def _functions(backend, me) -> dict:
    """The commands the user can use; none (an older server, a failure) only leaves the Open commands out, never the map."""
    try:
        return await backend.functions(me)
    except Exception:                                   # noqa: BLE001
        return {"packs": []}


@router.get("/datamap")
async def page(request: Request, q: str = "", view: str = "", kind: str = "", domain: str = "", chip: str = "", find: str = ""):
    try:
        m = await model(request)
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="datamap", id="", error=e, embed=False)
    if q.strip():                                       # DATAMAP <words>: the kind or domain they name, in the address
        focus = datamap.resolve(m, q)
        params = {k: v for k, v in (("view", view), ("kind", focus.get("kind", "")), ("domain", "" if focus.get("kind") else focus.get("domain", "")),
                                     ("find", "" if focus else q.strip())) if v}
        return RedirectResponse("/datamap" + (f"?{urlencode(params)}" if params else ""), status_code=303)
    kinds = {k["kind"]: k for k in m["kinds"]}
    domains = {d["id"]: d for d in m["domains"]}
    selected = kinds.get(kind)
    open_domain = domains.get(domain) or (domains.get(selected["domain"]) if selected else None)
    view = "cards" if view == "cards" else "map"
    words = " ".join(w for w in (kind or (open_domain["id"] if open_domain and domain else "")).split())
    return render(request, "terminal/datamap.html", m=m, view=view, selected=selected, open_domain=open_domain, chip=chip if chip in domains else "",
                  find=find, screen="datamap", command_text=(f"{datamap.WORD} {words}".strip()))


@router.get("/datamap/data")
async def data(request: Request):
    """The model as JSON: the page asks again while the server is still counting or checking (``state`` not ``ready``)."""
    try:
        return await model(request)
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
