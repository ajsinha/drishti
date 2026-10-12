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

"""``type: ollama``: a local Ollama server over httpx (``OllamaProvider`` of sajhamcpserver). No key is needed.

``POST {base-url}/api/chat`` (tools with object arguments; ids are made up as ``call_<n>``), ``/api/embed``, and ``GET /api/tags`` for the models pulled on the host.
Settings: ``base-url`` (http://localhost:11434; ``OLLAMA_HOST`` / ``OLLAMA_BASE_URL`` are read, and a bare ``host:port`` gets ``http://``), ``keep-alive``, ``num-ctx``,
``think`` (true/false), ``options.<name>`` (any Ollama runtime option, for example ``options.top_k``), ``live-models`` (true: ``models()`` stays the catalogue, and
``live_models()`` lists what is pulled), ``read-timeout`` is the file's ``timeout`` (local models can be slow to load: raise it). An optional ``api-key`` is sent as a
bearer token (a proxy in front of Ollama). ``/api/show`` capability detection is not built."""
from __future__ import annotations

import json

from core.ai.providers.base import CHAT_EXTRAS
from core.ai.providers.http_base import JsonHttpProvider
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"stop": "stop", "length": "length", "load": "stop", "unload": "stop"}


def normalize_base(url: str) -> str:
    url = (url or "http://localhost:11434").strip().rstrip("/")
    if "://" not in url:
        host = url if url and not url.startswith(":") else "localhost" + url
        url = "http://" + (host if ":" in host else host + ":11434")
    return url


class OllamaProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime=None):
        super().__init__(cfg, normalize_base(cfg.base_url))

    def _headers(self) -> dict:
        h = super()._headers()
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    async def live_models(self) -> list[dict]:
        j = await self.get(self.base + "/api/tags", 5.0)
        out = []
        for m in j.get("models") or []:
            n = str(m.get("name") or m.get("model") or "")
            n = n[:-7] if n.endswith(":latest") else n
            if n:
                out.append({"id": n, "kind": "embedding" if "embed" in n else "chat"})
        return out

    async def probe(self) -> tuple[bool, str]:
        try:
            return True, f"{self.base}: {len(await self.live_models())} model(s) pulled"
        except LlmError as e:
            return False, f"{self.base} is not answering ({e.message})"

    def _options(self, req: ChatRequest, info: ModelInfo) -> dict:
        o = {k[8:]: _scalar(v) for k, v in self.cfg.settings.items() if k.startswith("options.")}
        o["num_predict"] = self.max_tokens(req, info, 1024)
        t = req.temperature if req.temperature is not None else info.temperature
        if t is not None:
            o["temperature"] = t
        if self.cfg.setting("num-ctx"):
            o["num_ctx"] = int(self.cfg.setting("num-ctx"))
        return o

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content} for m in req.messages if m.role != "system"]
        body: dict = {"model": info.id, "messages": msgs, "stream": False, "options": self._options(req, info)}
        if req.json_mode:
            body["format"] = "json"
        if self.cfg.setting("keep-alive"):
            body["keep_alive"] = _scalar(self.cfg.setting("keep-alive"))
        if self.cfg.setting("think"):
            body["think"] = self.cfg.setting("think").lower() == "true"
        if req.tools:
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description, "parameters": json.loads(s.parameters_json_schema)}}
                             for s in req.tools]
        j = await self.post(self.base + "/api/chat", body, req.timeout)
        msg = j.get("message") or {}
        text = msg.get("content") or ""
        calls = tuple(ToolCall(f"call_{i}", (c.get("function") or {}).get("name", ""), json.dumps((c.get("function") or {}).get("arguments") or {}, separators=(",", ":")))
                      for i, c in enumerate(msg.get("tool_calls") or []))
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        pi, co = int(j.get("prompt_eval_count") or 0), int(j.get("eval_count") or 0)
        return ChatResponse(text, calls, j.get("model") or info.id, "tool_calls" if calls else FINISH.get(j.get("done_reason", "stop"), "stop"),
                            Usage(pi, co, info.cost(pi, co)))

    async def embed(self, info: ModelInfo, texts: list[str]) -> list[list[float]]:
        j = await self.post(self.base + "/api/embed", {"model": info.id, "input": texts}, None)
        out = j.get("embeddings")
        if not isinstance(out, list) or len(out) != len(texts):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def _scalar(v):
    s = str(v)
    for cast in (int, float):
        try:
            return cast(s)
        except ValueError:
            pass
    return {"true": True, "false": False}.get(s.lower(), s)


def factory(cfg: ProviderConfig, runtime=None) -> OllamaProvider:
    return OllamaProvider(cfg, runtime)
