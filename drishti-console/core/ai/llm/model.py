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

"""Model metadata and the shared model implementations (``sajha/ai/llm/model.py``): ModelCapabilities, ModelInfo, HealthStatus, ChatModel, EmbeddingModel.

``ChatModel`` implements the abstract ``LLMModel`` for chat: a subclass writes ``_acreate`` (native async) or ``_create`` (sync) and the other four calls follow;
streaming is emulated from one completion unless ``_stream`` / ``_astream`` are overridden. ``EmbeddingModel`` does the same with ``_aembed`` / ``_embed``.
"""
from __future__ import annotations

import asyncio
import concurrent.futures
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Dict, FrozenSet, Iterator, List, Optional

from core.ai.llm.base import LLMModel
from core.ai.llm.canonical import (ChatCompletion, ChatCompletionChunk, ChatCompletionRequest, EmbeddingData, EmbeddingsRequest, EmbeddingsResponse,
                                   EmbeddingsUsage, completion_to_chunks)
from core.ai.llm.errors import UnsupportedFeature


@dataclass(frozen=True)
class ModelCapabilities:
    chat: bool = True
    tools: bool = False
    structured_output: bool = False
    vision: bool = False
    streaming: bool = True
    embedding: bool = False
    context_window: int = 0
    max_output_tokens: int = 4096
    input_cost_per_mtok: float = 0.0
    output_cost_per_mtok: float = 0.0
    dimensions: int = 0
    temperature: bool = True
    forced_tool_choice: bool = True
    tags: FrozenSet[str] = frozenset()
    json_mode: Optional[bool] = None

    def cost(self, input_tokens: int, output_tokens: int) -> float:
        return (input_tokens * self.input_cost_per_mtok + output_tokens * self.output_cost_per_mtok) / 1_000_000

    def to_dict(self) -> Dict[str, Any]:
        d = {k: getattr(self, k) for k in self.__dataclass_fields__}
        d["tags"] = sorted(self.tags)
        return d


@dataclass(frozen=True)
class ModelInfo:
    """One model as ``GET /v1/models`` would list it."""
    id: str
    provider: str
    kind: str = "chat"
    capabilities: ModelCapabilities = field(default_factory=ModelCapabilities)
    display_name: str = ""
    source: str = "config"

    @property
    def qualified_id(self) -> str:
        return f"{self.provider}/{self.id}"

    def to_dict(self) -> Dict[str, Any]:
        return {"id": self.qualified_id, "object": "model", "owned_by": self.provider,
                "drishti": {"model": self.id, "kind": self.kind, "display_name": self.display_name or self.id, "source": self.source,
                            "capabilities": self.capabilities.to_dict()}}


@dataclass
class HealthStatus:
    status: str = "ok"              # ok | degraded | down
    detail: str = ""

    @property
    def ok(self) -> bool:
        return self.status in ("ok", "degraded")

    def to_dict(self):
        return {"status": self.status, "detail": self.detail}


def run_sync(coro):
    """Runs a coroutine to its end from sync code, also when a loop is already running in this thread (it then runs on a thread of its own)."""
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(asyncio.run, coro).result()


class ChatModel(LLMModel):
    def __init__(self, provider: Any, model_id: str, info: Optional[ModelInfo] = None):
        self.provider, self.model_id = provider, model_id
        self._info = info or ModelInfo(model_id, getattr(provider, "name", "") or "")

    def info(self) -> ModelInfo:
        return self._info

    # hooks: implement _acreate or _create
    def _create(self, request: ChatCompletionRequest) -> ChatCompletion:
        if type(self)._acreate is ChatModel._acreate:
            raise NotImplementedError("a ChatModel implements _create or _acreate")
        return run_sync(self._acreate(request))

    async def _acreate(self, request: ChatCompletionRequest) -> ChatCompletion:
        if type(self)._create is ChatModel._create:
            raise NotImplementedError("a ChatModel implements _create or _acreate")
        return await asyncio.to_thread(self._create, request)

    def _stream(self, request: ChatCompletionRequest) -> Iterator[ChatCompletionChunk]:
        return completion_to_chunks(self._create(request))

    async def _astream(self, request: ChatCompletionRequest) -> AsyncIterator[ChatCompletionChunk]:
        for chunk in completion_to_chunks(await self._acreate(request)):
            yield chunk

    def chat_completions_create(self, request: Any = None, /, **fields) -> ChatCompletion:
        return self._create(ChatCompletionRequest.coerce(request, **fields))

    async def achat_completions_create(self, request: Any = None, /, **fields) -> ChatCompletion:
        return await self._acreate(ChatCompletionRequest.coerce(request, **fields))

    def chat_completions_stream(self, request: Any = None, /, **fields) -> Iterator[ChatCompletionChunk]:
        return self._stream(ChatCompletionRequest.coerce(request, **fields))

    def achat_completions_stream(self, request: Any = None, /, **fields) -> AsyncIterator[ChatCompletionChunk]:
        return self._astream(ChatCompletionRequest.coerce(request, **fields))

    def embeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        raise UnsupportedFeature(f"model {self._info.qualified_id} is a chat model; it has no embeddings")

    async def aembeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        raise UnsupportedFeature(f"model {self._info.qualified_id} is a chat model; it has no embeddings")


class EmbeddingModel(LLMModel):
    def __init__(self, provider: Any, model_id: str, info: Optional[ModelInfo] = None):
        self.provider, self.model_id = provider, model_id
        self._info = info or ModelInfo(model_id, getattr(provider, "name", "") or "", "embedding")

    def info(self) -> ModelInfo:
        return self._info

    def _embed(self, texts: List[str]) -> List[List[float]]:
        if type(self)._aembed is EmbeddingModel._aembed:
            raise NotImplementedError("an EmbeddingModel implements _embed or _aembed")
        return run_sync(self._aembed(texts))

    async def _aembed(self, texts: List[str]) -> List[List[float]]:
        if type(self)._embed is EmbeddingModel._embed:
            raise NotImplementedError("an EmbeddingModel implements _embed or _aembed")
        return await asyncio.to_thread(self._embed, texts)

    def _response(self, texts: List[str], out: List[List[float]]) -> EmbeddingsResponse:
        n = sum((len(t) + 3) // 4 for t in texts)
        return EmbeddingsResponse(data=[EmbeddingData(embedding=[float(x) for x in v], index=i) for i, v in enumerate(out)], model=self._info.id,
                                  usage=EmbeddingsUsage(prompt_tokens=n, total_tokens=n))

    def embeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        texts = EmbeddingsRequest.coerce(request, **fields).texts
        return self._response(texts, self._embed(texts))

    async def aembeddings_create(self, request: Any = None, /, **fields) -> EmbeddingsResponse:
        texts = EmbeddingsRequest.coerce(request, **fields).texts
        return self._response(texts, await self._aembed(texts))

    def _no_chat(self) -> UnsupportedFeature:
        return UnsupportedFeature(f"model {self._info.qualified_id} is an embedding model; it cannot chat")

    def chat_completions_create(self, request: Any = None, /, **fields) -> ChatCompletion:
        raise self._no_chat()

    async def achat_completions_create(self, request: Any = None, /, **fields) -> ChatCompletion:
        raise self._no_chat()

    def chat_completions_stream(self, request: Any = None, /, **fields) -> Iterator[ChatCompletionChunk]:
        raise self._no_chat()

    def achat_completions_stream(self, request: Any = None, /, **fields) -> AsyncIterator[ChatCompletionChunk]:
        raise self._no_chat()
