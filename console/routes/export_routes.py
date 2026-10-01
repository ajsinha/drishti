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


"""Export (W18): CSV downloads of a panel, a search and a comparison, and the entity's JSON. Each export reads through
the server like the screen does, so it carries the same business date, "known at" time, entitlements and redaction."""
from __future__ import annotations

import json

from fastapi import APIRouter, Request
from fastapi.responses import Response

from core import asof
from core.backend import BackendError
from core.export import filename, grid, panel_rows, plain, to_csv, to_xlsx
from routes.common import ident

router = APIRouter(include_in_schema=False)


def _csv(body: bytes, name: str) -> Response:
    return Response(body, media_type="text/csv; charset=utf-8", headers={"Content-Disposition": f'attachment; filename="{name}"'})


def _problem(e: BackendError) -> Response:
    return Response(f"{e.code} {e.detail}\n", status_code=e.status if e.status < 500 else 502, media_type="text/plain; charset=utf-8")


def _date() -> str:
    return "" if asof.current() == "live" else asof.current()


# the specific routes first: /export/compare/... would otherwise match the panel route's pattern
@router.get("/export/search.csv")
async def search_csv(request: Request, q: str = ""):
    try:
        data = await request.app.state.backend.search(q, ident(request))
    except BackendError as e:
        return _problem(e)
    cols = data.get("columns", [])
    labels = data.get("labels", {})
    header = [data.get("mnemonic") or data.get("kind"), "Title"] + [labels.get(c, c) for c in cols]
    rows = [[r["ref"]["id"], r.get("title") or ""] + [r["values"].get(c) for c in cols] for r in data.get("rows", [])]
    return _csv(to_csv(header, rows), filename(data.get("kind", "search"), "search", _date()))


async def _grid(request: Request):
    try:
        body = json.loads(await request.body() or b"{}")
    except ValueError:
        body = {}
    return grid(body if isinstance(body, dict) else {})


@router.post("/export/grid.csv")
async def grid_csv(request: Request):
    """A grid the page computed (the Pivot tab, as shown): ``{"name", "header", "rows"}`` as CSV. Text a spreadsheet would
    run as a formula is prefixed so it stays text."""
    name, header, rows = await _grid(request)
    safe = [[("'" + v) if isinstance(v, str) and v[:1] in ("=", "+", "-", "@") and plain(v) == v else v for v in r] for r in rows]
    return _csv(to_csv(header, safe), filename(name, _date()))


@router.post("/export/grid.xlsx")
async def grid_xlsx(request: Request):
    """The same grid as an Excel workbook: one sheet, a bold frozen header row, numbers as numbers."""
    name, header, rows = await _grid(request)
    file = filename(name, _date())[:-4] + ".xlsx"
    return Response(to_xlsx(header, rows), media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    headers={"Content-Disposition": f'attachment; filename="{file}"'})


@router.get("/export/compare/{kind}/{id_}.csv")
async def compare_csv(request: Request, kind: str, id_: str):
    params = {k: v for k, v in request.query_params.items() if k in ("from", "to", "fromKnownAt", "toKnownAt") and v}
    if params.get("fromKnownAt"):
        info = request.state.business_date or {}
        params["fromKnownAt"] = asof.to_instant(params["fromKnownAt"], info.get("zone") or "America/New_York") or ""
    try:
        d = await request.app.state.backend.history_diff(kind, id_, ident(request), **params)
    except BackendError as e:
        return _problem(e)
    f, t = (d.get("from") or {}).get("businessDate", ""), (d.get("to") or {}).get("businessDate", "")
    rows = [[c.get("label"), c.get("path"), c.get("kind"), c.get("before"), c.get("after"), c.get("delta")] for c in d.get("changes", [])]
    return _csv(to_csv(["Field", "Path", "Change", f, t, "Difference"], rows), filename(id_, "changes", f, t))


@router.get("/export/{kind}/{id_}/{panel}.csv")
async def panel_csv(request: Request, kind: str, id_: str, panel: str):
    try:
        vm = await request.app.state.backend.view(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)
    p = next((x for x in vm.get("panels", []) if x.get("id") == panel), None)
    if p is None:
        return Response(f"no panel '{panel}' in this view\n", status_code=404, media_type="text/plain; charset=utf-8")
    header, rows = panel_rows(p)
    if not header and not rows:
        return Response("this panel has nothing to export\n", status_code=404, media_type="text/plain; charset=utf-8")
    return _csv(to_csv(header, rows), filename(id_, panel, _date()))


@router.get("/export/{kind}/{id_}.json")
async def entity_json(request: Request, kind: str, id_: str):
    try:
        raw = await request.app.state.backend.raw(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)
    name = filename(id_, _date())[:-4] + ".json"
    return Response(json.dumps(raw, indent=2, ensure_ascii=False).encode("utf-8"), media_type="application/json",
                    headers={"Content-Disposition": f'attachment; filename="{name}"'})
