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

"""``type: anthropic``: the Anthropic Messages API over httpx (no SDK). Settings: ``base-url`` (https://api.anthropic.com),
``messages-path`` (/v1/messages), ``anthropic-version`` (2023-06-01), ``header.<Name>``, ``tls.*``."""
from __future__ import annotations

import json

import httpx

from core.ai.providers.base import BaseProvider, map_http_error, ssl_context
from core.ai.types import TOOL_CALLS, ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage


class AnthropicProvider(BaseProvider):
    default_caps = (TOOL_CALLS,)

    def __init__(self, cfg: ProviderConfig, runtime=None):
        super().__init__(cfg)
        base = (cfg.base_url or "https://api.anthropic.com").rstrip("/")
        self._url = base + cfg.setting("messages-path", "/v1/messages")
        self._client = httpx.AsyncClient(verify=ssl_context(cfg, base), timeout=cfg.timeout, follow_redirects=False)

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        body: dict = {"model": info.id, "max_tokens": self.max_tokens(req, info, 1024),
                      "messages": [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content}
                                   for m in req.messages if m.role != "system"]}
        sys_text = self.system_text(req)
        if sys_text:
            body["system"] = sys_text
        t = req.temperature if req.temperature is not None else info.temperature
        if t is not None:
            body["temperature"] = t
        if req.tools:
            body["tools"] = [{"name": s.name, "description": s.description, "input_schema": json.loads(s.parameters_json_schema)} for s in req.tools]
        headers = {"Content-Type": "application/json", "Accept": "application/json",
                   "anthropic-version": self.cfg.setting("anthropic-version", "2023-06-01")}
        headers.update({k[7:]: v for k, v in self.cfg.settings.items() if k.startswith("header.")})
        if self.cfg.api_key:
            headers["x-api-key"] = self.cfg.api_key
        try:
            r = await self._client.post(self._url, json=body, headers=headers, timeout=req.timeout or self.cfg.timeout)
        except httpx.HTTPError as e:
            raise map_http_error(e) from None
        if r.status_code // 100 != 2:
            raise LlmError("failed", f"the model endpoint answered HTTP {r.status_code}")
        try:
            j = r.json()
        except ValueError:
            raise LlmError("failed", "the model endpoint answered something unreadable") from None
        text = "".join(b.get("text", "") for b in j.get("content") or [] if b.get("type") == "text")
        calls = tuple(ToolCall(b.get("id", ""), b.get("name", ""), json.dumps(b.get("input") or {})) for b in j.get("content") or []
                      if b.get("type") == "tool_use")
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        u = j.get("usage") or {}
        pi, co = int(u.get("input_tokens") or 0), int(u.get("output_tokens") or 0)
        return ChatResponse(text, calls, j.get("model", info.id), j.get("stop_reason") or "", Usage(pi, co, info.cost(pi, co)))

    async def aclose(self) -> None:
        await self._client.aclose()


def factory(cfg: ProviderConfig, runtime=None) -> AnthropicProvider:
    return AnthropicProvider(cfg, runtime)
