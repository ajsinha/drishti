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

"""The HTTP part the JSON providers share (``sajha/ai/llm/http.py``): one httpx client per provider (proxy, TLS), JSON calls, retries, and the error mapping.

A status is mapped to an ``LlmError`` without the body (a body can echo a prompt): 429 is ``rate_limited``, a timeout is ``timeout``, everything else ``failed`` with a hint
for the usual causes (401/403 key or permission, 404 path or model). Subclasses override ``auth`` for headers that depend on the request (Entra token, SigV4)."""
from __future__ import annotations

import asyncio
import json
import re
import ssl
import weakref

import httpx

from core.ai.providers.base import BaseProvider, client_args, map_http_error, tls_result
from core.ai.types import LlmError, ProviderConfig

_HINTS = {401: "authentication failed: check the api key and where it is read from",
          403: "access denied: the key lacks permission for this model or endpoint",
          404: "not found: check base-url, the path and the model id",
          408: "the endpoint timed out",
          413: "the request is too large",
          429: "rate limited: slow down, raise the account limit or add retries"}
SECRETISH = re.compile(r"(?i)(^|.*[-_.])(key|secret|token|password|passwd|authorization|auth|cookie|credential|credentials)([-_.].*)?$")


def status_error(code: int) -> LlmError:
    hint = _HINTS.get(code)
    return LlmError("rate_limited" if code == 429 else "failed", f"the model endpoint answered HTTP {code}" + (f" ({hint})" if hint else ""))


class JsonHttpProvider(BaseProvider):
    """A provider that speaks JSON over HTTP to ``base``."""

    def __init__(self, cfg: ProviderConfig, base: str):
        super().__init__(cfg)
        self.base = base.rstrip("/")
        url = self.base or "http://localhost"
        args, self.proxy = client_args(cfg.settings, url)
        self.tls = tls_result(cfg, url)
        self._kw = dict(verify=self.tls.context, timeout=cfg.timeout, follow_redirects=False, trust_env=False, **args)
        self._clients: "weakref.WeakKeyDictionary" = weakref.WeakKeyDictionary()

    @property
    def _client(self) -> httpx.AsyncClient:
        """The client of the running event loop (a sync caller runs each call on a loop of its own, and an httpx connection cannot move between loops)."""
        loop = asyncio.get_running_loop()
        c = self._clients.get(loop)
        if c is None:
            c = self._clients[loop] = httpx.AsyncClient(**self._kw)
        return c

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json", "Accept": "application/json"}
        h.update({k[7:]: v for k, v in self.cfg.settings.items() if k.startswith("header.")})
        return h

    async def auth(self, method: str, url: str, data: bytes) -> dict:
        """The authentication headers of one request (the whole signed request, for SigV4). Default: none."""
        return {}

    async def request(self, method: str, url: str, body: dict | None = None, timeout: float | None = None) -> dict:
        data = json.dumps(body).encode() if body is not None else b""
        err: LlmError | None = None
        for _ in range(self.cfg.retries + 1):
            h = self._headers()
            h.update(await self.auth(method, url, data))
            try:
                r = await self._client.request(method, url, content=data or None, headers=h, timeout=timeout or self.cfg.timeout)
            except (httpx.HTTPError, ssl.SSLError, OSError) as e:          # a TLS alert read through a proxy tunnel can surface unwrapped
                err = map_http_error(e)
                continue
            if r.status_code // 100 != 2:
                err = status_error(r.status_code)
                if r.status_code < 500 and r.status_code != 429:
                    break
                continue
            try:
                j = r.json()
            except ValueError:
                err = LlmError("failed", "the model endpoint answered something unreadable")
                continue
            if isinstance(j, dict):
                return j
            err = LlmError("failed", "the model endpoint answered something unreadable")
        raise err or LlmError("failed", "the model call failed")

    async def post(self, url: str, body: dict, timeout: float | None = None) -> dict:
        return await self.request("POST", url, body, timeout)

    async def get(self, url: str, timeout: float | None = None) -> dict:
        return await self.request("GET", url, None, timeout)

    # ---- optional hooks of ProviderBase (core/ai/llm/provider.py)

    def check(self) -> tuple[bool, str]:
        """No-network readiness: is what the type needs there (a key, a region, ...)? Overridden per type."""
        return True, "configured; no call is made to find out"

    async def probe(self) -> tuple[bool, str]:
        """One cheap live call (a model list); ``(ok, detail)``. Default: the readiness check."""
        return self.check()

    async def live_models(self) -> list[dict]:
        """The model ids the endpoint reports now: ``[{"id", "kind"}]``; empty when the API has no listing or the type does not ask."""
        return []

    async def aclose(self) -> None:
        for c in list(self._clients.values()):
            try:
                await c.aclose()
            except RuntimeError:
                pass                                    # its loop is closed already
