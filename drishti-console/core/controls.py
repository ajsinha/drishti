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
with the same limits as the server's, and handed to the backend untouched; the server decides what is a valid value (options,
defaults, masks). Nothing here evaluates anything."""
from __future__ import annotations

MAX_CONTROLS = 32
MAX_KEY = 40
MAX_VALUE = 2000
PREFIX = "ctl."


def from_query(params) -> dict[str, str]:
    """The ``ctl.*`` parameters of a request (a starlette QueryParams or a mapping), bounded."""
    out: dict[str, str] = {}
    for key in params.keys():
        if not key.startswith(PREFIX) or len(key) <= len(PREFIX) or len(key) > MAX_KEY or len(out) >= MAX_CONTROLS:
            continue
        values = params.getlist(key) if hasattr(params, "getlist") else [params[key]]
        out[key] = ",".join(values)[:MAX_VALUE]
    return out


def to_query(values: dict[str, str]) -> str:
    """``ctl.leg=L2&ctl.when=...`` (percent-encoded), for a link or a stream subscription."""
    from urllib.parse import quote

    return "&".join(f"{quote(k, safe='.')}={quote(v, safe=',:.')}" for k, v in values.items())


def split_subscription(sub: str) -> tuple[str, dict[str, str]]:
    """``view:trade/T1?ctl.leg=L2`` -> (``view:trade/T1``, {"ctl.leg": "L2"}): a live subscription may carry the view's control values."""
    from urllib.parse import parse_qs

    head, sep, tail = sub.rpartition("?ctl.")
    if not sep:
        return sub, {}
    parsed = parse_qs("ctl." + tail, keep_blank_values=True)
    return head, {k: ",".join(v)[:MAX_VALUE] for k, v in list(parsed.items())[:MAX_CONTROLS] if k.startswith(PREFIX)}
