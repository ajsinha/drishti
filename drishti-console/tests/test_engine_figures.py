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

"""The console's handle on the server's figures (core/engine_figures.py): the body it sends to POST /api/v1/pivot/inline (numbers as
numbers, a masked or missing value left out, members as text), the engine's answer read by member (as PivotAnswer shapes it: header
and total rows skipped, the grand total under None, Others under its own key), and nothing asked when there are no rows."""
import asyncio

import pytest

from core import engine_figures as engine


def test_the_body_sends_numbers_as_numbers_and_leaves_out_what_is_not_one():
    b = engine.body([("BOOK", "string"), ("MTM", "number")], [["B1", 5], ["B2", "•••"], [None, float("nan")], ["", True]],
                    [{"measure": "sum(MTM)", "as": "mtm"}], by="BOOK")
    assert b["rows"] == [["B1", 5.0], ["B2", None], [None, None], [None, None]]
    assert b["view"]["rows"] == ["BOOK"] and b["view"]["columns"] == [] and b["fields"][1] == {"name": "MTM", "type": "number"}
    with pytest.raises(ValueError):
        engine.body([("not a name", "string")], [], [])


def test_the_answer_is_read_by_member_as_the_engine_shapes_it():
    a = {"rows": [{"key": "BOOK=B1", "kind": "node", "value": "B1"}, {"key": "BOOK=\u0000Others", "kind": "others", "value": None},
                  {"key": "BOOK=B9", "kind": "header", "value": "B9"}, {"key": "*", "kind": "grand", "value": None}],
         "columns": [{"key": "*", "kind": "grand", "value": None}],
         "cells": {"BOOK=B1|*": {"values": {"mtm": 7.0}}, "BOOK=\u0000Others|*": {"values": {"mtm": 2.0}}, "*|*": {"values": {"mtm": 9.0}}}}
    f = engine.Figures(a)
    assert f.members() == ["B1"] and f.get("mtm", "B1") == 7.0 and f.get("mtm") == 9.0 and f.get("mtm", engine.OTHERS) == 2.0
    assert f.get("mtm", "B2") is None and f.at("B2") == {}


def test_no_rows_asks_nothing():
    class Never:
        async def pivot(self, *a, **k):
            raise AssertionError("asked")
    f = asyncio.run(engine.ask(Never(), None, [("V", "number")], [], [{"measure": "sum(V)", "as": "v"}]))
    assert f.get("v") is None and f.members() == []
