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

"""The abstract LLM API: two abstract base classes shaped like an OpenAI client.

IDENTICAL, name for name and signature for signature, to ``sajha/ai/llm/base.py`` in sajhamcpserver; the Java layer has the same two types in camelCase
(``LLMModel.chatCompletionsCreate``, ``LLMProvider.describeConfig`` ... in ``com.ash.drishti.ai.llm``). docs/admin/AI.md › Model providers, section 10 has the table.

    LLMModel      one model: chat_completions_create / chat_completions_stream and their async twins, embeddings_create / aembeddings_create, and info().
                  A chat model refuses embeddings with UnsupportedFeature and an embedding model refuses chat the same way.
    LLMProvider   a vendor or runtime: a factory for its LLMModel objects (model, chat_model, embedding_model), its catalogue (models), health, configuration and close.

Application code never sees which provider is behind an LLMModel: ``LLMFactory.model()`` hands out GovernedModel proxies that implement LLMModel and run the AI policy
(masks, audit, limits, roles) before delegating.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING, Any, AsyncIterator, ClassVar, Dict, Iterator, List, Optional, Type

if TYPE_CHECKING:   # pragma: no cover
    from core.ai.llm.canonical import ChatCompletion, ChatCompletionChunk, EmbeddingsResponse
    from core.ai.llm.model import HealthStatus, ModelInfo


class LLMModel(ABC):
    """One model behind an OpenAI-style interface. ``request`` is a canonical request object, a dict, or keyword fields (``messages=[...]``, ``input=[...]``)."""

    @abstractmethod
    def info(self) -> "ModelInfo":
        """The model as one entry of GET /v1/models (id, provider, kind, capabilities)."""

    @abstractmethod
    def chat_completions_create(self, request: Any = None, /, **fields) -> "ChatCompletion":
        """POST /v1/chat/completions."""

    @abstractmethod
    async def achat_completions_create(self, request: Any = None, /, **fields) -> "ChatCompletion":
        """The native-async twin of chat_completions_create."""

    @abstractmethod
    def chat_completions_stream(self, request: Any = None, /, **fields) -> Iterator["ChatCompletionChunk"]:
        """POST /v1/chat/completions with stream=true, as chunks."""

    @abstractmethod
    def achat_completions_stream(self, request: Any = None, /, **fields) -> AsyncIterator["ChatCompletionChunk"]:
        """The async twin of chat_completions_stream (an async generator)."""

    @abstractmethod
    def embeddings_create(self, request: Any = None, /, **fields) -> "EmbeddingsResponse":
        """POST /v1/embeddings."""

    @abstractmethod
    async def aembeddings_create(self, request: Any = None, /, **fields) -> "EmbeddingsResponse":
        """The async twin of embeddings_create."""


class LLMProvider(ABC):
    """A vendor or runtime: credentials, HTTP clients, a model catalogue, and a factory for its models."""

    name: ClassVar[str] = ""
    config_model: ClassVar[Optional[Type[Any]]] = None

    @classmethod
    @abstractmethod
    def from_settings(cls, name: str, config: Optional[Dict[str, Any]] = None, **kw) -> "LLMProvider":
        """Build the provider from its settings block: what a ``config/ai/<name>.yaml`` file holds, as a dict."""

    @property
    @abstractmethod
    def active(self) -> bool:
        """Enabled."""

    @abstractmethod
    def models(self) -> List["ModelInfo"]:
        """Every model of this provider with resolved capabilities (like GET /v1/models)."""

    @abstractmethod
    def model(self, name: str = "", kind: str = "") -> LLMModel:
        """A model by id; ``kind`` (chat | embedding) defaults to what the catalogue says."""

    @abstractmethod
    def chat_model(self, model_id: str = "", **options) -> LLMModel:
        """A chat model (the provider's default when ``model_id`` is empty)."""

    @abstractmethod
    def embedding_model(self, model_id: str = "") -> LLMModel:
        """An embedding model (the provider's default when ``model_id`` is empty)."""

    @abstractmethod
    def health(self) -> "HealthStatus":
        """Cheap and bounded: no call is made."""

    @abstractmethod
    def describe_config(self) -> Dict[str, Any]:
        """The effective configuration (secrets masked)."""

    @abstractmethod
    def close(self) -> None:
        """Release HTTP clients and other resources."""


__all__ = ["LLMModel", "LLMProvider"]
