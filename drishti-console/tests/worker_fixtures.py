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

"""The per-worker fixtures of the browser tests (docs/architecture/TEST_HARNESS.md, "Per-worker fixtures"). Each is session-scoped,
so under pytest-xdist it is made once per worker, the first time a test asks, kept for the worker's later tests and closed when the
worker ends. conftest.py imports them, so every test file sees one definition (one instance per worker, whichever file asks).

  browsers        one Chromium on the worker, async, contexts reused per width (browser_harness.Browsers)
  render_site     the render harness's made-up origin, served over HTTP on the worker's port (render_harness.Site, SiteServer)
  pack_views      every pack Sutra bound on its sample by a real server, once per run for every worker (pack_views.py)"""
from __future__ import annotations

import pytest


@pytest.fixture(scope="session")
def browsers():
    pytest.importorskip("playwright.async_api", reason="needs Playwright (pip install playwright)")
    from browser_harness import Browsers
    b = Browsers()
    try:
        b.start()
    except Exception as e:  # noqa: BLE001 - no browser installed: skip
        b.close()
        pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
    yield b
    b.close()


@pytest.fixture(scope="session")
def render_site():
    """The made-up origin http://render.test, served on this worker's scratch port (render_harness.SiteServer)."""
    import render_harness as H
    site = H.Site()
    server = H.SiteServer(site)
    yield site
    server.close()


@pytest.fixture(scope="session")
def pack_views(tmp_path_factory):
    """{"views": {sutra path: ViewModel}, "skipped": [...]}: bound once per test run. The first worker to ask starts a server of its
    own for it (wb_live's stack, closed at once) and writes the answer to the run's shared folder; every other worker reads it."""
    import browser_harness
    import pack_views as P
    from wb_live import _stack

    def make():
        stack = _stack(tmp_path_factory, {})
        url = next(stack)                         # skips when the server jar or a JDK is missing
        try:
            return P.bind(url.server)
        finally:
            next(stack, None)                     # runs the stack's own teardown: the console stopped, the server terminated
    return browser_harness.shared_once(browser_harness.run_dir(tmp_path_factory), "pack-views", make)
