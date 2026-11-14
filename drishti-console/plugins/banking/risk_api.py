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

"""The banking engines' calls on the server, as the person (docs/architecture/DOMAIN_MODULES.md section 5, C7): each over the core
client's one generic call (``BackendClient.engine``), under the module's API, ``/api/v1/risk`` (the old prefixes are answered by the
server for one release only). The core client names none of them."""
from __future__ import annotations

from typing import Any

ROOT = "/risk"
VAR = ROOT + "/var"
EXPOSURE = ROOT + "/exposure"
MR = ROOT + "/mr"


async def var(backend, method: str, path: str, ident, /, body=None, raw: bool = False, accept: str = "", **params) -> Any:
    """The VaR engine (``/api/v1/risk/var``, docs/guides/VAR_GUIDE.md section 11); ``path`` after it (``/scope``). The first answer of a
    business date loads its vectors: up to a minute. ``accept`` (with ``raw``): the Arrow stream of a P&L path or a contribution table."""
    return await backend.engine(method, VAR + path, ident, body=body, raw=raw, accept=accept, timeout=60.0, **params)


async def exposure(backend, method: str, path: str, ident, /, body=None, raw: bool = False, **params) -> Any:
    """The exposure engine (``/api/v1/risk/exposure``, docs/guides/EXPOSURE_GUIDE.md section 11); ``path`` after it."""
    return await backend.engine(method, EXPOSURE + path, ident, body=body, raw=raw, timeout=60.0, **params)


async def risk(backend, method: str, path: str, ident, /, body=None, **params) -> Any:
    """FRTB-SA, the sensitivities, the risk factors and SIMM (``/api/v1/risk/frtb``, ``/sensitivities``, ``/risk-factors``, ``/simm``);
    ``path`` after /api/v1/risk (``/frtb/sa``); a list value is a repeated parameter (``override``)."""
    return await backend.engine(method, ROOT + path, ident, body=body, timeout=60.0, **params)


async def mr(backend, method: str, path: str, ident, /, body=None, **params) -> Any:
    """The MR cube's extras beside the pivot (``/api/v1/risk/mr``, docs/guides/BI_GUIDE.md#29-the-mr-cube-preset); ``path`` after it (``/headline``)."""
    return await backend.engine(method, MR + path, ident, body=body, timeout=120.0, **params)


def path(sub: str) -> str:
    """A server path of the module from /api/v1 (a workbook streamed by core/workbook_stream.py): ``/var/playground.xlsx`` ->
    ``/risk/var/playground.xlsx``."""
    return ROOT + sub
