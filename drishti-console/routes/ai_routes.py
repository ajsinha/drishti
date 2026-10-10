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

"""Admin → AI (read-only) and the console's AI API.

The page shows, for the console's own layer and for the server's: whether it is on, the providers with their health and models, the MCP servers
and (on request, one server at a time) their tools. Nothing here changes configuration; the files in ``config/ai`` and ``config/mcp`` do.
``POST /api/ai/chat`` is what console features call (signed in, JSON body only, through the policy)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.ai.types import CallContext, ChatMessage, ChatRequest, LlmError
from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

_STATUS = {"not_found": 404, "policy": 403, "rate_limited": 429, "timeout": 504, "unsupported": 400, "config": 400, "failed": 502}


def _problem(e: LlmError) -> JSONResponse:
    return JSONResponse({"code": "DRS-4016", "kind": e.kind, "detail": e.message}, status_code=_STATUS.get(e.kind, 502))


def _local(ai) -> dict:
    reg = ai.registry
    providers = [{"name": e.config.name, "type": e.config.type, "enabled": e.config.enabled, "baseUrl": e.config.base_url, "keySet": bool(e.config.api_key),
                  "health": reg.health(e), "models": [{"ref": m.ref, "id": m.id, "kind": m.kind, "capabilities": list(m.capabilities)} for m in
                                                      (e.provider.models() if e.provider else [])]} for e in reg.entries()]
    servers = [{"name": e.config["name"], "transport": e.config["transport"], "enabled": e.config["enabled"], "url": e.config["url"],
                "allowedTools": e.config["allowed_tools"], "health": ai.mcp.health(e)} for e in ai.mcp.entries()]
    return {"enabled": ai.enabled, "dir": ai.dir, "types": reg.types(), "providers": providers, "fileErrors": reg.file_errors,
            "mcpEnabled": ai.mcp_enabled, "mcpDir": ai.mcp_dir, "mcpServers": servers, "mcpFileErrors": ai.mcp.file_errors}


async def _remote(request: Request):
    try:
        return await request.app.state.backend.admin("GET", "/ai", ident(request)), None
    except BackendError as e:
        return None, e


@router.get("/admin/ai")
async def page(request: Request, tools: str = "", where: str = "console"):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    ai = request.app.state.ai
    remote, remote_error = await _remote(request)
    tool_list, tool_error = None, None
    if tools:
        try:
            if where == "server":
                tool_list = await request.app.state.backend.admin("GET", f"/mcp/{tools}/tools", me)
            else:
                ctx = CallContext(me.user, frozenset(me.roles), "admin", me)
                tool_list = [{"name": t.name, "description": t.description} for t in await ai.mcp_gateway.tools(ctx, tools)]
        except (BackendError, LlmError) as e:
            tool_error = getattr(e, "detail", None) or getattr(e, "message", str(e))
    return render(request, "admin/ai.html", local=_local(ai), remote=remote, remote_error=remote_error, tools_of=tools, tools_where=where,
                  tool_list=tool_list, tool_error=tool_error)


@router.get("/admin/ai/api/overview")
async def overview(request: Request):
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    remote, err = await _remote(request)
    return {"console": _local(request.app.state.ai), "server": remote, "serverError": err.detail if err else None}


@router.post("/api/ai/chat")
async def chat(request: Request):
    """``{prompt | messages, system, model, maxTokens, temperature, jsonMode, timeoutSeconds, feature}`` -> ``{text, model, usage}``."""
    me = ident(request)
    body = await json_body(request)
    msgs = [ChatMessage(str(m.get("role", "user")), str(m.get("content", ""))) for m in body.get("messages") or []]
    if body.get("prompt"):
        msgs.append(ChatMessage("user", str(body["prompt"])))
    if not msgs:
        return JSONResponse({"code": "DRS-5001", "detail": "send messages or a prompt"}, status_code=400)
    ai = request.app.state.ai
    if not ai.enabled:
        return JSONResponse({"code": "DRS-4015", "detail": "the AI layer is switched off (ai.enabled)"}, status_code=404)
    req = ChatRequest(model=body.get("model"), system=str(body.get("system") or ""), messages=tuple(msgs), temperature=body.get("temperature"),
                      max_tokens=body.get("maxTokens"), json_mode=bool(body.get("jsonMode")),
                      timeout=float(min(120, max(1, int(body.get("timeoutSeconds") or 30)))))
    feature = "console:" + str(body.get("feature") or "other")
    try:
        r = await ai.gateway.chat(CallContext(me.user, frozenset(me.roles), feature, me), req)
    except LlmError as e:
        return _problem(e)
    return {"text": r.text, "model": r.model, "finishReason": r.finish_reason,
            "usage": {"promptTokens": r.usage.prompt_tokens, "completionTokens": r.usage.completion_tokens, "cost": r.usage.cost}}
