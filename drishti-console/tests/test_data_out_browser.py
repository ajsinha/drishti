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

"""Admin -> Data out in a real browser (FLIGHT_GUIDE.md section 9; mock-ups A and B), over a real server with the engine flight on
(TLS from a throw-away PKI, any free port) and a console:

- the page view (the default): the service bar says running, TLS on, the Arrow memory; the nodes with their real addresses (never
  localhost); the live reads, readers, tokens and limits tables; the Data out tab is the current one;
- the toggle shows the map (the address keeps ?view=map, a reload opens the map) and back; the shared service bar stays;
- no page error, in the dark and the light theme.

``DRISHTI_DATA_OUT_SHOTS=DIR`` saves the guide's screenshots there (light theme): page.png, map.png. Skipped when Playwright, Chromium,
the built server jar, a JDK or the cryptography package is missing. A live read on the map is checked by the heavy run's three-node end
to end (FLIGHT_GUIDE.md section 12)."""
import os
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

SHOTS = os.environ.get("DRISHTI_DATA_OUT_SHOTS")


def shot(pg, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=True)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    pytest.importorskip("cryptography")
    from test_flight_client import _pki
    tls = tmp_path_factory.mktemp("flight-tls")
    cert, key = _pki(tls)
    (tls / "server.pem").write_bytes(cert)
    (tls / "server.key").write_bytes(key)
    yield from _stack(tmp_path_factory, {"DRISHTI_OFFLINE": "true", "DRISHTI_FLIGHT_ENABLED": "true", "DRISHTI_FLIGHT_PORT": "0",
                                         "DRISHTI_FLIGHT_TLS_CERT": str(tls / "server.pem"), "DRISHTI_FLIGHT_TLS_KEY": str(tls / "server.key")})


def open_page(browser, stack, query=""):
    pg = browser.new_page(viewport={"width": 1560, "height": 1000})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.on("console", lambda m: m.type == "error" and "Content Security Policy" in m.text and pg.errors.append(m.text))
    pg.goto(stack + "/admin/data-out" + query)
    wait(pg, "document.querySelector('[data-dout-service]').textContent.indexOf('Flight service') >= 0")
    if SHOTS:
        pg.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
    return pg


def test_the_page_view_shows_the_service_nodes_reads_tokens_and_limits(browser, stack):
    pg = open_page(browser, stack)
    assert pg.get_attribute(".adm-tabs a[href='/admin/data-out']", "aria-current") == "page"
    service = pg.inner_text("[data-dout-service]")
    assert "Flight service running" in service and "TLS" in service and "Arrow memory" in service
    nodes = pg.inner_text("[data-dout-nodes]")
    assert "grpc+tls://" in nodes and "localhost" not in nodes and "127.0.0.1" not in nodes
    assert "No live read now." in pg.inner_text("[data-dout-live]")
    assert "every other role" in pg.inner_text("[data-dout-limits]")
    assert pg.is_hidden("[data-dout-pane='map']")
    shot(pg, "page")
    assert not pg.errors, pg.errors


def test_the_toggle_shows_the_map_keeps_it_in_the_address_and_goes_back(browser, stack):
    pg = open_page(browser, stack)
    pg.click("[data-dout-switch='map']")
    assert pg.is_visible("[data-dout-pane='map']") and pg.is_hidden("[data-dout-pane='page']")
    assert "view=map" in pg.url
    assert pg.get_attribute("[data-dout-switch='map']", "aria-checked") == "true"
    assert pg.locator("[data-dout-map] rect.box").count() >= 1          # this node at least
    assert pg.is_visible("[data-dout-map-empty]")
    assert "Flight service running" in pg.inner_text("[data-dout-service]")   # the shared bar stays
    shot(pg, "map")
    pg.reload()
    wait(pg, "document.querySelector('[data-dout-service]').textContent.indexOf('Flight service') >= 0")
    assert pg.is_visible("[data-dout-pane='map']")
    pg.click("[data-dout-switch='page']")
    assert pg.is_visible("[data-dout-pane='page']") and "view=map" not in pg.url
    assert not pg.errors, pg.errors


def test_the_screen_answers_its_api_and_refuses_an_unknown_stop(browser, stack):
    pg = open_page(browser, stack)
    r = pg.request.get(stack + "/admin/api/data-out")
    assert r.status == 200 and r.json()["service"]["running"] is True
    stop = pg.request.post(stack + "/admin/api/data-out/streams/f999/stop", data="{}", headers={"Content-Type": "application/json"})
    assert stop.status == 404 and "DRS-1001" in stop.text()
