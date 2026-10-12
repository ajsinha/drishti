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

"""LLMFactory and GovernedModel (``sajha/ai/llm/factory.py`` and ``governed.py``): the one door application code uses.

``LLMFactory.model(name)`` hands out a ``GovernedModel``: an ``LLMModel`` whose every call goes through the AI policy (prompt masking, audit, per-user / per-feature limits,
feature roles, health: the existing ``LlmGateway`` and ``DefaultPolicy``) before it reaches a provider. Application code holds an ``LLMModel`` and never knows which provider is behind it.
"""
from __future__ import annotations

import time
from typing import Any, AsyncIterator, Iterator, List, Optional

from core.ai.gateway import LlmGateway
from core.ai.llm import errors as E
from core.ai.llm.canonical import (ChatCompletion, ChatCompletionChunk, ChatCompletionRequest, EmbeddingsRequest, EmbeddingsResponse, completion_to_chunks)
from core.ai.llm.model import ChatModel, EmbeddingModel, ModelInfo, run_sync
from core.ai.llm.provider import ProviderBase, from_wire, info_of, to_wire
from core.ai.types import CallContext, LlmError


class GovernedModel(ChatModel):
    """A chat (and, by ``kind="embedding"``, embedding) model behind the policy."""

    def __init__(self, gateway: LlmGateway, ref: Optional[str], info: Optional[ModelInfo], context: Optional[CallContext]):
        super().__init__(None, ref or "default", info)
        self._gw, self._ref, self._ctx = gateway, ref, context or CallContext()

    def _caller(self, request: ChatCompletionRequest) -> CallContext:
        return request.context if isinstance(request.context, CallContext) else self._ctx

    async def _acreate(self, request: ChatCompletionRequest) -> ChatCompletion:
        ctx = self._caller(request)
        t0 = time.monotonic()
        try:
            resp = await self._gw.chat(ctx, to_wire(request, model=self._ref))
            _, wire_model = self._gw.registry.chat(self._ref)
        except LlmError as e:
            raise E.from_wire(e, model=self._ref or "") from None
        return from_wire(resp, wire_model.info, int((time.monotonic() - t0) * 1000))

    async def aembeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        req = EmbeddingsRequest.coerce(request, **fields)
        ref = self._ref or req.model
        m = self._gw.registry.embedding(ref)
        if m is None:
            raise E.NoModelAvailable(f"no embedding model '{ref}' is configured", model=ref)
        try:
            vectors = await m.embed(req.texts)
        except LlmError as e:
            raise E.from_wire(e, model=ref) from None

        class _Shim(EmbeddingModel):
            async def _aembed(self, texts):
                return vectors
        return await _Shim(None, m.info.name, info_of(m.info)).aembeddings_create(req)

    def embeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        return run_sync(self.aembeddings_create(request, **fields))


class LLMFactory:
    def __init__(self, gateway: LlmGateway):
        self.gateway = gateway

    def model(self, name: str = "default", *, kind: str = "", context: Optional[CallContext] = None) -> GovernedModel:
        """A governed model by ``provider/model``, a model name only one provider has, or (``default``) the layer's default. ``kind`` is chat or embedding."""
        ref = None if not name or name == "default" else name
        if kind == "embedding":
            return GovernedModel(self.gateway, ref, ModelInfo(name, "", "embedding"), context)
        try:
            _, m = self.gateway.registry.chat(ref)
        except LlmError as e:
            raise E.from_wire(e, model=name) from None
        return GovernedModel(self.gateway, ref, info_of(m.info), context)

    def provider(self, name: str) -> Optional[ProviderBase]:
        e = next((x for x in self.gateway.registry.entries() if x.config.name == name and x.usable), None)
        return ProviderBase(e.config, e.provider) if e else None

    def provider_names(self) -> List[str]:
        return [e.config.name for e in self.gateway.registry.entries() if e.usable]

    @staticmethod
    def provider_types() -> dict:
        from core.ai.llm.registry import registered_providers
        return {k: v.__name__ for k, v in registered_providers().items()}

    # passthrough to the default model, as the caller in the request (drishti.context) or anonymous

    def _for(self, request: Any, fields: dict) -> tuple:
        req = ChatCompletionRequest.coerce(request, **fields)
        return req, self.model(req.model, context=req.context if isinstance(req.context, CallContext) else None)

    def chat_completions_create(self, request: Any = None, /, **fields) -> ChatCompletion:
        req, m = self._for(request, fields)
        return m.chat_completions_create(req)

    async def achat_completions_create(self, request: Any = None, /, **fields) -> ChatCompletion:
        req, m = self._for(request, fields)
        return await m.achat_completions_create(req)

    def chat_completions_stream(self, request: Any = None, /, **fields) -> Iterator[ChatCompletionChunk]:
        req, m = self._for(request, fields)
        return m.chat_completions_stream(req)

    def achat_completions_stream(self, request: Any = None, /, **fields) -> AsyncIterator[ChatCompletionChunk]:
        req, m = self._for(request, fields)
        return m.achat_completions_stream(req)

    def embeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        req = EmbeddingsRequest.coerce(request, **fields)
        return self.model(req.model, kind="embedding").embeddings_create(req)

    async def aembeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        req = EmbeddingsRequest.coerce(request, **fields)
        return await self.model(req.model, kind="embedding").aembeddings_create(req)
