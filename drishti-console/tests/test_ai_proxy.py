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

"""The console's AI providers and MCP client through an outbound proxy (``docs/connectors/PROXY.md``): a small fake forward proxy on loopback
(HTTP forwarding and CONNECT) counts what it serves and checks ``Proxy-Authorization``. The ``proxy:`` block, the site default from the
environment, ``proxy: none``, the no-proxy list, a literal password refused, the password never in a description. No real network."""
import asyncio
import base64
import datetime
import ipaddress
import json
import socket
import ssl
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from core.ai import proxy as pxmod
from core.ai import config as cfgmod
from core.ai.gateway import LlmGateway
from core.ai.mcp import StreamableHttpClient
from core.ai.providers import FACTORIES
from core.ai.registry import from_directory
from core.ai.types import CallContext, ChatRequest, LlmError

CTX = CallContext("ann", frozenset({"user"}), "test")
OK = json.dumps({"model": "gpt-x", "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "4"}}],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 2}})


def run(coro):
    return asyncio.run(coro)


class FakeProxy:
    """HTTP forwarding and CONNECT on loopback; counts what it serves; demands Basic ``Proxy-Authorization`` when told."""

    def __init__(self, user=None, password=None):
        self.expected = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode() if user else None
        self.forwarded = self.connects = self.rejected = 0
        self.requests, self.authorizations = [], []
        outer = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def _auth(self):
                got = self.headers.get("Proxy-Authorization")
                if got:
                    outer.authorizations.append(got)
                if outer.expected and got != outer.expected:
                    outer.rejected += 1
                    self.send_response(407)
                    self.send_header("Proxy-Authenticate", 'Basic realm="fake"')
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return False
                return True

            def do_CONNECT(self):
                if not self._auth():
                    return
                host, port = self.path.rsplit(":", 1)
                outer.requests.append(f"CONNECT {self.path}")
                up = socket.create_connection((host, int(port)))
                outer.connects += 1
                self.send_response(200, "Connection established")
                self.end_headers()
                self.wfile.flush()
                self.connection.setblocking(True)
                t = threading.Thread(target=self._pipe, args=(self.connection, up), daemon=True)
                t.start()
                self._pipe(up, self.connection)
                t.join(2)
                self.close_connection = True

            @staticmethod
            def _pipe(src, dst):
                try:
                    while True:
                        data = src.recv(65536)
                        if not data:
                            break
                        dst.sendall(data)
                except OSError:
                    pass
                finally:
                    try:
                        dst.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass

            def _forward(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if not self._auth():
                    return
                outer.requests.append(f"{self.command} {self.path}")
                from urllib.parse import urlsplit
                u = urlsplit(self.path)
                with socket.create_connection((u.hostname, u.port or 80)) as up:
                    head = f"{self.command} {u.path or '/'}{'?' + u.query if u.query else ''} HTTP/1.1\r\n"
                    for k, v in self.headers.items():
                        if not k.lower().startswith("proxy-") and k.lower() != "connection":
                            head += f"{k}: {v}\r\n"
                    up.sendall((head + "Connection: close\r\n\r\n").encode() + body)
                    outer.forwarded += 1
                    out = b""
                    while chunk := up.recv(65536):
                        out += chunk
                self.wfile.write(out)
                self.close_connection = True

            do_GET = do_POST = do_DELETE = _forward

            def log_message(self, *a):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.server.daemon_threads = True
        self.port = self.server.server_port
        self.url = f"http://127.0.0.1:{self.port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def served(self):
        return self.forwarded + self.connects

    def close(self):
        self.server.shutdown()


class Stub:
    """A loopback server (HTTPS when ``context`` is given) answering from ``handler(method, path, headers, body)``."""

    def __init__(self, handler, context=None):
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

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.server.daemon_threads = True
        if context:
            self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.url = f"{'https' if context else 'http'}://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


def self_signed(tmp_path):
    """A throw-away certificate for 127.0.0.1 and localhost: (server context, path of the PEM a client trusts)."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=2))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    pem = tmp_path / "ca.pem"
    pem.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    keyfile = tmp_path / "key.pem"
    keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(str(pem), str(keyfile))
    return ctx, pem


def write(tmp_path, name, text):
    (tmp_path / name).write_text(text, encoding="utf-8")


def gateway(tmp_path, env=None):
    reg = from_directory(tmp_path, FACTORIES, None, "", env=(env or {}).get)
    return LlmGateway(reg)


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for v in ("DRISHTI_HTTP_PROXY_URL", "DRISHTI_HTTP_PROXY_USERNAME", "DRISHTI_HTTP_PROXY_PASSWORD", "DRISHTI_HTTP_PROXY_NO_PROXY",
              "DRISHTI_HTTP_PROXY_ENABLED", "DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT", "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy"):
        monkeypatch.delenv(v, raising=False)


def ask(gw):
    return run(gw.chat(CTX, ChatRequest.of("2+2?", model="m", timeout=10))).text


def test_an_openai_provider_goes_through_the_proxy_with_a_password_from_the_environment(tmp_path, monkeypatch):
    stub, proxy = Stub(lambda *a: (200, "application/json", OK, None)), FakeProxy("svc", "s3cr3t")
    try:
        monkeypatch.setenv("PX_PW", "s3cr3t")
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nproxy:\n  url: {proxy.url}\n  username: svc\n  password: ${{PX_PW}}\n"
                                    "models: [{name: m, id: gpt-x}]\n")
        assert ask(gateway(tmp_path, {"PX_PW": "s3cr3t"})) == "4"
        assert proxy.forwarded == 1 and proxy.authorizations[-1] == "Basic " + base64.b64encode(b"svc:s3cr3t").decode()
    finally:
        stub.close(), proxy.close()


def test_a_wrong_proxy_login_is_reported_without_the_password(tmp_path):
    stub, proxy = Stub(lambda *a: (200, "application/json", OK, None)), FakeProxy("svc", "right")
    try:
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nproxy:\n  url: {proxy.url}\n  username: svc\n  password: ${{PX_PW}}\n"
                                    "models: [{name: m}]\n")
        with pytest.raises(LlmError) as e:
            ask(gateway(tmp_path, {"PX_PW": "wrong-pw"}))
        assert "wrong-pw" not in e.value.message
        assert proxy.served == 0 and proxy.rejected >= 1
    finally:
        stub.close(), proxy.close()


def test_proxy_none_and_the_no_proxy_list_go_direct_and_the_site_default_is_inherited(tmp_path, monkeypatch):
    stub, proxy = Stub(lambda *a: (200, "application/json", OK, None)), FakeProxy()
    try:
        monkeypatch.setenv("DRISHTI_HTTP_PROXY_URL", proxy.url)
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nmodels: [{{name: m}}]\n")
        assert ask(gateway(tmp_path)) == "4" and proxy.forwarded == 1                                 # the site default
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nproxy: none\nmodels: [{{name: m}}]\n")
        assert ask(gateway(tmp_path)) == "4" and proxy.forwarded == 1                                 # proxy: none
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nproxy:\n  url: {proxy.url}\n  no-proxy: [localhost, 127.0.0.0/8]\n"
                                    "models: [{name: m}]\n")
        assert ask(gateway(tmp_path)) == "4" and proxy.forwarded == 1                                 # a no-proxy host
        monkeypatch.setenv("DRISHTI_HTTP_PROXY_NO_PROXY", "127.0.0.1")
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nmodels: [{{name: m}}]\n")
        assert ask(gateway(tmp_path)) == "4" and proxy.forwarded == 1                                 # the site's own no-proxy list
    finally:
        stub.close(), proxy.close()


def test_the_environment_is_honoured_only_when_the_site_asks(tmp_path, monkeypatch):
    stub, proxy = Stub(lambda *a: (200, "application/json", OK, None)), FakeProxy()
    try:
        monkeypatch.setenv("HTTP_PROXY", proxy.url)
        write(tmp_path, "oai.yaml", f"type: openai\nbase-url: {stub.url}\nmodels: [{{name: m}}]\n")
        assert ask(gateway(tmp_path)) == "4" and proxy.served == 0
        monkeypatch.setenv("DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT", "true")
        assert ask(gateway(tmp_path)) == "4" and proxy.forwarded == 1
    finally:
        stub.close(), proxy.close()


def test_a_literal_password_refuses_the_file_without_echoing_it(tmp_path):
    write(tmp_path, "oai.yaml", "type: openai\nproxy:\n  url: http://p:3128\n  username: svc\n  password: hunter2\nmodels: [{name: m}]\n")
    write(tmp_path, "mcp.yaml", "transport: mock\nproxy:\n  password: hunter2\n")
    _, errors = cfgmod.load_providers(tmp_path)
    assert "proxy.password must be an ${ENV_NAME}" in errors["oai.yaml"] and "hunter2" not in errors["oai.yaml"]
    (tmp_path / "oai.yaml").unlink()
    _, errors = cfgmod.load_mcp(tmp_path)
    assert "proxy.password" in errors["mcp.yaml"] and "hunter2" not in errors["mcp.yaml"]


def test_malformed_proxies_are_refused_naming_the_key():
    for settings, text in (({"proxy.username": "u"}, "proxy.url is missing"), ({"proxy.url": "https://p:1"}, "https://"),
                           ({"proxy.url": "http://u:pw@p:1"}, "must not carry credentials"), ({"proxy.url": "http://p:1", "proxy.username": "u"}, "password"),
                           ({"proxy": "maybe"}, "proxy: none")):
        with pytest.raises(LlmError, match=text) as e:
            pxmod.resolve(settings, "https://x.example", {}.get)
        assert "pw@" not in e.value.message


def test_the_description_is_masked_and_says_where_it_came_from():
    p = pxmod.resolve({"proxy.url": "http://proxy.corp:3128", "proxy.username": "svc", "proxy.password": "s3cr3t"}, "https://x.example", {}.get)
    d = p.describe()
    assert d["source"] == "file" and d["url"] == "http://proxy.corp:3128" and d["password"] == "***" and "s3cr3t" not in json.dumps(d) + str(p) + repr(p)
    site = pxmod.resolve({}, "https://x.example", {"DRISHTI_HTTP_PROXY_URL": "http://site:1"}.get)
    assert site.describe()["sourceLabel"] == "site default"
    env = pxmod.resolve({}, "https://x.example", {"DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT": "true", "HTTPS_PROXY": "http://bob:pw@e:3128", "NO_PROXY": "localhost"}.get)
    assert env.describe()["sourceLabel"] == "environment (HTTPS_PROXY)" and env.username == "bob" and "pw" not in json.dumps(env.describe())
    assert not env.applies_to("http://localhost/x") and env.applies_to("https://api.example.com")


def test_no_proxy_matching():
    n = pxmod.NoProxy(["localhost", ".corp.example", "*.dev.example", "10.0.0.0/8", "192.168.1.5", "fd00::/8", "svc:8080"])
    for h in ("localhost", "git.corp.example", "corp.example", "a.dev.example", "10.200.1.1", "192.168.1.5", "fd12::1", "svc"):
        assert n.matches(h), h
    for h in ("notcorp.example", "11.0.0.1", "192.168.1.6", "example.com"):
        assert not n.matches(h), h
    assert pxmod.NoProxy(["*"]).matches("anything.example")


def test_the_mcp_client_tunnels_an_https_server_with_connect_and_keeps_its_own_tls(tmp_path):
    ctx, ca = self_signed(tmp_path)

    def handler(method, path, headers, body):
        if method == "DELETE":
            return 200, "text/plain", "", None
        if '"initialize"' in body:
            return 200, "application/json", json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-11-25", "capabilities": {}}}), {"Mcp-Session-Id": "s1"}
        if "notifications/initialized" in body:
            return 202, "text/plain", "", None
        return 200, "application/json", json.dumps({"jsonrpc": "2.0", "id": 2, "result": {"tools": [{"name": "echo"}]}}), None

    stub, proxy = Stub(handler, ctx), FakeProxy("svc", "pw")
    try:
        settings = {"proxy.url": proxy.url, "proxy.username": "svc", "proxy.password": "pw", "tls.ca-file": str(ca)}
        c = StreamableHttpClient({"name": "remote", "url": stub.url + "/mcp", "headers": {}, "timeout": 5.0, "allowed_tools": [], "settings": settings,
                                  "enabled": True, "transport": "streamable-http"})

        async def go():
            tools = await c.tools()
            await c.aclose()
            return tools

        assert [t.name for t in run(go())] == ["echo"]
        assert proxy.connects >= 1 and proxy.requests[0].startswith("CONNECT 127.0.0.1:")
        # the target's certificate is still checked through the tunnel: without the CA it is refused
        bad = StreamableHttpClient({"name": "remote", "url": stub.url + "/mcp", "headers": {}, "timeout": 5.0, "allowed_tools": [],
                                    "settings": {"proxy.url": proxy.url, "proxy.username": "svc", "proxy.password": "pw"}, "enabled": True,
                                    "transport": "streamable-http"})
        with pytest.raises(LlmError):
            run(bad.tools())
    finally:
        stub.close(), proxy.close()
