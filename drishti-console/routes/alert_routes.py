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

"""Alerts on numbers (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20, phase 7 part 2): a person watches a BI visual, a dataset measure or a field of a Sutra entity and is told, in the
inbox (and by email or a chat bridge if they ask), when it crosses a limit, moves too far or does not arrive. The server evaluates every alert as its owner
(their masks, their row rules) and keeps the state and the history; this module is the form, the edit page with the history, the actions and the
administrators' overview. The list of a person's alerts is on ``/alerts`` (monitor_routes), together with the older rules on one Sutra entity."""
from __future__ import annotations

import json
import re
from urllib.parse import parse_qs, quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError, drs_message
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

STATE_LIMIT = 15_000                      # characters of report state a link or a form may carry
SCHEDULES = ("hourly", "business-days 07:30", "business-days 09:35", "weekdays 07:00", "daily 06:00", "cron 0 */15 * * * *")
STATE_ORDER = ("armed", "active", "paused")


def _num(raw) -> float | None:
    try:
        return float(str(raw).replace(",", "").strip())
    except ValueError:
        return None


def _filters(text: str) -> list[dict]:
    """``desk eq FX, Rates`` per line: the field, the operator, the values."""
    out = []
    for line in text.splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) >= 2:
            out.append({"field": parts[0], "op": parts[1], "values": [v.strip() for v in (parts[2] if len(parts) > 2 else "").split(",") if v.strip()]})
    return out


def _state(raw: str) -> dict | None:
    if not raw or len(raw) > STATE_LIMIT:
        return None
    try:
        value = json.loads(raw)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def body_from(f: dict) -> dict:
    """The server's alert body from the form's fields. What cannot be read is left out, so the server answers with the key that is missing."""
    kind = f.get("source_type", "visual")
    if kind == "model":
        query = _state(f.get("model_query", "")) or {}
        source = {"type": "model", "model": f.get("model_ref", "").strip(), "query": query}
        if f.get("model_reduce", "").strip():                         # blank: one cell (the query answers one row); the server says so when it does not
            source["reduce"] = f["model_reduce"].strip()
    elif kind == "dataset":
        source = {"type": "dataset", "dataset": f.get("dataset", "").strip(), "measure": f.get("ds_measure", "").strip()}
        filters = _filters(f.get("filters", ""))
        if filters:
            source["filters"] = filters
    elif kind == "field":
        source = {"type": "field", "kind": f.get("kind", "").strip(), "id": f.get("id", "").strip(), "field": f.get("field", "").strip()}
    else:
        source = {"type": "visual", "report": f.get("report", "").strip(), "page": f.get("page", "").strip(), "visual": f.get("visual", "").strip(),
                  "measure": f.get("measure", "").strip()}
        if f.get("reduce", "").strip():                               # blank: one cell (a card, a KPI, or the screen's filters leave one row)
            source["reduce"] = f["reduce"].strip()
        state = _state(f.get("state", ""))
        if state:
            source["state"] = state
    ctype = f.get("condition_type", "threshold")
    if ctype == "change":
        cond = {"type": "change", "mode": f.get("change_mode", "pct"), "op": f.get("change_op", "moves"), "against": f.get("against", "previous-run")}
        if (v := _num(f.get("change_value", ""))) is not None:
            cond["value"] = v
    elif ctype == "nodata":
        cond = {"type": "nodata", "by": f.get("by", "").strip()}
    else:
        cond = {"type": "threshold", "op": f.get("op", "above")}
        if (v := _num(f.get("value", ""))) is not None:
            cond["value"] = v
        if cond["op"] in ("between", "outside") and (h := _num(f.get("high", ""))) is not None:
            cond["high"] = h
    mode = f.get("trigger_mode", "schedule")
    trigger = {"mode": mode}
    if mode == "schedule":
        trigger["schedule"] = f.get("schedule", "").strip() or "hourly"
    elif mode == "after-load" and f.get("load_kind", "").strip():
        trigger["kind"] = f["load_kind"].strip()
    elif mode == "live" and f.get("debounce", "").strip():
        trigger["debounce"] = f["debounce"].strip()
    body = {"title": f.get("title", "").strip(), "severity": f.get("severity", "warn"), "source": source, "condition": cond, "trigger": trigger,
            "delivery": {"inbox": f.get("d_inbox") == "on", "email": f.get("d_email") == "on", "bridge": f.get("d_bridge") == "on",
                         "webhooks": sorted(k[2:] for k, v in f.items() if k.startswith("w_") and v == "on"), "sms": f.get("d_sms") == "on"},
            "enabled": f.get("enabled", "on") == "on"}
    if (h := _num(f.get("hysteresis", ""))) is not None:
        body["hysteresis"] = h
    if f.get("cooldown", "").strip():
        body["cooldown"] = f["cooldown"].strip()
    return body


def form_from(a: dict) -> dict:
    """The form's fields from a stored alert (the edit page)."""
    s, c, t, d = a.get("source", {}), a.get("condition", {}), a.get("trigger", {}), a.get("delivery", {})
    f = {"name": a.get("name", ""), "title": a.get("title", ""), "severity": a.get("severity", "warn"), "source_type": s.get("type", "visual"),
         "report": s.get("report", ""), "page": s.get("page", ""), "visual": s.get("visual", ""), "measure": s.get("measure", "") if s.get("type") == "visual" else "",
         "reduce": s.get("reduce", ""), "state": json.dumps(s["state"], separators=(",", ":")) if s.get("state") else "",
         "dataset": s.get("dataset", ""), "ds_measure": s.get("measure", "") if s.get("type") == "dataset" else "",
         "model_ref": s.get("model", ""), "model_query": json.dumps(s["query"], separators=(",", ":")) if s.get("type") == "model" and s.get("query") else "",
         "model_reduce": s.get("reduce", "") if s.get("type") == "model" else "",
         "filters": "\n".join(f"{x.get('field')} {x.get('op')} {', '.join(str(v) for v in x.get('values', []))}".strip() for x in s.get("filters", [])),
         "kind": s.get("kind", ""), "id": s.get("id", ""), "field": s.get("field", ""),
         "condition_type": c.get("type", "threshold"), "op": c.get("op", "above") if c.get("type") == "threshold" else "above", "value": c.get("value", "") if c.get("type") == "threshold" else "",
         "high": c.get("high", ""), "change_mode": c.get("mode", "pct"), "change_op": c.get("op", "moves") if c.get("type") == "change" else "moves",
         "change_value": c.get("value", "") if c.get("type") == "change" else "", "against": c.get("against", "previous-run"), "by": c.get("by", ""),
         "trigger_mode": t.get("mode", "schedule"), "schedule": t.get("schedule", "hourly"), "load_kind": t.get("kind", ""), "debounce": t.get("debounce", ""),
         "hysteresis": a.get("hysteresis", 0), "cooldown": a.get("cooldown", ""), "d_inbox": "on" if d.get("inbox", True) else "", "d_email": "on" if d.get("email") else "",
         "d_bridge": "on" if d.get("bridge") else "", "d_sms": "on" if d.get("sms") else "", "webhooks": list(d.get("webhooks") or []),
         "enabled": "on" if a.get("enabled", True) else ""}
    return f


def _fields(raw: bytes) -> dict:
    return {k: v[0] for k, v in parse_qs(raw.decode(), keep_blank_values=True).items()}


def standing(a: dict, user: str = "") -> str:
    """``paused``, ``active`` (it has fired and has not cleared) or ``armed`` (watching)."""
    if not a.get("enabled", True):
        return "paused"
    states = a.get("state") or {}
    mine = states.get(user) if user and user in states else next(iter(states.values()), {}) if states else {}
    return "active" if (mine or {}).get("active") or (mine or {}).get("a") == 1 else "armed"


def _last(a: dict) -> dict | None:
    h = a.get("history") or []
    return h[0] if h else None


def decorate(alerts: list[dict], user: str = "") -> list[dict]:
    """Each alert with its ``standing`` and its latest event (``last``), for the lists."""
    return [{**a, "standing": standing(a, a.get("owner") or user), "last": _last(a)} for a in alerts]


async def _page(request: Request, f: dict, *, alert: dict | None = None, error: str = "", status_code: int = 200, msg: str = ""):
    from routes.dist_routes import _options
    return render(request, "alert_form.html", status_code=status_code, f=f, alert=alert, error=error, msg=msg, schedules=SCHEDULES, screen="alerts", opts=await _options(request),
                  standing=standing(alert, getattr(request.state.identity, "user", "")) if alert else "")


# -- the form ---------------------------------------------------------------------------------------------------------

def alert_name(text: str) -> str:
    """A suggested name inside the rule the server keeps (letters, digits, space . _ -, starting with a letter or digit, at most 64): ``Risk: MTM`` becomes ``Risk - MTM``."""
    t = re.sub(r"\s*[:/\\|]+\s*", " - ", text or "")
    t = re.sub(r"[^A-Za-z0-9 ._-]+", "", t)
    t = re.sub(r"\s{2,}", " ", t).strip(" ._-")
    return t[:64].strip(" ._-")


@router.get("/alerts/new")
async def new_alert(request: Request, source: str = "visual", report: str = "", page: str = "", visual: str = "", state: str = "", measure: str = "", dataset: str = "",
                    kind: str = "", id: str = "", field: str = "", title: str = ""):
    """The form, filled from the thing the person pressed *Alert me* on: a visual (with the filters on the screen), a dataset measure or a field."""
    q = request.query_params
    f = {"name": alert_name(title or field or visual or measure) or "My alert", "title": "", "severity": "warn", "source_type": source if source in ("visual", "model", "dataset", "field") else "visual",
         "model_ref": q.get("model", ""), "model_query": "", "model_reduce": q.get("reduce", "") if source == "model" else "",
         "report": report, "page": page, "visual": visual, "measure": measure, "reduce": q.get("reduce", "") if source != "model" else "", "state": state if _state(state) else "",
         "dataset": dataset, "ds_measure": measure, "filters": "", "kind": kind, "id": id, "field": field, "condition_type": "threshold", "op": "above", "value": "", "high": "",
         "change_mode": "pct", "change_op": "moves", "change_value": "", "against": "previous-run", "by": "09:00", "trigger_mode": "schedule", "schedule": "hourly",
         "load_kind": "", "debounce": "", "hysteresis": 0, "cooldown": "", "d_inbox": "on", "d_email": "", "d_bridge": "", "d_sms": "", "webhooks": [], "enabled": "on"}
    return await _page(request, f)


@router.post("/alerts/new")
async def create_alert(request: Request):
    f = _fields(await request.body())
    name = f.get("name", "").strip()
    try:
        if f.get("phone", "").strip():                               # the person's own number for texts
            await request.app.state.backend.set_phone(f["phone"].strip(), ident(request))
        await request.app.state.backend.save_measure_alert(name, body_from(f), ident(request))
    except BackendError as e:
        return await _page(request, f, error=drs_message(e.code, e.detail, " "), status_code=e.page_status)
    return RedirectResponse(f"/alerts?msg={quote('Alert saved: ' + name)}", status_code=303)


@router.get("/alerts/measure/{name}")
async def edit_alert(request: Request, name: str, msg: str = ""):
    try:
        a = await request.app.state.backend.measure_alert(name, ident(request))
    except BackendError as e:
        return render(request, "alert_form.html", status_code=e.page_status, f={}, alert=None, error=drs_message(e.code, e.detail, " "), msg="", schedules=SCHEDULES, opts={},
                      screen="alerts", standing="")
    return await _page(request, form_from(a), alert=a, msg=msg)


@router.post("/alerts/measure/{name}")
async def update_alert(request: Request, name: str):
    f = _fields(await request.body())
    f["name"] = name
    backend = request.app.state.backend
    try:
        if f.get("phone", "").strip():
            await backend.set_phone(f["phone"].strip(), ident(request))
        await backend.save_measure_alert(name, body_from(f), ident(request))
    except BackendError as e:
        try:
            current = await backend.measure_alert(name, ident(request))
        except BackendError:
            current = None
        return await _page(request, f, alert=current, error=drs_message(e.code, e.detail, " "), status_code=e.page_status)
    return RedirectResponse(f"/alerts/measure/{quote(name)}?msg={quote('Saved. It is armed afresh.')}", status_code=303)


@router.post("/alerts/preview")
async def preview_alert(request: Request):
    """What the alert would read for the signed-in person right now (the form's *Check now*): a number, its date and whether it would fire. Saves nothing."""
    body = await json_body(request)
    try:
        return await request.app.state.backend.preview_measure_alert(body_from({k: str(v) if not isinstance(v, str) else v for k, v in body.items()}), ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)


# -- actions on an alert ------------------------------------------------------------------------------------------------

@router.post("/alerts/measure/{name}/{action}")
async def alert_action(request: Request, name: str, action: str):
    backend, me = request.app.state.backend, ident(request)
    try:
        if action == "delete":
            await backend.delete_measure_alert(name, me)
            return RedirectResponse(f"/alerts?msg={quote('Deleted ' + name)}", status_code=303)
        if action in ("pause", "resume"):
            await backend.pause_measure_alert(name, action == "resume", me)
            return RedirectResponse(f"/alerts?msg={quote(('Resumed ' if action == 'resume' else 'Paused ') + name)}", status_code=303)
        if action == "check":
            a = await backend.check_measure_alert(name, me)
            last = _last(a)
            text = f"{name}: looked now. " + (f"{last['action'].capitalize()}: {last['message']}" if last else "Nothing to report.")
            return RedirectResponse(f"/alerts?msg={quote(text)}", status_code=303)
    except BackendError as e:
        return RedirectResponse(f"/alerts?msg={quote(drs_message(e.code, e.detail, ' '))}", status_code=303)
    return Response("unknown action\n", status_code=404, media_type="text/plain; charset=utf-8")


# -- administrators: everyone's -----------------------------------------------------------------------------------------

@router.get("/alerts/all")
async def all_alerts(request: Request, msg: str = ""):
    backend, me = request.app.state.backend, ident(request)
    try:
        items = await backend.all_measure_alerts(me)
        boards = await backend.board_alerts(me)
    except BackendError as e:
        return render(request, "alerts_all.html", status_code=e.page_status if e.status == 403 else 200, items=[], boards=[], msg="", screen="alerts",
                      error="Only administrators see everyone's alerts." if e.status == 403 else drs_message(e.code, e.detail, " "))
    items.sort(key=lambda a: (a.get("owner", "").lower(), a.get("name", "").lower()))
    return render(request, "alerts_all.html", items=decorate(items), boards=boards, msg=msg, error="", screen="alerts")


@router.post("/alerts/all/{owner}/{name}/{action}")
async def admin_alert_action(request: Request, owner: str, name: str, action: str):
    backend, me = request.app.state.backend, ident(request)
    try:
        if action == "delete":
            await backend.admin_delete_measure_alert(owner, name, me)
        elif action in ("pause", "resume"):
            await backend.admin_pause_measure_alert(owner, name, action == "resume", me)
        else:
            return Response("unknown action\n", status_code=404, media_type="text/plain; charset=utf-8")
    except BackendError as e:
        return RedirectResponse(f"/alerts/all?msg={quote(drs_message(e.code, e.detail, ' '))}", status_code=303)
    return RedirectResponse(f"/alerts/all?msg={quote(action.capitalize() + 'd ' + owner + '/' + name)}", status_code=303)
