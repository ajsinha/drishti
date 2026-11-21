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

"""The performance budget of Drishti's own Elements libraries (a quick file check): each bundle stays under its byte budget. A budget is
raised only with a reason in the commit; a library that grows past it is split or lazy-loaded, not allowed to creep. Drishti Elements'
eight libraries keep their budgets in their own project."""
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ELEMENTS = Path(__file__).resolve().parent.parent / "web" / "static" / "elements"
# bytes; about 15% over the size when written (2026-11-16)
BUNDLES = {"drishti-insight": 82_000, "drishti-risk": 32_000}


@pytest.mark.parametrize("library,budget", sorted(BUNDLES.items()))
def test_a_bundle_stays_under_its_budget(library, budget):
    f = ELEMENTS / library / ("drishti_" + library.split("-", 1)[1] + ".js")
    assert f.stat().st_size <= budget, f"{f.name} is {f.stat().st_size} bytes, over its budget of {budget}: split it or lazy-load the part"


def test_every_library_has_a_budget():
    import json
    libs = set(json.loads((ELEMENTS / "drishti_elements.json").read_text(encoding="utf-8"))["libraries"])
    assert libs == set(BUNDLES), sorted(libs ^ set(BUNDLES))
