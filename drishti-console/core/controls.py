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

"""Controls of a Sutra on the console side: the ``ctl.<id>=value`` request parameters (the address ``?ctl.leg=L2&ctl.when=...``), read
with the server's bounds and handed to the backend untouched; the server decides what is a valid value (options, defaults, masks) and
bounds them again. Nothing here evaluates anything. The bounds are the server's (``ControlValues``), sent in its installation answer's
``limits.controls`` (THIN_CONSOLE_DESIGN.md step S6): the console keeps no copy of the numbers."""
from __future__ import annotations

from dataclasses import dataclass

PREFIX = "ctl."


@dataclass(frozen=True)
class Limits:
    """How many ``ctl.`` parameters a request carries, how long a name and a value may be; ``0`` = not bounded here (the server bounds)."""
    max_controls: int = 0
    max_key: int = 0
    max_value: int = 0


def limits(app) -> Limits:
    """The server's bounds from its installation answer; before its first answer nothing is bounded here (the server bounds anyway)."""
    answer = getattr(getattr(getattr(app, "state", None), "editions", None), "answer", None) or {}
    c = (answer.get("limits") or {}).get("controls") or {}
    try:
        return Limits(int(c.get("maxControls") or 0), int(c.get("maxKey") or 0), int(c.get("maxValue") or 0))
    except (TypeError, ValueError):
        return Limits()


def from_query(params, bounds: Limits = Limits()) -> dict[str, str]:
    """The ``ctl.*`` parameters of a request (a starlette QueryParams or a mapping), within the server's bounds."""
    out: dict[str, str] = {}
    for key in params.keys():
        if not key.startswith(PREFIX) or len(key) <= len(PREFIX):
            continue
        if (bounds.max_key and len(key) > bounds.max_key) or (bounds.max_controls and len(out) >= bounds.max_controls):
            continue
        values = params.getlist(key) if hasattr(params, "getlist") else [params[key]]
        joined = ",".join(values)
        out[key] = joined[:bounds.max_value] if bounds.max_value else joined
    return out


def of(request) -> dict[str, str]:
    """The ``ctl.*`` parameters of a request, within the bounds of the server this console follows."""
    return from_query(request.query_params, limits(request.app))


def to_query(values: dict[str, str]) -> str:
    """``ctl.leg=L2&ctl.when=...`` (percent-encoded), for a link or a stream subscription."""
    from urllib.parse import quote

    return "&".join(f"{quote(k, safe='.')}={quote(v, safe=',:.')}" for k, v in values.items())


def split_subscription(sub: str) -> tuple[str, dict[str, str]]:
    """``view:trade/T1?ctl.leg=L2`` -> (``view:trade/T1``, {"ctl.leg": "L2"}): a live subscription may carry the view's control values
    (the channel's request body is bounded; the server bounds the values of the stream it opens)."""
    from urllib.parse import parse_qs

    head, sep, tail = sub.rpartition("?ctl.")
    if not sep:
        return sub, {}
    parsed = parse_qs("ctl." + tail, keep_blank_values=True)
    return head, {k: ",".join(v) for k, v in parsed.items() if k.startswith(PREFIX)}
