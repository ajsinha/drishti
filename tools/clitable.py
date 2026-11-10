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
"""How the command-line reports of the measures print a figure and a table."""
from __future__ import annotations


def money(v) -> str:
    return "—" if not isinstance(v, (int, float)) or isinstance(v, bool) else f"{v:,.0f}"


def table(rows: list[list[str]], say) -> None:
    if not rows:
        return
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    for n, r in enumerate(rows):
        say("  ".join(c.rjust(widths[i]) if i and n else c.ljust(widths[i]) for i, c in enumerate(r)).rstrip())
        if n == 0:
            say("  ".join("-" * w for w in widths))
