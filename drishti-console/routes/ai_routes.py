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

"""Admin → AI (read-only) and the console's AI API: the server's AI layer only (THIN_CONSOLE_DESIGN.md step S7).

The console calls no model and no MCP server itself: keys, policy, masking, limits and audit live on the server (``drishti.ai.*``,
``drishti.mcp.*``, the files in its ``config/ai`` and ``config/mcp``). The page shows the server's providers with their health and models,
its MCP servers and (on request, one at a time) their tools, and the AI tools the hosted modules describe. ``POST /api/ai/chat`` is what
console features call: it passes the call to the server as the person (``POST /api/v1/ai/chat``)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code or "DRS-4016", "detail": e.detail}, status_code=e.status if 400 <= e.status < 600 else 502)


async def _remote(request: Request):
    try:
        return await request.app.state.backend.admin("GET", "/ai", ident(request)), None
    except BackendError as e:
        return None, e


@router.get("/admin/ai")
async def page(request: Request, tools: str = ""):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    remote, remote_error = await _remote(request)
    tool_list, tool_error = None, None
    if tools:
        try:
            tool_list = await request.app.state.backend.admin("GET", f"/mcp/{tools}/tools", me)
        except BackendError as e:
            tool_error = e.detail
    return render(request, "admin/ai.html", remote=remote, remote_error=remote_error, tools_of=tools, tool_list=tool_list,
                  tool_error=tool_error, module_tools=module_tools(request))


def module_tools(request: Request) -> list[dict]:
    """The AI tools the hosted modules describe (``ai: tools:`` of a plugin.yaml format 2: name, description, the word or route it opens),
    of the modules on for this person: what the assistant may offer (KERNEL_AND_MODULES.md 4.7). A module that goes takes its own."""
    from core import modules as _modules

    parts = request.app.state.module_parts
    return [{"name": str(t["name"]), "description": str(t.get("description") or ""), "module": t["module"],
             "href": parts.href(t) if (t.get("route") or t.get("cmd")) else ""}
            for t in parts.tools() if _modules.module_on(t["module"])]


@router.get("/api/ai/module-tools")
async def module_tools_api(request: Request):
    """The hosted modules' AI tools for the signed-in person (the assistant's list)."""
    return JSONResponse({"tools": module_tools(request)})


@router.post("/admin/api/retry/{area}")
async def retry_now(request: Request, area: str):
    """Admin "Retry now" (administrators; audited by the server). ``area``: packs, connectors, ai, mcp or all; the server tries again."""
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    body = await json_body(request)
    if area not in ("packs", "connectors", "ai", "mcp", "all"):
        return JSONResponse({"code": "DRS-5001", "detail": "area must be packs, connectors, ai, mcp or all"}, status_code=400)
    out: dict = {"area": area}
    try:
        params = {"name": body["name"]} if body.get("name") else {}
        out["server"] = await request.app.state.backend.admin("POST", f"/retry/{area}", me, **params)
    except BackendError as e:
        out["serverError"] = e.detail
    return out


@router.post("/admin/ai/api/test/{model:path}")
async def test_connection(request: Request, model: str):
    """Admin -> AI "Test connection": one live call to a model of the server's (``POST /api/v1/admin/ai/test``), run on request only,
    audited by the server. The answer never carries a key."""
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    await json_body(request)
    try:
        r = await request.app.state.backend.admin("POST", "/ai/test", me, body={"model": model})
    except BackendError as e:
        return {"name": model, "ok": False, "network": True, "detail": e.detail, "models": []}
    return {"name": model, "ok": bool(r.get("ok")), "network": True, "detail": str(r.get("text") or ""),
            "models": [r["model"]] if r.get("model") else [], "millis": r.get("millis")}


@router.get("/admin/ai/api/overview")
async def overview(request: Request):
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    remote, err = await _remote(request)
    return {"server": remote, "serverError": err.detail if err else None}


@router.post("/api/ai/chat")
async def chat(request: Request):
    """``{prompt | messages, system, model, maxTokens, temperature, jsonMode, timeoutSeconds, feature}`` -> ``{text, model, usage}``: the
    server's AI layer, as the person (its policy, masking, limits and audit)."""
    me = ident(request)
    body = await json_body(request)
    msgs = [{"role": str(m.get("role", "user")), "content": str(m.get("content", ""))} for m in body.get("messages") or []]
    if body.get("prompt"):
        msgs.append({"role": "user", "content": str(body["prompt"])})
    if not msgs:
        return JSONResponse({"code": "DRS-5001", "detail": "send messages or a prompt"}, status_code=400)
    ask = {"model": body.get("model"), "system": str(body.get("system") or "") or None, "messages": msgs,
           "temperature": body.get("temperature"), "maxTokens": body.get("maxTokens"), "jsonMode": bool(body.get("jsonMode")) or None,
           "feature": "console:" + str(body.get("feature") or "other"),
           "timeoutSeconds": int(min(120, max(1, int(body.get("timeoutSeconds") or 30))))}
    try:
        r = await request.app.state.backend.ai_chat(me, {k: v for k, v in ask.items() if v is not None})
    except BackendError as e:
        return _problem(e)
    u = r.get("usage") or {}
    return {"text": r.get("text", ""), "model": r.get("model", ""), "finishReason": r.get("finishReason") or "",
            "usage": {"promptTokens": u.get("promptTokens") or 0, "completionTokens": u.get("completionTokens") or 0, "cost": u.get("cost") or 0}}
