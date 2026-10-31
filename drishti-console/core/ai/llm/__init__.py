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

"""The console's LLM layer on the abstractions of sajhamcpserver (``sajha/ai/llm``): ``LLMProvider`` / ``LLMModel``, the canonical OpenAI-shaped types, ``ProviderBase``,
the provider registry and ``LLMFactory`` / ``GovernedModel``. The Java layer (``drishti-ai``, package ``com.ash.drishti.ai.llm``) has the same design in camelCase.
docs/admin/AI.md › Model providers, section 10 maps each Python name to its Java name.

    factory = request.app.state.ai.llm                        # an LLMFactory
    model = factory.model("default", context=CallContext(user, roles, "my-feature", ident))
    completion = await model.achat_completions_create(messages=[{"role": "user", "content": "hello"}])
    completion.text
"""
from core.ai.llm.base import LLMModel, LLMProvider
from core.ai.llm.canonical import (ChatCompletion, ChatCompletionChunk, ChatCompletionRequest, ChatMessage, ChunkAccumulator, DrishtiRequest, EmbeddingData,
                                   EmbeddingsRequest, EmbeddingsResponse, ResponseFormat, ToolCall, ToolDefinition, completion_to_chunks)
from core.ai.llm.errors import (AuthenticationFailed, BudgetExceeded, ConfigurationError, ContentFiltered, ContextTooLong, InvalidRequest, LLMError, ModelFailed,
                                NoModelAvailable, PolicyDenied, ProviderUnavailable, RateLimited, UnsupportedFeature)
from core.ai.llm.factory import GovernedModel, LLMFactory
from core.ai.llm.model import ChatModel, EmbeddingModel, HealthStatus, ModelCapabilities, ModelInfo
from core.ai.llm.provider import ProviderBase
from core.ai.llm.registry import load_class, provider_class, register_provider, registered_providers, unregister_provider

__all__ = [n for n in dir() if not n.startswith("_") and n[0].isupper()] + ["completion_to_chunks", "load_class", "provider_class", "register_provider",
                                                                           "registered_providers", "unregister_provider"]
