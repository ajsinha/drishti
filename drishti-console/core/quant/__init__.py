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

"""The console's quant slot (docs/guides/QUANT_SERVICE.md): :class:`QuantEngine`, the stand-in, and :func:`get_engine`."""
from __future__ import annotations

from core.quant.engine import QuantEngine, QuantError
from core.quant.standin import StandInQuantEngine

__all__ = ["QuantEngine", "QuantError", "StandInQuantEngine", "get_engine"]


def get_engine(name: str = "stand-in") -> QuantEngine:
    """The engine named in ``bi.explore.quant_engine``. Only ``stand-in`` is built in; QLRisk's adapter registers here (QUANT_SERVICE.md section 4)."""
    if name in ("stand-in", "standin", ""):
        return StandInQuantEngine()
    raise QuantError(f"no quant engine named '{name}' is installed (built in: stand-in)")
