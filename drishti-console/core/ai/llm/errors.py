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

"""The error hierarchy of the LLM layer: the names of ``sajha/ai/llm/errors.py`` in sajhamcpserver. Each carries a ``kind`` equal to the Java layer's ``LlmException.Kind``,
so the same scenario raises the same kind in both (the shared contract test, ``tests/test_llm_contract.py``). The message never carries a key or a prompt."""
from __future__ import annotations

from typing import Optional

from core.ai.types import LlmError


class LLMError(Exception):
    kind = "FAILED"

    def __init__(self, message: str = "", *, provider: str = "", model: str = "", kind: Optional[str] = None):
        super().__init__(message)
        self.message, self.provider, self.model = message, provider, model
        if kind:
            self.kind = kind

    def to_dict(self) -> dict:
        return {"kind": self.kind, "message": self.message, "provider": self.provider, "model": self.model}


class RateLimited(LLMError):
    kind = "RATE_LIMITED"

    def __init__(self, message: str = "", *, retry_after: Optional[float] = None, **kw):
        super().__init__(message, **kw)
        self.retry_after = retry_after


class ProviderUnavailable(LLMError):
    kind = "TIMEOUT"


class AuthenticationFailed(LLMError):
    kind = "FAILED"


class ContextTooLong(LLMError):
    kind = "FAILED"


class ContentFiltered(LLMError):
    kind = "FAILED"


class UnsupportedFeature(LLMError):
    kind = "UNSUPPORTED"


class ModelFailed(LLMError):
    kind = "FAILED"


class InvalidRequest(LLMError):
    kind = "FAILED"


class PolicyDenied(LLMError):
    kind = "POLICY"


class BudgetExceeded(RateLimited):
    kind = "RATE_LIMITED"


class NoModelAvailable(LLMError):
    kind = "NOT_FOUND"


class ConfigurationError(LLMError):
    kind = "CONFIG"


_BY_KIND = {"config": ConfigurationError, "not_found": NoModelAvailable, "unsupported": UnsupportedFeature, "policy": PolicyDenied,
            "rate_limited": RateLimited, "timeout": ProviderUnavailable, "failed": ModelFailed}


def from_wire(e: LlmError, *, provider: str = "", model: str = "") -> LLMError:
    """The wire layer's ``LlmError(kind, message)`` as the canonical error of that kind."""
    return _BY_KIND.get(e.kind, ModelFailed)(e.message, provider=provider, model=model)
