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

"""Scheduled reports: a search run as you on a schedule and delivered as CSV to a folder or a webhook."""
from __future__ import annotations

from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/reports", include_in_schema=False)


async def _page(request: Request, q: str = "", msg: str = "", error: str = "", status_code: int = 200, form: dict | None = None):
    try:
        mine = await request.app.state.backend.reports(ident(request))
    except BackendError as e:
        mine, error = [], error or e.detail
    return render(request, "reports.html", status_code=status_code, reports=sorted(mine, key=lambda r: r.get("name", "").lower()),
                  form=form or {"query": q, "schedule": "business-days 18:30", "deliver": "folder", "date": "today"},
                  msg=msg, error=error, screen="reports")


@router.get("")
async def index(request: Request, q: str = "", msg: str = ""):
    return await _page(request, q=q, msg=msg)


@router.post("")
async def save(request: Request):
    f = {k: v[0] for k, v in parse_qs((await request.body()).decode(), keep_blank_values=True).items()}
    name = f.get("name", "").strip()
    body = {"query": f.get("query", ""), "schedule": f.get("schedule", ""), "deliver": f.get("deliver", "folder"),
            "webhook": f.get("webhook", ""), "date": f.get("date", "today"), "enabled": f.get("enabled", "on") == "on"}
    try:
        await request.app.state.backend.save_report(name, body, ident(request))
    except BackendError as e:
        return await _page(request, error=e.detail, status_code=e.status if e.status < 500 else 502, form={"name": name, **body})
    return RedirectResponse(f"/reports?msg={quote('Saved ' + name)}", status_code=303)


@router.post("/{name}/run")
async def run(request: Request, name: str):
    try:
        r = await request.app.state.backend.run_report(name, ident(request))
    except BackendError as e:
        return await _page(request, error=e.detail, status_code=e.status if e.status < 500 else 502)
    text = (f"{name}: {r.get('rows', 0)} rows to {r.get('target')}" if r.get("status") == "ok" else f"{name} failed: {r.get('error')}")
    return RedirectResponse(f"/reports?msg={quote(text)}", status_code=303)


@router.post("/{name}/delete")
async def delete(request: Request, name: str):
    try:
        await request.app.state.backend.delete_report(name, ident(request))
    except BackendError as e:
        return await _page(request, error=e.detail, status_code=e.status if e.status < 500 else 502)
    return RedirectResponse(f"/reports?msg={quote('Deleted ' + name)}", status_code=303)
