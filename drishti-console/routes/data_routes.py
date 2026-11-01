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

"""The Data view (``/data``, ``DATA [dataset] [date] <GO>``; docs/guides/DATA_GUIDE.md "The Data view", USER_GUIDE.md "The Data view").

Download only, never a viewer. On the left every dataset the person may open (by source, with its format and the dates it holds; internal-only
ones marked); on the right the chosen dataset's facts, a calendar of its dates, the simple selection (columns; filter lines joined by AND,
deliberately not SQL), the selection read back in words and as a SQL-like line, the row count before download, Download, Copy link and the
CLI equivalent. The selection lives in the address (``kind``, ``date``, ``columns``, each ``where``), so a link is the selection.

The server does the work (/api/v1/data: entitlements, masks, the edition's internal-only rule, the audit); this module draws the page, relays
the JSON the page asks for, and relays the download as it streams (``/data/<kind>/download``), never holding it whole.
"""
from __future__ import annotations

import re
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, StreamingResponse
from starlette.background import BackgroundTask

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "DATA"
_KIND = re.compile(r"^[a-z0-9][a-z0-9._-]{0,80}$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _setting(request: Request, key: str, default: float) -> float:
    try:
        return float(request.app.state.settings.get(f"data_view.{key}", default) or default)
    except (TypeError, ValueError):
        return default


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


def selection(request: Request) -> dict:
    """The selection as the address carries it: ``date``, ``columns`` (comma-separated) and each ``where`` line."""
    q = request.query_params
    return {"date": (q.get("date") or "").strip(), "columns": (q.get("columns") or "").strip(),
            "where": [w for w in q.getlist("where") if w.strip()]}


def resolve(datasets: list[dict], words: str) -> tuple[str, str]:
    """``DATA <dataset> [date]``: the dataset its first word names (its kind or its command, any case) and the date, if one follows."""
    parts = words.split()
    if not parts:
        return "", ""
    first = parts[0].lower()
    hit = next((d for d in datasets if d.get("kind", "").lower() == first or (d.get("code") or "").lower() == first), None)
    date = next((p for p in parts[1:] if _DATE.match(p)), "")
    return (hit["kind"] if hit else ""), date


@router.get("/data")
async def page(request: Request, q: str = "", kind: str = "", find: str = ""):
    me, backend = ident(request), request.app.state.backend
    try:
        listed = await backend.data_datasets(me, read_timeout=_setting(request, "list_timeout_seconds", 30))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="data", id="", error=e, embed=False)
    datasets = listed.get("datasets") or []
    if q.strip():                                           # DATA <dataset> [date]: the dataset and date in the address
        k, d = resolve(datasets, q)
        params = {"kind": k, "date": d} if k else {"find": q.strip()}
        return RedirectResponse("/data?" + urlencode({a: b for a, b in params.items() if b}), status_code=303)
    chosen, error = None, request.query_params.get("error", "")
    if kind:
        try:
            chosen = await backend.data_dataset(kind, me)
            chosen["contract"] = await _contract_link(request, chosen)
        except BackendError as e:
            error = f"{e.code} {e.detail}"
    groups: dict[str, list] = {}
    for d in datasets:
        groups.setdefault(d.get("pack") or "", []).append(d)
    sel = selection(request)
    words = " ".join(w for w in (kind, sel["date"]) if w)
    return render(request, "terminal/data.html", datasets=datasets, groups=groups, chosen=chosen, kind=kind, sel=sel, find=find, error=error,
                  refused_all=bool(listed.get("internalOnlyRefused")), screen="data", command_text=f"{WORD} {words}".strip())


async def _contract_link(request: Request, d: dict) -> str:
    """The help page of the dataset's data contract (its command's own page, else its section of the pack's contract page), or ""."""
    from pathlib import Path

    from core.datamap import _contract_anchor
    from routes.common import library
    docs = getattr(request.app.state, "docs_dir", None)
    if not docs:
        return ""
    by_file = getattr(await library(request), "by_file", {}) or {}
    own = Path(docs) / "contracts" / f"{d.get('code') or ''}.md"
    page = Path(docs) / "contracts" / "packs" / f"{d.get('pack') or ''}.md"
    if d.get("code") and own.is_file() and by_file.get(own.resolve()):
        return f"/help/{by_file[own.resolve()]}"
    if page.is_file() and by_file.get(page.resolve()):
        anchor = _contract_anchor(page, d.get("kind") or "")
        return f"/help/{by_file[page.resolve()]}" + (f"#{anchor}" if anchor else "")
    return ""


@router.get("/api/data/{kind}")
async def dataset(request: Request, kind: str):
    if not _KIND.match(kind):
        return JSONResponse({"code": "DRS-5001", "detail": "not a dataset name"}, status_code=400)
    try:
        return await request.app.state.backend.data_dataset(kind, ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/api/data/{kind}/count")
async def count(request: Request, kind: str):
    """The selection's row count, size and read-back: the page asks again (debounced) whenever the selection changes."""
    if not _KIND.match(kind):
        return JSONResponse({"code": "DRS-5001", "detail": "not a dataset name"}, status_code=400)
    s = selection(request)
    try:
        return await request.app.state.backend.data_count(kind, ident(request), s["date"], s["columns"], s["where"],
                                                          read_timeout=_setting(request, "count_timeout_seconds", 90))
    except BackendError as e:
        return _problem(e)


@router.get("/data/{kind}/download")
async def download(request: Request, kind: str):
    """The selected rows as ``<kind>_<date>.jsonl.gz``, relayed as the server streams them (never held whole here)."""
    if not _KIND.match(kind):
        return JSONResponse({"code": "DRS-5001", "detail": "not a dataset name"}, status_code=400)
    s = selection(request)
    params = [(k, v) for k, v in (("date", s["date"]), ("columns", s["columns"])) if v] + [("where", w) for w in s["where"]]
    path = f"/data/{quote(kind, safe='')}/rows" + (f"?{urlencode(params)}" if params else "")
    try:
        upstream = await request.app.state.backend.open_download(path, ident(request), read_timeout=_setting(request, "download_idle_seconds", 300))
    except BackendError as e:
        back = [("kind", kind)] + params + [("error", f"{e.code} {e.detail}")]
        return RedirectResponse("/data?" + urlencode(back), status_code=303)
    name = f"{kind}_{s['date'] or 'latest'}.jsonl.gz"
    disposition = upstream.headers.get("content-disposition") or f'attachment; filename="{name}"'
    return StreamingResponse(upstream.aiter_raw(), media_type="application/gzip", background=BackgroundTask(upstream.aclose),
                             headers={"Content-Disposition": disposition, "Cache-Control": "no-store"})
