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

"""``type: server``: delegates to the Drishti server's AI API (``POST /api/v1/ai/chat``), so keys, policy, masking, limits and audit live in one
place. The recommended default for the console. The model ``id`` in the file is the server's own reference (``provider/model``); omit it to
use the server's default. The signed-in identity travels with the call (``CallContext.ident`` -> ``request.context["ident"]``)."""
from __future__ import annotations

from core.ai.providers.base import CHAT_EXTRAS, BaseProvider
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

_KINDS = {"timeout": "timeout", "rate_limited": "rate_limited", "policy": "policy", "not_found": "not_found"}
_CODES = {"DRS-4015": "not_found", "DRS-4016": "failed", "DRS-4017": "rate_limited", "DRS-4018": "policy", "DRS-4019": "not_found"}


class ServerProvider(BaseProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime):
        super().__init__(cfg)
        self._runtime = runtime

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        from core.backend import BackendError

        backend = self._runtime.backend()
        body = {"model": None if info.id in ("", info.name, "default") else info.id, "system": req.system,
                "messages": [{"role": m.role, "content": m.content} for m in req.messages], "temperature": req.temperature,
                "maxTokens": req.max_tokens, "jsonMode": req.json_mode, "feature": req.context.get("feature", "console"),
                "timeoutSeconds": int(req.timeout) if req.timeout else None}
        try:
            j = await backend.ai_chat(req.context.get("ident"), {k: v for k, v in body.items() if v is not None})
        except BackendError as e:
            kind = "timeout" if e.status == 504 else _CODES.get(e.code, "failed")
            raise LlmError(kind, e.detail) from None
        u = j.get("usage") or {}
        return ChatResponse(j.get("text", ""), tuple(ToolCall(c.get("id", ""), c.get("name", ""), c.get("argumentsJson", "{}")) for c in j.get("toolCalls") or []),
                            j.get("model", ""), j.get("finishReason") or "",
                            Usage(int(u.get("promptTokens") or 0), int(u.get("completionTokens") or 0), float(u.get("cost") or 0)))


def factory(cfg: ProviderConfig, runtime) -> ServerProvider:
    return ServerProvider(cfg, runtime)
