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

"""The console's AI layer: provider and MCP interfaces, implementations, registry, policy and gateway. Off by default (``ai.enabled``,
``mcp.enabled``). Console features use ``app.state.ai`` (an :class:`AiRuntime`)::

    resp = await request.app.state.ai.gateway.chat(CallContext(user, roles, "my-feature", ident), ChatRequest.of("question"))

See docs/architecture/AI_PROVIDERS.md."""
from __future__ import annotations

from pathlib import Path

from core.ai import config as cfgmod
from core.ai.gateway import LlmGateway
from core.ai.mcp import McpGateway, McpPolicy, McpRegistry
from core.ai.policy import DefaultPolicy, NoPolicy, PolicySettings, PromptMasker
from core.ai.providers import FACTORIES
from core.ai.registry import LlmRegistry
from core.ai.types import CallContext, ChatMessage, ChatRequest, ChatResponse, LlmError, ModelInfo  # noqa: F401  (the public names)


class AiRuntime:
    """Everything the console's AI layer needs, built once from the settings. ``backend`` is a callable returning the current backend client
    (the ``server`` provider and transport use it)."""

    def __init__(self, settings, backend=None, env=None):
        self.enabled = _on(settings.get("ai.enabled", False))
        self.mcp_enabled = _on(settings.get("mcp.enabled", False))
        self.dir = str(settings.get("ai.dir", "./config/ai"))
        self.mcp_dir = str(settings.get("mcp.dir", "./config/mcp"))
        self._backend = backend
        redact = list(settings.get("ai.redact", []) or [])
        patterns = list(settings.get("ai.policy.mask_patterns", []) or [])
        masker = PromptMasker(redact, patterns)
        ps = PolicySettings(mask_redacted=_on(settings.get("ai.policy.mask_redacted", True)), audit=_on(settings.get("ai.policy.audit", True)),
                            per_user_per_minute=int(settings.get("ai.policy.per_user_per_minute", 0) or 0),
                            per_user_per_day=int(settings.get("ai.policy.per_user_per_day", 0) or 0),
                            cost_per_user_per_day=float(settings.get("ai.policy.cost_per_user_per_day", 0) or 0),
                            feature_roles={k: list(v.get("roles", [])) for k, v in (settings.get("ai.policy.features", {}) or {}).items()})
        self.audit_log: list[dict] = []
        self.registry = LlmRegistry(FACTORIES, (lambda: cfgmod.load_providers(self.dir, env)) if self.enabled else (lambda: ([], {})), self,
                                    str(settings.get("ai.default_model", "") or ""))
        self.gateway = LlmGateway(self.registry, DefaultPolicy(ps, masker, self._audit))
        self.mcp = McpRegistry((lambda: cfgmod.load_mcp(self.mcp_dir, env)) if self.mcp_enabled else (lambda: ([], {})), self)
        self.mcp_gateway = McpGateway(self.mcp, McpPolicy(
            server_roles=dict(settings.get("mcp.server_roles", {}) or {}), tool_roles=dict(settings.get("mcp.tool_roles", {}) or {}),
            server_features=dict(settings.get("mcp.server_features", {}) or {}), redacted_fields=frozenset(redact), masker=masker, audit=self._audit))
        self.reload()

    def _audit(self, record: dict) -> None:
        self.audit_log.append(record)
        del self.audit_log[:-500]

    def backend(self):
        if self._backend is None:
            raise LlmError("config", "the 'server' provider needs the console's backend client")
        return self._backend()

    def reload(self) -> None:
        self.registry.reload()
        self.mcp.reload()

    async def aclose(self) -> None:
        await self.registry.aclose()


def _on(v) -> bool:
    return str(v).lower() in ("true", "1", "yes", "on")


__all__ = ["AiRuntime", "CallContext", "ChatMessage", "ChatRequest", "ChatResponse", "LlmError", "ModelInfo", "NoPolicy", "Path"]
