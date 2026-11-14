#
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
#
"""The VaR family's figures before and after a move onto Drishti Elements (ELEMENTS.md section 12): every screen of the family on the
market-risk pack's demo data, each tab, read by screen_figures (every table's or grid's rows, the tiles, the playground's cells) and
written to DRISHTI_FIGURES_OUT; with DRISHTI_FIGURES_BASELINE (the folder an earlier run wrote, from the code before the move) each
screen is compared with its baseline and must show the same figures: VaR, ES, components, limits, the history, the currencies.

Run only when asked (one of the two variables set): the moved screens' own browser files keep their assertions every run."""
import json
import os
from pathlib import Path

import pytest

from screen_figures import compare, figures
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from var_stack import VAR_TRADES, var_data, var_lake, var_server

OUT = os.environ.get("DRISHTI_FIGURES_OUT")
BASE = os.environ.get("DRISHTI_FIGURES_BASELINE")
pytestmark = pytest.mark.skipif(not (OUT or BASE), reason="the before-and-after comparison runs when DRISHTI_FIGURES_OUT or _BASELINE is set")

#: the playground's cells as drawn (the old canvas's and the grid's .pg-c each carry its workbook row and column; the grid's own cells
#: carry their display row, so they are left out)
CELLS = "() => Object.fromEntries([...document.querySelectorAll('[data-pg-canvas] .pg-c[data-r][data-c]')].map((e) => [e.dataset.r + ',' + e.dataset.c, e.textContent.trim()]))"

#: (name, address, tab or by, what to wait for)
SCREENS = [
    ("var-desk-path", "/var?scope=DESK:DESK-FX", None),
    ("var-desk-hist", "/var?scope=DESK:DESK-FX&tab=hist", None),
    ("var-desk-contrib", "/var?scope=DESK:DESK-FX&tab=contrib", None),
    ("var-desk-contrib-position", "/var?scope=DESK:DESK-FX&tab=contrib", "position"),
    ("var-desk-contrib-trade", "/var?scope=DESK:DESK-FX&tab=contrib", "trade"),
    ("var-desk-ccy", "/var?scope=DESK:DESK-FX&tab=ccy", None),
    ("var-bank-tree", "/var?scope=BANK", None),
    ("var-bank-parametric", "/var?scope=BANK&pm=1", None),
    ("var-desk-10d", "/var?scope=DESK:DESK-RATES&h=10d&m=sum&tab=contrib", None),
    ("play-desk", "/play?scope=DESK:DESK-FX", None),
    ("play-desk-parametric", "/play?scope=DESK:DESK-FX&measure=parametric", None),
    ("backtesting-bank", "/var/backtesting?scope=BANK", None),
    ("statistics-bank", "/var/statistics?scope=BANK", None),
    ("reports", "/var/reports", None),
    ("stress-window-bank", "/var/stress-window?scope=BANK", None),
    ("smat-desk", "/smat?id=DESK:DESK-FX", None),
]


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from var_server(tmp_path_factory, var_lake(tmp_path_factory, var_data(*VAR_TRADES)), shared=True)   # reads only: the pool guards it


def _settle(pg, by):
    """Waits until the screen has its answers: the explorer says when it is painted; any screen when nothing is asked for a while."""
    if pg.evaluate("!!document.querySelector('[data-var]')"):
        wait(pg, "!!document.querySelector('[data-var]').dataset.painted", 120)
        if by:
            pg.locator(f'.vx-segb[data-by="{by}"]').click()
            word = {"position": "POS:", "trade": "TRD:"}[by]
            wait(pg, "!!document.querySelector('[data-vx-ctable] [data-scope^=\"%s\"]')" % word, 120)
    # every screen: no panel still computing or loading (a live connection keeps the network busy, so not networkidle)
    wait(pg, "!/computing|loading…|reading …/i.test(document.body.innerText)", 120)


@pytest.mark.parametrize("name,path,by", SCREENS, ids=[s[0] for s in SCREENS])
def test_the_screen_shows_the_same_figures(stack, browser, name, path, by):
    pg = browser.new_page(viewport={"width": 1600, "height": 1000})
    try:
        pg.goto(str(stack) + path, wait_until="domcontentloaded", timeout=120000)
        _settle(pg, by)
        got = figures(pg)
        got["cells"] = pg.evaluate(CELLS)
    finally:
        pg.close()
    if OUT:
        Path(OUT).mkdir(parents=True, exist_ok=True)
        (Path(OUT) / f"{name}.json").write_text(json.dumps(got, indent=1, ensure_ascii=False), encoding="utf-8")
    if BASE:
        before = json.loads((Path(BASE) / f"{name}.json").read_text(encoding="utf-8"))
        problems = compare(before, got)
        shared = set(before["cells"]) & set(got["cells"])
        problems += [f"cell {k}: {before['cells'][k]!r} / {got['cells'][k]!r}" for k in sorted(shared) if before["cells"][k] != got["cells"][k]][:10]
        if before["cells"] and len(shared) < 20:
            problems.append(f"only {len(shared)} playground cells drawn on both sides")
        assert not problems, name + ":\n" + "\n".join(problems)
