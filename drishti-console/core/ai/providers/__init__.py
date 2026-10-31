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

"""Provider types by name. To add one: write a class with the ``LlmProvider`` methods (``core.ai.providers.base.BaseProvider`` does the
bookkeeping), a ``factory(config, runtime)``, and add it here (or call ``register``)."""
from __future__ import annotations

from core.ai.providers import anthropic, azure, bedrock, cohere, gemini, mock, ollama, openai_compat, server

FACTORIES = {"openai": openai_compat.factory, "anthropic": anthropic.factory, "server": server.factory, "mock": mock.factory,
             # the providers of sajhamcpserver (docs/admin/AI.md › Model providers, section 11): the OpenAI-compatible presets, then one class each
             **{t: openai_compat.factory for t in ("openai_compatible", "groq", "together", "fireworks", "deepseek", "xai", "openrouter", "perplexity",
                                                   "huggingface", "vllm", "lmstudio", "mistral")},
             "azure_openai": azure.factory, "gemini": gemini.factory, "cohere": cohere.factory, "ollama": ollama.factory, "bedrock": bedrock.factory}


def register(type_name: str, factory) -> None:
    """Adds (or replaces) a provider type at start-up."""
    FACTORIES[type_name.lower()] = factory
