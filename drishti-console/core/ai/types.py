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

"""Value types of the console's AI layer; they mirror the server's ``drishti-ai`` module (docs/admin/AI.md)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

CHAT, STREAMING, TOOL_CALLS, EMBEDDINGS, JSON_MODE = "chat", "streaming", "tool-calls", "embeddings", "json-mode"
CAPABILITIES = (CHAT, STREAMING, TOOL_CALLS, EMBEDDINGS, JSON_MODE)


class LlmError(Exception):
    """A model call or a provider set-up failed. ``kind`` is config, not_found, unsupported, policy, rate_limited, timeout or failed.
    The message never carries a key or a prompt."""

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message

    @property
    def timeout(self) -> bool:
        return self.kind == "timeout"


@dataclass(frozen=True)
class ChatMessage:
    role: str                    # system | user | assistant | tool
    content: str = ""


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str = ""
    parameters_json_schema: str = '{"type":"object"}'


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments_json: str = "{}"


@dataclass(frozen=True)
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost: float = 0.0


@dataclass(frozen=True)
class ChatRequest:
    """A chat call, independent of any provider. ``model`` is ``provider/model``, a unique model name, or None for the default.
    ``context`` carries what a provider may need from the caller (the ``server`` provider forwards the signed-in identity)."""
    model: str | None = None
    system: str = ""
    messages: tuple[ChatMessage, ...] = ()
    tools: tuple[ToolSpec, ...] = ()
    temperature: float | None = None
    max_tokens: int | None = None
    json_mode: bool = False
    timeout: float | None = None
    context: dict = field(default_factory=dict, compare=False, repr=False)

    @staticmethod
    def of(user: str, system: str = "", model: str | None = None, max_tokens: int | None = None, timeout: float | None = None, **ctx: Any) -> "ChatRequest":
        return ChatRequest(model=model, system=system, messages=(ChatMessage("user", user),), max_tokens=max_tokens, timeout=timeout, context=ctx)

    def prompt_chars(self) -> int:
        return len(self.system) + sum(len(m.content) for m in self.messages)


@dataclass(frozen=True)
class ChatResponse:
    text: str = ""
    tool_calls: tuple[ToolCall, ...] = ()
    model: str = ""
    finish_reason: str = ""
    usage: Usage = Usage()


@dataclass(frozen=True)
class ModelInfo:
    name: str                    # the logical name
    provider: str
    id: str                      # the provider's own id
    kind: str = "chat"           # chat | embedding
    capabilities: tuple[str, ...] = (CHAT,)
    max_tokens: int | None = None
    temperature: float | None = None
    cost_per_1k_input: float = 0.0
    cost_per_1k_output: float = 0.0

    @property
    def ref(self) -> str:
        return f"{self.provider}/{self.name}"

    @property
    def embedding(self) -> bool:
        return self.kind == "embedding"

    def cost(self, prompt_tokens: int, completion_tokens: int) -> float:
        return prompt_tokens / 1000 * self.cost_per_1k_input + completion_tokens / 1000 * self.cost_per_1k_output


@dataclass(frozen=True)
class CallContext:
    """Who is calling and for which feature; the policy decides on this, never the provider."""
    user: str = "anonymous"
    roles: frozenset = frozenset()
    feature: str = "other"
    ident: Any = None            # the console identity, for the ``server`` provider


@dataclass(frozen=True)
class ProviderConfig:
    name: str
    type: str
    enabled: bool = True
    base_url: str = ""
    api_key: str = field(default="", repr=False)
    timeout: float = 60.0
    retries: int = 0
    default_model: str = ""
    settings: dict = field(default_factory=dict)
    models: tuple[dict, ...] = ()

    def setting(self, key: str, default: str = "") -> str:
        v = self.settings.get(key)
        return default if v is None or str(v).strip() == "" else str(v)
