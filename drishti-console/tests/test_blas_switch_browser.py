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

"""The Linear algebra switch in a real browser (docs/admin/OPERATIONS.md section 2.1), over a real server and console, the engines' folder a
temporary one:

- Admin -> Engines lists "Linear algebra: OpenBLAS" with its switch; turned off with a reason it applies live (no restart), History says so;
- Admin -> Health and Admin -> Engines then carry the notice "runs on the pure-Java path, as chosen" (not the DEGRADED fallback warning);
- turned on again, the server runs the self-test first: on a machine with the bundled OpenBLAS the row says running and the notice is gone;
  where it cannot load, the switch is refused in words and the Java path stays;
- no page error.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import pytest

from test_engines_browser import apply, open_page, row  # noqa: F401 - helpers
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    engines = tmp_path_factory.mktemp("engines")
    yield from _stack(tmp_path_factory, {"DRISHTI_OFFLINE": "true", "DRISHTI_ENGINES_DIR": str(engines)})


def test_linear_algebra_off_and_on_live(browser, stack):
    pg = open_page(browser, stack)
    assert "Linear algebra: OpenBLAS" in row(pg, "openblas").inner_text()

    pg.click("[data-eng-switch='openblas']")
    assert "Live, no restart" in pg.inner_text("[data-eng-effect]")
    apply(pg, "benchmark on the Java path")
    assert "off: live" in pg.inner_text("[data-eng-done]")
    first = pg.locator("[data-eng-history] tbody tr").first.inner_text()
    assert "benchmark on the Java path" in first

    for page in ("/admin/engines", "/admin/health"):
        pg.goto(stack + page)
        wait(pg, "!!document.querySelector('[data-native-chosen]')")
        assert "as chosen" in pg.inner_text("[data-native-chosen]")
        assert pg.locator("[data-native-banner]").count() == 0          # a choice, not the fallback warning

    pg.goto(stack + "/admin/engines")
    wait(pg, "document.querySelectorAll('[data-eng-table] tbody tr').length >= 10")
    pg.click("[data-eng-switch='openblas']")
    apply(pg, "back to OpenBLAS")
    text = row(pg, "openblas").inner_text()
    if "running" in text:                                                # the self-test passed: OpenBLAS again
        pg.goto(stack + "/admin/health")
        assert pg.locator("[data-native-chosen]").count() == 0
    else:                                                                # no OpenBLAS here: refused in words, Java stays
        assert "self-test" in text or "cannot be used here" in text
    assert not pg.errors, pg.errors
