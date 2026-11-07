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

"""One rounding rule for the few figures the panel macros compute (a bar's width, a donut's share, a gauge's level, a heat bucket, a
computation's progress): half up on the number's decimal value, as the server's FormatSpec rounds and as drishti-core's roundHalfUp
does in the client renderer (decided 2026-11-07: the server's half-up wins over Python's half to even). The templates use the filter
``half_up``: ``{{ x | half_up(1) }}``."""
from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any


def half_up(x: Any, digits: int = 0) -> Any:
    """x rounded half up to ``digits`` decimals (a float, as Jinja's round gives); anything that is not a finite number is returned as it is."""
    if isinstance(x, bool) or not isinstance(x, (int, float)):
        return x
    try:
        d = Decimal(repr(float(x))).quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP)
    except (InvalidOperation, ValueError, OverflowError):
        return x
    return float(d)


def install(env) -> None:
    """Registers the filter on a Jinja environment (the console's, and any test's that renders the panel macros)."""
    env.filters["half_up"] = half_up
