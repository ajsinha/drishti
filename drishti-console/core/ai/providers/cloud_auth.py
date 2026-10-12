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

"""Microsoft Entra ID tokens for Azure OpenAI (``sajha/ai/llm/cloud_auth.py``), with httpx and no SDK.

Two flows are built: ``client_secret`` (the client-credentials grant with ``tenant-id``, ``client-id`` and ``client-secret`` as an ``${ENV}``) and
``workload_identity`` (the same grant with a federated token read from ``federated-token-file``, as AKS and GitHub Actions provide). ``auto`` takes the secret if there
is one, else the federated file. **Managed identity (the instance metadata endpoint) is not built.** A token is cached until a minute before it expires; the secret
and the token are never logged. The request goes through the provider's own client, so the proxy and TLS settings apply to it too."""
from __future__ import annotations

import asyncio
import time
from pathlib import Path

import httpx

from core.ai.providers.base import map_http_error
from core.ai.types import LlmError

ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"


class EntraTokenSource:
    def __init__(self, client, settings: dict):
        self._get_client = client
        self.mode = settings.get("entra-mode", "auto").lower()
        self.tenant = settings.get("tenant-id", "")
        self.client_id = settings.get("client-id", "")
        self.secret = settings.get("client-secret", "")
        self.federated = settings.get("federated-token-file", "")
        self.scope = settings.get("entra-scope", "https://cognitiveservices.azure.com/.default")
        self.authority = settings.get("entra-authority", "https://login.microsoftonline.com").rstrip("/")
        self._token, self._expires = "", 0.0
        self._lock = asyncio.Lock()

    def effective_mode(self) -> str:
        if self.mode == "auto":
            return "client_secret" if self.secret else "workload_identity" if self.federated else "managed_identity"
        return self.mode

    def check(self) -> tuple[bool, str]:
        m = self.effective_mode()
        if m == "managed_identity":
            return False, "entra: managed identity is not built; give client-secret or federated-token-file"
        if not (self.tenant and self.client_id):
            return False, "entra: tenant-id and client-id are required (AZURE_TENANT_ID, AZURE_CLIENT_ID)"
        if m == "client_secret" and not self.secret:
            return False, "entra: client-secret (an ${ENV} placeholder) is required"
        if m == "workload_identity" and not self.federated:
            return False, "entra: federated-token-file is required"
        return True, f"entra ({m})"

    async def token(self) -> str:
        async with self._lock:
            if self._token and time.time() < self._expires - 60:
                return self._token
            ok, why = self.check()
            if not ok:
                raise LlmError("config", why)
            form = {"grant_type": "client_credentials", "client_id": self.client_id, "scope": self.scope}
            if self.effective_mode() == "client_secret":
                form["client_secret"] = self.secret
            else:
                try:
                    form.update(client_assertion_type=ASSERTION, client_assertion=Path(self.federated).read_text(encoding="utf-8").strip())
                except OSError:
                    raise LlmError("config", "entra: the federated token file cannot be read") from None
            try:
                r = await self._get_client().post(f"{self.authority}/{self.tenant}/oauth2/v2.0/token", data=form)
            except httpx.HTTPError as e:
                raise map_http_error(e) from None
            if r.status_code // 100 != 2:
                raise LlmError("failed", f"entra: the token request was refused (HTTP {r.status_code}); check tenant-id, client-id and the secret")
            try:
                j = r.json()
                self._token, self._expires = j["access_token"], time.time() + float(j.get("expires_in", 3600))
            except (ValueError, KeyError, TypeError):
                raise LlmError("failed", "entra: the token answer is unreadable") from None
            return self._token
