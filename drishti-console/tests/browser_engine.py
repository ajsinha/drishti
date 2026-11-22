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


"""Which browser engine the console's browser tests drive: Chromium unless ``DRISHTI_BROWSER`` says ``webkit`` or ``firefox``.

The tests launch ``p.chromium`` in some eighty files and the per-worker harness (browser_harness.py) launches it once a worker; one
switch, here, makes ``Playwright.chromium`` (sync and async) answer the chosen engine, so no test changes. For WebKit on a host that
lacks its system libraries, tools/browser/webkit_env.py makes them available without root before the first launch. A test that is
Chromium's by nature (DevTools protocol, ``--host-resolver-rules``) fails on the other engines and is reported as such: the switch
is for the cross-engine run of the gate, not for every run."""
from __future__ import annotations

import os
import sys
from pathlib import Path

ENGINES = ("chromium", "firefox", "webkit")


def engine() -> str:
    got = (os.environ.get("DRISHTI_BROWSER") or "chromium").strip().lower()
    if got not in ENGINES:
        raise ValueError(f"DRISHTI_BROWSER={got!r}: one of {', '.join(ENGINES)}")
    return got


def install() -> str:
    """Makes the chosen engine the one ``Playwright.chromium`` gives; the engine's name."""
    chosen = engine()
    if chosen == "chromium":
        return chosen
    if chosen == "webkit":
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "browser"))
        import webkit_env
        webkit_env.apply()
    from playwright import async_api, sync_api
    for module in (sync_api, async_api):
        module.Playwright.chromium = property(lambda self, name=chosen: getattr(self, name))
    return chosen


def launch_args(rules: str) -> list[str]:
    """The launch arguments: Chromium's host-resolver rules only for Chromium (the other engines have no such switch)."""
    return [f"--host-resolver-rules={rules}"] if engine() == "chromium" else []
