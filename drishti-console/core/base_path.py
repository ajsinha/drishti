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

"""The console behind a reverse proxy at a URL sub-path (``https://host/drishti/``): ``server.base_path`` (``DRISHTI_CONSOLE_BASE_PATH``).

One rule: inside the console every route keeps its own path (``/bi``, ``/api/...``, ``/static/...``); the base is added only at the edges.

* :class:`BasePath`, the outermost middleware, takes the base off an incoming path (a proxy that forwards ``/drishti/bi`` as it is) and accepts
  a path without it too (a proxy that strips it); it puts the base in the scope as ``app_root_path``, so ``request.base_url`` and
  ``request.url_for`` name it, and adds it to what leaves: a ``Location`` that is a path, and a cookie's ``Path``.
* The pages name it once, ``<meta name="drishti-base">`` (``templates/base.html``); the template global ``BASE`` and the filter ``u`` prefix
  a URL in a template; the scripts send every root-absolute URL through ``drishti_url()`` (drishti-core, ``src/drishti_base.js``).
  ``tests/test_base_path_guard.py`` keeps it so.

Blank (the default) serves the console at ``/`` and changes nothing."""
from __future__ import annotations

import re
from urllib.parse import urlsplit, urlunsplit

_SEGMENT = re.compile(r"^[A-Za-z0-9._~\-]+$")


def normalize(value) -> str:
    """``/drishti`` from ``drishti``, ``/drishti/`` or ``/drishti``; ``""`` for blank or ``/``. A segment that is not plain URL text is refused."""
    text = str(value or "").strip().strip("/")
    if not text:
        return ""
    parts = text.split("/")
    for p in parts:
        if not _SEGMENT.match(p) or p in (".", ".."):
            raise ValueError(f"server.base_path: '{value}' is not a URL path (letters, digits, . _ ~ - between slashes)")
    return "/" + "/".join(parts)


def prefixed(base: str, url) -> str:
    """``url`` under the base when it is a path on this console (``/x``, not ``//host`` nor ``https://``) and does not carry the base already."""
    if not base or not isinstance(url, str) or not url.startswith("/") or url.startswith("//"):
        return url
    if url == base or url.startswith((base + "/", base + "?", base + "#")):
        return url
    return base + url


def install(env, base: str = "") -> None:
    """A Jinja environment's ``BASE`` (``<meta name="drishti-base">``, ``href="{{ BASE }}/bi"``) and filter ``u`` (a URL from data:
    ``href="{{ item.href | u }}"``); the app's and every test's environment that renders the console's templates."""
    env.globals["BASE"] = base
    env.filters["u"] = lambda url: prefixed(base, url)
    env.filters["based"] = lambda html: rebase_html(base, html)


_HTML_URL = re.compile(r"""(\s(?:href|src)=")(/(?!/)[^"]*)(")""")


def rebase_html(base: str, html):
    """A server-built HTML fragment (a guide, a README) with its root-absolute ``href``/``src`` under the base: ``{{ page.html | based }}``."""
    if not base or not html:
        return html
    from markupsafe import Markup

    out = _HTML_URL.sub(lambda m: m.group(1) + prefixed(base, m.group(2)) + m.group(3), str(html))
    return Markup(out) if isinstance(html, Markup) else out


def of(request) -> str:
    """The base of the request's console ("" when none)."""
    return str(request.scope.get("app_root_path", "") or "") if request is not None else ""


class BasePath:
    """Pure ASGI, outermost. The base off the incoming path; the base onto ``Location`` paths and cookie paths on the way out."""

    def __init__(self, app, base: str = ""):
        self.app = app
        self.base = normalize(base)
        self._raw = self.base.encode("latin-1")

    async def __call__(self, scope, receive, send):
        if not self.base or scope.get("type") not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "") or "/"
        if path == self.base or path.startswith(self.base + "/"):
            path = path[len(self.base):] or "/"
        scope = dict(scope, path=path, app_root_path=self.base)
        raw = scope.get("raw_path")
        if isinstance(raw, (bytes, bytearray)) and (raw == self._raw or raw.startswith(self._raw + b"/")):
            scope["raw_path"] = bytes(raw[len(self._raw):]) or b"/"
        host = next((v.decode("latin-1") for k, v in scope.get("headers", []) if k.lower() == b"host"), "")

        async def outgoing(message):
            if message.get("type") == "http.response.start":
                message = dict(message, headers=[self._header(k, v, host) for k, v in message.get("headers", [])])
            await send(message)

        await self.app(scope, receive, outgoing)

    def _header(self, key: bytes, value: bytes, host: str = "") -> tuple[bytes, bytes]:
        name = key.lower()
        if name == b"location":
            text = value.decode("latin-1")
            u = urlsplit(text)
            if u.scheme in ("http", "https") and host and u.netloc == host:      # this console by its full address (a trailing-slash redirect)
                return key, urlunsplit(u._replace(path=prefixed(self.base, u.path or "/"))).encode("latin-1")
            return key, prefixed(self.base, text).encode("latin-1")
        if name == b"set-cookie":
            text = value.decode("latin-1")
            return key, _COOKIE_PATH.sub(lambda m: m.group(1) + self.cookie_path(m.group(2)), text).encode("latin-1")
        return key, value

    def cookie_path(self, path: str) -> str:
        """A cookie's ``Path`` under the base: ``/`` becomes ``/drishti`` (the console and nothing else on the host)."""
        return self.base if path == "/" else prefixed(self.base, path)


_COOKIE_PATH = re.compile(r"(?i)(;\s*path=)(/[^;]*)")
