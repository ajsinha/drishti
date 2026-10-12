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

"""``type: openai`` and its relatives: any OpenAI-compatible endpoint (OpenAI, vLLM, Ollama's /v1, LiteLLM, a company gateway) over httpx, and the presets of
``sajha/ai/llm/providers/openai_compat.py``: ``groq``, ``together``, ``fireworks``, ``deepseek``, ``xai``, ``openrouter``, ``perplexity``, ``huggingface``, ``vllm``,
``lmstudio``, ``mistral`` and the generic ``openai_compatible``. A preset is a base URL, the environment variable a key is read from and a model catalogue
(``llm_catalog.json``); everything else is this class.

Settings: ``base-url`` (``openai``: https://api.openai.com; a preset: its own, with ``/v1`` in it), ``completions-path`` / ``chat-path`` (``/v1/chat/completions`` for
``openai``, ``/chat/completions`` for a preset), ``embeddings-path``, ``max-tokens-param`` (``max_tokens``, or ``max_completion_tokens``), ``live-models`` (list the
server's own models with ``GET models-path``; on by default for the keyless presets), ``models-path`` (``/models``), ``header.<Name>`` extra headers,
``proxy.*``, ``tls.*``, ``retries``."""
from __future__ import annotations

import json

from core.ai.providers import catalog
from core.ai.providers.base import CHAT_EXTRAS
from core.ai.providers.http_base import JsonHttpProvider
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage


class OpenAiCompatibleProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime=None):
        self.preset = catalog.preset(cfg.type)
        base = (cfg.base_url or (self.preset or {}).get("base-url") or ("" if self.preset else "https://api.openai.com")).rstrip("/")
        if not base:
            raise LlmError("config", "base-url is required for this type (the server's address, for example http://localhost:8000/v1)")
        super().__init__(cfg, base)
        chat = cfg.setting("completions-path") or cfg.setting("chat-path") or ("/chat/completions" if self.preset else "/v1/chat/completions")
        self._url = base + chat
        self._embed_url = base + cfg.setting("embeddings-path", "/embeddings" if self.preset else "/v1/embeddings")
        self._tokens_param = cfg.setting("max-tokens-param", (self.preset or {}).get("max-tokens-param", "max_tokens"))
        self._models_url = base + cfg.setting("models-path", "/models")

    def chat_url(self, info: ModelInfo) -> str:
        return self._url

    def embeddings_url(self, info: ModelInfo) -> str:
        return self._embed_url

    def wire_id(self, info: ModelInfo) -> str:
        """The ``model`` of the request body (Azure: the deployment)."""
        return info.id

    def _headers(self) -> dict:
        h = super()._headers()
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    def _keyless(self) -> bool:
        return self.preset is None or not self.preset.get("requires-key", True)

    def check(self) -> tuple[bool, str]:
        if not self._keyless() and not self.cfg.api_key:
            names = " or ".join(self.preset.get("key-env") or []) or "api-key"
            return False, f"no api key: set {names} (or api-key: ${{ENV_NAME}})"
        return True, "configured; no call is made to find out"

    async def probe(self) -> tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            await self.get(self._models_url, 5.0)
        except LlmError as e:
            return False, e.message
        return True, "the model list answered"

    async def live_models(self) -> list[dict]:
        live = self.cfg.setting("live-models", "")
        if (live or ("true" if self._keyless() and self.preset else "false")).lower() != "true":
            return []
        j = await self.get(self._models_url, 5.0)
        return [{"id": m["id"], "kind": "embedding" if "embed" in m["id"] else "chat"} for m in j.get("data") or [] if m.get("id")]

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content} for m in req.messages if m.role != "system"]
        body = {"model": self.wire_id(info), "messages": msgs, self._tokens_param: self.max_tokens(req, info, 1024), "stream": False}
        t = req.temperature if req.temperature is not None else info.temperature
        if t is not None:
            body["temperature"] = t
        if req.json_mode:
            body["response_format"] = {"type": "json_object"}
        if req.tools:
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description,
                                                              "parameters": json.loads(s.parameters_json_schema)}} for s in req.tools]
        j = await self.post(self.chat_url(info), body, req.timeout)
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
        j = await self.post(self.embeddings_url(info), {"model": self.wire_id(info), "input": texts}, None)
        return [d["embedding"] for d in sorted(j.get("data") or [], key=lambda d: d.get("index", 0))]


def factory(cfg: ProviderConfig, runtime=None) -> OpenAiCompatibleProvider:
    return OpenAiCompatibleProvider(cfg, runtime)
