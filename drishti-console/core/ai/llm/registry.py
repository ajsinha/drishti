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

"""Provider classes by type (``sajha/ai/llm/registry.py``): ``register_provider`` puts an ``LLMProvider`` subclass in the registry, ``provider_class`` finds it. The four
shipped types (openai, anthropic, server, mock) are registered with ``ProviderBase``; a plug-in names its class in ``ai.providers`` or registers it at start-up."""
from __future__ import annotations

import importlib
from typing import Dict, Optional

from core.ai.llm.base import LLMProvider
from core.ai.llm.errors import ConfigurationError

_PROVIDERS: Dict[str, type] = {}


def register_provider(cls=None, *, name: Optional[str] = None, replace: bool = True):
    """Register ``cls`` under ``name`` (default ``cls.name``). Usable as ``@register_provider`` or ``register_provider(cls, name="acme")``."""
    def deco(c):
        if not (isinstance(c, type) and issubclass(c, LLMProvider)):
            raise ConfigurationError(f"{c!r} is not an LLMProvider subclass")
        key = (name or c.name or "").lower()
        if not key:
            raise ConfigurationError("a provider needs a name")
        if key in _PROVIDERS and not replace:
            raise ConfigurationError(f"provider '{key}' is already registered")
        _PROVIDERS[key] = c
        return c
    return deco(cls) if cls is not None else deco


def unregister_provider(name: str) -> None:
    _PROVIDERS.pop(name.lower(), None)


def provider_class(name: str) -> Optional[type]:
    ensure_builtins()
    return _PROVIDERS.get(name.lower())


def registered_providers() -> Dict[str, type]:
    ensure_builtins()
    return dict(sorted(_PROVIDERS.items()))


def load_class(path: str) -> type:
    """``package.module:Class`` as a class."""
    mod, _, attr = path.partition(":")
    try:
        return getattr(importlib.import_module(mod), attr)
    except (ImportError, AttributeError) as e:
        raise ConfigurationError(f"cannot load {path}: {type(e).__name__}") from None


def ensure_builtins() -> None:
    from core.ai.llm.provider import ProviderBase
    from core.ai.providers import FACTORIES
    for t in FACTORIES:
        _PROVIDERS.setdefault(t, ProviderBase)
