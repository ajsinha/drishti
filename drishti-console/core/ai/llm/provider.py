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

"""ProviderBase: the shared implementation of the abstract ``LLMProvider`` (``sajha/ai/llm/provider.py``), over the wire-level providers in ``core/ai/providers/``
(``openai``, ``anthropic``, ``server``, ``mock``), whose HTTP code is unchanged. The wire call is translated to and from the canonical types here, once, so a
provider is written once."""
from __future__ import annotations

import os
import time
from typing import Any, Dict, List, Optional

from core.ai import config as cfgmod
from core.ai.llm import errors as E
from core.ai.llm.base import LLMProvider
from core.ai.llm.canonical import (ChatCompletion, ChatCompletionRequest, ChatMessage, Choice, CompletionUsage, ResponseDrishti, ToolCall)
from core.ai.llm.model import ChatModel, EmbeddingModel, HealthStatus, ModelCapabilities, ModelInfo, run_sync
from core.ai.types import CHAT, EMBEDDINGS, JSON_MODE, STREAMING, TOOL_CALLS, ChatMessage as WireMessage, ChatRequest, LlmError, ToolSpec


def to_wire(req: ChatCompletionRequest, ctx: Optional[dict] = None, model: Optional[str] = None, timeout: Optional[float] = None) -> ChatRequest:
    """The wire request for a canonical one. ``system`` / ``developer`` messages stay in ``messages`` (the providers fold them into the system text)."""
    msgs = tuple(WireMessage("system" if m.role in ("system", "developer") else m.role, m.text) for m in req.messages)
    tools = tuple(ToolSpec(t.name, (t.function.description or "") if t.function else "", _schema_json(t)) for t in req.tools or [])
    json_mode = bool(req.response_format and req.response_format.type != "text")
    return ChatRequest(model=model, system="", messages=msgs, tools=tools, temperature=req.temperature, max_tokens=req.max_output_tokens, json_mode=json_mode,
                       timeout=timeout, context=dict(ctx or {}))


def _schema_json(t) -> str:
    import json
    return json.dumps(t.function.parameters) if t.function and t.function.parameters else '{"type":"object"}'


def with_texts(req: ChatCompletionRequest, masked: ChatRequest) -> ChatCompletionRequest:
    """The same canonical request with the masked texts a policy returned, position by position."""
    msgs = [m.model_copy(update={"content": masked.messages[i].content if i < len(masked.messages) else m.text}) for i, m in enumerate(req.messages)]
    return req.model_copy(update={"messages": msgs})


def from_wire(resp, winfo, millis: int = 0) -> ChatCompletion:
    calls = [ToolCall.of(c.id, c.name, c.arguments_json) for c in resp.tool_calls]
    finish = resp.finish_reason or ("tool_calls" if calls else "stop")
    return ChatCompletion(model=resp.model or winfo.id, choices=[Choice(message=ChatMessage.assistant(resp.text, calls or None), finish_reason=finish)],
                          usage=CompletionUsage.of(resp.usage.prompt_tokens, resp.usage.completion_tokens),
                          drishti=ResponseDrishti(provider=winfo.provider, qualified_model=winfo.ref, cost_usd=resp.usage.cost, latency_ms=millis))


def info_of(w) -> ModelInfo:
    c = set(w.capabilities)
    caps = ModelCapabilities(chat=CHAT in c, tools=TOOL_CALLS in c, structured_output=JSON_MODE in c, streaming=True, embedding=EMBEDDINGS in c,
                             max_output_tokens=w.max_tokens or 4096, input_cost_per_mtok=w.cost_per_1k_input * 1000, output_cost_per_mtok=w.cost_per_1k_output * 1000,
                             json_mode=True if JSON_MODE in c else None)
    return ModelInfo(w.name, w.provider, w.kind, caps, w.name, "config")


class _WireChat(ChatModel):
    def __init__(self, provider: "ProviderBase", wire_model):
        super().__init__(provider, wire_model.info.name, info_of(wire_model.info))
        self._wire = wire_model

    async def _acreate(self, request: ChatCompletionRequest) -> ChatCompletion:
        ctx = request.context
        t0 = time.monotonic()
        try:
            resp = await self._wire.chat(to_wire(request, {"ident": getattr(ctx, "ident", None), "feature": getattr(ctx, "feature", "other")}))
        except LlmError as e:
            raise E.from_wire(e, provider=self.provider.name, model=self.model_id) from None
        return from_wire(resp, self._wire.info, int((time.monotonic() - t0) * 1000))


class _WireEmbed(EmbeddingModel):
    def __init__(self, provider: "ProviderBase", wire_model):
        super().__init__(provider, wire_model.info.name, info_of(wire_model.info))
        self._wire = wire_model

    async def _aembed(self, texts: List[str]) -> List[List[float]]:
        try:
            return await self._wire.embed(texts)
        except LlmError as e:
            raise E.from_wire(e, provider=self.provider.name, model=self.model_id) from None


class ProviderBase(LLMProvider):
    name = ""

    def __init__(self, cfg, wire):
        self.cfg, self._wire = cfg, wire
        self.name = cfg.name                         # the instance's name (a file name), as in sajhamcpserver where it is the provider's key
        self.type = cfg.type

    @classmethod
    def from_settings(cls, name: str, config: Optional[Dict[str, Any]] = None, *, runtime: Any = None, env=None, **kw) -> "ProviderBase":
        from core.ai.providers import FACTORIES
        data = dict(config or {})
        typ = str(data.get("type") or cls.name or "").lower()
        if typ not in FACTORIES:
            raise E.ConfigurationError(f"no provider type '{typ}' is installed (have: {sorted(FACTORIES)})")
        try:
            cfg = cfgmod.provider_from_dict(data if data.get("type") else {**data, "type": typ}, name, env or os.environ.get)
            return cls(cfg, FACTORIES[typ](cfg, runtime))
        except LlmError as e:
            raise E.from_wire(e, provider=name) from None

    @property
    def active(self) -> bool:
        return bool(self.cfg.enabled)

    def models(self) -> List[ModelInfo]:
        return [info_of(m) for m in self._wire.models()]

    def model(self, name: str = "", kind: str = "") -> LLMModel_:
        embedding = kind == "embedding" or (not kind and any(m.id == name and m.kind == "embedding" for m in self.models()))
        return self.embedding_model(name) if embedding else self.chat_model(name)

    def _default_chat(self) -> str:
        return self.cfg.default_model or next((m.id for m in self.models() if m.kind != "embedding"), "")

    def chat_model(self, model_id: str = "", **options) -> ChatModel:
        mid = model_id or self._default_chat()
        w = self._wire.chat_model(mid)
        if w is None:
            raise E.NoModelAvailable(f"provider {self.name} has no chat model '{mid}'", provider=self.name, model=mid)
        return _WireChat(self, w)

    def embedding_model(self, model_id: str = "") -> EmbeddingModel:
        mid = model_id or next((m.id for m in self.models() if m.kind == "embedding"), "")
        w = self._wire.embedding_model(mid)
        if w is None:
            raise E.NoModelAvailable(f"provider {self.name} has no embedding model '{mid}'", provider=self.name, model=mid)
        return _WireEmbed(self, w)

    def health(self) -> HealthStatus:
        return HealthStatus("ok", "configured; no call is made to find out") if self.cfg.enabled else HealthStatus("down", "enabled: false")

    def describe_config(self) -> Dict[str, Any]:
        c = self.cfg
        return {"name": c.name, "type": c.type, "enabled": c.enabled, "baseUrl": c.base_url, "apiKey": "********" if c.api_key else "",
                "timeoutSeconds": c.timeout, "defaultModel": c.default_model, "models": [m.id for m in self.models()]}

    def close(self) -> None:
        run_sync(self._wire.aclose())


LLMModel_ = Any
