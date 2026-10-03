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

"""Cross-site request forgery defence (beside the session cookie's SameSite=Lax).

* A state-changing request (POST, PUT, PATCH, DELETE) must come from the console's own pages: its ``Origin`` header
  (or, when a browser sends none, its ``Referer``) names this console (the request's ``Host``) or one of
  ``auth.allowed_origins`` (for a console behind a proxy that changes the host). ``Origin: null`` is refused. A request
  with neither header (a script, not a browser) passes: it cannot carry someone else's cookie.
* Routes that read JSON read it through :func:`json_body`, which refuses a body not sent as ``application/json``: a
  cross-site form can send ``text/plain`` without asking the browser's permission, but never ``application/json``.
"""
from __future__ import annotations

import json
from urllib.parse import urlsplit

from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

UNSAFE = frozenset({"POST", "PUT", "PATCH", "DELETE"})


def _origin(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}".lower() if parts.scheme and parts.netloc else ""


class SameOrigin(BaseHTTPMiddleware):
    """Refuses state-changing requests that a page on another site sent (403, DRS-5002)."""

    def __init__(self, app, allowed=()):
        super().__init__(app)
        if isinstance(allowed, str):
            allowed = allowed.split(",")
        self.allowed = frozenset(_origin(a.strip()) for a in allowed or () if a and a.strip())

    def permits(self, request) -> bool:
        if request.method not in UNSAFE:
            return True
        sent = request.headers.get("origin")
        if sent is None:
            referer = request.headers.get("referer")
            if referer is None:
                return True                              # not a browser on another site
            sent = _origin(referer)
        origin = _origin(sent) if sent and sent != "null" else ""
        if not origin:
            return False
        host = (request.headers.get("host") or "").lower()
        return urlsplit(origin).netloc == host or origin in self.allowed

    async def dispatch(self, request, call_next):
        if not self.permits(request):
            return JSONResponse({"code": "DRS-5002", "detail": "refused: this request came from another site"}, status_code=403)
        return await call_next(request)


class BodyError(Exception):
    """A request body a JSON route cannot take: answered as a problem by the app."""

    def __init__(self, status: int, detail: str, code: str = "DRS-5001"):
        super().__init__(detail)
        self.status = status
        self.detail = detail
        self.code = code


def problem(_request, e: BodyError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def limited_body(request, limit: int, what: str = "the request") -> bytes:
    """The request body, read in chunks and refused with 413 DRS-5005 the moment it passes ``limit`` bytes: a body sent chunked has no
    Content-Length to check first, and reading it whole before looking at its size is what QA 2026-10-03 S2-09 measured."""
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise BodyError(413, f"{what} is over the limit of {max(1, limit // 1048576)} MB", "DRS-5005")
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            raise BodyError(413, f"{what} is over the limit of {max(1, limit // 1048576)} MB", "DRS-5005")
        chunks.append(chunk)
    return b"".join(chunks)


async def json_body(request, default=None, limit: int | None = None):
    """The request's JSON body (``default``, else ``{}``, when there is none); 415 unless sent as application/json. With ``limit``
    the body is read in chunks and refused with 413 past that many bytes, chunked or not."""
    raw = await limited_body(request, limit) if limit else await request.body()
    if not raw.strip():
        return {} if default is None else default
    kind = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if kind != "application/json" and not kind.endswith("+json"):
        raise BodyError(415, "send JSON with Content-Type: application/json")
    try:
        value = json.loads(raw)
    except ValueError as e:
        raise BodyError(400, f"the body is not JSON: {e}") from e
    want = type({} if default is None else default)          # an object unless the caller expects a list: never a bare string or number
    if not isinstance(value, want):
        raise BodyError(400, f"the body must be a JSON {'array' if want is list else 'object'}")
    return value
