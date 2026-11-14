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
"""The visual snapshots of the var group (snapshot_pages.py GROUPS; docs/architecture/TEST_HARNESS.md, "The visual snapshots"): each page
at 1366 and 1920 px, dark and light, against its golden image, on a server of this file's own over the page walk's cached lake. Skipped
when Playwright, Chromium, the built server jar or a JDK is missing, and off Linux."""
from snapshot_pages import run_group
from test_page_walk_browser import stack  # noqa: F401 - the walk's stack, started again for this file
from test_workbench_browser import browser  # noqa: F401 - the fixture


def test_the_var_pages_look_as_approved(stack, browser, request, tmp_path):  # noqa: F811 - the fixtures imported above
    run_group("var", stack, browser, request, tmp_path)
