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

"""Monitors (live watchlists) and the page of the one alert engine (the alerts raised for me, my alert rules), with the notification stream."""
from __future__ import annotations

import json
from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, StreamingResponse

from core.csrf import json_body
from core.backend import BackendError, drs_message
from routes.alert_routes import decorate
from routes.common import ident, packs, render

router = APIRouter(include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _starters(request: Request) -> dict:
    out = {}
    for p in await packs(request):
        for name, ents in p["monitors"].items():
            out.setdefault(name, {"entities": ents, "pack": p["title"]})
    return out


def grid_rows(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    """A monitor's entities as the records grid's static rows (one row per entity, keyed ``kind/id``): the entity first, then one column per
    figure label (the labels the entities share share a column), each cell the server's text. ``_cells`` names the field of each strip index,
    so the stream's patches (cell index, new text) reach the right cell."""
    fields: dict[str, str] = {}                       # a figure's label -> its field
    tones: set[str] = set()                           # the fields some cell gave a tone (the grid colours them: toneField)
    links: set[str] = set()                           # the fields some cell links to a record (the grid draws a link: hrefField)
    table = []
    for r in rows:
        ref = r.get("ref") or {}
        row = {"KEY": f"{ref.get('kind')}/{ref.get('id')}", "ID": ref.get("id"), "KIND": ref.get("kind"), "ENTITY": ref.get("id"),
               "_cells": {}}
        if (r.get("title") or {}).get("pill"):
            row["PILL"] = r["title"]["pill"]
        if r.get("error"):
            fields.setdefault("Note", "NOTE")
            row["NOTE"] = r["error"]
        for i, c in enumerate(r.get("strip") or []):
            label = str(c.get("label") or f"Figure {i + 1}")
            field = fields.setdefault(label, f"F{len(fields) + 1}")
            row[field] = cell_text(c)
            row["_cells"][str(i)] = field
            if c.get("tone"):
                row[field + "_TONE"] = str(c["tone"])
                tones.add(field)
            if c.get("link"):
                row[field + "_LINK"] = f"/v/{quote(str(c['link'].get('kind')), safe='')}/{quote(str(c['link'].get('id')), safe='')}"
                links.add(field)
        table.append(row)
    columns = [{"field": "ENTITY", "label": "Entity", "type": "id"}, {"field": "KIND", "label": "Kind", "type": "text"}]
    if any("PILL" in r for r in table):
        columns.append({"field": "PILL", "label": "State", "type": "text"})
    columns += [{"field": f, "label": label, "type": "text", **({"toneField": f + "_TONE"} if f in tones else {}),
                 **({"hrefField": f + "_LINK"} if f in links else {})} for label, f in fields.items()]
    return columns, table


def cell_text(c: dict) -> str:
    """A strip cell's text as the server gave it (a missing one is a dash)."""
    t = c.get("text")
    return "—" if t is None or t == "" else str(t)


@router.get("/m")
async def monitors(request: Request):
    try:
        mine = await request.app.state.backend.mine("GET", "/monitors", ident(request))
    except BackendError:
        mine = []
    return render(request, "monitor/index.html", mine=mine, starters=await _starters(request), screen="monitor")


@router.get("/m/{name}")
async def monitor(request: Request, name: str, template: str = ""):
    backend, me = request.app.state.backend, ident(request)
    saved = False
    if template:
        starter = (await _starters(request)).get(template)
        if not starter:
            return render(request, "monitor/index.html", status_code=404, mine=[], starters=await _starters(request), missing=template)
        try:
            await backend.mine("PUT", f"/monitors/{quote(name)}", me, {"entities": starter["entities"]})
        except BackendError as e:
            return render(request, "monitor/index.html", status_code=e.status, mine=[], starters=await _starters(request), missing=e.detail)
    try:
        rows = await backend.mine("GET", f"/monitors/{quote(name)}", me)
        saved = True
    except BackendError as e:
        return render(request, "monitor/index.html", status_code=404, mine=[], starters=await _starters(request), missing=name)
    columns, table = grid_rows(rows)
    return render(request, "monitor/monitor.html", name=name, rows=rows, saved=saved, screen="monitor", grid_columns=columns, grid_rows=table)


@router.post("/m/api/{name}")
async def save_monitor(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.mine("PUT", f"/monitors/{quote(name)}", ident(request), body)
    except BackendError as e:
        return _problem(e)


@router.post("/m/api/{name}/delete")
async def delete_monitor(request: Request, name: str):
    try:
        await request.app.state.backend.mine("DELETE", f"/monitors/{quote(name)}", ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)


def _relay(request: Request, path: str) -> StreamingResponse:
    backend = request.app.state.backend

    async def events():
        try:
            async for event, data in backend.sse(path, ident(request)):
                if await request.is_disconnected():
                    break
                yield f"event: {event}\ndata: {data}\n\n"
        except BackendError as e:
            yield f"event: gone\ndata: {json.dumps({'code': e.code, 'detail': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.get("/api/monitor-stream/{name}")
async def monitor_stream(request: Request, name: str):
    return _relay(request, f"/me/monitors/{quote(name)}/stream")


@router.get("/api/alerts/stream")
async def alerts_stream(request: Request):
    return _relay(request, "/alerts/stream")


@router.get("/alerts")
async def alerts(request: Request, kind: str = "", id: str = "", msg: str = "", stage: str = "open"):
    """The one alert engine's page: the alerts raised for me (acknowledge, snooze, resolve; an administrator also sees the platform's), my
    alert rules, a new rule on one entity (a pack may suggest one), and the recent firings."""
    backend, me = request.app.state.backend, ident(request)
    try:
        raised = await backend.raised_alerts(me, "all" if stage == "all" else "open")
    except BackendError:
        raised = []
    try:
        rules = decorate(await backend.alert_rules(me), getattr(me, "user", ""))
    except BackendError:
        rules = []
    recent = sorted((dict(e, alert=a["name"]) for a in rules for e in (a.get("history") or []) if e.get("action") == "fired"), key=lambda e: e.get("at", ""), reverse=True)[:20]
    suggestions = [s for p in await packs(request) for s in p["alerts"] if not kind or s.get("kind") == kind]
    from routes.module_admin_routes import needs as _needs     # a rule that uses a removed or switched-off module: kept, marked (KERNEL_AND_MODULES.md 12.6)
    import json as _json
    needs = await _needs(request, [{"id": str(a.get("name")), "text": _json.dumps(a, default=str)} for a in rules])
    return render(request, "alerts.html", raised=raised, stage=stage, measure=rules, recent=recent, suggestions=suggestions, kind=kind, id=id, msg=msg, screen="alerts",
                  needs=needs)


@router.post("/alerts/raised/{action}")
async def act_on_raised(request: Request, action: str):
    """Acknowledge, snooze, resolve or assign one raised alert (a form on the page)."""
    if action not in ("acknowledge", "snooze", "resolve", "assign"):
        return RedirectResponse("/alerts", status_code=303)
    form = {k: v[0] for k, v in parse_qs((await request.body()).decode("utf-8", "replace"), keep_blank_values=True).items()}
    body = {"id": form.get("id", ""), "note": form.get("note", "")[:500], "for": form.get("for", ""), "to": form.get("to", "")}
    try:
        await request.app.state.backend.act_on_alert(action, body, ident(request))
    except BackendError as e:
        return RedirectResponse(f"/alerts?msg={quote(drs_message(e.code, e.detail, ' '))}", status_code=303)
    done = {"acknowledge": "Acknowledged: it is yours.", "snooze": "Snoozed.", "resolve": "Resolved.", "assign": "Assigned."}[action]
    return RedirectResponse(f"/alerts?msg={quote(done)}", status_code=303)


def rule_body(body: dict) -> dict:
    """A rule on one entity (the page's form, or a pack's suggestion: kind, id, when, severity, message) as an alert of the one engine: a field
    source whose field is the Rachana-EL predicate, the condition rule, looked at after each load of the kind (live when it pushes changes:
    choose it on the edit page), told in the inbox. The message, when given, is the title."""
    kind = str(body.get("kind") or "").strip()
    message = str(body.get("message") or "").strip()
    return {"title": message, "severity": body.get("severity") or "warn",
            "source": {"type": "field", "kind": kind, "id": str(body.get("id") or "").strip(), "field": str(body.get("when") or "").strip()},
            "condition": {"type": "rule"}, "trigger": {"mode": "after-load", "kind": kind}, "delivery": {"inbox": True}}


@router.post("/alerts/api/{name}")
async def save_rule(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.save_alert_rule(name, rule_body(body), ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/alerts/api/{name}/delete")
async def delete_rule(request: Request, name: str):
    try:
        await request.app.state.backend.delete_alert_rule(name, ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)
