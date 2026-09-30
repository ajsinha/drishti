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

"""The terminal: home, command dispatch (<GO>) and entity views."""
from __future__ import annotations

import re
from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Query, Request
from fastapi.responses import RedirectResponse

from core import asof
from core.backend import BackendError
from routes.common import ident, packs, render

router = APIRouter(include_in_schema=False)



@router.get("/t")
async def home(request: Request, error: str | None = None):
    current = await packs(request)
    examples = [(cmd, what, p["title"]) for p in current for cmd, what in p["examples"]]
    return render(request, "terminal/home.html", examples=examples, packs=current, error=error)


_SEARCH = re.compile(r"(?is)^\s*\S+\s+(where|order\s+by|limit)\s+.+")


@router.get("/go")
async def go(request: Request, q: str = ""):
    if _SEARCH.match(q.replace("<GO>", "")):
        return RedirectResponse(f"/s?q={quote(q.replace('<GO>', '').strip())}", status_code=303)
    try:
        r = await request.app.state.backend.command(q, ident(request))
    except BackendError as e:
        return RedirectResponse(f"/t?error={quote(e.detail)}", status_code=303)
    return RedirectResponse(f"/v/{r['ref']['kind']}/{quote(r['ref']['id'])}", status_code=303)


@router.get("/impact/{kind}/{id_}")
async def impact(request: Request, kind: str, id_: str):
    try:
        data = await request.app.state.backend.impact(kind, id_, ident(request))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.status if e.status < 500 else 502, kind=kind, id=id_, error=e)
    return render(request, "terminal/impact.html", kind=kind, id=id_, data=data, screen="impact")


def _shown(v) -> str:
    """A diff value as text: numbers with separators (up to six decimals), lists and objects compactly."""
    if v is None:
        return "—"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        text = f"{v:,.6f}".rstrip("0").rstrip(".") if isinstance(v, float) else f"{v:,}"
        return text.replace("-", "−")
    if isinstance(v, (list, dict)):
        return "[]" if v == [] else str(v)[:80]
    return str(v)


def _delta(d) -> str:
    if d is None:
        return ""
    text = f"{d:+,.6f}".rstrip("0").rstrip(".")
    return text.replace("-", "−")


@router.get("/s")
async def search(request: Request, q: str = ""):
    """Structured search (W17): entities by field values, e.g. TRD where mtm > 1m and currency = 'EUR' order by mtm desc."""
    data, error = None, None
    if q.strip():
        try:
            data = await request.app.state.backend.search(q, ident(request))
        except BackendError as e:
            error = e
    rows = []
    if data:
        for r in data.get("rows", []):
            rows.append({"ref": r["ref"], "title": r.get("title") or r["ref"]["id"],
                         "cells": [(_shown(r["values"].get(c)), isinstance(r["values"].get(c), (int, float)) and not isinstance(r["values"].get(c), bool))
                                   for c in data.get("columns", [])]})
    titled = any(r["title"] != r["ref"]["id"] for r in rows)       # a title that only repeats the id is left out
    return render(request, "terminal/search.html", q=q, data=data, rows=rows, error=error, titled=titled, screen="search")


@router.post("/s/watch")
async def watch_search(request: Request):
    """Saves a search's first 50 results as a monitor: the results, watched live."""
    form = parse_qs((await request.body()).decode("utf-8", "replace"))   # a plain form post: no multipart dependency
    q = form.get("q", [""])[0]
    name = re.sub(r"[^A-Za-z0-9 ._-]", "", form.get("name", [""])[0])[:64].strip() or "Search results"
    backend = request.app.state.backend
    try:
        data = await backend.search(q, ident(request))
        entities = [{"kind": r["ref"]["kind"], "id": r["ref"]["id"]} for r in data.get("rows", [])[:50]]
        if not entities:
            return RedirectResponse(f"/s?q={quote(q)}", status_code=303)
        await backend.mine("PUT", f"/monitors/{quote(name)}", ident(request), {"entities": entities})
    except BackendError as e:
        return render(request, "terminal/search.html", q=q, data=None, rows=[], error=e, titled=False, screen="search")
    return RedirectResponse(f"/m/{quote(name)}", status_code=303)


@router.get("/compare/{kind}/{id_}")
async def compare(request: Request, kind: str, id_: str, from_: str = Query("", alias="from"), to: str = "",
                  fromKnownAt: str = "", only: str = ""):
    """History (W16): what changed in an entity between two business dates, or since what was known at a time."""
    backend = request.app.state.backend
    info = request.state.business_date or {}
    params = {"from": from_, "to": to}
    if fromKnownAt:
        params["fromKnownAt"] = asof.to_instant(fromKnownAt, info.get("zone") or "America/New_York") or ""
    try:
        data = await backend.history_diff(kind, id_, ident(request), **params)
    except BackendError as e:
        if e.status == 404 or e.code in ("DRS-4003", "DRS-1001"):
            return render(request, "terminal/compare.html", kind=kind, id=id_, data=None, error=e, form=params, screen="compare",
                          from_known=fromKnownAt)
        return render(request, "terminal/missing.html", status_code=e.status if e.status < 500 else 502, kind=kind, id=id_, error=e)
    rows = [dict(c, before_text=_shown(c.get("before")), after_text=_shown(c.get("after")), delta_text=_delta(c.get("delta")))
            for c in data.get("changes", []) if not only or c.get("kind") == only]
    return render(request, "terminal/compare.html", kind=kind, id=id_, data=data, rows=rows, error=None, only=only, screen="compare",
                  form={"from": from_ or (data.get("from") or {}).get("businessDate") or "", "to": to or (data.get("to") or {}).get("businessDate") or ""},
                  from_known=fromKnownAt)


@router.get("/v/{kind}/{id_}")
async def view(request: Request, kind: str, id_: str, embed: int = 0):
    try:
        vm = await request.app.state.backend.view(kind, id_, ident(request))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.status if e.status < 500 else 502,
                      kind=kind, id=id_, error=e, embed=bool(embed))
    main = [p for p in vm["panels"] if p.get("area") != "right"]
    right = [p for p in vm["panels"] if p.get("area") == "right"]
    return render(request, "terminal/view.html", vm=vm, main=main, right=right, embed=bool(embed))
