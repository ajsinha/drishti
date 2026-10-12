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

"""Distribution (RUPAKA.md section 15): a BI report, page or visual and a Sutra view as PDF, PNG, Excel or CSV, and subscriptions (the same
content by email on a schedule). Every file is made by the server as the signed-in reader, so their masks and row rules apply; the console
passes the bytes on. Subscriptions are listed with the scheduled reports on ``/reports`` (report_routes); this module holds the download
links, the Subscribe form and the actions on a subscription, and the administrators' list of everyone's."""
from __future__ import annotations

import json
from urllib.parse import parse_qs, quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core import asof
from core.backend import BackendError, drs_message
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

FORMATS = ("pdf", "png", "xlsx", "csv")
STATE_LIMIT = 20_000                      # characters of view state a link or a form may carry
SUGGESTED_WHEN = ("business-days 07:30", "weekdays 07:00", "daily 06:00", "weekly mon 07:30 business", "monthly 1 07:30 business",
                  "monthly last 18:00 business", "after-load", "hourly")


def _problem(e: BackendError) -> Response:
    return Response(drs_message(e.code, e.detail, " ") + "\n", status_code=e.page_status, media_type="text/plain; charset=utf-8")


def _date() -> str:
    return "" if asof.current() == "live" else asof.current()


async def _file(request: Request, target: dict, fmt: str) -> Response:
    if fmt not in FORMATS:
        return Response(f"format is one of {', '.join(FORMATS)}\n", status_code=404, media_type="text/plain; charset=utf-8")
    try:
        body, headers = await request.app.state.backend.export(fmt, target, ident(request))
    except BackendError as e:
        return _problem(e)
    out = {"Cache-Control": "no-store"}
    if headers.get("content-disposition"):
        out["Content-Disposition"] = headers["content-disposition"]
    return Response(body, media_type=headers.get("content-type", "application/octet-stream"), headers=out)


def _state(raw: str) -> dict | None:
    if not raw or len(raw) > STATE_LIMIT:
        return None
    try:
        value = json.loads(raw)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


@router.get("/dist/status")
async def status(request: Request):
    """What the signed-in person can do here, for the report page's Export and Subscribe: whether exports and subscriptions are on, and their subscription count and limit."""
    try:
        out = await request.app.state.backend.distribution_status(ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)
    return JSONResponse(out, headers={"Cache-Control": "no-store"})


@router.get("/dist/bi/{rest:path}")
async def bi_download(request: Request, rest: str, page: str = "", visual: str = "", state: str = "", date: str = ""):
    """``/dist/bi/<report id>.<pdf|png|xlsx|csv>?page=&visual=&state=<the view state as JSON>&date=``."""
    item, _, fmt = rest.rpartition(".")
    target = {"type": "report", "id": item, "page": page, "visual": visual, "date": date}
    st = _state(state)
    if st is not None:
        target["state"] = st
    return await _file(request, target, fmt.lower())


@router.get("/dist/view/{rest:path}")
async def view_download(request: Request, rest: str):
    """``/dist/view/<kind>/<id>.<pdf|png|xlsx|csv>`` with the page's own query (``ctl.*`` control values, ``f.*`` column filters)."""
    item, _, fmt = rest.rpartition(".")
    kept = [(k, v) for k, v in request.query_params.multi_items() if k.startswith(("ctl.", "f."))]
    return await _file(request, {"type": "view", "id": item, "date": _date(), "view": urlencode(kept)}, fmt.lower())


# -- the Subscribe form ----------------------------------------------------------------------------------------------

def _describe(target: dict, title: str = "") -> str:
    named = f"“{title}” ({target.get('id')})" if title else str(target.get("id"))
    if target.get("type") == "view":
        return f"The view {named}" + (f" with your controls and filters ({target['view']})" if target.get("view") else "")
    scope = f", visual {target['visual']}" if target.get("visual") else (f", page {target['page']}" if target.get("page") else ", every page")
    return f"The BI report {named}{scope}" + (", with the filters you have set" if target.get("state") else "")


async def _options(request: Request) -> dict:
    """What the form may offer besides mail: AI narrative (the BI AI hooks are on), the administrator's webhooks, a text-message gateway. A server without them offers none."""
    out = {"ai": False, "mode": "labels-only", "webhooks": [], "sms": False, "phone": ""}
    backend, me = request.app.state.backend, ident(request)
    try:
        s = await backend.bi("GET", "/ai/status", me)
        out["ai"], out["mode"] = bool(s.get("enabled")), s.get("values", "labels-only")
    except BackendError:
        pass
    try:
        n = await backend.notify_options(me)
        out["webhooks"], out["sms"], out["phone"] = list(n.get("webhooks") or []), bool((n.get("sms") or {}).get("configured")), (n.get("sms") or {}).get("phone", "")
    except BackendError:
        pass
    return out


async def _form_page(request: Request, form: dict, error: str = "", status_code: int = 200):
    return render(request, "subscribe.html", status_code=status_code, form=form, error=error, what=_describe(form["target"], form.get("title", "")), when=SUGGESTED_WHEN,
                  target_json=json.dumps(form["target"], separators=(",", ":")), screen="reports", opts=await _options(request))


@router.get("/reports/subscribe")
async def subscribe_form(request: Request, type: str = "report", id: str = "", page: str = "", visual: str = "", state: str = "", view: str = "", title: str = ""):
    target = {"type": "view" if type == "view" else "report", "id": id, "page": page, "visual": visual, "view": view}
    st = _state(state)
    if st is not None:
        target["state"] = st
    me = request.state.identity
    return await _form_page(request, {"name": title.strip()[:64] or (id.split(":")[-1] if id else "My subscription"), "title": title.strip()[:200], "target": target, "schedule": "business-days 07:30",
                                      "format": "html", "date": "today", "recipients": getattr(me, "user", "")})


@router.post("/reports/subscribe")
async def subscribe_save(request: Request):
    parsed = parse_qs((await request.body()).decode(), keep_blank_values=True)
    f = {k: v[0] for k, v in parsed.items()}
    target = _state(f.get("target", "")) or {}
    recipients = [x.strip() for x in f.get("recipients", "").replace(";", ",").replace("\n", ",").split(",") if x.strip()]
    name = f.get("name", "").strip()
    hooks = [x for x in parsed.get("webhooks", []) if x]
    form = {"name": name, "title": f.get("title", ""), "target": target, "schedule": f.get("schedule", ""), "format": f.get("format", "html"), "date": f.get("date", "today"),
            "recipients": ", ".join(recipients), "narrative": f.get("narrative") == "on", "webhooks": hooks, "sms": f.get("sms") == "on"}
    body = {"title": form["title"], "target": target, "schedule": form["schedule"], "format": form["format"], "date": form["date"], "recipients": recipients,
            "enabled": f.get("enabled", "on") == "on", "narrative": form["narrative"], "webhooks": hooks, "sms": form["sms"]}
    try:
        if f.get("phone", "").strip():                               # a number typed here is the person's own: kept for texts (blank leaves the one on file)
            await request.app.state.backend.set_phone(f["phone"].strip(), ident(request))
        await request.app.state.backend.save_subscription(name, body, ident(request))
    except BackendError as e:
        return await _form_page(request, form, error=drs_message(e.code, e.detail, " "), status_code=e.page_status)
    return RedirectResponse(f"/reports?msg={quote('Subscribed: ' + name)}", status_code=303)


# -- actions on a subscription -----------------------------------------------------------------------------------------

async def _act(request: Request, fn, name: str, ok: str):
    try:
        await fn()
    except BackendError as e:
        return RedirectResponse(f"/reports?msg={quote(drs_message(e.code, e.detail, ' '))}", status_code=303)
    return RedirectResponse(f"/reports?msg={quote(ok)}", status_code=303)


@router.post("/reports/subscriptions/{name}/run")
async def run(request: Request, name: str):
    backend = request.app.state.backend
    try:
        r = await backend.run_subscription(name, ident(request))
    except BackendError as e:
        return RedirectResponse(f"/reports?msg={quote(drs_message(e.code, e.detail, ' '))}", status_code=303)
    text = (f"{name}: {r.get('sent', 0)} sent" + (f", {r.get('skipped')} skipped" if r.get("skipped") else "") + (f", {r.get('retrying')} to retry" if r.get("retrying") else "")
            + (f", {r.get('failed')} failed" if r.get("failed") else "") + (f" ({r.get('error')})" if r.get("error") else ""))
    return RedirectResponse(f"/reports?msg={quote(text)}", status_code=303)


@router.post("/reports/subscriptions/{name}/pause")
async def pause(request: Request, name: str):
    return await _act(request, lambda: request.app.state.backend.pause_subscription(name, False, ident(request)), name, f"Paused {name}")


@router.post("/reports/subscriptions/{name}/resume")
async def resume(request: Request, name: str):
    return await _act(request, lambda: request.app.state.backend.pause_subscription(name, True, ident(request)), name, f"Resumed {name}")


@router.post("/reports/subscriptions/{name}/delete")
async def delete(request: Request, name: str):
    return await _act(request, lambda: request.app.state.backend.delete_subscription(name, ident(request)), name, f"Deleted {name}")


# -- administrators: everyone's ---------------------------------------------------------------------------------------

@router.get("/reports/all")
async def all_subscriptions(request: Request, msg: str = ""):
    error = ""
    try:
        items = await request.app.state.backend.all_subscriptions(ident(request))
    except BackendError as e:
        items, error = [], drs_message(e.code, e.detail, " ")
        if e.status == 403:
            return render(request, "reports_all.html", status_code=403, items=[], msg="", error="Only administrators see everyone's subscriptions.", screen="reports")
    items.sort(key=lambda s: (s.get("owner", "").lower(), s.get("name", "").lower()))
    return render(request, "reports_all.html", items=items, msg=msg, error=error, screen="reports")


@router.post("/reports/all/{owner}/{name}/{action}")
async def admin_action(request: Request, owner: str, name: str, action: str):
    backend = request.app.state.backend
    try:
        if action == "delete":
            await backend.admin_delete_subscription(owner, name, ident(request))
        elif action in ("pause", "resume"):
            await backend.admin_pause_subscription(owner, name, action == "resume", ident(request))
        else:
            return Response("unknown action\n", status_code=404, media_type="text/plain; charset=utf-8")
    except BackendError as e:
        return RedirectResponse(f"/reports/all?msg={quote(drs_message(e.code, e.detail, ' '))}", status_code=303)
    return RedirectResponse(f"/reports/all?msg={quote(action.capitalize() + 'd ' + owner + '/' + name)}", status_code=303)
