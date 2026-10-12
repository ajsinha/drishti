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

"""The outbound HTTP proxy of an AI provider or an MCP server, with the same rules as the server's connectors (``docs/connectors/TLS.md``).

A provider or MCP file may carry a ``proxy:`` block (``url``, ``username``, ``password`` as an ``${ENV}`` placeholder, ``no-proxy``, ``enabled``)
or ``proxy: none``. Without either, the site default applies: the ``DRISHTI_HTTP_PROXY_URL`` / ``_USERNAME`` / ``_PASSWORD`` / ``_NO_PROXY`` /
``_ENABLED`` environment variables (the very variables the server's ``drishti.http.proxy.*`` settings read), and, when
``DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT=true``, the standard ``HTTPS_PROXY`` / ``HTTP_PROXY`` / ``NO_PROXY``. ``http://`` and ``https://`` targets both
go through an ``http://`` proxy (an https target is tunnelled with CONNECT and keeps its own TLS). The password is never shown or logged."""
from __future__ import annotations

import ipaddress
import re
from dataclasses import dataclass, field
from urllib.parse import unquote, urlsplit

from core.ai.types import LlmError

_ENV = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?\}$")
_NONE = {"none", "off", "false", "direct"}
_LABEL = {"none": "none", "disabled": "disabled by the file", "file": "file", "site": "site default", "environment": "environment"}


def _split(text: str | None) -> list[str]:
    return [s for s in re.split(r"[,\s]+", text or "") if s]


class NoProxy:
    """Hosts that bypass the proxy: ``*``, names (also their sub-domains), ``.suffix`` / ``*.suffix``, addresses and CIDR blocks."""

    def __init__(self, entries: list[str]):
        self.entries = list(entries)
        self._all = False
        self._names: list[str] = []
        self._nets = []
        for raw in entries:
            e = raw.strip().lower()
            if not e:
                continue
            if e == "*":
                self._all = True
            elif "/" in e:
                try:
                    self._nets.append(ipaddress.ip_network(e, strict=False))
                except ValueError:
                    raise LlmError("config", f"no-proxy: '{raw}' is not a valid CIDR block") from None
            else:
                if e.startswith("*."):
                    e = e[1:]
                if e.count(":") == 1:
                    e = e.split(":")[0]
                self._names.append(e)

    def matches(self, host: str | None) -> bool:
        if not host:
            return False
        if self._all:
            return True
        h = host.lower().strip("[]")
        for n in self._names:
            if (h.endswith(n) or h == n[1:]) if n.startswith(".") else (h == n or h.endswith("." + n)):
                return True
        try:
            ip = ipaddress.ip_address(h)
        except ValueError:
            return False
        return any(ip.version == n.version and ip in n for n in self._nets)


@dataclass(frozen=True)
class Proxy:
    source: str = "none"          # none | disabled | file | site | environment
    host: str = ""
    port: int = 0
    username: str | None = None
    password: str | None = field(default=None, repr=False)
    no_proxy: NoProxy = field(default_factory=lambda: NoProxy([]))
    variable: str | None = None

    @property
    def active(self) -> bool:
        return bool(self.host)

    def applies_to(self, url: str) -> bool:
        return self.active and not self.no_proxy.matches(urlsplit(url).hostname)

    def httpx_args(self, url: str) -> dict:
        """Keyword arguments for ``httpx.AsyncClient`` so a request to ``url`` goes through the proxy (nothing when it is bypassed or off)."""
        if not self.applies_to(url):
            return {}
        import httpx
        auth = (self.username, self.password) if self.username else None
        return {"proxy": httpx.Proxy(f"http://{self.host}:{self.port}", auth=auth)}

    def describe(self) -> dict:
        """The masked form for Admin pages and logs: never the password."""
        d = {"enabled": self.active, "source": self.source, "sourceLabel": _LABEL[self.source] + (f" ({self.variable})" if self.variable else "")}
        if self.active:
            d.update({"url": f"http://{self.host}:{self.port}", "username": self.username, "password": "***" if self.password else None,
                      "noProxy": self.no_proxy.entries})
        return d

    def note(self) -> str:
        return f"via proxy {self.host}:{self.port} ({_LABEL[self.source]})" if self.active else ""

    def __str__(self) -> str:
        return f"Proxy({_LABEL[self.source]}, {self.host}:{self.port})" if self.active else f"Proxy({_LABEL[self.source]})"


def check_password(settings: dict, env) -> None:
    """The proxy password is an ``${ENV}`` placeholder, never the value itself; resolves it in ``settings``."""
    pw = settings.get("proxy.password")
    if pw is None or str(pw).strip() == "":
        return
    m = _ENV.match(str(pw).strip())
    if not m:
        raise LlmError("config", "proxy.password must be an ${ENV_NAME} placeholder; passwords are never written into a file")
    v = env(m.group(1))
    settings["proxy.password"] = v.strip() if v and v.strip() else (m.group(2) or "")


def _url(text: str, what: str):
    t = text if "://" in text else "http://" + text
    u = urlsplit(t)
    if not u.hostname:
        raise LlmError("config", f"{what}: a proxy address looks like http://host:port")
    if u.scheme == "https":
        raise LlmError("config", f"{what}: a proxy reached over TLS (https://) is not supported; use http://{u.hostname}:{u.port or 80} "
                                 "(https targets are still tunnelled with CONNECT and keep their own TLS)")
    if u.scheme != "http":
        raise LlmError("config", f"{what}: '{u.scheme}://' is not supported: use http://host:port")
    return u


def _parse(settings: dict, source: str, where: str) -> Proxy:
    enabled = str(settings.get("proxy.enabled", "")).strip().lower()
    if enabled == "false":
        return Proxy("disabled") if source == "file" else Proxy()
    url = str(settings.get("proxy.url", "")).strip()
    if not url:
        if source == "file":
            raise LlmError("config", f"{where}url is missing: give the proxy as http://host:port (or write 'proxy: none')")
        return Proxy()
    u = _url(url, where + "url")
    if u.username or u.password:
        raise LlmError("config", f"{where}url must not carry credentials: put them in {where}username and {where}password (an ${{ENV}} reference)")
    user = str(settings.get("proxy.username", "")).strip() or None
    pw = str(settings.get("proxy.password", "")) or None
    if (user is None) != (pw is None):
        raise LlmError("config", f"{where}{'password is given without ' + where + 'username' if user is None else 'username is given without ' + where + 'password'}")
    skip = _split(settings.get("proxy.no-proxy"))
    for k in sorted(k for k in settings if re.fullmatch(r"proxy\.no-proxy\.\d+", k)):
        skip += _split(settings[k])
    return Proxy(source, u.hostname, u.port or 80, user, pw, NoProxy(skip))


def _from_environment(target: str, env) -> Proxy:
    https = target.lower().startswith("https://")
    for name in (("HTTPS_PROXY", "https_proxy") if https else ("HTTP_PROXY", "http_proxy")):
        v = env(name)
        if v and v.strip():
            u = _url(v.strip(), name)
            skip = _split(env("NO_PROXY") or env("no_proxy"))
            return Proxy("environment", u.hostname, u.port or 80, unquote(u.username) if u.username else None,
                         unquote(u.password) if u.password else None, NoProxy(skip), name.upper())
    return Proxy()


def site_settings(env) -> tuple[dict, bool]:
    """The site default from the environment: the ``proxy.*`` keys and whether to honour ``HTTPS_PROXY`` and friends."""
    s = {}
    for key, var in (("url", "URL"), ("username", "USERNAME"), ("password", "PASSWORD"), ("no-proxy", "NO_PROXY"), ("enabled", "ENABLED")):
        v = env(f"DRISHTI_HTTP_PROXY_{var}")
        if v and v.strip():
            s[f"proxy.{key}"] = v.strip()
    return s, (env("DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT") or "").strip().lower() == "true"


def resolve(settings: dict, target_url: str, env) -> Proxy:
    """The proxy for a provider or MCP file whose target is ``target_url``: the file's own, else the site default, else the environment."""
    bare = settings.get("proxy")
    if bare is not None and str(bare).strip():
        if str(bare).strip().lower() in _NONE:
            return Proxy("disabled")
        raise LlmError("config", f"proxy: '{bare}' is not a proxy: write 'proxy: none' to go direct, or a block with 'url: http://host:port'")
    if any(k.startswith("proxy.") for k in settings):
        return _parse(settings, "file", "proxy.")
    site, from_env = site_settings(env)
    p = _parse(site, "site", "DRISHTI_HTTP_PROXY_") if site else Proxy()
    if p.active:
        return p
    return _from_environment(target_url, env) if from_env else Proxy()
