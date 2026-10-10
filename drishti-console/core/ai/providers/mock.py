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

"""``type: mock``: a provider with no network and deterministic answers, for tests, demos and features built before a model is chosen.
Configured under ``mock.*`` exactly like the server's mock (see docs/architecture/AI_PROVIDERS.md)::

    type: mock
    mock:
      reply: "a fixed answer"      # omit to echo the last user message
      latency-ms: 0                # simulated delay
      fail: ""                     # every call fails with this message
      fail-every: 0                # every Nth call fails
      dimensions: 8                # embedding vector size
      rules:                       # first rule whose regex is found in the last user message applies
        - {match: "(?i)hello", reply: "Hi there"}
        - {match: "weather", tool: get_weather, tool-args: '{"city":"Oslo"}'}
        - {match: "boom", error: "scripted failure"}

Usage is characters / 4, so cost limits and audit can be exercised."""
from __future__ import annotations

import asyncio
import re

from core.ai.providers.base import CHAT_EXTRAS, BaseProvider
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage


class MockProvider(BaseProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime=None):
        super().__init__(cfg)
        self.calls = 0
        self._reply = cfg.settings.get("mock.reply")
        self._latency = int(cfg.setting("mock.latency-ms", "0")) / 1000
        self._fail = cfg.setting("mock.fail")
        self._fail_every = int(cfg.setting("mock.fail-every", "0"))
        self._dims = max(1, int(cfg.setting("mock.dimensions", "8")))
        self._rules = []
        i = 0
        while f"mock.rules.{i}.match" in cfg.settings:
            p = f"mock.rules.{i}."
            self._rules.append((re.compile(cfg.settings[p + "match"]), cfg.setting(p + "reply"), cfg.setting(p + "tool"),
                                cfg.setting(p + "tool-args", "{}"), cfg.setting(p + "error"), cfg.setting(p + "latency-ms", "-1")))
            i += 1

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        self.calls += 1
        last = req.messages[-1].content if req.messages else ""
        hit = next((r for r in self._rules if r[0].search(last)), None)
        delay = int(hit[5]) / 1000 if hit and int(hit[5]) >= 0 else self._latency
        if delay > 0:
            await asyncio.sleep(delay)
        if self._fail or (self._fail_every > 0 and self.calls % self._fail_every == 0):
            raise LlmError("failed", self._fail or "mock: scheduled failure")
        if hit and hit[4]:
            raise LlmError("failed", hit[4])
        text = hit[1] if hit else (self._reply if self._reply is not None else "echo: " + last)
        calls = (ToolCall(f"mock-{self.calls}", hit[2], hit[3]),) if hit and hit[2] else ()
        pi, co = (req.prompt_chars() + 3) // 4, (len(text) + 3) // 4
        return ChatResponse(text, calls, info.id, "tool_calls" if calls else "stop", Usage(pi, co, info.cost(pi, co)))

    async def embed(self, info: ModelInfo, texts: list[str]) -> list[list[float]]:
        out = []
        for t in texts:
            v = [0.0] * self._dims
            for i, ch in enumerate(t):
                v[i % self._dims] += (ord(ch) % 31) / 31
            out.append(v)
        return out


def factory(cfg: ProviderConfig, runtime=None) -> MockProvider:
    return MockProvider(cfg, runtime)
