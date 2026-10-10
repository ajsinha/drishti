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

"""The one door console features use to call a model: resolve by logical name, policy before and after, timeout, health bookkeeping."""
from __future__ import annotations

import asyncio
import time
from dataclasses import replace

from core.ai.policy import NoPolicy
from core.ai.registry import LlmRegistry
from core.ai.types import CHAT, JSON_MODE, TOOL_CALLS, CallContext, ChatRequest, ChatResponse, LlmError


class LlmGateway:
    def __init__(self, registry: LlmRegistry, policy=None):
        self.registry = registry
        self.policy = policy or NoPolicy()

    async def chat(self, ctx: CallContext, request: ChatRequest) -> ChatResponse:
        entry, model = self.registry.chat(request.model)
        info = model.info
        if CHAT not in info.capabilities:
            raise LlmError("unsupported", f"model {info.ref} cannot chat")
        masked = self.policy.before(ctx, info, request)
        if masked.json_mode and JSON_MODE not in info.capabilities:
            raise LlmError("unsupported", f"model {info.ref} has no json-mode")
        if masked.tools and TOOL_CALLS not in info.capabilities:
            raise LlmError("unsupported", f"model {info.ref} has no tool-calls")
        masked = replace(masked, context={**masked.context, "ident": ctx.ident, "feature": ctx.feature})
        t0 = time.monotonic()
        try:
            try:
                resp = await asyncio.wait_for(model.chat(masked), timeout=masked.timeout) if masked.timeout else await model.chat(masked)
            except asyncio.TimeoutError:
                raise LlmError("timeout", f"the model endpoint did not answer within {int(masked.timeout or 0)}s") from None
        except LlmError as e:
            self.registry.record(entry, False, e.message)
            self.policy.after(ctx, info, masked, None, e, int((time.monotonic() - t0) * 1000))
            raise
        self.registry.record(entry, True)
        self.policy.after(ctx, info, masked, resp, None, int((time.monotonic() - t0) * 1000))
        return resp
