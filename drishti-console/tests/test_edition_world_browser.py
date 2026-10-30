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

"""Drishti World's three Home landings in a browser (EDITIONS.md section 7): the console with the stand-in server answering the packs'
searches and raw reads as the server shapes them (the search grammar's rows of ``$.FIELD`` values, a raw entity's provenance and data;
port 17972). Each landing fills its panels from those answers, fits a phone without sideways scrolling, and draws in every theme with
its teal accent; skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT  # noqa: E402
from scratch_ports import worker_port  # noqa: E402

PORT = worker_port(17972)
THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]


def _rows(kind, items):
    """A search answer as the server shapes it: each row's ref and its values under ``$.FIELD``."""
    return {"kind": kind, "matched": len(items), "scanned": len(items),
            "rows": [{"ref": {"kind": kind, "id": i}, "title": i, "values": {f"$.{k}": v for k, v in vals.items()}} for i, vals in items]}


ECON = [("ECON-USA", {"NAME": "United States", "ISO3": "USA", "REGION_NAME": "North America", "INCOME_NAME": "High income", "GDP_USD_BN": 30769.7,
                      "GROWTH": 2.16, "INFLATION": 2.95, "DEBT_TO_GDP": 123.9}),
        ("ECON-CHN", {"NAME": "China", "ISO3": "CHN", "REGION_NAME": "East Asia & Pacific", "INCOME_NAME": "Upper middle income", "GDP_USD_BN": 19498,
                      "GROWTH": 4.96, "INFLATION": 0.06, "DEBT_TO_GDP": 99.2}),
        ("ECON-DEU", {"NAME": "Germany", "ISO3": "DEU", "REGION_NAME": "Europe & Central Asia", "INCOME_NAME": "High income", "GDP_USD_BN": 5050.9,
                      "GROWTH": 0.24, "INFLATION": 2.17, "DEBT_TO_GDP": 62.9}),
        ("ECON-CAN", {"NAME": "Canada", "ISO3": "CAN", "REGION_NAME": "North America", "INCOME_NAME": "High income", "GDP_USD_BN": 2319.9,
                      "GROWTH": 1.74, "INFLATION": 2.07, "DEBT_TO_GDP": 113.5}),
        ("ECON-IND", {"NAME": "India", "ISO3": "IND", "REGION_NAME": "South Asia", "INCOME_NAME": "Lower middle income", "GDP_USD_BN": 4187,
                      "GROWTH": 6.5, "INFLATION": 4.2, "DEBT_TO_GDP": 82.6})]
PATHS = {"ECON-USA": (2.8, 2.16), "ECON-CHN": (5.0, 4.96), "ECON-DEU": (-0.2, 0.24), "ECON-CAN": (2.05, 1.74), "ECON-IND": (6.5, 6.5)}
ECG = [("ECG-WLD", {"NAME": "World", "GROUP_TYPE": "World", "ECONOMIES": 213, "GDP_USD_BN": 111000, "GROWTH": 2.7997}),
       ("ECG-NAC", {"NAME": "North America", "GROUP_TYPE": "World Bank region", "ECONOMIES": 3, "GDP_USD_BN": 33098, "GROWTH": 2.13}),
       ("ECG-SAS", {"NAME": "South Asia", "GROUP_TYPE": "World Bank region", "ECONOMIES": 8, "GDP_USD_BN": 5000, "GROWTH": 7.07})]
QUOTES = [("QUOTE-SHEL", {"SYMBOL": "SHEL", "NAME": "Shell plc", "PRICE": 106.66, "CHANGE_PCT": 3.0, "AS_OF": "2026-10-16 16:00 America/New_York (snapshot captured 2026-10-18)"}),
          ("QUOTE-AAPL", {"SYMBOL": "AAPL", "NAME": "Apple Inc.", "PRICE": 343.46, "CHANGE_PCT": 0.43, "AS_OF": "2026-10-16 16:00 America/New_York (snapshot captured 2026-10-18)"}),
          ("QUOTE-TSLA", {"SYMBOL": "TSLA", "NAME": "Tesla", "PRICE": 250.1, "CHANGE_PCT": -1.2, "AS_OF": "2026-10-16 16:00 America/New_York (snapshot captured 2026-10-18)"})]
TOPICS = [("TOPIC-RATES", {"NAME": "interest rates", "CATEGORY": "Theme", "NEWS_ID": "NEWS-interest_rates"}),
          ("TOPIC-OIL", {"NAME": "oil prices", "CATEGORY": "Theme", "NEWS_ID": "NEWS-oil_prices"}),
          ("TOPIC-BITCOIN", {"NAME": "bitcoin", "CATEGORY": "Theme", "NEWS_ID": "NEWS-bitcoin"})]
NEWS = {"NEWS-interest_rates": [{"PUBLISHED": "2026-10-29 09:42 UTC", "SOURCE": "Reuters", "TITLE": "Central bank holds its policy rate", "LINK": "https://example.org/a"}],
        "NEWS-oil_prices": [{"PUBLISHED": "2026-10-29 09:10 UTC", "SOURCE": "Reuters", "TITLE": "Crude extends its fall on supply data", "LINK": "https://example.org/b"}]}
USEQ = [("AMZN", {"NAME": "Amazon", "SECTOR": "Retail Trade", "REVENUE": 716.9e9}), ("AAPL", {"NAME": "Apple", "SECTOR": "Manufacturing", "REVENUE": 416e9}),
        ("JPM", {"NAME": "JPMorgan", "SECTOR": "Finance, Insurance and Real Estate", "REVENUE": 280e9}),
        ("XOM", {"NAME": "Exxon", "SECTOR": "Manufacturing", "REVENUE": 340e9})]
FUNDS = [("VTI", {"NAME": "Vanguard Total Stock Market Index Fund", "TYPE": "Mutual fund", "NET_ASSETS": 2.2978e12, "HOLDING_COUNT": 3546, "INDEX_ID": ""}),
         ("SPY", {"NAME": "SPDR S&P 500 ETF Trust", "TYPE": "ETF", "NET_ASSETS": 7.1e11, "HOLDING_COUNT": 503, "INDEX_ID": "SP500"}),
         ("QQQ", {"NAME": "Invesco QQQ Trust", "TYPE": "ETF", "NET_ASSETS": 3.9e11, "HOLDING_COUNT": 101, "INDEX_ID": "NDX"})]
TRACK = [("SP500", {"INDEX": "SP500", "NAME": "S&P 500"}), ("NDX", {"INDEX": "NDX", "NAME": "Nasdaq-100"})]
COUNTS = {"ECON": 213, "USEQ": 586, "LEI": 9937, "LEIG": 591, "FUND": 200, "FILING": 11691, "TOPIC": 15}


class WorldBackend(FakeBackend):
    async def search(self, q, ident=None):
        head = q.split()[0]
        if q == "FILING order by FILED desc limit 1":
            out = _rows("filing", [("0000950103-26-016137", {"FILED": "2026-10-26", "COMPANY": "ASST", "FORM": "424B5"})])
            out["matched"] = 11691
            return out
        if q.startswith("FILING where FILED = '2026-10-26'"):
            return {**_rows("filing", []), "matched": 20}
        if q.endswith(" limit 1") and head in COUNTS and " where " not in q and " order " not in q:
            return {**_rows("x", []), "matched": COUNTS[head]}
        table = {"ECON": ("economy", ECON), "ECG": ("country-group", ECG), "QUOTE": ("quote", QUOTES), "TOPIC": ("news-topic", TOPICS),
                 "USEQ": ("us-company", USEQ), "FUND": ("fund", FUNDS), "TRACK": ("index-funds", TRACK)}
        if head not in table:
            raise BackendError(404, "DRS-4023", f"{head} is not part of the edition")
        kind, items = table[head]
        out = _rows(kind, items)
        if head == "FUND":
            out["matched"] = 200
        return out

    async def raw(self, kind, id_, ident=None):
        prov = {"source": "macro-store", "businessDate": "2026-10-28"}
        if kind == "economy" and id_ in PATHS:
            before, now = PATHS[id_]
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov,
                    "data": {"GROWTH_PATH": [{"YEAR": "2024", "GROWTH": before}, {"YEAR": "2025", "GROWTH": now}]}}
        if kind == "economic-forecast":
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov, "data": {"VINTAGE": "IMF World Economic Outlook, April 2026 edition"}}
        if kind == "news" and id_ in NEWS:
            return {"ref": {"kind": kind, "id": id_}, "provenance": {"source": "news"}, "data": {"ITEMS": NEWS[id_]}}
        if kind == "filing-feed":
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov,
                    "data": {"ROWS": [{"FILED": "2026-10-23", "COMPANY": "JBL", "COMPANY_NAME": "Jabil", "FORM": "10-K"}]}}
        raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")

    async def command_history(self, ident=None):
        return ["ECON CAN", "FUND SPY", "TRD MX-20000001"]


@pytest.fixture(scope="module")
def world_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = WorldBackend()
    app.state.editions.learn({"product": "Drishti World", "edition": {"name": "world", "title": "Drishti World"}})
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{PORT}"
    server.should_exit = True
    thread.join(10)


@pytest.mark.parametrize("landing", ["desk", "explorer", "map"])
@pytest.mark.parametrize("width", [390, 1280])
def test_each_world_landing_fills_from_the_packs_and_fits(browser, world_console, landing, width):
    ctx = browser.new_context(viewport={"width": width, "height": 900}, is_mobile=width < 600, has_touch=width < 600)
    page = ctx.new_page()
    try:
        page.goto(f"{world_console}/?landing={landing}", wait_until="load")
        page.locator(f"[data-world-landing='{landing}']").wait_for()
        if landing == "desk":
            page.wait_for_function("() => document.querySelector('[data-wl-date]').textContent === '2026-10-28'")
            page.wait_for_function("() => document.querySelectorAll('[data-wl-moved] tr').length >= 4")
            page.wait_for_function("() => document.querySelectorAll('[data-wl-news] li a').length >= 4")
            page.wait_for_function("() => document.querySelector('[data-wl-filings]').textContent === '20'")
            assert page.locator("[data-wl-world]").inner_text() == "2.8%"
            assert page.locator("[data-wl-breadth]").inner_text() == "2 · 1"
            assert page.locator("[data-wl-mover]").inner_text().startswith("SHEL")
            assert page.locator("[data-wl-funds-n]").inner_text() == "200"
            assert "United States" in page.locator("[data-wl-moved] tr").first.inner_text()     # the largest move (−0.6) first
            assert "interest rates" in page.locator("[data-wl-news] li").first.inner_text()      # the newest story first
            assert "April 2026" in page.locator("[data-wl-vintage]").inner_text()
            assert page.locator("[data-home-card='FUND SPY']").count() == 1
        elif landing == "explorer":
            assert page.locator("#wlCmd").is_visible()
            assert page.locator(".wl-chip", has_text="ECON USA").count() == 1
            page.wait_for_function("() => document.querySelector('[data-wl-count=LEI]').textContent === '9,937'")
            assert page.locator("[data-wl-count=USEQ]").inner_text() == "586"
            recents = page.locator("[data-wl-recents]").inner_text()
            assert "ECON CAN" in recents and "TRD" not in recents
        else:
            page.wait_for_function("() => document.querySelectorAll('[data-wl-regions] .wl-reg').length >= 4")
            assert "South Asia" in page.locator("[data-wl-regions] .wl-reg.hot").inner_text()    # the fastest-growing region leads
            page.locator("[data-wl-groups] button[data-group=g7]").click()
            page.wait_for_function("() => document.querySelectorAll('[data-wl-regions] .wl-reg').length === 3")   # USA, DEU, CAN of the G7
            page.locator("[data-wl-measures] button[data-measure=debt]").click()
            page.wait_for_function("() => document.querySelector('[data-wl-regions] .wl-reg.hot b').textContent === 'United States'")
            page.wait_for_function("() => document.querySelectorAll('[data-wl-sectors] .wl-bar').length === 3")
            assert "Manufacturing" in page.locator("[data-wl-sectors] .wl-bar").first.inner_text()
            page.wait_for_function("() => document.querySelectorAll('[data-wl-funds] tr').length === 3")
            assert "S&P 500" in page.locator("[data-wl-funds] tr").nth(1).inner_text()
            page.wait_for_function("() => document.querySelectorAll('[data-wl-changes] li').length >= 4")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.evaluate(AUDIT)
    finally:
        ctx.close()


def test_the_world_desk_draws_in_every_theme_with_its_teal_accent(browser, world_console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = ctx.new_page()
    try:
        page.goto(f"{world_console}/?landing=desk", wait_until="load")
        page.wait_for_function("() => document.querySelectorAll('[data-wl-moved] tr').length >= 4")
        seen = set()
        for theme in THEMES:
            page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
            colours = page.evaluate("""() => { const t = getComputedStyle(document.querySelector('.wl-tile'));
                const on = getComputedStyle(document.querySelector('.wl-switch a.on'));
                return [t.backgroundColor, t.color, on.color]; }""")
            assert all(c and c != "rgba(0, 0, 0, 0)" for c in colours), (theme, colours)
            assert colours[2] in ("rgb(76, 201, 184)", "rgb(11, 116, 104)"), (theme, colours)      # teal, light or dark
            seen.add(colours[0])
        assert len(seen) > 1                                                     # the tiles follow the theme
    finally:
        ctx.close()
