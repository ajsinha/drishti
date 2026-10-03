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

"""UX-02: the server's refusal of a Sutra becomes located problems, and the panels that are wrong can be cut out so the others draw."""
from core import partial

SUTRA = """rachana: 1
sutra: s
version: 1
match: { kind: shipment }
panels:
  - id: facts
    kind: kv
    columns:
      - { label: A, bind: $.a }
  # the legs
  - id: legs
    kind: table
    rows: '$.legs[?'
  - { id: tail, kind: markdown, text: hi }
"""
REFUSAL = ("1 problem(s): [studio.sutra.yaml:11:5 DRS-2101 expression '$.legs[?': DRS-2101 the expression ends early: a value is missing at 8, "
           "studio.sutra.yaml:7:3 DRS-2011 unknown key 'zz']")


def test_a_refusal_is_read_into_located_problems_with_the_code_once():
    ps = partial.parse_problems(REFUSAL)
    assert [p["code"] for p in ps] == ["DRS-2101", "DRS-2011"]
    assert ps[0]["message"] == "expression '$.legs[?': the expression ends early: a value is missing at 8"
    assert ps[1]["message"] == "unknown key 'zz'" and ps[1]["line"] == 7


def test_a_problem_is_placed_in_its_panel_and_on_the_line_of_the_expression():
    ps = partial.locate(SUTRA, partial.parse_problems(REFUSAL))
    assert (ps[0]["panel"], ps[0]["option"], ps[0]["line"]) == ("legs", "rows", 13)
    assert ps[1]["panel"] == "facts"


def test_cutting_a_panel_out_keeps_every_other_line():
    cut = partial.without_panels(SUTRA, {"legs"})
    assert "id: legs" not in cut and "rows:" not in cut and "id: facts" in cut and "id: tail" in cut and "match:" in cut
    assert partial.without_panels(SUTRA, {"tail"}).count("\n") == SUTRA.count("\n") - 1
    assert partial.without_panels("not: [valid", {"x"}) == "not: [valid\n"
