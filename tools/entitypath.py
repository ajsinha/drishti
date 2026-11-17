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


"""The one way the tools name an entity in a server path (docs/reference/API_REFERENCE.md): ``{kind}/{id}`` percent-encoded, except an id a
path cannot carry (one with ``/`` or ``\\``, or ``.``, ``..``, ``~``), which goes in the query as ``id`` with ``~`` in its place. Every
``{kind}/{id}`` endpoint of the server accepts that form (EntityIdInQuery); the servlet container refuses a ``%2F`` in a path with a bare
400 (a risk factor ``CM/PRICE/BRENT``, an official-VaR scope ``VL-LEGAL~LE-FRA/DESK-SFT``). The console (core/backend.py entity_path) and
the script client (clients/python/drishti_client.py) do the same."""
from __future__ import annotations

import urllib.parse

MARK = "~"


def needs_query(id_: str) -> bool:
    return "/" in id_ or "\\" in id_ or id_ in (".", "..", MARK)


def url(prefix: str, kind: str, id_: str, suffix: str = "", params=None) -> str:
    """``prefix/kind/id[suffix][?query]``: e.g. ``url("/api/v1/views", "risk-factor", "CM/PRICE/BRENT", "", {"asOf": "2026-11-06"})`` is
    ``/api/v1/views/risk-factor/~?asOf=2026-11-06&id=CM%2FPRICE%2FBRENT``. ``params``: a dict or a list of pairs; empty values are dropped."""
    pairs = list(params.items() if isinstance(params, dict) else (params or []))
    pairs = [(k, v) for k, v in pairs if v not in (None, "")]
    k = urllib.parse.quote(kind, safe="")
    if needs_query(id_):
        where = f"{k}/{MARK}"
        pairs.append(("id", id_))
    else:
        where = f"{k}/{urllib.parse.quote(id_, safe='')}"
    return f"{prefix.rstrip('/')}/{where}{suffix}" + ("?" + urllib.parse.urlencode(pairs) if pairs else "")
