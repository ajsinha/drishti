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

"""``type: cohere``: Cohere's v2 API over httpx (``CohereProvider`` of sajhamcpserver; no SDK).

``POST {base-url}/v2/chat`` and ``/v2/embed`` with ``Authorization: Bearer``. Settings: ``base-url`` (https://api.cohere.com, or ``COHERE_BASE_URL``), ``chat-path``
(/v2/chat), ``embed-path`` (/v2/embed), ``embedding-input-type`` (search_document), the key from ``api-key: ${COHERE_API_KEY}`` (or ``COHERE_API_KEY`` /
``CO_API_KEY`` in the environment). Cohere's ``document`` tool-result format is not built."""
from __future__ import annotations

import json

from core.ai.providers.base import CHAT_EXTRAS
from core.ai.providers.http_base import JsonHttpProvider
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"COMPLETE": "stop", "STOP_SEQUENCE": "stop", "MAX_TOKENS": "length", "TOOL_CALL": "tool_calls", "ERROR": "stop", "TIMEOUT": "stop"}


class CohereProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime=None):
        super().__init__(cfg, cfg.base_url or "https://api.cohere.com")
        self._chat = self.base + cfg.setting("chat-path", "/v2/chat")
        self._embed = self.base + cfg.setting("embed-path", "/v2/embed")

    def _headers(self) -> dict:
        h = super()._headers()
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    def check(self) -> tuple[bool, str]:
        if not self.cfg.api_key:
            return False, "no api key: set COHERE_API_KEY or api-key: ${COHERE_API_KEY}"
        return True, "configured; no call is made to find out"

    async def live_models(self) -> list[dict]:
        j = await self.get(f"{self.base}/v1/models", 5.0)
        return [{"id": m["name"], "kind": "embedding" if "embed" in (m.get("endpoints") or []) else "chat"} for m in j.get("models") or [] if m.get("name")]

    async def probe(self) -> tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            return True, f"the model list answered ({len(await self.live_models())} models)"
        except LlmError as e:
            return False, e.message

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content} for m in req.messages if m.role != "system"]
        body: dict = {"model": info.id, "messages": msgs, "max_tokens": self.max_tokens(req, info, 1024), "stream": False}
        t = req.temperature if req.temperature is not None else info.temperature
        if t is not None:
            body["temperature"] = t
        if req.json_mode:
            body["response_format"] = {"type": "json_object"}
        if req.tools:
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description, "parameters": json.loads(s.parameters_json_schema)}}
                             for s in req.tools]
        j = await self.post(self._chat, body, req.timeout)
        msg = j.get("message") or {}
        content = msg.get("content")
        text = "".join(b.get("text", "") for b in content if b.get("type", "text") == "text") if isinstance(content, list) else (content or "")
        calls = tuple(ToolCall(c.get("id", ""), (c.get("function") or {}).get("name", ""), (c.get("function") or {}).get("arguments") or "{}")
                      for c in msg.get("tool_calls") or [])
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        tok = (j.get("usage") or {}).get("tokens") or (j.get("usage") or {}).get("billed_units") or {}
        pi, co = int(tok.get("input_tokens") or 0), int(tok.get("output_tokens") or 0)
        return ChatResponse(text, calls, info.id, "tool_calls" if calls else FINISH.get(j.get("finish_reason", "COMPLETE"), "stop"), Usage(pi, co, info.cost(pi, co)))

    async def embed(self, info: ModelInfo, texts: list[str]) -> list[list[float]]:
        body = {"model": info.id, "texts": texts, "input_type": self.cfg.setting("embedding-input-type", "search_document"), "embedding_types": ["float"]}
        j = await self.post(self._embed, body, None)
        out = (j.get("embeddings") or {}).get("float") if isinstance(j.get("embeddings"), dict) else j.get("embeddings")
        if not isinstance(out, list) or len(out) != len(texts):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def factory(cfg: ProviderConfig, runtime=None) -> CohereProvider:
    return CohereProvider(cfg, runtime)
