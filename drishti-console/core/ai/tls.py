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

"""The shared ``tls.*`` settings of the connectors (docs/connectors/TLS.md; Java: ``com.ash.drishti.api.tls.TlsHttp``) for the console's HTTPS clients: the AI providers and the
MCP servers. The same keys mean the same thing:

* trust: ``tls.ca-file`` (a PEM file, or the PEM text itself), ``tls.truststore`` (PKCS12; **JKS is not built in the console**: ``keytool -importkeystore -deststoretype PKCS12``
  converts it), ``tls.truststore-password`` / ``-password-file``, ``tls.trust-jvm-default`` (here: the system's authorities; true when nothing else is named);
* a client identity for mutual TLS: ``tls.cert-file`` + ``tls.key-file`` (+ ``tls.key-password`` / ``-password-file``) or ``tls.keystore`` (PKCS12) with its password;
* ``tls.protocols`` (TLSv1.3,TLSv1.2), ``tls.cipher-suites`` (OpenSSL names), ``tls.verify-hostname`` (true), ``tls.insecure-trust-all`` (refused unless
  ``DRISHTI_ALLOW_INSECURE_TLS=true``).

A ``tls.*`` key against an ``http://`` address is a configuration error, never a silent downgrade. A password is an ``${ENV}`` placeholder or a ``-password-file``, never a
literal. The expiry of every certificate given (client chain, CA bundle) is read at start; ``TlsResult.note()`` is the sentence Admin shows once the soonest is less than 30
days away. Through an HTTP proxy an https target is tunnelled with CONNECT and keeps all of this (httpx hands the context to the tunnelled connection)."""
from __future__ import annotations

import os
import ssl
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from core.ai.types import LlmError

WARN_DAYS = 30


@dataclass(frozen=True)
class CertInfo:
    subject: str
    source: str
    not_before: datetime
    not_after: datetime


@dataclass
class TlsResult:
    context: object = True                               # an ssl.SSLContext, or True (httpx default verification) when no tls.* key is set
    certs: list = field(default_factory=list)
    ca_pem: str | None = None                            # the custom trust as PEM text, for clients that take a bundle (boto3)
    cert_pem: str | None = None
    key_pem: str | None = None                           # decrypted; never logged, never stored on disk by this module
    verify_hostname: bool = True
    insecure: bool = False

    def soonest(self, now: datetime | None = None):
        now = now or datetime.now(timezone.utc)
        return min(self.certs, key=lambda c: c.not_after, default=None)

    def note(self, now: datetime | None = None) -> str:
        """The expiry sentence, or '' while the soonest certificate has 30 days or more."""
        now = now or datetime.now(timezone.utc)
        c = self.soonest(now)
        if c is None:
            return ""
        days = (c.not_after - now).days
        if c.not_after < now:
            return f"TLS certificate {c.subject} ({c.source}) EXPIRED on {c.not_after.date()}; connections will fail"
        if c.not_before > now:
            return f"TLS certificate {c.subject} ({c.source}) is not valid before {c.not_before.date()}"
        if days < WARN_DAYS:
            return f"TLS certificate {c.subject} ({c.source}) expires in {days} days ({c.not_after.date()})"
        return ""

    def describe(self) -> list[dict]:
        return [{"subject": c.subject, "source": c.source, "notAfter": c.not_after.date().isoformat()} for c in self.certs]


def _secret(settings: dict, key: str, env) -> str | None:
    """``tls.<key>-file``'s first line, or ``tls.<key>`` as already resolved by the file loader (``core/ai/config.py`` accepts only an ${ENV} placeholder there)."""
    f = settings.get(f"tls.{key}-file")
    if f:
        try:
            return Path(f).read_text(encoding="utf-8").splitlines()[0]
        except (OSError, IndexError):
            raise LlmError("config", f"tls.{key}-file ({f}) cannot be read") from None
    return settings.get(f"tls.{key}") or None


def _text_or_file(v: str, name: str) -> str:
    if "-----BEGIN" in v:
        return v
    try:
        return Path(v).read_text(encoding="utf-8")
    except OSError:
        raise LlmError("config", f"{name} ({v}) cannot be read") from None


def _certs_of(pem: str, source: str) -> list[CertInfo]:
    from cryptography import x509
    out = []
    try:
        for c in x509.load_pem_x509_certificates(pem.encode()):
            out.append(CertInfo(c.subject.rfc4514_string() or "?", source, c.not_valid_before_utc, c.not_valid_after_utc))
    except ValueError:
        raise LlmError("config", f"{source}: no readable PEM certificate") from None
    return out


def _pem_of(cert) -> str:
    from cryptography.hazmat.primitives.serialization import Encoding
    return cert.public_bytes(Encoding.PEM).decode()


def _pkcs12(path: str, password: str | None, name: str):
    from cryptography.hazmat.primitives.serialization import pkcs12
    try:
        return pkcs12.load_pkcs12(Path(path).read_bytes(), password.encode() if password else None)
    except (OSError, ValueError):
        raise LlmError("config", f"{name} ({path}) is not a readable PKCS12 file or the password is wrong") from None


def _reject_jks(path: str, name: str) -> None:
    try:
        if Path(path).read_bytes()[:4] == b"\xfe\xed\xfe\xed":
            raise LlmError("config", f"{name} ({path}) is a JKS store; the console reads PKCS12 only: keytool -importkeystore -srckeystore {path} -destkeystore out.p12 -deststoretype PKCS12")
    except OSError:
        raise LlmError("config", f"{name} ({path}) cannot be read") from None


def build(settings: dict, url: str, env=os.environ.get) -> TlsResult:
    """The TLS context for ``url`` from the ``tls.*`` entries of ``settings`` (keys flattened as in a provider file)."""
    tls = {k: v for k, v in settings.items() if k.startswith("tls.")}
    https = url.lower().startswith("https://")
    if not https:
        if tls:
            raise LlmError("config", "tls.* is set but the URL is not https://: use an https:// address")
        return TlsResult()
    if not tls:
        return TlsResult()
    res = TlsResult()
    insecure = tls.get("tls.insecure-trust-all", "false").lower() == "true"
    if insecure and tls.get("tls.insecure-allowed") != "true" and (env("DRISHTI_ALLOW_INSECURE_TLS") or "").lower() != "true":
        raise LlmError("config", "tls.insecure-trust-all is refused unless DRISHTI_ALLOW_INSECURE_TLS=true is in the environment")
    verify_host = tls.get("tls.verify-hostname", "true").lower() != "false"
    try:
        ca_pems: list[str] = []
        if tls.get("tls.ca-file"):
            pem = _text_or_file(tls["tls.ca-file"], "tls.ca-file")
            res.certs += _certs_of(pem, "tls.ca-file")
            ca_pems.append(pem)
        if tls.get("tls.truststore"):
            _reject_jks(tls["tls.truststore"], "tls.truststore")
            p12 = _pkcs12(tls["tls.truststore"], _secret(tls, "truststore-password", env), "tls.truststore")
            certs = ([p12.cert.certificate] if p12.cert else []) + [c.certificate for c in p12.additional_certs]
            pem = "".join(_pem_of(c) for c in certs)
            res.certs += _certs_of(pem, "tls.truststore") if pem else []
            ca_pems.append(pem)
        jvm_default = tls.get("tls.trust-jvm-default")
        use_system = (jvm_default.lower() == "true") if jvm_default else not ca_pems
        ctx = ssl.create_default_context() if use_system else ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT                 # as the JVM: a private CA without key-usage / key-identifier extensions is accepted
        for pem in ca_pems:
            if pem:
                ctx.load_verify_locations(cadata=pem)
        res.ca_pem = "".join(ca_pems) or None
        # a client identity
        cert_pem = key_pem = None
        if tls.get("tls.keystore"):
            _reject_jks(tls["tls.keystore"], "tls.keystore")
            p12 = _pkcs12(tls["tls.keystore"], _secret(tls, "keystore-password", env), "tls.keystore")
            if p12.key is None or p12.cert is None:
                raise LlmError("config", f"tls.keystore ({tls['tls.keystore']}) holds no private key with a certificate")
            from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat
            cert_pem = _pem_of(p12.cert.certificate) + "".join(_pem_of(c.certificate) for c in p12.additional_certs)
            key_pem = p12.key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()).decode()
        elif tls.get("tls.cert-file"):
            cert_pem = _text_or_file(tls["tls.cert-file"], "tls.cert-file")
            if not tls.get("tls.key-file"):
                raise LlmError("config", "tls.cert-file needs tls.key-file")
            key_raw = _text_or_file(tls["tls.key-file"], "tls.key-file")
            pw = _secret(tls, "key-password", env)
            from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, load_pem_private_key
            try:
                key = load_pem_private_key(key_raw.encode(), pw.encode() if pw else None)
            except (ValueError, TypeError):
                raise LlmError("config", f"tls.key-file ({tls['tls.key-file']}) is not a readable private key or its password is wrong") from None
            key_pem = key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()).decode()
        if cert_pem:
            res.certs += _certs_of(cert_pem, "tls.cert-file" if tls.get("tls.cert-file") else "tls.keystore")
            res.cert_pem, res.key_pem = cert_pem, key_pem
            _load_chain(ctx, cert_pem, key_pem)
        if tls.get("tls.protocols"):
            vs = {"TLSv1.2": ssl.TLSVersion.TLSv1_2, "TLSv1.3": ssl.TLSVersion.TLSv1_3}
            want = [p.strip() for p in tls["tls.protocols"].split(",") if p.strip()]
            bad = [p for p in want if p not in vs]
            if bad:
                raise LlmError("config", f"tls.protocols: {', '.join(bad)} is not supported (TLSv1.2, TLSv1.3)")
            ctx.minimum_version, ctx.maximum_version = min(vs[p] for p in want), max(vs[p] for p in want)
        if tls.get("tls.cipher-suites"):
            ctx.set_ciphers(":".join(s.strip() for s in tls["tls.cipher-suites"].split(",") if s.strip()))
        if insecure:
            ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
        elif not verify_host:
            ctx.check_hostname = False
        res.context, res.verify_hostname, res.insecure = ctx, verify_host and not insecure, insecure
        return res
    except ssl.SSLError as e:
        raise LlmError("config", f"cannot create the TLS context ({type(e).__name__})") from None


def _load_chain(ctx: ssl.SSLContext, cert_pem: str, key_pem: str) -> None:
    """``load_cert_chain`` wants files: write both to a private temporary folder, load, and remove them at once."""
    import tempfile
    with tempfile.TemporaryDirectory(prefix="drishti-tls-") as d:
        c, k = Path(d, "c.pem"), Path(d, "k.pem")
        for p, t in ((c, cert_pem), (k, key_pem)):
            fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(t)
        ctx.load_cert_chain(str(c), str(k))
