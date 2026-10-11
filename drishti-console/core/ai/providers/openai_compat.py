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

"""``type: openai``: any OpenAI-compatible endpoint (OpenAI, Azure OpenAI, vLLM, Ollama's /v1, LiteLLM, a company gateway) over httpx.

Settings: ``base-url`` (default https://api.openai.com), ``completions-path`` (/v1/chat/completions), ``embeddings-path`` (/v1/embeddings),
``header.<Name>`` extra headers, ``tls.ca-file`` / ``tls.cert-file`` / ``tls.key-file``, ``retries`` (extra attempts after a failure)."""
from __future__ import annotations

import httpx

from core.ai.providers.base import CHAT_EXTRAS, BaseProvider, client_args, map_http_error, ssl_context
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage


class OpenAiCompatibleProvider(BaseProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime=None):
        super().__init__(cfg)
        base = (cfg.base_url or "https://api.openai.com").rstrip("/")
        self._url = base + cfg.setting("completions-path", "/v1/chat/completions")
        self._embed_url = base + cfg.setting("embeddings-path", "/v1/embeddings")
        args, self.proxy = client_args(cfg.settings, base)
        self._client = httpx.AsyncClient(verify=ssl_context(cfg, base), timeout=cfg.timeout, follow_redirects=False, trust_env=False, **args)

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json", "Accept": "application/json"}
        h.update({k[7:]: v for k, v in self.cfg.settings.items() if k.startswith("header.")})
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    async def _post(self, url: str, body: dict, timeout: float | None) -> dict:
        err: LlmError | None = None
        for _ in range(self.cfg.retries + 1):
            try:
                r = await self._client.post(url, json=body, headers=self._headers(), timeout=timeout or self.cfg.timeout)
            except httpx.HTTPError as e:
                err = map_http_error(e)
                continue
            if r.status_code // 100 != 2:
                err = LlmError("failed", f"the model endpoint answered HTTP {r.status_code}")
                if r.status_code < 500:
                    break
                continue
            try:
                return r.json()
            except ValueError:
                err = LlmError("failed", "the model endpoint answered something unreadable")
        raise err or LlmError("failed", "the model call failed")

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content} for m in req.messages if m.role != "system"]
        body = {"model": info.id, "messages": msgs, "max_tokens": self.max_tokens(req, info, 1024), "stream": False}
        t = req.temperature if req.temperature is not None else info.temperature
        if t is not None:
            body["temperature"] = t
        if req.json_mode:
            body["response_format"] = {"type": "json_object"}
        if req.tools:
            import json
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description,
                                                              "parameters": json.loads(s.parameters_json_schema)}} for s in req.tools]
        j = await self._post(self._url, body, req.timeout)
        choice = (j.get("choices") or [{}])[0]
        msg = choice.get("message") or {}
        calls = tuple(ToolCall(c.get("id", ""), (c.get("function") or {}).get("name", ""), (c.get("function") or {}).get("arguments", "{}"))
                      for c in msg.get("tool_calls") or [])
        text = msg.get("content")
        if not isinstance(text, str) and not calls:
            raise LlmError("failed", "the model endpoint's answer has no message content")
        u = j.get("usage") or {}
        pi, co = int(u.get("prompt_tokens") or 0), int(u.get("completion_tokens") or 0)
        return ChatResponse(text or "", calls, j.get("model", info.id), choice.get("finish_reason") or "", Usage(pi, co, info.cost(pi, co)))

    async def embed(self, info: ModelInfo, texts: list[str]) -> list[list[float]]:
        j = await self._post(self._embed_url, {"model": info.id, "input": texts}, None)
        return [d["embedding"] for d in sorted(j.get("data") or [], key=lambda d: d.get("index", 0))]

    async def aclose(self) -> None:
        await self._client.aclose()


def factory(cfg: ProviderConfig, runtime=None) -> OpenAiCompatibleProvider:
    return OpenAiCompatibleProvider(cfg, runtime)
