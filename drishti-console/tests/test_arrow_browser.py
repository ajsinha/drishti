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

"""Arrow to the browser (2.9.7, docs/guides/DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6) in Chromium, over the real server and console with the market-risk
pack's committed VaR data (the stack of test_var_explorer_browser.py, its lake shared through var_stack.py: free scratch ports, a scratch folder, the demo connector off):

- the VaR explorer reads its contribution tables by position and by trade as Arrow (and the rest as JSON) and draws them, the P&L path
  and its charts exactly as it does from JSON alone (?tables=json), cell for cell;
- the Arrow answers rebuild the JSON answers equal, through the console from the server (by trade and by position, with its booleans);
- without Arrow JS (the vendored script refused) the page asks JSON and nothing breaks;
- the page asks nothing of any other host, and the content security policy reports nothing (Arrow JS needs no eval);
- on a phone (390 px) nothing scrolls sideways.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
from urllib.parse import urlparse

import pytest

from test_var_explorer_browser import painted
from test_workbench_browser import browser  # noqa: F401
from var_stack import VAR_TRADES, var_data, var_lake, var_server

DESK = "NODE:MGMT~DESK-RATES"
WATCH = """window.__csp = []; document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"""

#: What the page shows, as text: the tiles, the path's ranked table, the series and tail charts (each bar's height and class), the contributions.
SNAPSHOT = """() => ({
  tiles: [...document.querySelectorAll('.vx-tile .v')].map(e => e.textContent),
  path: [...document.querySelectorAll('[data-vx-pathtable] tbody tr')].map(tr => tr.textContent),
  series: [...document.querySelectorAll('[data-vx-series] *')].filter(e => e.getAttribute && (e.getAttribute('height') || e.style.height)).map(e => (e.getAttribute('class') || '') + ':' + (e.getAttribute('height') || e.style.height)),
  tail: [...document.querySelectorAll('[data-vx-tail] *')].filter(e => e.getAttribute && (e.getAttribute('height') || e.style.height)).map(e => (e.getAttribute('class') || '') + ':' + (e.getAttribute('height') || e.style.height)),
  contrib: [...document.querySelectorAll('[data-vx-ctable] tbody tr')].map(tr => tr.textContent)
})"""

WIDE = """() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1"""


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    """The VaR explorer's stack (test_var_explorer_browser.py): its data, the compute workers off."""
    yield from var_server(tmp_path_factory, var_lake(tmp_path_factory, var_data(*VAR_TRADES)), DRISHTI_COMPUTE_ENABLED="false")


def open_page(browser, base, query, block_arrow=False, size=None):  # noqa: F811
    ctx = browser.new_context(viewport=size or {"width": 1600, "height": 1000})
    ctx.add_init_script(WATCH)
    pg = ctx.new_page()
    pg.errors, pg.hosts, pg.accepts = [], set(), []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.on("request", lambda r: (pg.hosts.add(urlparse(r.url).netloc),
                                pg.accepts.append((r.url, r.headers.get("accept", ""))) if "/api/var/scope/" in r.url else None))
    if block_arrow:
        pg.route("**/vendor/apache-arrow/**", lambda route: route.abort())
    pg.goto(f"{base}/var?scope={DESK}{query}")
    painted(pg, DESK)
    return pg


def contributions(pg, by):
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    pg.wait_for_function("() => document.querySelectorAll('[data-vx-ctable] tbody tr').length > 1 && !/computing/.test(document.querySelector('[data-vx-ctable] tbody').textContent)")
    if by != "child":
        first = pg.locator("[data-vx-ctable] tbody tr").first.text_content()
        pg.locator(f'.vx-segb[data-by="{by}"]').click()
        pg.wait_for_function("(f) => document.querySelector('[data-vx-ctable] tbody tr') && document.querySelector('[data-vx-ctable] tbody tr').textContent !== f "
                             "&& !/computing/.test(document.querySelector('[data-vx-ctable] tbody').textContent)", arg=first)


def test_the_explorer_draws_the_same_from_arrow_and_from_json(stack, browser):  # noqa: F811
    base = str(stack)
    host = urlparse(base).netloc
    shots = {}
    for name, query in (("arrow", ""), ("json", "&tables=json")):
        pg = open_page(browser, base, query)
        assert pg.evaluate("window.Drishti.table.available()") is (name == "arrow")
        snap = pg.evaluate(SNAPSHOT)
        contributions(pg, "child")
        snap["child"] = pg.evaluate(SNAPSHOT)["contrib"]
        contributions(pg, "trade")
        snap["trade"] = pg.evaluate(SNAPSHOT)["contrib"]
        contributions(pg, "position")
        snap["position"] = pg.evaluate(SNAPSHOT)["contrib"]
        shots[name] = snap
        tables = [a for u, a in pg.accepts if "by=trade" in u or "by=position" in u]
        others = [a for u, a in pg.accepts if not ("by=trade" in u or "by=position" in u)]
        assert len(tables) >= 2 and others and all("arrow" not in a for a in others), pg.accepts
        assert all(("application/vnd.apache.arrow.stream" in a) is (name == "arrow") for a in tables), pg.accepts
        assert pg.hosts == {host}, f"asked another host: {pg.hosts}"
        assert pg.evaluate("window.__csp") == [], pg.evaluate("window.__csp")
        assert pg.errors == [], pg.errors
        pg.context.close()
    assert shots["arrow"]["path"] and shots["arrow"]["series"] and len(shots["arrow"]["trade"]) > 3
    assert shots["arrow"] == shots["json"]


@pytest.mark.parametrize("path,shape,at", [
    (f"scope/contributions?scope={DESK}&by=trade", "records", "rows"),
    (f"scope/contributions?scope={DESK}&by=position", "records", "rows"),
    ("scope/contributions?scope=NODE:MGMT~BANK&by=trade", "records", "rows"),
])
def test_an_arrow_answer_is_the_json_answer(stack, browser, path, shape, at):  # noqa: F811
    """Through the console from the server: the Arrow answer, read by the page's own helper, rebuilds the JSON answer cell for cell
    (computeMs, the server's own timing, aside)."""
    pg = open_page(browser, str(stack), "")
    got = pg.evaluate("""async ([url, shape, at]) => {
      const j = await (await fetch(url, { headers: { Accept: 'application/json' } })).json();
      const r = await fetch(url, { headers: { Accept: 'application/vnd.apache.arrow.stream' } });
      const kind = r.headers.get('content-type');
      const t = await window.Drishti.table.fromResponse(r, shape, at);
      return { kind, json: JSON.stringify(j, Object.keys(j).sort()), a: t.answer(), j };
    }""", ["/api/var/" + path, shape, at])
    assert "arrow" in got["kind"], got["kind"]
    a, j = got["a"], got["j"]
    a.pop("computeMs", None)
    j.pop("computeMs", None)
    if shape == "records":                     # a contribution row's parametric ? lines travel only in JSON (VarController: the columns as columns)
        for r in j.get(at) or []:
            if isinstance(r, dict):
                r.pop("describe", None)
    assert json.dumps(a, sort_keys=True) == json.dumps(j, sort_keys=True)
    pg.context.close()


def test_without_arrow_js_the_page_asks_json_and_works(stack, browser):  # noqa: F811
    pg = open_page(browser, str(stack), "", block_arrow=True)
    assert pg.evaluate("window.Drishti.table.available()") is False
    contributions(pg, "trade")
    assert pg.locator("[data-vx-pathtable] tbody tr").count() > 10
    assert all("arrow" not in a for _, a in pg.accepts), pg.accepts
    assert pg.errors == [], pg.errors
    pg.context.close()


def test_a_phone_does_not_scroll_sideways(stack, browser):  # noqa: F811
    pg = open_page(browser, str(stack), "", size={"width": 390, "height": 844})
    assert not pg.evaluate(WIDE)
    contributions(pg, "trade")
    assert not pg.evaluate(WIDE)
    assert pg.errors == [], pg.errors
    pg.context.close()
