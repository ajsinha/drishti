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

import time
from pathlib import Path

from core import retry as retrymod
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
        self.poll_seconds = max(0.5, float(settings.get("ai.poll_seconds", 5) or 5))
        self._fingerprint = None
        self.retry_ai = retrymod.RetryBoard("ai provider", retrymod.RetryPolicy.of(settings, "ai"), listener=lambda *a: self._retry_event("ai", *a))
        self.retry_mcp = retrymod.RetryBoard("mcp server", retrymod.RetryPolicy.of(settings, "mcp"), listener=lambda *a: self._retry_event("mcp", *a))
        self.registry = LlmRegistry(FACTORIES, (lambda: cfgmod.load_providers(self.dir, env)) if self.enabled else (lambda: ([], {})), self,
                                    str(settings.get("ai.default_model", "") or ""), self.retry_ai)
        self.gateway = LlmGateway(self.registry, DefaultPolicy(ps, masker, self._audit))
        self.mcp = McpRegistry((lambda: cfgmod.load_mcp(self.mcp_dir, env)) if self.mcp_enabled else (lambda: ([], {})), self, retry=self.retry_mcp)
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
        self._fingerprint = self._files()

    def _retry_event(self, area: str, event: str, key: str, detail: str) -> None:
        self._audit({"user": "system", "feature": "retry", "area": area, "name": key, "outcome": f"{area}-{event}", "detail": detail})

    def _files(self) -> tuple:
        """Names, sizes and modification times of the provider and MCP files: what a reload would read."""
        out = []
        for d, on in ((self.dir, self.enabled), (self.mcp_dir, self.mcp_enabled)):
            if not on:
                continue
            try:
                out.extend((f.name, f.stat().st_size, f.stat().st_mtime_ns) for f in sorted(Path(d).glob("*.y*ml")))
            except OSError:
                out.append((d, -1, 0))
        return tuple(out)

    async def tick(self) -> bool:
        """One look: reload when a file changed or something that failed is due for another try, then reconnect MCP servers that lost
        their connection. Returns whether a reload happened. Called every ``ai.poll_seconds`` by :meth:`supervise`."""
        import asyncio

        files = self._files()
        due = bool(self.retry_ai.due() or self.retry_mcp.due())
        reloaded = files != self._fingerprint or due
        if reloaded:
            await asyncio.to_thread(self.reload)
        await self.mcp.probe_down()
        return reloaded

    async def supervise(self) -> None:
        """Runs for the life of the console: files that change are picked up (no restart), and what failed is retried with backoff."""
        import asyncio
        import logging

        while True:
            await asyncio.sleep(self.poll_seconds)
            try:
                await self.tick()
            except Exception:                                # noqa: BLE001  (one bad pass must not end the supervision)
                logging.getLogger("drishti.ai").exception("ai/mcp supervision pass failed; it runs again")

    def retry_status(self) -> dict:
        now = time.time()
        return {"ai": {k: s.as_dict(now) for k, s in self.retry_ai.statuses().items()},
                "mcp": {k: s.as_dict(now) for k, s in self.retry_mcp.statuses().items()}}

    async def retry_now(self) -> int:
        """Admin "Retry now": everything failing is tried at once. Returns how many were failing."""
        n = self.retry_ai.retry_all_now() + self.retry_mcp.retry_all_now()
        await self.tick()
        return n

    async def aclose(self) -> None:
        await self.registry.aclose()


def _on(v) -> bool:
    return str(v).lower() in ("true", "1", "yes", "on")


__all__ = ["AiRuntime", "CallContext", "ChatMessage", "ChatRequest", "ChatResponse", "LlmError", "ModelInfo", "NoPolicy", "Path"]
