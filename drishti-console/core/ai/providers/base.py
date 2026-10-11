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

"""Common parts of console providers: the model list from the provider file, TLS from ``tls.*`` keys, and error mapping for httpx."""
from __future__ import annotations

import os
import ssl

import httpx

from core.ai import proxy as proxy_mod
from core.ai.types import CHAT, EMBEDDINGS, JSON_MODE, TOOL_CALLS, ChatRequest, LlmError, ModelInfo, ProviderConfig


def ssl_context(cfg: ProviderConfig, url: str):
    """``https://`` trusts the system authorities unless ``tls.ca-file`` names others; ``tls.cert-file`` + ``tls.key-file`` give a client identity.
    A ``tls.*`` key against an ``http://`` URL is a configuration error, never a silent downgrade."""
    tls = {k: v for k, v in cfg.settings.items() if k.startswith("tls.")}
    https = url.lower().startswith("https://")
    if not https:
        if tls:
            raise LlmError("config", "tls.* is set but the URL is not https://: use an https:// address")
        return True
    try:
        ctx = ssl.create_default_context(cafile=cfg.setting("tls.ca-file") or None)
        if cfg.setting("tls.cert-file"):
            ctx.load_cert_chain(cfg.setting("tls.cert-file"), cfg.setting("tls.key-file") or None)
        return ctx
    except (OSError, ssl.SSLError) as e:
        raise LlmError("config", f"cannot create the TLS context ({type(e).__name__})") from None


def client_args(settings: dict, url: str) -> tuple[dict, "proxy_mod.Proxy"]:
    """The ``httpx.AsyncClient`` arguments that send requests to ``url`` through the file's proxy, the site default or the environment
    (see ``core/ai/proxy.py``), and the proxy that was chosen."""
    px = proxy_mod.resolve(settings, url, os.environ.get)
    return px.httpx_args(url), px


def map_http_error(e: Exception) -> LlmError:
    if isinstance(e, httpx.ProxyError):
        return LlmError("failed", "the proxy refused the request or could not be used (check proxy.url, proxy.username and proxy.password)")
    if isinstance(e, httpx.TimeoutException):
        return LlmError("timeout", "the model endpoint did not answer in time")
    return LlmError("failed", "the model endpoint could not be reached or answered something unreadable")


class BaseProvider:
    default_caps: tuple[str, ...] = ()

    def __init__(self, cfg: ProviderConfig):
        self.cfg = cfg
        self.id = cfg.name
        self.type = cfg.type
        self._models: list[ModelInfo] = []
        for m in cfg.models:
            caps = tuple(m["capabilities"]) or ((EMBEDDINGS,) if m["kind"] == "embedding" else (CHAT,))
            if m["kind"] != "embedding" and caps == (CHAT,):
                caps = caps + self.default_caps
            self._models.append(ModelInfo(m["name"], cfg.name, m["id"], m["kind"], caps, m["max_tokens"], m["temperature"],
                                          m["cost_per_1k_input"], m["cost_per_1k_output"]))

    def models(self) -> list[ModelInfo]:
        return list(self._models)

    def _find(self, name: str, embedding: bool) -> ModelInfo | None:
        return next((m for m in self._models if m.name == name and m.embedding == embedding), None)

    def chat_model(self, logical_name: str):
        info = self._find(logical_name, False)
        return _Bound(info, self) if info else None

    def embedding_model(self, logical_name: str):
        info = self._find(logical_name, True)
        return _BoundEmbed(info, self) if info and hasattr(self, "embed") else None

    async def aclose(self) -> None:
        return None

    @staticmethod
    def max_tokens(req: ChatRequest, info: ModelInfo, default: int) -> int:
        return req.max_tokens or info.max_tokens or default

    @staticmethod
    def system_text(req: ChatRequest) -> str:
        parts = [req.system] if req.system else []
        parts += [m.content for m in req.messages if m.role == "system"]
        return "\n".join(parts)


class _Bound:
    def __init__(self, info: ModelInfo, provider):
        self.info = info
        self._p = provider

    async def chat(self, request: ChatRequest):
        return await self._p.complete(self.info, request)


class _BoundEmbed:
    def __init__(self, info: ModelInfo, provider):
        self.info = info
        self._p = provider

    async def embed(self, texts: list[str]):
        return await self._p.embed(self.info, texts)


CHAT_EXTRAS = (TOOL_CALLS, JSON_MODE)
