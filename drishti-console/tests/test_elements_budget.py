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

"""The Elements' performance budget (a quick file check): each library's bundle stays under its byte budget, and the style sheets of the
components under theirs. A budget is raised only with a reason in the commit; a library that grows past it is split or lazy-loaded
(drishti-core's loader), not allowed to creep. Frame time per gallery page is the browser budget of the gallery test, not here."""
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ELEMENTS = Path(__file__).resolve().parent.parent / "web" / "static" / "elements"
CSS = Path(__file__).resolve().parent.parent / "web" / "static" / "css"
# bytes; about 15% over the size at the time the budget was written (2026-11-16)
BUNDLES = {"drishti-core": 180_000, "drishti-inputs": 290_000, "drishti-grid": 610_000, "drishti-tree": 145_000, "drishti-designer": 170_000,
           "drishti-data": 48_000, "drishti-chart": 240_000, "drishti-graph": 165_000, "drishti-insight": 82_000, "drishti-risk": 32_000,
           "drishti-renderer": 165_000}
SHEETS = {"drishti_states.css": 4_000, "drishti_command-palette.css": 5_000, "drishti_dock.css": 5_000, "drishti_chips.css": 5_000}


@pytest.mark.parametrize("library,budget", sorted(BUNDLES.items()))
def test_a_bundle_stays_under_its_budget(library, budget):
    f = ELEMENTS / library / ("drishti_" + library.split("-", 1)[1] + ".js")
    assert f.stat().st_size <= budget, f"{f.name} is {f.stat().st_size} bytes, over its budget of {budget}: split it or lazy-load the part"


@pytest.mark.parametrize("sheet,budget", sorted(SHEETS.items()))
def test_a_component_sheet_stays_under_its_budget(sheet, budget):
    assert (CSS / sheet).stat().st_size <= budget, sheet


def test_every_library_has_a_budget():
    import json
    libs = set(json.loads((ELEMENTS / "drishti_elements.json").read_text(encoding="utf-8"))["libraries"])
    assert libs == set(BUNDLES), sorted(libs ^ set(BUNDLES))
