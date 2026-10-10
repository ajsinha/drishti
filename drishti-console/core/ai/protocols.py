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

"""The console's AI interfaces as ``typing.Protocol``s, mirroring the server's ``LlmProvider`` / ``LlmModel`` / ``EmbeddingModel`` /
``LlmRegistry`` / ``LlmPolicy``. A new provider is one class with these methods plus an entry in ``providers.FACTORIES``."""
from __future__ import annotations

from typing import Protocol, runtime_checkable

from core.ai.types import CallContext, ChatRequest, ChatResponse, ModelInfo, ProviderConfig


@runtime_checkable
class ChatModel(Protocol):
    info: ModelInfo

    async def chat(self, request: ChatRequest) -> ChatResponse: ...


@runtime_checkable
class EmbeddingModel(Protocol):
    info: ModelInfo

    async def embed(self, texts: list[str]) -> list[list[float]]: ...


@runtime_checkable
class LlmProvider(Protocol):
    id: str
    type: str

    def models(self) -> list[ModelInfo]: ...

    def chat_model(self, logical_name: str) -> ChatModel | None: ...

    def embedding_model(self, logical_name: str) -> EmbeddingModel | None: ...

    async def aclose(self) -> None: ...


class ProviderFactory(Protocol):
    """``FACTORIES[type] = factory``: builds a provider from one parsed ``config/ai/<name>.yaml``. ``runtime`` gives access to the
    console's backend client (the ``server`` provider)."""

    def __call__(self, config: ProviderConfig, runtime: object) -> LlmProvider: ...


class Policy(Protocol):
    def before(self, ctx: CallContext, model: ModelInfo, request: ChatRequest) -> ChatRequest: ...

    def after(self, ctx: CallContext, model: ModelInfo, request: ChatRequest, response: ChatResponse | None, error: Exception | None,
              millis: int) -> None: ...


class Registry(Protocol):
    def reload(self) -> None: ...

    def models(self) -> list[ModelInfo]: ...

    def chat(self, ref: str | None): ...
