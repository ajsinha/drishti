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

"""The console's MCP (Model Context Protocol) client layer, mirroring the server's ``McpClient`` / ``McpRegistry`` / ``McpGateway``
(docs/architecture/MCP_CLIENT.md). Transports: ``streamable-http`` (httpx; JSON or SSE answers, session id kept), ``mock`` (in-process,
scripted) and ``server`` (delegates to the Drishti server, so credentials and policy live in one place)."""
from __future__ import annotations

import itertools
import json
import time
from dataclasses import dataclass, field
from typing import Protocol

import httpx

from core.ai import config as cfgmod
from core.ai.providers.base import map_http_error
from core.ai.policy import MASK, PromptMasker
from core.ai.types import CallContext, LlmError

PROTOCOL = "2025-11-25"


@dataclass(frozen=True)
class Tool:
    name: str
    description: str = ""
    input_schema: str = "{}"


@dataclass(frozen=True)
class Resource:
    uri: str
    name: str = ""
    description: str = ""
    mime_type: str = ""


@dataclass(frozen=True)
class Prompt:
    name: str
    description: str = ""


@dataclass(frozen=True)
class Result:
    text: str
    error: bool = False


class McpClient(Protocol):
    """One MCP server connection. Add a transport: a class with these methods and an entry in ``TRANSPORTS``."""
    server: str

    async def connect(self) -> None: ...
    async def tools(self) -> list[Tool]: ...
    async def resources(self) -> list[Resource]: ...
    async def prompts(self) -> list[Prompt]: ...
    async def call_tool(self, name: str, arguments: dict) -> Result: ...
    async def read_resource(self, uri: str) -> str: ...
    async def aclose(self) -> None: ...


def _allowed(cfg: dict, tool: str) -> bool:
    return not cfg["allowed_tools"] or tool in cfg["allowed_tools"]


class StreamableHttpClient:
    def __init__(self, cfg: dict):
        if not cfg["url"]:
            raise LlmError("config", "url is missing")
        self.cfg, self.server = cfg, cfg["name"]
        s = {k: v for k, v in cfg["settings"].items() if k.startswith("tls.")}
        if s and not cfg["url"].lower().startswith("https://"):
            raise LlmError("config", "tls.* is set but the URL is not https://: use an https:// address")
        verify: object = True
        if cfg["url"].lower().startswith("https://"):
            import ssl
            try:
                verify = ssl.create_default_context(cafile=s.get("tls.ca-file") or None)
                if s.get("tls.cert-file"):
                    verify.load_cert_chain(s["tls.cert-file"], s.get("tls.key-file") or None)
            except (OSError, ssl.SSLError):
                raise LlmError("config", "cannot create the TLS context") from None
        self._http = httpx.AsyncClient(verify=verify, timeout=cfg["timeout"], follow_redirects=False)
        self._ids = itertools.count(1)
        self._session: str | None = None
        self._protocol = PROTOCOL
        self._connected = False

    def _headers(self) -> dict:
        h = {"Accept": "application/json, text/event-stream", "MCP-Protocol-Version": self._protocol, **{k: v for k, v in self.cfg["headers"].items() if v}}
        if self._session:
            h["Mcp-Session-Id"] = self._session
        return h

    async def _post(self, message: dict, want: bool):
        try:
            r = await self._http.post(self.cfg["url"], json=message, headers=self._headers())
        except httpx.HTTPError as e:
            raise map_http_error(e) from None
        if r.headers.get("Mcp-Session-Id"):
            self._session = r.headers["Mcp-Session-Id"]
        if r.status_code // 100 != 2:
            raise LlmError("failed", f"the MCP server answered HTTP {r.status_code}")
        if not want:
            return None
        if r.headers.get("content-type", "").startswith("text/event-stream"):
            data = []
            for line in r.text.splitlines() + [""]:
                if line.startswith("data:"):
                    data.append(line[5:].strip())
                elif not line and data:
                    try:
                        n = json.loads("".join(data))
                    except ValueError:
                        n = {}
                    data = []
                    if n.get("id") == message["id"] and ("result" in n or "error" in n):
                        return n
            return None
        try:
            return r.json()
        except ValueError:
            raise LlmError("failed", "the MCP server answered something unreadable") from None

    async def _rpc(self, method: str, params: dict) -> dict:
        msg = {"jsonrpc": "2.0", "id": next(self._ids), "method": method, "params": params}
        reply = await self._post(msg, True)
        if reply is None:
            raise LlmError("failed", f"the MCP server sent no answer to {method}")
        if "error" in reply:
            raise LlmError("failed", f"the MCP server refused {method}: {(reply['error'] or {}).get('message', 'error')}")
        return reply.get("result") or {}

    async def connect(self) -> None:
        if self._connected:
            return
        r = await self._rpc("initialize", {"protocolVersion": PROTOCOL, "capabilities": {}, "clientInfo": {"name": "drishti-console", "version": "1"}})
        self._protocol = r.get("protocolVersion", PROTOCOL)
        await self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, False)
        self._connected = True

    async def _paged(self, method: str, field_: str) -> list[dict]:
        await self.connect()
        out, cursor = [], None
        for _ in range(100):
            r = await self._rpc(method, {"cursor": cursor} if cursor else {})
            out += r.get(field_) or []
            cursor = r.get("nextCursor")
            if not cursor:
                break
        return out

    async def tools(self) -> list[Tool]:
        return [Tool(t["name"], t.get("description", ""), json.dumps(t.get("inputSchema") or {})) for t in await self._paged("tools/list", "tools")
                if _allowed(self.cfg, t["name"])]

    async def resources(self) -> list[Resource]:
        return [Resource(r["uri"], r.get("name", ""), r.get("description", ""), r.get("mimeType", "")) for r in await self._paged("resources/list", "resources")]

    async def prompts(self) -> list[Prompt]:
        return [Prompt(p["name"], p.get("description", "")) for p in await self._paged("prompts/list", "prompts")]

    async def call_tool(self, name: str, arguments: dict) -> Result:
        if not _allowed(self.cfg, name):
            raise LlmError("policy", f"tool '{name}' is not in allowed-tools of {self.server}")
        await self.connect()
        r = await self._rpc("tools/call", {"name": name, "arguments": arguments or {}})
        text = "\n".join(c.get("text", "") for c in r.get("content") or [] if "text" in c)
        if not text and "structuredContent" in r:
            text = json.dumps(r["structuredContent"])
        return Result(text, bool(r.get("isError")))

    async def read_resource(self, uri: str) -> str:
        await self.connect()
        r = await self._rpc("resources/read", {"uri": uri})
        return "\n".join(c.get("text", "") for c in r.get("contents") or [])

    async def aclose(self) -> None:
        if self._session:
            try:
                await self._http.delete(self.cfg["url"], headers=self._headers())
            except httpx.HTTPError:
                pass
        self._session, self._connected = None, False
        await self._http.aclose()


class MockClient:
    """``transport: mock`` with ``mock.tools.N.{name,description,reply,error}``, ``mock.resources.N.{uri,text}``, ``mock.prompts.N.name``."""

    def __init__(self, cfg: dict):
        self.cfg, self.server = cfg, cfg["name"]
        s = cfg["settings"]

        def listing(kind, key):
            out, i = [], 0
            while f"mock.{kind}.{i}.{key}" in s:
                out.append({k.split(".", 3)[3]: v for k, v in s.items() if k.startswith(f"mock.{kind}.{i}.")})
                i += 1
            return out

        self._tools, self._resources, self._prompts = listing("tools", "name"), listing("resources", "uri"), listing("prompts", "name")

    async def connect(self) -> None:
        return None

    async def tools(self):
        return [Tool(t["name"], t.get("description", ""), '{"type":"object"}') for t in self._tools if _allowed(self.cfg, t["name"])]

    async def resources(self):
        return [Resource(r["uri"], r.get("name", r["uri"]), "", "text/plain") for r in self._resources]

    async def prompts(self):
        return [Prompt(p["name"], p.get("description", "")) for p in self._prompts]

    async def call_tool(self, name: str, arguments: dict) -> Result:
        if not _allowed(self.cfg, name):
            raise LlmError("policy", f"tool '{name}' is not in allowed-tools of {self.server}")
        for t in self._tools:
            if t["name"] == name:
                text = t.get("reply", f"echo {name} {arguments}")
                for k, v in (arguments or {}).items():
                    text = text.replace("${" + k + "}", str(v))
                return Result(text, str(t.get("error", "false")).lower() == "true")
        raise LlmError("not_found", f"no tool '{name}' on {self.server}")

    async def read_resource(self, uri: str) -> str:
        for r in self._resources:
            if r["uri"] == uri:
                return r.get("text", "")
        raise LlmError("not_found", f"no resource '{uri}' on {self.server}")

    async def aclose(self) -> None:
        return None


class ServerClient:
    """``transport: server``: calls go to the Drishti server's ``/api/v1/ai/mcp/{server}/tools/{tool}``; the server holds the credentials and policy."""

    def __init__(self, cfg: dict, runtime):
        self.cfg, self.server, self._runtime = cfg, cfg["name"], runtime
        self._ident = None

    async def connect(self) -> None:
        return None

    async def tools(self):
        raise LlmError("unsupported", "list the tools in Admin -> AI (the server lists them to administrators)")

    async def resources(self):
        return []

    async def prompts(self):
        return []

    async def call_tool(self, name: str, arguments: dict, ident=None) -> Result:
        from core.backend import BackendError
        try:
            j = await self._runtime.backend().ai_mcp_call(ident, self.server, name, {"arguments": arguments or {}, "feature": "console"})
        except BackendError as e:
            raise LlmError("policy" if e.code == "DRS-4018" else "timeout" if e.status == 504 else "failed", e.detail) from None
        return Result(j.get("text", ""), bool(j.get("error")))

    async def read_resource(self, uri: str) -> str:
        raise LlmError("unsupported", "resources are read through the server's own MCP layer")

    async def aclose(self) -> None:
        return None


TRANSPORTS = {"streamable-http": lambda cfg, rt: StreamableHttpClient(cfg), "mock": lambda cfg, rt: MockClient(cfg), "server": ServerClient}


@dataclass
class McpEntry:
    config: dict
    client: object | None
    error: str | None
    calls: int = 0
    failures: int = 0
    last_error: str = ""
    last_was_failure: bool = False

    @property
    def usable(self) -> bool:
        return self.client is not None and self.config["enabled"]


class McpRegistry:
    def __init__(self, source, runtime, factories: dict | None = None):
        self._source, self._runtime = source, runtime
        self._factories = factories if factories is not None else TRANSPORTS
        self._entries: dict[str, McpEntry] = {}
        self.file_errors: dict[str, str] = {}

    def reload(self) -> None:
        configs, errors = self._source()
        entries = {}
        for c in configs:
            old = self._entries.get(c["name"])
            e = McpEntry(c, None, None, *( (old.calls, old.failures, old.last_error, old.last_was_failure) if old else () ))
            f = self._factories.get(c["transport"].lower())
            if not c["enabled"]:
                pass
            elif f is None:
                e.error = f"no MCP transport '{c['transport']}' is installed (have: {sorted(self._factories)})"
            else:
                try:
                    e.client = f(c, self._runtime)
                except LlmError as ex:
                    e.error = ex.message
            entries[c["name"]] = e
        self._entries, self.file_errors = entries, dict(errors)

    def entries(self) -> list[McpEntry]:
        return sorted(self._entries.values(), key=lambda e: e.config["name"])

    def server(self, name: str) -> McpEntry | None:
        return self._entries.get(name)

    def health(self, e: McpEntry) -> dict:
        if not e.config["enabled"]:
            st, d = "OFF", "enabled: false in the server file"
        elif e.error:
            st, d = "ERROR", e.error
        elif e.calls == 0:
            st, d = "UNKNOWN", "configured; no call yet"
        else:
            st, d = ("DOWN", e.last_error) if e.last_was_failure else ("UP", "last call succeeded")
        return {"status": st, "detail": d, "calls": e.calls, "failures": e.failures}


@dataclass
class McpPolicy:
    server_roles: dict = field(default_factory=dict)
    tool_roles: dict = field(default_factory=dict)
    server_features: dict = field(default_factory=dict)
    redacted_fields: frozenset = frozenset()
    masker: PromptMasker = field(default_factory=PromptMasker)
    audit: object = None


class McpGateway:
    """Which roles and features may use which server and tool, arguments and results masked, an audit record per call."""

    def __init__(self, registry: McpRegistry, policy: McpPolicy | None = None):
        self.registry, self.policy = registry, policy or McpPolicy()

    def _entry(self, ctx: CallContext, server: str) -> McpEntry:
        e = self.registry.server(server)
        if e is None:
            raise LlmError("not_found", f"no MCP server '{server}'")
        if not e.usable:
            raise LlmError("not_found", f"MCP server '{server}' is not available")
        roles = self.policy.server_roles.get(server)
        if roles and not set(roles) & set(ctx.roles):
            raise LlmError("policy", f"MCP server '{server}' is not open to your roles")
        feats = self.policy.server_features.get(server)
        if feats and ctx.feature not in feats:
            raise LlmError("policy", f"MCP server '{server}' is not open to feature '{ctx.feature}'")
        return e

    def _tool_ok(self, ctx: CallContext, server: str, tool: str) -> bool:
        roles = self.policy.tool_roles.get(f"{server}/{tool}")
        return not roles or bool(set(roles) & set(ctx.roles))

    async def tools(self, ctx: CallContext, server: str) -> list[Tool]:
        e = self._entry(ctx, server)
        return [t for t in await e.client.tools() if self._tool_ok(ctx, server, t.name)]

    async def call_tool(self, ctx: CallContext, server: str, tool: str, arguments: dict | None = None) -> Result:
        e = self._entry(ctx, server)
        if not self._tool_ok(ctx, server, tool):
            raise LlmError("policy", f"tool '{server}/{tool}' is not open to your roles")
        p = self.policy
        clean = {k: MASK if k.lower() in {f.lower() for f in p.redacted_fields} else p.masker.mask(v) if isinstance(v, str) else v
                 for k, v in (arguments or {}).items()}
        t0 = time.monotonic()
        try:
            r = await (e.client.call_tool(tool, clean, ctx.ident) if isinstance(e.client, ServerClient) else e.client.call_tool(tool, clean))
        except LlmError as ex:
            e.calls, e.failures, e.last_error, e.last_was_failure = e.calls + 1, e.failures + 1, ex.message, True
            self._audit(ctx, server, tool, 0, ex.kind, t0)
            raise
        e.calls, e.last_was_failure = e.calls + 1, False
        out = Result(p.masker.mask(r.text), r.error)
        self._audit(ctx, server, tool, len(out.text), "tool-error" if r.error else "ok", t0)
        return out

    def _audit(self, ctx, server, tool, chars, outcome, t0) -> None:
        if self.policy.audit:
            try:
                self.policy.audit({"user": ctx.user, "feature": ctx.feature, "server": server, "tool": tool, "resultChars": chars,
                                   "outcome": outcome, "millis": int((time.monotonic() - t0) * 1000)})
            except Exception:
                pass


def from_directory(directory, runtime, env=None) -> McpRegistry:
    reg = McpRegistry(lambda: cfgmod.load_mcp(directory, env), runtime)
    reg.reload()
    return reg
