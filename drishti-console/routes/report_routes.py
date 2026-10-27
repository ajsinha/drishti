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

"""Scheduled reports: a search run as you on a schedule and delivered as CSV or JSON Lines to a folder or a webhook."""
from __future__ import annotations

from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core.backend import BackendError
from routes.common import ident, render
from routes.dist_routes import SUGGESTED_WHEN

router = APIRouter(prefix="/reports", include_in_schema=False)


def _subscription_link(s: dict) -> str:
    t = s.get("target") or {}
    if t.get("type") == "view":
        return f"/v/{t.get('id', '')}" + (f"?{t['view']}" if t.get("view") else "")
    return f"/bi/r/{quote(t.get('id', ''), safe=':')}" + (f"/{quote(t['page'])}" if t.get("page") else "")


def _rows(reports: list, subs: list) -> list:
    """The scheduled reports and the subscriptions as one list, by name: what each is, when it runs, who gets it, and how its last run went."""
    rows = []
    for r in reports:
        rows.append({"kind": "report", "name": r.get("name", ""), "enabled": r.get("enabled", True), "what": r.get("query", ""), "href": "/s?q=" + quote(r.get("query", "")),
                     "note": "the business day before" if r.get("date") == "previous" else "", "when": r.get("schedule", ""),
                     "to": "webhook" if r.get("deliver") == "webhook" else "folder", "to_note": r.get("webhook", ""), "next": r.get("nextRun"),
                     "runs": r.get("runs") or [], "base": f"/reports/{quote(r.get('name', ''))}"})
    for s in subs:
        t = s.get("target") or {}
        scope = t.get("visual") or t.get("page") or ""
        rows.append({"kind": "subscription", "name": s.get("name", ""), "enabled": s.get("enabled", True), "what": s.get("title") or t.get("id", ""), "href": _subscription_link(s),
                     "note": ((f"{scope} · " if scope else "") + {"previous": "the business day before", "load": "the date of the load"}.get(s.get("date"), "")).strip(" ·"),
                     "when": s.get("schedule", ""), "to": ", ".join(s.get("recipients") or []), "to_note": {"html": "in the email", "pdf": "PDF attached", "xlsx": "Excel attached"}.get(s.get("format"), ""),
                     "next": s.get("nextRun"), "runs": s.get("runs") or [], "base": f"/reports/subscriptions/{quote(s.get('name', ''))}"})
    return sorted(rows, key=lambda r: (r["name"].lower(), r["kind"]))


async def _page(request: Request, q: str = "", msg: str = "", error: str = "", status_code: int = 200, form: dict | None = None):
    backend = request.app.state.backend
    try:
        mine = await backend.reports(ident(request))
    except BackendError as e:
        mine, error = [], error or e.detail
    try:
        subs = await backend.subscriptions(ident(request))
    except BackendError:
        subs = []                                          # an older server has no subscriptions: the scheduled reports still list
    return render(request, "reports.html", status_code=status_code, reports=mine, rows=_rows(mine, subs), n_subs=len(subs),
                  form=form or {"query": q, "schedule": "business-days 18:30", "deliver": "folder", "date": "today", "format": "csv"},
                  msg=msg, error=error, screen="reports", when=SUGGESTED_WHEN)


@router.get("")
async def index(request: Request, q: str = "", msg: str = ""):
    return await _page(request, q=q, msg=msg)


@router.post("")
async def save(request: Request):
    f = {k: v[0] for k, v in parse_qs((await request.body()).decode(), keep_blank_values=True).items()}
    name = f.get("name", "").strip()
    body = {"query": f.get("query", ""), "schedule": f.get("schedule", ""), "deliver": f.get("deliver", "folder"),
            "webhook": f.get("webhook", ""), "date": f.get("date", "today"), "format": f.get("format", "csv"), "enabled": f.get("enabled", "on") == "on"}
    try:
        await request.app.state.backend.save_report(name, body, ident(request))
    except BackendError as e:
        return await _page(request, error=e.detail, status_code=e.page_status, form={"name": name, **body})
    return RedirectResponse(f"/reports?msg={quote('Saved ' + name)}", status_code=303)


@router.post("/{name}/run")
async def run(request: Request, name: str):
    try:
        r = await request.app.state.backend.run_report(name, ident(request))
    except BackendError as e:
        return await _page(request, error=e.detail, status_code=e.page_status)
    text = (f"{name}: {r.get('rows', 0)} rows to {r.get('target')}" if r.get("status") == "ok" else f"{name} failed: {r.get('error')}")
    return RedirectResponse(f"/reports?msg={quote(text)}", status_code=303)


@router.post("/{name}/delete")
async def delete(request: Request, name: str):
    try:
        await request.app.state.backend.delete_report(name, ident(request))
    except BackendError as e:
        return await _page(request, error=e.detail, status_code=e.page_status)
    return RedirectResponse(f"/reports?msg={quote('Deleted ' + name)}", status_code=303)
