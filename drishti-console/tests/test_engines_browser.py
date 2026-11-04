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

"""Admin -> Engines in a real browser (docs/architecture/COMPUTE_ENGINES.md section 8, mock-up A; section 13's browser test), over a real
server and console, the engines' folder a temporary one:

- the page lists every engine with its switch, state, memory, needs, used by and where its switch is set; the not-yet-built engines say
  "not installed"; the Engines tab is the current one;
- flipping FRTB-SA stages it (nothing changes yet); the review panel names what disappears (FRSA, PLAY FRTB) and what happens on the
  server; Apply waits for a reason; applied, the row says off, History says live, and FRSA typed on the command line answers DRS-1020;
- a refused change is said in words before it is sent (Sensitivities is needed by FRTB-SA and SIMM), with the button that adds them;
- the in-memory lake turned on says "restart needed";
- FRTB-SA back on; no page error.

``DRISHTI_ENGINES_SHOTS=DIR`` saves the guide's screenshots there (light theme): list.png, review.png, refused.png, restart.png, history.png.
Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import os
from pathlib import Path
from urllib.parse import unquote

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

SHOTS = os.environ.get("DRISHTI_ENGINES_SHOTS")


def shot(pg, name, full=False):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=full)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    engines = tmp_path_factory.mktemp("engines")
    yield from _stack(tmp_path_factory, {"DRISHTI_OFFLINE": "true", "DRISHTI_ENGINES_DIR": str(engines)})


def open_page(browser, stack):
    pg = browser.new_page(viewport={"width": 1560, "height": 1000})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.on("console", lambda m: m.type == "error" and "Content Security Policy" in m.text and pg.errors.append(m.text))
    pg.goto(stack + "/admin/engines")
    wait(pg, "document.querySelectorAll('[data-eng-table] tbody tr').length >= 10")
    if SHOTS:
        pg.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
    return pg


def row(pg, engine):
    return pg.locator(f"[data-eng-table] tr[data-engine='{engine}']")


def apply(pg, reason):
    pg.fill("[data-eng-reason]", reason)
    assert pg.is_enabled("[data-eng-apply]")
    pg.click("[data-eng-apply]")
    wait(pg, "!document.querySelector('[data-eng-done]').hidden || !document.querySelector('[data-eng-error]').hidden")


def test_the_engines_page_stages_reviews_and_applies(browser, stack):
    pg = open_page(browser, stack)
    shot(pg, "list", full=True)
    assert pg.get_attribute(".adm-tabs a[href='/admin/engines']", "aria-current") == "page"
    assert "running" in row(pg, "var").inner_text() and "shipped copy" in row(pg, "var").inner_text()
    assert "not installed" in row(pg, "fva").inner_text()
    assert "var, sensitivities" in row(pg, "frtb-sa").inner_text()
    assert pg.is_hidden("[data-eng-panel]")

    # FRTB-SA off: staged, reviewed, applied with a reason
    pg.click("[data-eng-switch='frtb-sa']")
    assert pg.is_visible("[data-eng-panel]")
    assert "Turn FRTB-SA off on" in pg.inner_text("[data-eng-panel-title]")
    assert "staged" in row(pg, "frtb-sa").inner_text()
    hides = pg.inner_text("[data-eng-hides]")
    assert "FRSA" in hides and "PLAY FRTB" in hides
    assert "Live, no restart" in pg.inner_text("[data-eng-effect]")
    assert not pg.is_enabled("[data-eng-apply]"), "a reason is required"
    pg.fill("[data-eng-reason]", "market-risk capital moves to node var-1")
    shot(pg, "review", full=True)
    apply(pg, "market-risk capital moves to node var-1")
    assert "FRTB-SA off: live" in pg.inner_text("[data-eng-done]")
    wait(pg, "document.querySelector(\"[data-eng-table] tr[data-engine='frtb-sa']\").textContent.indexOf('off') >= 0")
    first = pg.locator("[data-eng-history] tbody tr").first.inner_text()
    assert "FRTB-SA" in first and "live" in first and "market-risk capital" in first

    # the command line now says it in the server's words
    go = pg.request.get(stack + "/go?q=" + "FRSA BANK <GO>", max_redirects=0)
    assert go.status == 303 and "DRS-1020" in unquote(go.headers["location"])

    # a refusal is said before anything is sent, with the button that adds what is needed
    pg.click("[data-eng-switch='sensitivities']")
    refusal = pg.inner_text("[data-eng-deps]")
    assert "Sensitivities is needed by SIMM on this server" in refusal and "DRS-1021" in refusal
    pg.fill("[data-eng-reason]", "trying")
    assert not pg.is_enabled("[data-eng-apply]")
    shot(pg, "refused", full=True)
    pg.click("[data-eng-fix]")
    assert pg.is_enabled("[data-eng-apply]")
    pg.click("[data-eng-cancel]")
    assert pg.is_hidden("[data-eng-panel]")

    # the in-memory lake on waits for a restart
    pg.click("[data-eng-switch='memory-lake']")
    assert "restart needed" in pg.inner_text("[data-eng-effect]")
    apply(pg, "news feed must not touch disk")
    wait(pg, "document.querySelector(\"[data-eng-table] tr[data-engine='memory-lake']\").textContent.indexOf('restart needed') >= 0")
    assert "starts at the next restart" in pg.inner_text("[data-eng-notes]")
    shot(pg, "restart", full=True)

    # back as it was
    pg.click("[data-eng-switch='memory-lake']")
    apply(pg, "back")
    pg.click("[data-eng-switch='frtb-sa']")
    apply(pg, "back")
    wait(pg, "document.querySelector(\"[data-eng-table] tr[data-engine='frtb-sa']\").textContent.indexOf('running') >= 0")
    pg.click("[data-eng-tab='history']")
    assert pg.is_hidden("[data-eng-view='server']") and pg.is_visible("[data-eng-history-card]")
    shot(pg, "history", full=True)
    pg.click("[data-eng-tab='cluster']")
    assert pg.is_visible("[data-eng-matrix]") and "Available to people" in pg.inner_text("[data-eng-matrix]")
    assert pg.errors == [], pg.errors
