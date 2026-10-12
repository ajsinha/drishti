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

"""The shared ``tls.*`` settings of the connectors (docs/connectors/TLS.md) for the AI providers, against fake HTTPS servers with a test CA: a private CA is trusted only when
configured, mutual TLS is refused without the client certificate, the other trust and identity forms (PEM text, PKCS12, encrypted key with an ${ENV} password), hostname
verification, the insecure switch, the expiry note, and everything through an HTTP proxy that tunnels with CONNECT. No real network."""
import datetime as dt
import ipaddress
import socket
import threading

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

from core.ai.llm import LLMError, ProviderBase
from llm_fakes import KEY, FakeServer

ENV = {"THE_KEY": KEY, "KEYPW": "s3cret"}


def _name(cn):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def issue(cn, issuer=None, issuer_key=None, san=None, days=365, ca=False):
    key = ec.generate_private_key(ec.SECP256R1())
    now = dt.datetime.now(dt.timezone.utc)
    b = (x509.CertificateBuilder().subject_name(_name(cn)).issuer_name(issuer.subject if issuer else _name(cn)).public_key(key.public_key())
         .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(minutes=5)).not_valid_after(now + dt.timedelta(days=days))
         .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
    if san:
        b = b.add_extension(x509.SubjectAlternativeName(san), critical=False)
    return b.sign(issuer_key or key, hashes.SHA256()), key


def pem(c):
    return c.public_bytes(serialization.Encoding.PEM)


def key_pem(k, pw=None):
    enc = serialization.BestAvailableEncryption(pw.encode()) if pw else serialization.NoEncryption()
    return k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, enc)


@pytest.fixture(scope="module")
def pki(tmp_path_factory):
    d = tmp_path_factory.mktemp("pki")
    ca, ca_key = issue("Test CA", ca=True)
    other, other_key = issue("Other CA", ca=True)
    srv, srv_key = issue("server", ca, ca_key, [x509.IPAddress(ipaddress.ip_address("127.0.0.1"))])
    wrong, wrong_key = issue("server", ca, ca_key, [x509.DNSName("elsewhere.example")])       # right CA, wrong name
    cli, cli_key = issue("client", ca, ca_key)
    soon, soon_key = issue("expiring-client", ca, ca_key, days=10)
    f = {}
    for name, c, k in (("ca", ca, ca_key), ("other", other, other_key), ("srv", srv, srv_key), ("wrong", wrong, wrong_key), ("cli", cli, cli_key), ("soon", soon, soon_key)):
        (d / f"{name}.pem").write_bytes(pem(c))
        (d / f"{name}.key").write_bytes(key_pem(k))
        f[name] = str(d / f"{name}.pem")
        f[name + "_key"] = str(d / f"{name}.key")
    (d / "cli-enc.key").write_bytes(key_pem(cli_key, "s3cret"))
    f["cli_enc_key"] = str(d / "cli-enc.key")
    (d / "cli.p12").write_bytes(pkcs12.serialize_key_and_certificates(b"c", cli_key, cli, [ca], serialization.BestAvailableEncryption(b"s3cret")))
    f["cli_p12"] = str(d / "cli.p12")
    (d / "trust.p12").write_bytes(pkcs12.serialize_java_truststore([pkcs12.PKCS12Certificate(ca, b"ca")], serialization.BestAvailableEncryption(b"s3cret")))
    f["trust_p12"] = str(d / "trust.p12")
    (d / "trust.jks").write_bytes(b"\xfe\xed\xfe\xed\x00\x00\x00\x02")
    f["jks"] = str(d / "trust.jks")
    f["ca_text"] = pem(ca).decode()
    return f


@pytest.fixture()
def https(pki):
    made = []

    def make(cert="srv", mtls=False, api="openai"):
        s = FakeServer(api, tls=(pki[cert], pki[cert + "_key"], pki["ca"] if mtls else None))
        made.append(s)
        return s
    yield make
    for s in made:
        s.close()


def prov(url, **extra):
    return ProviderBase.from_settings("p", {"type": "groq", "base-url": url, "api-key": "${THE_KEY}", "timeout": "5s", "catalogue": False, "models": [{"name": "m"}], **extra},
                                      env=ENV.get)


def ask(p, text="ping"):
    return p.chat_model("m").chat_completions_create(messages=[{"role": "user", "content": text}]).text


def test_a_private_ca_is_trusted_only_when_it_is_configured(https, pki):
    s = https()
    with pytest.raises(LLMError) as e:
        ask(prov(s.url))
    assert e.value.kind == "FAILED" and "could not be reached" in str(e.value)                 # the system authorities do not know the test CA
    assert ask(prov(s.url, tls={"ca-file": pki["ca"]})) == "echo: ping"
    with pytest.raises(LLMError):
        ask(prov(s.url, tls={"ca-file": pki["other"]}))                                          # another CA is not the server's


def test_the_other_trust_forms_pem_text_pkcs12_and_the_system_authorities_merged(https, pki):
    s = https()
    assert ask(prov(s.url, tls={"ca-file": pki["ca_text"]})) == "echo: ping"
    assert ask(prov(s.url, tls={"truststore": pki["trust_p12"], "truststore-password": "${KEYPW}"})) == "echo: ping"
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "trust-jvm-default": "true"})) == "echo: ping"
    with pytest.raises(LLMError) as e:
        prov(s.url, tls={"truststore": pki["trust_p12"], "truststore-password": "wrong-literal"})
    assert "placeholder" in str(e.value) and "wrong-literal" not in str(e.value)
    with pytest.raises(LLMError) as e:
        prov(s.url, tls={"truststore": pki["jks"]})
    assert "JKS" in str(e.value) and "PKCS12" in str(e.value)


def test_mutual_tls_is_refused_without_the_client_certificate_and_accepted_with_it(https, pki):
    s = https(mtls=True)
    with pytest.raises(LLMError):
        ask(prov(s.url, tls={"ca-file": pki["ca"]}))
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "cert-file": pki["cli"], "key-file": pki["cli_key"]})) == "echo: ping"
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "cert-file": pki["cli"], "key-file": pki["cli_enc_key"], "key-password": "${KEYPW}"})) == "echo: ping"
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "keystore": pki["cli_p12"], "keystore-password": "${KEYPW}"})) == "echo: ping"
    with pytest.raises(LLMError) as e:
        prov(s.url, tls={"ca-file": pki["ca"], "cert-file": pki["cli"], "key-file": pki["cli_enc_key"], "key-password": "${NOT_SET_ANYWHERE}"})
    assert "NOT_SET_ANYWHERE" in str(e.value)
    with pytest.raises(LLMError):
        prov(s.url, tls={"ca-file": pki["ca"], "cert-file": pki["cli"], "key-file": pki["srv_key"]})          # a key that is not the certificate's


def test_hostname_verification_is_on_unless_switched_off_and_insecure_needs_the_environment(https, pki):
    s = https(cert="wrong")
    with pytest.raises(LLMError):
        ask(prov(s.url, tls={"ca-file": pki["ca"]}))
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "verify-hostname": "false"})) == "echo: ping"
    with pytest.raises(LLMError) as e:
        prov(s.url, tls={"insecure-trust-all": "true"})
    assert "DRISHTI_ALLOW_INSECURE_TLS" in str(e.value)
    p = ProviderBase.from_settings("p", {"type": "groq", "base-url": s.url, "api-key": "${THE_KEY}", "catalogue": False, "models": [{"name": "m"}], "tls": {"insecure-trust-all": "true"}},
                                   env={**ENV, "DRISHTI_ALLOW_INSECURE_TLS": "true"}.get)
    assert ask(p) == "echo: ping"


def test_tls_settings_against_a_plain_address_are_an_error_not_a_downgrade(https, pki):
    with pytest.raises(LLMError) as e:
        prov("http://127.0.0.1:1", tls={"ca-file": pki["ca"]})
    assert "not https://" in str(e.value)
    with pytest.raises(LLMError):
        prov("https://127.0.0.1:1", tls={"protocols": "SSLv3"})


def test_the_protocols_setting_narrows_what_is_offered(https, pki):
    s = https()
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "protocols": "TLSv1.3,TLSv1.2"})) == "echo: ping"
    assert ask(prov(s.url, tls={"ca-file": pki["ca"], "protocols": "TLSv1.3"})) == "echo: ping"


def test_a_certificate_close_to_expiry_shows_in_health_and_describe_config(https, pki):
    s = https()
    p = prov(s.url, tls={"ca-file": pki["ca"], "cert-file": pki["soon"], "key-file": pki["soon_key"]})
    h = p.health()
    assert h.ok and "TLS certificate" in h.detail and "expires in" in h.detail and "tls.cert-file" in h.detail
    d = p.describe_config()["tls"]
    assert {c["source"] for c in d} == {"tls.ca-file", "tls.cert-file"}
    assert "expires in" not in prov(s.url, tls={"ca-file": pki["ca"]}).health().detail


class ConnectProxy:
    """An HTTP proxy that only tunnels (CONNECT host:port, then bytes both ways); it counts the tunnels."""

    def __init__(self):
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(8)
        self.tunnels, self.auth = [], []
        self.url = f"http://127.0.0.1:{self.sock.getsockname()[1]}"
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while True:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(c,), daemon=True).start()

    def _serve(self, c):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = c.recv(4096)
            if not chunk:
                return
            data += chunk
        head = data.split(b"\r\n")[0].decode()
        self.auth.append(next((ln.decode() for ln in data.split(b"\r\n") if ln.lower().startswith(b"proxy-authorization")), ""))
        if not head.startswith("CONNECT "):
            c.sendall(b"HTTP/1.1 405 no\r\nContent-Length: 0\r\n\r\n")
            return
        host, port = head.split()[1].rsplit(":", 1)
        self.tunnels.append(head.split()[1])
        up = socket.create_connection((host, int(port)))
        c.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")

        def pipe(a, b):
            try:
                while (x := a.recv(65536)):
                    b.sendall(x)
            except OSError:
                pass
            finally:
                for s in (a, b):
                    try:
                        s.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
        threading.Thread(target=pipe, args=(c, up), daemon=True).start()
        pipe(up, c)

    def close(self):
        self.sock.close()


def test_the_tls_settings_hold_through_an_http_proxy_tunnel(https, pki):
    s = https(mtls=True)
    px = ConnectProxy()
    try:
        tls = {"ca-file": pki["ca"], "cert-file": pki["cli"], "key-file": pki["cli_key"]}
        assert ask(prov(s.url, tls=tls, proxy={"url": px.url})) == "echo: ping"
        assert px.tunnels == [s.url.split("//")[1]]                                           # it went through CONNECT, and TLS ran end to end
        with pytest.raises(LLMError):
            ask(prov(s.url, tls={"ca-file": pki["ca"]}, proxy={"url": px.url}))                # mTLS still refused inside the tunnel
        with pytest.raises(LLMError):
            ask(prov(s.url, tls={"cert-file": pki["cli"], "key-file": pki["cli_key"]}, proxy={"url": px.url}))   # the CA is still needed inside the tunnel
        assert len(px.tunnels) == 3
    finally:
        px.close()


@pytest.mark.parametrize("typ,api,extra", [("gemini", "gemini", {}), ("cohere", "cohere", {}), ("ollama", "ollama", {}), ("azure_openai", "azure", {}),
                                           ("mistral", "openai", {}), ("openai", "openai", {"completions-path": "/chat/completions"})])
def test_every_provider_type_takes_the_same_tls_block(https, pki, typ, api, extra):
    s = https(api=api)
    cfg = {"type": typ, "base-url": s.url, "timeout": "5s", "catalogue": False, "models": [{"name": "m"}], **extra}
    if typ != "ollama":
        cfg["api-key"] = "${THE_KEY}"
    bad = ProviderBase.from_settings("p", cfg, env=ENV.get)
    with pytest.raises(LLMError):
        ask(bad)
    good = ProviderBase.from_settings("p", {**cfg, "tls": {"ca-file": pki["ca"]}}, env=ENV.get)
    assert ask(good) == "echo: ping"


def test_bedrock_through_boto3_takes_the_ca_and_the_client_certificate(https, pki, monkeypatch, tmp_path):
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "AKIDEXAMPLE")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "s")
    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")
    monkeypatch.setenv("AWS_CONFIG_FILE", str(tmp_path / "none"))
    s = https(api="bedrock")
    cfg = {"type": "bedrock", "base-url": s.url, "region": "us-east-1", "timeout": "5s", "catalogue": False, "models": [{"name": "m"}]}
    with pytest.raises(LLMError):
        ask(ProviderBase.from_settings("b", cfg, env={}.get))
    p = ProviderBase.from_settings("b", {**cfg, "tls": {"ca-file": pki["ca"]}}, env={}.get)
    assert ask(p) == "echo: ping"
    m = https(api="bedrock", mtls=True)
    cfg["base-url"] = m.url
    with pytest.raises(LLMError):
        ask(ProviderBase.from_settings("b", {**cfg, "tls": {"ca-file": pki["ca"]}}, env={}.get))
    ok = ProviderBase.from_settings("b", {**cfg, "tls": {"ca-file": pki["ca"], "cert-file": pki["cli"], "key-file": pki["cli_key"]}}, env={}.get)
    assert ask(ok) == "echo: ping"
    ok.close()
    with pytest.raises(LLMError) as e:
        ProviderBase.from_settings("b", {**cfg, "tls": {"ca-file": pki["ca"], "verify-hostname": "false"}}, env={}.get)
    assert "verify-hostname" in str(e.value)


def test_the_mcp_client_builds_its_context_from_the_same_settings(pki):
    from core.ai.mcp import StreamableHttpClient
    from core.ai import config as cfgmod
    c = StreamableHttpClient({"name": "s", "url": "https://127.0.0.1:1/mcp", "headers": {}, "timeout": 1.0, "allowed_tools": [],
                              "settings": {"tls.ca-file": pki["ca"], "tls.cert-file": pki["cli"], "tls.key-file": pki["cli_key"]}})
    assert c.tls.certs and {x.source for x in c.tls.certs} == {"tls.ca-file", "tls.cert-file"}
    from core.ai.types import LlmError
    with pytest.raises(LlmError):
        StreamableHttpClient({"name": "s", "url": "http://x/mcp", "headers": {}, "timeout": 1.0, "allowed_tools": [], "settings": {"tls.ca-file": pki["ca"]}})
