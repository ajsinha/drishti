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

"""History queries (HISTORY_QUERIES.md): ``TRD 1000015 30 <GO>`` opens ``/v/trade/1000015/history?days=30``, one row per business
day the entity has data, newest first, with what changed marked; a market kind adds a chart and its headline numbers. The page,
its exports (CSV, JSON, Excel) and the short link ``/v/{kind}/{id}?days=30`` live here. This router is included before the
terminal's and the export router's, so its routes win over ``/v/{kind}/{id}`` and ``/export/{kind}/{id}.json``."""
from __future__ import annotations

import json
import re
from datetime import date
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import Response

from core.backend import BackendError, drs_message
from core.export import filename, to_csv, to_xlsx
from core.history_rows import build_page, export_rows, range_label
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

#: the query parameters a history page and its exports pass to the server
ASKED = ("days", "range", "from", "to", "fields", "knownAt", "order")
#: the parameters that make a link to an entity a history link (``/v/trade/1000015?days=30``)
RANGE_KEYS = ("days", "range", "from")


def asked(query) -> dict:
    """The server's parameters from a page's query: the range, the fields, and ``diff=true`` for ``diff=1``."""
    out = {k: query.get(k, "") for k in ASKED if query.get(k)}
    if query.get("diff") in ("1", "true", "on"):
        out["diff"] = "true"
    return out


def page_query(params: dict, **change) -> str:
    """The page's own query for ``params`` with some keys changed (None removes one), in a stable order."""
    p = {k: v for k, v in params.items() if v}
    if "diff" in p:
        p["diff"] = "1"
    for k, v in change.items():
        k = "from" if k == "from_" else k
        if v is None:
            p.pop(k, None)
        else:
            p[k] = v
    return urlencode(p)


_SPAN = re.compile(r"its days with data run from (\d{4}-\d{2}-\d{2}) to (\d{4}-\d{2}-\d{2})|its only day with data is (\d{4}-\d{2}-\d{2})")


def data_span(e: BackendError) -> tuple[str, str] | None:
    """The days an entity has data, as DRS-1013 names them (*its days with data run from A to B*, *its only day with data is A*), so
    the page can link to them (QA1-11); None for any other problem."""
    if e.code != "DRS-1013":
        return None
    m = _SPAN.search(e.detail or "")
    if not m:
        return None
    return (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(3))


def _shown_problem(e: BackendError) -> Response:
    return Response(drs_message(e.code, e.detail, " ") + "\n", status_code=e.page_status, media_type="text/plain; charset=utf-8")


@router.get("/v/{kind}/{id_:path}/history")
async def history(request: Request, kind: str, id_: str):
    """The entity over time: the document shape (a table of its top-level fields, a row per business day) or the market shape (a
    chart, the headline numbers, the same table)."""
    params = asked(request.query_params)
    if not any(k in params for k in RANGE_KEYS):
        params["days"] = "30"                      # a link with no range: the server's usual 30 days
    try:
        data = await request.app.state.backend.history(kind, id_, ident(request), **params)
    except BackendError as e:
        return render(request, "terminal/history.html", status_code=e.page_status, kind=kind, id=id_, error=e, page=None,
                      params=params, screen="history", today=f"/v/{quote(kind)}/{quote(id_)}", data_span=data_span(e))
    page = build_page(data, kind, id_, known_at=getattr(request.state, "known_at", None))
    base = str(request.base_url).rstrip("/")
    to = params.get("to") or (data.get("window") or {}).get("to") or ""
    only = request.query_params.get("only") == "1"
    share = f"{base}/v/{quote(kind)}/{quote(id_)}/history?" + page_query(params, to=to or None) + ("&only=1" if only else "")   # pinned: the same rows a week later
    q = page_query(params)
    return render(request, "terminal/history.html", kind=kind, id=id_, error=None, page=page, params=params, screen="history",
                  share=share, q=q, today=f"/v/{quote(kind)}/{quote(id_)}",
                  diff_href=f"?{page_query(params, diff=None if 'diff' in params else '1')}",
                  ranges=[(label, f"?{page_query(params, **{**{'days': None, 'range': None, 'from_': None, 'to': None}, key: value})}",
                           params.get(key) == value) for label, key, value in page["ranges"]],
                  range_text=range_label(params), only=only)


async def _data(request: Request, kind: str, id_: str):
    params = asked(request.query_params)
    if not any(k in params for k in RANGE_KEYS):
        params["days"] = "30"
    return await request.app.state.backend.history(kind, id_, ident(request), **params)


def _name(id_: str, data: dict, ext: str) -> str:
    w = data.get("window") or {}
    return filename(id_, "history", w.get("from") or "", w.get("to") or "")[:-4] + ext


@router.get("/export/history/{kind}/{id_:path}.csv")
async def history_csv(request: Request, kind: str, id_: str):
    """The table as shown, newest first: the date, each column (numbers as numbers), each part's count, what changed. Text a
    spreadsheet would run as a formula is prefixed so it stays text; masked values are the server's masks."""
    try:
        data = await _data(request, kind, id_)
    except BackendError as e:
        return _shown_problem(e)
    header, rows = export_rows(data, only_changed=request.query_params.get("only") == "1")
    safe = [[("'" + v) if isinstance(v, str) and v[:1] in ("=", "+", "-", "@") else v for v in r] for r in rows]
    return Response(to_csv(header, safe), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": f'attachment; filename="{_name(id_, data, ".csv")}"'})


@router.get("/export/history/{kind}/{id_:path}.xlsx")
async def history_xlsx(request: Request, kind: str, id_: str):
    """The same table as an Excel workbook: one sheet, a bold frozen header row, numbers as numbers."""
    try:
        data = await _data(request, kind, id_)
    except BackendError as e:
        return _shown_problem(e)
    header, rows = export_rows(data, only_changed=request.query_params.get("only") == "1")
    rows = [[_day(r[0])] + r[1:] for r in rows]                     # the dates as date cells, so they sort and filter as dates (QA1-17)
    return Response(to_xlsx(header, rows, sheet="History"), media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    headers={"Content-Disposition": f'attachment; filename="{_name(id_, data, ".xlsx")}"'})


def _day(text):
    try:
        return date.fromisoformat(text)
    except (TypeError, ValueError):
        return text


@router.get("/export/history/{kind}/{id_:path}.json")
async def history_json(request: Request, kind: str, id_: str):
    """The server's answer as served (section 8 of HISTORY_QUERIES.md: rows oldest first), masks applied; with ``only=1`` its rows are the
    changed days only, as the CSV and Excel files are (QA1-17)."""
    try:
        data = await _data(request, kind, id_)
    except BackendError as e:
        return _shown_problem(e)
    if request.query_params.get("only") == "1" and isinstance(data.get("rows"), list):
        data = {**data, "rows": [r for r in data["rows"] if r.get("changed")], "onlyChanged": True}
    return Response(json.dumps(data, indent=2, ensure_ascii=False).encode("utf-8"), media_type="application/json",
                    headers={"Content-Disposition": f'attachment; filename="{_name(id_, data, ".json")}"'})


def history_link(kind: str, id_: str, query) -> str | None:
    """``/v/{kind}/{id}?days=30`` (the short form) as the history page's link, or None when the query names no range."""
    if not any(query.get(k) for k in RANGE_KEYS):
        return None
    keep = {k: query.get(k) for k in ("days", "range", "from", "to", "diff", "fields") if query.get(k)}
    return f"/v/{quote(kind)}/{quote(id_)}/history?" + urlencode(keep)


def history_query(query) -> str:
    """The range a pick list carries (``/s?q=TRD MX-2000&days=30``), as the query its rows' history links take; empty when none."""
    keep = {k: query.get(k) for k in ("days", "range", "from", "to", "diff") if query.get(k)}
    return urlencode(keep) if any(k in keep for k in RANGE_KEYS) else ""
