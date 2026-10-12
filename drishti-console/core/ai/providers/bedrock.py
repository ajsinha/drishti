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

"""``type: bedrock``: Amazon Bedrock through boto3 (``BedrockProvider`` of sajhamcpserver): the Converse API for chat, InvokeModel for embeddings, ``ListFoundationModels``
for the live model list. boto3 does the SigV4 signing and the AWS credential chain, so everything an AWS account offers works: environment keys, a shared-credentials
profile, SSO, container and instance roles, web-identity (IRSA).

Settings: ``region`` (``AWS_REGION`` / ``AWS_DEFAULT_REGION`` are read, then us-east-1), ``profile`` (a named profile; else the default chain), ``aws-access-key-id`` /
``aws-secret-access-key`` / ``aws-session-token`` as ``${ENV}`` placeholders (never literals; without them the chain decides), ``base-url`` (an endpoint override: a VPC
endpoint, or a local fake in a test), ``control-url`` (the same for the control plane that lists models), ``embedding-input-type`` (search_document, Cohere models),
``proxy.*`` (the file's proxy or the site default is handed to botocore), the shared ``tls.*`` (CA, PKCS12 truststore, client certificate; ``verify-hostname: false`` and ``protocols`` are not available through boto3), ``timeout`` and ``retries`` (botocore's standard mode). The chat call is run on a
worker thread, as boto3 is blocking. Guardrails and ``additional-model-request-fields`` are not built."""
from __future__ import annotations

import asyncio
import json
import os
from core import tmpdir
import threading

from core.ai import proxy as proxy_mod
from core.ai import tls as tls_mod
from core.ai.providers.base import CHAT_EXTRAS, BaseProvider
from core.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"end_turn": "stop", "stop_sequence": "stop", "max_tokens": "length", "tool_use": "tool_calls", "content_filtered": "content_filter",
          "guardrail_intervened": "content_filter"}
_AUTH = {"UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException", "AccessDeniedException", "InvalidClientTokenId", "SignatureDoesNotMatch",
         "MissingAuthenticationToken", "UnauthorizedOperation"}


def map_error(e: Exception) -> LlmError:
    """A botocore error as an ``LlmError`` with the cause named and nothing of the message (it may echo a prompt)."""
    from botocore import exceptions as X
    if isinstance(e, LlmError):
        return e
    if isinstance(e, (X.ReadTimeoutError, X.ConnectTimeoutError)):
        return LlmError("timeout", "the model endpoint did not answer in time")
    if isinstance(e, X.ClientError):
        code = (e.response.get("Error") or {}).get("Code", "")
        status = (e.response.get("ResponseMetadata") or {}).get("HTTPStatusCode", 0)
        if code in ("ThrottlingException", "TooManyRequestsException", "ServiceQuotaExceededException") or status == 429:
            return LlmError("rate_limited", f"the model endpoint answered HTTP {status or 429} {code} (rate limited: slow down, raise the Bedrock quota or add retries)")
        if code in _AUTH or status in (401, 403):
            return LlmError("failed", f"the model endpoint answered HTTP {status} {code} (authentication failed or access denied: check the credentials, the region and the model access)")
        if code == "ResourceNotFoundException" or status == 404:
            return LlmError("failed", f"the model endpoint answered HTTP {status or 404} {code} (not found: check the model id and the region)")
        return LlmError("failed", f"the model endpoint answered HTTP {status} {code}")
    if isinstance(e, (X.NoCredentialsError, X.PartialCredentialsError)):
        return LlmError("config", "no AWS credentials: set AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY, a profile, or run with a role")
    if isinstance(e, X.ProxyConnectionError):
        return LlmError("failed", "the proxy refused the request or could not be used (check proxy.url, proxy.username and proxy.password)")
    return LlmError("failed", "the model endpoint could not be reached or answered something unreadable")


class BedrockProvider(BaseProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime=None):
        super().__init__(cfg)
        try:
            import boto3  # noqa: F401
            import botocore  # noqa: F401
        except ImportError:
            raise LlmError("config", "bedrock needs boto3: pip install boto3 (it is in requirements.txt)") from None
        self.region = cfg.setting("region", "us-east-1")
        self.tls = tls_mod.build(cfg.settings, cfg.base_url or f"https://bedrock-runtime.{self.region}.amazonaws.com", os.environ.get)
        if self.tls.context is not True and not self.tls.verify_hostname and not self.tls.insecure:
            raise LlmError("config", "bedrock: tls.verify-hostname: false is not supported by boto3; use tls.ca-file for a private endpoint")
        self.proxy = proxy_mod.resolve(cfg.settings, cfg.base_url or f"https://bedrock-runtime.{self.region}.amazonaws.com", os.environ.get)
        self._lock = threading.Lock()
        self._session = None
        self._clients: dict = {}
        self._tmp = None

    # ---- boto3

    def _boto_session(self):
        import boto3
        if self._session is None:
            s = self.cfg.settings
            kw = {}
            if s.get("aws-access-key-id") and s.get("aws-secret-access-key"):
                kw = {"aws_access_key_id": s["aws-access-key-id"], "aws_secret_access_key": s["aws-secret-access-key"]}
                if s.get("aws-session-token"):
                    kw["aws_session_token"] = s["aws-session-token"]
            self._session = boto3.Session(profile_name=self.cfg.setting("profile") or None, region_name=self.region, **kw)
        return self._session

    def _client(self, service: str):
        with self._lock:
            c = self._clients.get(service)
            if c is None:
                from botocore.config import Config
                px = {}
                if self.proxy.active:
                    cred = f"{self.proxy.username}:{self.proxy.password}@" if self.proxy.username else ""
                    px = {"proxies": {"http": f"http://{cred}{self.proxy.host}:{self.proxy.port}", "https": f"http://{cred}{self.proxy.host}:{self.proxy.port}"}}
                verify, cert = self._tls_files()
                bc = Config(**({"client_cert": cert} if cert else {}), connect_timeout=self.cfg.timeout, read_timeout=self.cfg.timeout, retries={"max_attempts": self.cfg.retries + 1, "mode": "standard"}, **px)
                url = (self.cfg.base_url if service == "bedrock-runtime" else self.cfg.setting("control-url")) or None
                c = self._clients[service] = self._boto_session().client(service, endpoint_url=url, config=bc, verify=verify)
            return c

    async def _call(self, service: str, op: str, timeout: float | None = None, **kw):
        from botocore.exceptions import BotoCoreError, ClientError
        try:
            return await asyncio.to_thread(getattr(self._client(service), op), **kw)
        except (BotoCoreError, ClientError) as e:
            raise map_error(e) from None

    def _tls_files(self):
        """boto3 takes a CA bundle path and a client certificate path: the shared ``tls.*`` material is written once to a private temporary folder (removed at close)."""
        t = self.tls
        if t.context is True:
            return None, None
        if t.insecure:
            return False, None
        if self._tmp is None and (t.ca_pem or t.cert_pem):
            self._tmp = tmpdir.mkdtemp("drishti-tls-")
            os.chmod(self._tmp, 0o700)
            import atexit
            import shutil
            atexit.register(shutil.rmtree, self._tmp, True)               # also when the provider is never closed
        ca = cert = None
        if t.ca_pem:
            ca = os.path.join(self._tmp, "ca.pem")
            ca_text = t.ca_pem
            use_system = self.cfg.setting("tls.trust-jvm-default", "false").lower() == "true"
            if use_system:
                import certifi
                ca_text += open(certifi.where(), encoding="utf-8").read()
            self._write(ca, ca_text)
        if t.cert_pem:
            cert = os.path.join(self._tmp, "client.pem")
            self._write(cert, t.key_pem + t.cert_pem)
        return ca, cert

    @staticmethod
    def _write(path: str, text: str) -> None:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(text)

    # ---- the hooks of ProviderBase

    def check(self) -> tuple[bool, str]:
        s = self.cfg.settings
        if bool(s.get("aws-access-key-id")) != bool(s.get("aws-secret-access-key")):
            return False, "aws-access-key-id and aws-secret-access-key go together (as ${ENV} placeholders)"
        try:
            creds = self._boto_session().get_credentials()
        except Exception:                                          # a broken profile name, an unreadable config
            return False, "the AWS profile or credential configuration cannot be read"
        if creds is None:
            return False, "no AWS credentials: set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (or aws-access-key-id / aws-secret-access-key as ${ENV}), a profile, or run with a role"
        return True, f"configured for {self.region}; no call is made to find out"

    async def live_models(self) -> list[dict]:
        j = await self._call("bedrock", "list_foundation_models")
        out = []
        for m in j.get("modelSummaries") or []:
            mods = m.get("outputModalities") or []
            if m.get("modelId"):
                out.append({"id": m["modelId"], "kind": "embedding" if "EMBEDDING" in mods else "chat"})
        return out

    async def probe(self) -> tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            return True, f"{self.region}: {len(await self.live_models())} foundation models listed"
        except LlmError as e:
            return False, e.message

    # ---- calls

    async def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs = [{"role": "assistant" if m.role == "assistant" else "user", "content": [{"text": m.content}]} for m in req.messages if m.role != "system"]
        inf: dict = {"maxTokens": self.max_tokens(req, info, 1024)}
        t = req.temperature if req.temperature is not None else info.temperature
        if t is not None:
            inf["temperature"] = t
        kw: dict = {"modelId": info.id, "messages": msgs, "inferenceConfig": inf}
        sys_text = self.system_text(req)
        if sys_text:
            kw["system"] = [{"text": sys_text}]
        if req.tools:
            kw["toolConfig"] = {"tools": [{"toolSpec": {"name": s.name, **({"description": s.description} if s.description else {}), "inputSchema": {"json": json.loads(s.parameters_json_schema)}}}
                                          for s in req.tools]}
        j = await self._call("bedrock-runtime", "converse", **kw)
        blocks = ((j.get("output") or {}).get("message") or {}).get("content") or []
        text = "".join(b.get("text", "") for b in blocks if "text" in b)
        calls = tuple(ToolCall(b["toolUse"].get("toolUseId", ""), b["toolUse"].get("name", ""), json.dumps(b["toolUse"].get("input") or {}, separators=(",", ":")))
                      for b in blocks if "toolUse" in b)
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        u = j.get("usage") or {}
        pi, co = int(u.get("inputTokens") or 0), int(u.get("outputTokens") or 0)
        return ChatResponse(text, calls, info.id, "tool_calls" if calls else FINISH.get(j.get("stopReason", "end_turn"), "stop"), Usage(pi, co, info.cost(pi, co)))

    async def _invoke(self, model_id: str, body: dict) -> dict:
        j = await self._call("bedrock-runtime", "invoke_model", modelId=model_id, body=json.dumps(body), contentType="application/json", accept="application/json")
        try:
            return json.loads(j["body"].read())
        except (ValueError, KeyError):
            raise LlmError("failed", "the model endpoint answered something unreadable") from None

    async def embed(self, info: ModelInfo, texts: list[str]) -> list[list[float]]:
        if "cohere" in info.id:
            j = await self._invoke(info.id, {"texts": texts, "input_type": self.cfg.setting("embedding-input-type", "search_document")})
            out = j.get("embeddings")
            if isinstance(out, dict):
                out = out.get("float")
        else:
            out = [(await self._invoke(info.id, {"inputText": t})).get("embedding") for t in texts]          # Titan: one text per call
        if not isinstance(out, list) or len(out) != len(texts) or not all(isinstance(v, list) for v in out):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out

    async def aclose(self) -> None:
        with self._lock:
            for c in self._clients.values():
                c.close()
            self._clients.clear()
            if self._tmp:
                import shutil
                shutil.rmtree(self._tmp, ignore_errors=True)
                self._tmp = None


def factory(cfg: ProviderConfig, runtime=None) -> BedrockProvider:
    return BedrockProvider(cfg, runtime)
