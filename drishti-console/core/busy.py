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

"""Busy protection in the console (docs/admin/OPERATIONS.md, "Busy protection"). The server decides: when it asks new work to wait it
answers ``503`` with a ``DRS-9501`` to ``DRS-9505`` code, ``Retry-After`` and calm words (``title``, ``because``). The console passes all of it
on unchanged to the page that asked, which shows the message in place with a countdown (static/js/busy.js); it keeps no list of waiting
requests and decides nothing itself."""
from __future__ import annotations

import re
from typing import Any

from fastapi.responses import JSONResponse

BUSY_CODE = re.compile(r"^DRS-950[1-5]$")


def is_busy(e: Any) -> bool:
    """True when a :class:`core.backend.BackendError` is the server's busy refusal."""
    extra = getattr(e, "extra", None) or {}
    return bool(extra.get("busy")) or bool(BUSY_CODE.match(str(getattr(e, "code", "") or "")))


def retry_after(e: Any) -> int:
    """The seconds to wait: the server's ``Retry-After`` (or its ``retryAfterSeconds``), at least 1."""
    for v in (getattr(e, "retry_after", None), (getattr(e, "extra", None) or {}).get("retryAfterSeconds")):
        try:
            n = int(str(v).strip())
            if n > 0:
                return n
        except (TypeError, ValueError):
            continue
    return 30


def fields(e: Any) -> dict:
    """The busy refusal's words for a page: ``busy``, ``title``, ``because``, ``work``, ``retryAfterSeconds`` (empty when not busy)."""
    if not is_busy(e):
        return {}
    extra = getattr(e, "extra", None) or {}
    return {"busy": True, "title": extra.get("title") or "The server is busy", "because": extra.get("because") or "",
            "work": extra.get("work") or "", "retryAfterSeconds": retry_after(e)}


def problem(e: Any, status: int | None = None) -> JSONResponse:
    """A server problem as the JSON a page reads (``code``, ``detail``), with a busy refusal's words and its ``Retry-After`` passed on."""
    body = {"code": getattr(e, "code", ""), "detail": getattr(e, "detail", "")}
    body.update(fields(e))
    headers = {"Retry-After": str(retry_after(e))} if body.get("busy") else (
        {"Retry-After": str(e.retry_after)} if getattr(e, "retry_after", None) else None)
    code = 503 if body.get("busy") else (status if status is not None else getattr(e, "status", 502))
    return JSONResponse(body, status_code=code, headers=headers)
