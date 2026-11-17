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


"""One way the tools name an entity in a server path (tools/entitypath.py): an id with a / goes in the query, never as %2F in the path."""
from __future__ import annotations

import re
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import entitypath  # noqa: E402


def test_an_id_a_path_cannot_carry_goes_in_the_query():
    assert entitypath.url("/api/v1/views", "var-limit", "VL-LEGAL~LE-FRA/DESK-SFT", params={"asOf": "2026-11-06"}) \
        == "/api/v1/views/var-limit/~?asOf=2026-11-06&id=VL-LEGAL~LE-FRA%2FDESK-SFT"
    assert entitypath.url("/api/v1/entities", "x", "a\\b", "/history") == "/api/v1/entities/x/~/history?id=a%5Cb"
    for odd in (".", "..", "~"):
        assert entitypath.url("/api/v1/views", "k", odd) == "/api/v1/views/k/~?id=" + odd
    assert entitypath.url("/api/v1/views", "trade", "MX 1", "/explain", [("panel", None)]) == "/api/v1/views/trade/MX%201/explain"


def test_no_tool_puts_an_id_in_an_entity_path_by_hand():
    """The tools that named {kind}/{id} with quote() sent CM%2FPRICE%2FBRENT and got Tomcat's bare 400 (server smoke, view get, change history)."""
    by_hand = re.compile(r"/api/v1/(views|history|entities)/\{[^}]*\}/\{[^}]*\(")    # {q(kind)}/{q(id)}: the id quoted into the path
    found = [f"{p.name}:{i}" for p in TOOLS.rglob("*.py") if "test_" not in p.name
             for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1) if by_hand.search(line)]
    assert not found, found
