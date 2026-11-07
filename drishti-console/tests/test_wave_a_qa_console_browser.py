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

"""The console fixes of the Wave A experience QA, in Chromium over the real server on scratch ports 18909 and 17909 (the stack of
test_w1_model_designer_browser.py, its jar taken from the main checkout when the worktree has none): a visual hidden for the reader's role
says so without Retry. (The model designer's checks went with it: models are written in Build › Tables & models.)

Skipped when Playwright, Chromium or a built server jar is missing, or a scratch port is in use. Each pytest-xdist worker adds 100 to the ports (tests/scratch_ports.py)."""
from __future__ import annotations

import os
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

import test_w1_model_designer_browser as base  # noqa: E402

MAIN = Path(os.environ.get("DRISHTI_MAIN_CHECKOUT", "/home/ashutosh/IdeaProjects/drishti"))
if not list((base.ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) and (MAIN / "drishti-server" / "target").exists():
    base.ROOT = MAIN                                   # the jar, the packs and the sample config are read-only inputs; the console under test is this checkout's
from test_w1_model_designer_browser import browser, clean, fetch, sign_in, stack  # noqa: E402,F401

PORTS = base.worker_ports(18909, 17909)              # this file's own stack: the stack fixture switches the base module to them
CONSOLE_URL = f"http://127.0.0.1:{PORTS[1]}"
MODEL = """rachana: 2
model: {name}
title: {title}
version: 1.0.0
datasets:
  trade: {{dataset: pack.trading.trade@^1, role: fact}}
  desk: {{dataset: pack.core.desk@^1, role: dimension}}
relationships:
  - {{from: trade.DESK, to: desk.id}}
measures:
  MTM: {{expr: sum(trade.MTM)}}
"""


def test_a_visual_hidden_for_the_role_says_so_without_retry(browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/r/file:report:trades-overview")
    page.wait_for_function("!!(window.drishtiBi && window.drishtiBi.frame)", timeout=30000)
    out = page.evaluate("""() => { const B = window.drishtiBi, f = B.frame({ id: 'x', type: 'table', title: 'X' }, { onRetry: () => {} });
        document.body.appendChild(f.el); f.error({ code: 'DRS-9163', message: "the measure 'MTM' is not available to your role", detail: "the measure 'MTM' is not available to your role" });
        const a = f.el.textContent, retry = f.el.querySelectorAll('.bi-retry').length;
        f.result({ rowCount: 0, meta: { millis: 1, layer: 'engine', noAccess: ['trade'] } }, (host) => ({ dispose() {}, resize() {} }));
        return { a, retry, b: f.el.textContent }; }""")
    assert "Not available to your role" in out["a"] and out["retry"] == 0, out
    assert "Your role has no rows of trade" in out["b"], out
    page.context.close()
