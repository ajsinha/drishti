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

"""The console's AI layer with no network: config files, registry and reload, the mock provider, an OpenAI-compatible and an Anthropic stub on
loopback, the ``server`` provider, policy (masking, limits, audit, roles), the MCP client (mock, a Streamable HTTP stub, policy), Admin -> AI
and ``POST /api/ai/chat``. Nothing here reaches a real model."""
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from core.ai import AiRuntime
from core.ai import config as cfgmod
from core.ai.gateway import LlmGateway
from core.ai.mcp import McpGateway, McpPolicy
from core.ai.policy import DefaultPolicy, PolicySettings, PromptMasker
from core.ai.providers import FACTORIES
from core.ai.registry import LlmRegistry, from_directory
from core.ai.types import CallContext, ChatRequest, LlmError
from core.backend import BackendError
from core.config import Settings

CTX = CallContext("ann", frozenset({"user"}), "test")


def run(coro):
    return asyncio.run(coro)


class Stub:
    """A loopback HTTP server: ``handler(method, path, headers, body) -> (status, content-type, body, extra-headers)``."""

    def __init__(self, handler):
        self.seen = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def _do(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
                outer.seen.append((self.command, self.path, dict(self.headers), body))
                status, ctype, out, extra = handler(self.command, self.path, dict(self.headers), body)
                data = out.encode()
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(data)))
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(data)

            do_POST = do_GET = do_DELETE = _do

            def log_message(self, *a):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


def write(tmp_path, name, text):
    (tmp_path / name).write_text(text, encoding="utf-8")


MOCK = """
type: mock
default-model: m
models:
  - {name: m, cost-per-1k-input: 1.0, cost-per-1k-output: 1.0}
  - {name: vec, kind: embedding}
mock:
  rules:
    - {match: "(?i)hello", reply: "Hi there"}
    - {match: "weather", tool: get_weather, tool-args: '{"city":"Oslo"}'}
    - {match: "boom", error: "scripted"}
"""


def registry(tmp_path, env=None, default=""):
    return from_directory(tmp_path, FACTORIES, None, default, env=(env or {}).get)


def test_the_mock_provider_answers_by_rule_echo_tools_errors_and_embeds(tmp_path):
    write(tmp_path, "mock.yaml", MOCK)
    reg = registry(tmp_path)
    gw = LlmGateway(reg)
    assert run(gw.chat(CTX, ChatRequest.of("Hello you"))).text == "Hi there"
    assert run(gw.chat(CTX, ChatRequest.of("plain", model="mock/m"))).text == "echo: plain"
    r = run(gw.chat(CTX, ChatRequest.of("the weather", model="m")))
    assert r.tool_calls[0].name == "get_weather" and r.usage.cost > 0
    with pytest.raises(LlmError, match="scripted"):
        run(gw.chat(CTX, ChatRequest.of("boom")))
    assert run(reg.embedding("mock/vec").embed(["abc"])) == run(reg.embedding("vec").embed(["abc"]))
    assert reg.health(reg.entries()[0])["status"] == "DOWN"


def test_timeouts_are_reported_as_timeouts(tmp_path):
    write(tmp_path, "slow.yaml", "type: mock\nmodels: [{name: m}]\nmock: {latency-ms: 2000}\n")
    with pytest.raises(LlmError) as e:
        run(LlmGateway(registry(tmp_path)).chat(CTX, ChatRequest.of("x", timeout=0.1)))
    assert e.value.timeout


def test_bad_files_are_reported_per_file_and_never_echo_a_literal_key(tmp_path):
    write(tmp_path, "good.yaml", "type: mock\nmodels: [{name: m}]\n")
    write(tmp_path, "literal.yaml", "type: openai\napi-key: sk-123\n")
    write(tmp_path, "notype.yaml", "models: []\n")
    write(tmp_path, "unknown.yaml", "type: nonesuch\nmodels: [{name: x}]\n")
    write(tmp_path, "off.yaml", "type: mock\nenabled: false\nmodels: [{name: y}]\n")
    reg = registry(tmp_path)
    assert {"literal.yaml", "notype.yaml"} <= set(reg.file_errors)
    assert "placeholder" in reg.file_errors["literal.yaml"] and "sk-123" not in reg.file_errors["literal.yaml"]
    status = {e.config.name: reg.health(e)["status"] for e in reg.entries()}
    assert status == {"good": "UNKNOWN", "off": "OFF", "unknown": "ERROR"}
    assert [m.ref for m in reg.models()] == ["good/m"]


def test_reload_picks_up_added_and_removed_files_and_ambiguity_is_refused(tmp_path):
    write(tmp_path, "a.yaml", "type: mock\nmodels: [{name: same}]\n")
    reg = registry(tmp_path)
    write(tmp_path, "b.yaml", "type: mock\nmodels: [{name: same}]\n")
    reg.reload()
    with pytest.raises(LlmError, match="several providers"):
        reg.chat("same")
    assert reg.chat("a/same")[1].info.ref == "a/same"
    (tmp_path / "a.yaml").unlink()
    reg.reload()
    assert [m.ref for m in reg.models()] == ["b/same"]
    with pytest.raises(LlmError, match="no chat model"):
        reg.chat("nope")


def test_the_openai_compatible_provider_speaks_to_a_stub_and_keeps_the_key_out(tmp_path):
    ok = json.dumps({"model": "gpt-x", "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "4"}}],
                     "usage": {"prompt_tokens": 10, "completion_tokens": 2}})
    stub = Stub(lambda m, p, h, b: (200, "application/json", ok, None) if p.endswith("/v1/chat/completions") else (404, "application/json", "{}", None))
    try:
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\napi-key: ${{OAI}}\nheader: {{X-Team: red}}\nmodels:\n"
                                   "  - {name: fast, id: gpt-x, max-tokens: 77, cost-per-1k-input: 2.0, cost-per-1k-output: 4.0}\n")
        gw = LlmGateway(registry(tmp_path, {"OAI": "sekret"}))
        r = run(gw.chat(CTX, ChatRequest.of("2+2?", system="be brief", model="fast", timeout=10)))
        assert r.text == "4" and r.usage.prompt_tokens == 10 and abs(r.usage.cost - 0.028) < 1e-9
        _, path, headers, body = stub.seen[0]
        assert headers["Authorization"] == "Bearer sekret" and headers["X-Team"] == "red"
        sent = json.loads(body)
        assert sent["model"] == "gpt-x" and sent["max_tokens"] == 77 and sent["messages"][0] == {"role": "system", "content": "be brief"}
    finally:
        stub.close()


def test_a_server_error_is_a_failure_without_the_body(tmp_path):
    stub = Stub(lambda m, p, h, b: (500, "application/json", '{"error":"secret prompt echo"}', None))
    try:
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nmodels: [{{name: m}}]\n")
        with pytest.raises(LlmError) as e:
            run(LlmGateway(registry(tmp_path)).chat(CTX, ChatRequest.of("q")))
        assert "HTTP 500" in e.value.message and "secret" not in e.value.message and len(stub.seen) == 1
    finally:
        stub.close()


def test_the_anthropic_provider_speaks_the_messages_api(tmp_path):
    ok = json.dumps({"model": "claude-x", "stop_reason": "end_turn", "content": [{"type": "text", "text": "hello"}], "usage": {"input_tokens": 5, "output_tokens": 1}})
    stub = Stub(lambda m, p, h, b: (200, "application/json", ok, None))
    try:
        write(tmp_path, "claude.yaml", f"type: anthropic\nbase-url: {stub.url}\napi-key: ${{AK}}\nmodels: [{{name: c, id: claude-x}}]\n")
        r = run(LlmGateway(registry(tmp_path, {"AK": "k1"})).chat(CTX, ChatRequest.of("hi", system="sys", max_tokens=9)))
        assert r.text == "hello"
        _, path, headers, body = stub.seen[0]
        assert path == "/v1/messages" and headers["x-api-key"] == "k1" and headers["anthropic-version"] == "2023-06-01"
        assert json.loads(body)["system"] == "sys" and json.loads(body)["max_tokens"] == 9
    finally:
        stub.close()


def test_the_server_provider_delegates_and_maps_server_errors(tmp_path):
    calls = []

    class Backend:
        async def ai_chat(self, ident, body):
            calls.append((ident, body))
            if "refuse" in body["messages"][0]["content"]:
                raise BackendError(403, "DRS-4018", "feature 'x' is not open to your roles")
            return {"text": "from server", "model": "srv", "usage": {"promptTokens": 3, "completionTokens": 1, "cost": 0.5}}

    class RT:
        def backend(self):
            return Backend()

    write(tmp_path, "srv.yaml", "type: server\nmodels:\n  - {name: default}\n  - {name: pinned, id: openai/gpt}\n")
    reg = from_directory(tmp_path, FACTORIES, RT(), "")
    gw = LlmGateway(reg)
    r = run(gw.chat(CallContext("ann", frozenset(), "ask", "IDENT"), ChatRequest.of("hello", model="srv/pinned")))
    assert r.text == "from server" and r.usage.cost == 0.5
    ident, body = calls[0]
    assert ident == "IDENT" and body["model"] == "openai/gpt" and body["feature"] == "ask"
    run(gw.chat(CTX, ChatRequest.of("hello", model="srv/default")))
    assert "model" not in calls[1][1]                                           # the server's own default
    with pytest.raises(LlmError) as e:
        run(gw.chat(CTX, ChatRequest.of("refuse", model="srv/default")))
    assert e.value.kind == "policy"


def test_policy_masks_limits_audits_and_gates_features(tmp_path):
    write(tmp_path, "mock.yaml", "type: mock\nmodels: [{name: m, cost-per-1k-input: 1000}]\n")
    audit = []
    ps = PolicySettings(per_user_per_minute=2, cost_per_user_per_day=0.5, feature_roles={"admin-only": ["admin"]})
    pol = DefaultPolicy(ps, PromptMasker(["salary"], [r"\b\d{16}\b"]), audit.append)
    gw = LlmGateway(registry(tmp_path), pol)
    ann = CallContext("ann", frozenset({"user"}), "help")
    assert run(gw.chat(ann, ChatRequest.of("salary: 90000 card 1234567812345678 ok"))).text == "echo: salary: ••• card ••• ok"
    assert audit[0]["user"] == "ann" and audit[0]["provider"] == "mock" and audit[0]["outcome"] == "ok"
    assert "echo" not in json.dumps(audit[0])                                   # audit carries sizes and outcome, never the text
    with pytest.raises(LlmError, match="not open to your roles"):
        run(gw.chat(CallContext("ann", frozenset({"user"}), "admin-only"), ChatRequest.of("x")))
    with pytest.raises(LlmError) as e:                                          # the first call spent the whole daily budget
        run(gw.chat(ann, ChatRequest.of("again")))
    assert e.value.kind == "rate_limited"


def test_per_minute_limit(tmp_path):
    write(tmp_path, "mock.yaml", "type: mock\nmodels: [{name: m}]\n")
    gw = LlmGateway(registry(tmp_path), DefaultPolicy(PolicySettings(per_user_per_minute=2)))
    run(gw.chat(CTX, ChatRequest.of("a")))
    run(gw.chat(CTX, ChatRequest.of("b")))
    with pytest.raises(LlmError) as e:
        run(gw.chat(CTX, ChatRequest.of("c")))
    assert e.value.kind == "rate_limited"


def test_mcp_mock_server_under_policy(tmp_path):
    d = tmp_path / "mcp"
    d.mkdir()
    (d / "demo.yaml").write_text("transport: mock\nmock:\n  tools:\n    - {name: add, description: adds, reply: 'sum ${a}+${b}'}\n    - {name: secret, reply: 'key is hunter2'}\n"
                                 "  resources: [{uri: 'mock://readme', text: 'ok'}]\n", encoding="utf-8")
    (d / "bad.yaml").write_text("transport: mock\nheaders: {Authorization: Bearer abc}\n", encoding="utf-8")
    from core.ai.mcp import from_directory as mcp_dir
    reg = mcp_dir(d, None, env={}.get)
    audit = []
    gw = McpGateway(reg, McpPolicy(server_roles={"demo": ["user"]}, tool_roles={"demo/secret": ["admin"]}, redacted_fields=frozenset({"password"}),
                                   masker=PromptMasker([], ["hunter2"]), audit=audit.append))
    assert "placeholder" in reg.file_errors["bad.yaml"] and "abc" not in reg.file_errors["bad.yaml"]
    ann = CallContext("ann", frozenset({"user"}), "chat")
    assert [t.name for t in run(gw.tools(ann, "demo"))] == ["add", "secret"][:1]
    assert run(gw.call_tool(ann, "demo", "add", {"a": 1, "b": 2, "password": "p"})).text == "sum 1+2"
    with pytest.raises(LlmError) as e:
        run(gw.call_tool(ann, "demo", "secret", {}))
    assert e.value.kind == "policy"
    with pytest.raises(LlmError):
        run(gw.call_tool(CallContext("x", frozenset({"guest"}), "chat"), "demo", "add", {}))
    admin = CallContext("root", frozenset({"user", "admin"}), "chat")
    assert run(gw.call_tool(admin, "demo", "secret", {})).text == "key is •••"          # results are masked too
    assert [a["tool"] for a in audit] == ["add", "secret"]


def test_mcp_streamable_http_handshake_lists_calls_and_closes():
    def handler(method, path, headers, body):
        if method == "DELETE":
            return 200, "text/plain", "", None
        if '"initialize"' in body:
            return 200, "application/json", json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-11-25", "capabilities": {}}}), {"Mcp-Session-Id": "s1"}
        if "notifications/initialized" in body:
            return 202, "text/plain", "", None
        if "tools/list" in body:
            ev = json.dumps({"jsonrpc": "2.0", "id": 2, "result": {"tools": [{"name": "echo", "description": "d"}, {"name": "hidden"}]}})
            return 200, "text/event-stream", f"event: message\ndata: {ev}\n\n", None
        return 200, "application/json", json.dumps({"jsonrpc": "2.0", "id": 3, "result": {"content": [{"type": "text", "text": "pong"}]}}), None

    stub = Stub(handler)
    try:
        from core.ai.mcp import StreamableHttpClient
        c = StreamableHttpClient({"name": "remote", "url": stub.url + "/mcp", "headers": {"Authorization": "Bearer t0k"}, "timeout": 5.0,
                                  "allowed_tools": ["echo"], "settings": {}, "enabled": True, "transport": "streamable-http"})

        async def go():
            tools = await c.tools()
            res = await c.call_tool("echo", {"m": "ping"})
            with pytest.raises(LlmError, match="allowed-tools"):
                await c.call_tool("hidden", {})
            await c.aclose()
            return tools, res

        tools, res = run(go())
        assert [t.name for t in tools] == ["echo"] and res.text == "pong"
        assert stub.seen[0][2]["Authorization"] == "Bearer t0k"
        assert stub.seen[2][2]["Mcp-Session-Id"] == "s1" and stub.seen[2][2]["MCP-Protocol-Version"] == "2025-11-25"
        assert stub.seen[-1][0] == "DELETE"
    finally:
        stub.close()


def test_everything_is_off_by_default():
    ai = AiRuntime(Settings({}))
    assert not ai.enabled and not ai.mcp_enabled and ai.registry.models() == [] and ai.mcp.entries() == []


def test_admin_page_and_chat_api(client, backend, monkeypatch, tmp_path):
    write(tmp_path, "mock.yaml", MOCK)
    mcp = tmp_path / "mcp"
    mcp.mkdir()
    (mcp / "demo.yaml").write_text("transport: mock\nmock:\n  tools:\n    - {name: add, description: adds}\n", encoding="utf-8")
    client.app.state.ai = AiRuntime(Settings({"ai": {"enabled": True, "dir": str(tmp_path), "default_model": "mock/m"}, "mcp": {"enabled": True, "dir": str(mcp)}}))
    server_view = {"enabled": True, "dir": "./config/ai", "types": ["openai"], "providers": [
        {"name": "corp", "type": "openai", "health": {"status": "UP", "detail": "last call succeeded"}, "models": [{"ref": "corp/gpt", "capabilities": ["chat"]}]}],
        "fileErrors": {}, "mcpEnabled": False, "mcpDir": "./config/mcp", "mcpServers": [], "mcpFileErrors": {}}

    async def admin(method, path, ident, body=None, **kw):
        if path == "/ai":
            return server_view
        if path.startswith("/mcp/"):
            return [{"name": "srv-tool", "description": "x"}]
        return {}

    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/ai").text
    for text in ("data-ai", "mock/m", "demo", "corp/gpt", "Server", "Console", "Show tools", "UNKNOWN"):
        assert text in page, text
    assert "add" in client.get("/admin/ai?tools=demo&where=console").text
    assert "srv-tool" in client.get("/admin/ai?tools=x&where=server").text
    r = client.post("/api/ai/chat", json={"prompt": "Hello there", "feature": "t"})
    assert r.status_code == 200 and r.json()["text"] == "Hi there"
    assert client.post("/api/ai/chat", json={}).status_code == 400
    assert client.post("/api/ai/chat", json={"prompt": "boom"}).status_code == 502


def test_chat_api_is_404_while_off(client):
    client.app.state.ai = AiRuntime(Settings({}))
    r = client.post("/api/ai/chat", json={"prompt": "x"})
    assert r.status_code == 404 and r.json()["code"] == "DRS-4015"


def test_admin_ai_test_connection_probes_one_provider_and_audits_it(client, tmp_path):
    """Admin -> AI "Test connection": the provider's probe() and live_models() on request, bounded, rate-limited per provider, audited, never carrying the key."""
    from routes import ai_routes

    models = json.dumps({"data": [{"id": "live-x"}, {"id": "live-embed"}]})
    stub = Stub(lambda m, p, h, b: (200, "application/json", models, None) if p.endswith("/models") else (404, "application/json", "{}", None))
    try:
        write(tmp_path, "mock.yaml", MOCK)
        write(tmp_path, "local.yaml", f"type: vllm\nbase-url: {stub.url}/v1\nmodels:\n  - {{name: base}}\n")
        write(tmp_path, "off.yaml", "type: groq\nenabled: false\n")
        client.app.state.ai = AiRuntime(Settings({"ai": {"enabled": True, "dir": str(tmp_path), "default_model": "mock/m"}}))
        ai_routes._TESTED.clear()
        page = client.get("/admin/ai").text
        assert 'data-ai-test="mock"' in page and 'data-ai-test="local"' in page and "admin-ai-test.js" in page

        r = client.post("/admin/ai/api/test/mock", json={})
        assert r.status_code == 200 and r.json()["ok"] is True and r.json()["models"] == [] and "no live call" in r.json()["detail"]

        r = client.post("/admin/ai/api/test/local", json={})
        body = r.json()
        assert r.status_code == 200 and body["ok"] is True and body["network"] is True and "live-x" in body["models"] and "live-embed" in body["models"]
        assert any(p.endswith("/models") for _, p, _, _ in stub.seen)                       # the endpoint really was asked

        again = client.post("/admin/ai/api/test/local", json={})                              # a second test straight after: one per provider every few seconds
        assert again.status_code == 429 and again.json()["kind"] == "rate_limited"

        off = client.post("/admin/ai/api/test/off", json={}).json()
        assert off["ok"] is False and off["network"] is False and "enabled: false" in off["detail"]
        assert client.post("/admin/ai/api/test/nope", json={}).status_code == 404

        audited = [a for a in client.app.state.ai.audit_log if a.get("feature") == "test-connection"]
        assert [(a["provider"], a["outcome"]) for a in audited] == [("mock", "ok"), ("local", "ok"), ("off", "not-usable")]
        assert all(a["user"] for a in audited)                                                # who tested it is on every row
    finally:
        stub.close()


def test_admin_ai_test_connection_reports_a_provider_that_does_not_answer(client, tmp_path):
    from routes import ai_routes

    write(tmp_path, "gone.yaml", "type: vllm\nbase-url: http://127.0.0.1:1/v1\nmodels:\n  - {name: base}\n")
    client.app.state.ai = AiRuntime(Settings({"ai": {"enabled": True, "dir": str(tmp_path)}}))
    ai_routes._TESTED.clear()
    body = client.post("/admin/ai/api/test/gone", json={}).json()
    assert body["ok"] is False and body["models"] == [] and body["detail"]
    assert [a["outcome"] for a in client.app.state.ai.audit_log if a.get("feature") == "test-connection"] == ["failed"]
