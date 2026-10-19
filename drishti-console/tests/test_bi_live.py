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

"""Rupaka phase 5 (streaming BI), the console's routes and policy, over a stand-in server: a report or board page that holds a Perspective visual (and only it) gets the
narrow WebAssembly policy; every other BI page keeps the strict one; the Arrow answer of a Perspective visual passes through with its live state; a report's live stream
reaches the browser over the one channel (a subscription ``bilive:<report>``); the live settings; and the code never opens a stream, a blob worker or inline script of
its own."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from bi_backend import DATASET_ANSWER, DATASET_MODEL, BiBackend
from conftest import CONSOLE
from core.app import CSP, create_app
from core.channel import ChannelSession
from core.config import load_settings

LIVE = "file:report:live-desk"
PLAIN = "file:report:plain"
BOARD = "file:board:wall"
LIVE_MODEL = {"rachana": 1, "report": "live-desk", "version": 1, "title": "Live desk", "dataset": "live-trading",
              "pages": [{"id": "desk", "title": "Desk", "visuals": [
                  {"id": "mtm", "type": "card", "title": "MTM", "at": {"x": 0, "y": 0, "w": 3, "h": 2}, "wells": {"values": ["MTM"]}},
                  {"id": "grid", "type": "perspective-grid", "title": "Trades", "at": {"x": 0, "y": 2, "w": 12, "h": 8}, "wells": {"columns": ["trade.tradeId", "trade.desk"]}}]}]}
PLAIN_MODEL = {"rachana": 1, "report": "plain", "version": 1, "title": "Plain", "dataset": "live-trading",
               "pages": [{"id": "p", "title": "P", "visuals": [{"id": "mtm", "type": "card", "title": "MTM", "at": {"x": 0, "y": 0, "w": 3, "h": 2}, "wells": {"values": ["MTM"]}}]}]}
LIVE_DATASET = dict(DATASET_MODEL, tables=[dict(DATASET_MODEL["tables"][0], mode="live")])
BOARD_MODEL = {"id": BOARD, "title": "Wall", "valid": True, "problems": [], "board": {"rachana": 1, "board": "wall", "version": 1, "title": "Wall", "rotate": "20s", "pages": [
    {"id": "p", "title": "P", "tiles": [{"id": "t1", "report": "live-desk", "visual": "mtm", "at": {"x": 0, "y": 0, "w": 4, "h": 3}}]}]},
    "tiles": {"p/t1": {"report": LIVE, "reportTitle": "Live desk", "page": "desk", "type": "card", "live": True}}, "mayEdit": False}
ARROW = (Path(__file__).resolve().parent / "fixtures" / "poc_extract.arrows").read_bytes()
LIVE_STATE = {"generation": 12, "lastUpdate": "2026-10-11T07:00:00Z", "state": "live", "at": "2026-10-11T07:00:01Z",
              "tables": [{"table": "trade", "state": "live", "seq": 40, "epoch": 0, "rows": 750}]}


class LiveBackend(BiBackend):
    """The BI paths of phase 5: a live report holding a Perspective visual, a plain one, a board, the Arrow answer and the report's live stream."""

    def __init__(self):
        super().__init__()
        self.arrow_calls, self.fail_probe = [], False

    async def bi(self, method, path, ident, body=None, **params):
        if path in ("/reports/" + LIVE, "/reports/" + PLAIN):
            if self.fail_probe:
                from core.backend import BackendError
                raise BackendError(500, "DRS-9999", "the server is busy")
            return {"id": path[9:], "title": "Live desk", "valid": True, "problems": [], "mayEdit": False, "report": LIVE_MODEL if path.endswith(LIVE) else PLAIN_MODEL,
                    "dataset": LIVE_DATASET, "shipped": True, "sample": True}
        if path == "/boards/" + BOARD:
            return BOARD_MODEL
        if path == "/reports/" + LIVE + "/query":
            return dict(DATASET_ANSWER, live=LIVE_STATE)
        return await super().bi(method, path, ident, body, **params)

    async def bi_arrow(self, path, ident, body, **params):
        self.arrow_calls.append((path, body, params))
        return ARROW, {"x-bi-rows": "960", "x-bi-masked": "trader", "x-bi-live": json.dumps(LIVE_STATE), "content-type": "application/vnd.apache.arrow.stream", "server-timing": "x"}

    async def sse(self, path, ident=None, opened=None):
        self.sse_paths = getattr(self, "sse_paths", []) + [path]
        if path.endswith("/live"):
            yield "hello", json.dumps(LIVE_STATE)
            yield "tick", json.dumps(dict(LIVE_STATE, generation=13))
            yield "status", json.dumps(dict(LIVE_STATE, generation=13))
        while True:
            await asyncio.sleep(3600)


@pytest.fixture()
def live():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = LiveBackend()
    return TestClient(app)


# ---- the policy ---------------------------------------------------------------------------------------------------------------

def test_every_bi_page_has_the_strict_policy_even_with_a_2_1_perspective_visual(live):
    """Wave B (BI_DESIGN.md P3): no BI page compiles WebAssembly; a 2.1 report with a Perspective visual is drawn as Drishti's own grid of the server's answer."""
    for path in (f"/bi/r/{LIVE}", f"/bi/r/{PLAIN}", f"/bi/b/{BOARD}", "/bi", "/bi/catalogue", "/bi/datasets", "/bi/designer", "/bi/explore"):
        page = live.get(path)
        assert page.status_code == 200, path
        csp = page.headers["content-security-policy"]
        assert csp == CSP and "wasm" not in csp and "sha256" not in csp, path
        assert "bi-perspective.js" not in page.text and "vendor/perspective" not in page.text and "data-perspective" not in page.text, path
    assert "/static/js/bi-live.js" in live.get(f"/bi/r/{LIVE}").text


def test_a_board_is_a_report_with_board_pages_and_keeps_the_strict_policy(live):
    moved = live.get(f"/bi/b/{BOARD}", follow_redirects=False)                       # 2.4.0 (R7): the report viewer draws board pages
    assert moved.status_code == 307 and moved.headers["location"].startswith("/bi/r/")
    page = live.get(moved.headers["location"])
    assert page.headers["content-security-policy"] == CSP and 'data-bi-page="report"' in page.text and "/static/js/bi-report-board.js" in page.text


def test_the_board_and_the_report_open_from_the_item_page(live):
    page = live.get("/bi/i/anything").text
    assert "data-open-board" in page and "data-open-report" in page


# ---- the data -----------------------------------------------------------------------------------------------------------------

def test_the_arrow_answer_passes_through_with_its_live_state(live):
    r = live.post(f"/bi/api/reports/{LIVE}/query?format=arrow", json={"page": "desk", "visual": "grid", "state": {}})
    assert r.status_code == 200 and r.headers["content-type"] == "application/vnd.apache.arrow.stream" and r.content == ARROW
    assert r.headers["x-bi-rows"] == "960" and r.headers["x-bi-masked"] == "trader" and r.headers["cache-control"] == "no-store"
    assert json.loads(r.headers["x-bi-live"])["tables"][0]["seq"] == 40
    path, body, params = live.app.state.backend.arrow_calls[-1]
    assert path == f"/reports/{LIVE}/query" and body["visual"] == "grid" and params == {"format": "arrow"}


def test_the_arrow_route_takes_json_only_and_passes_a_refusal_on(live):
    assert live.post(f"/bi/api/reports/{LIVE}/query?format=arrow", content="x=1", headers={"content-type": "text/plain"}).status_code == 415
    from core.backend import BackendError

    async def refuse(path, ident, body, **params):
        raise BackendError(422, "DRS-9035", "the report has no page 'zz'")

    live.app.state.backend.bi_arrow = refuse
    r = live.post(f"/bi/api/reports/{LIVE}/query?format=arrow", json={"page": "zz", "visual": "grid"})
    assert r.status_code == 422 and r.json()["code"] == "DRS-9035"


def test_json_answers_carry_the_live_block(live):
    r = live.post(f"/bi/api/reports/{LIVE}/query", json={"page": "desk", "visual": "mtm", "state": {}}).json()
    assert r["live"]["generation"] == 12 and r["live"]["tables"][0]["epoch"] == 0


def test_boards_are_reached_through_the_proxy(live):
    assert live.get(f"/bi/api/boards/{BOARD}").json()["tiles"]["p/t1"]["report"] == LIVE


# ---- the stream ---------------------------------------------------------------------------------------------------------------

def test_a_report_stream_reaches_the_browser_over_the_one_channel():
    class Backend(LiveBackend):
        pass

    async def go():
        backend = Backend()
        session = ChannelSession(backend, SimpleNamespace(user="rita"), lambda event, data: data, limit=4)

        async def never():
            return False

        out = []
        agen = session.events([f"bilive:{LIVE}"], never)
        async for chunk in agen:
            out.append(chunk)
            if len(out) >= 4:
                break
        await agen.aclose()
        return out, backend

    out, backend = asyncio.run(go())
    assert json.loads(out[0].split("data: ")[1])["d"]["subs"] == [f"bilive:{LIVE}"]
    events = [(c.split("\n")[0], json.loads(c.split("\n")[1][6:])) for c in out[1:]]
    assert [e[0] for e in events] == ["event: hello", "event: tick", "event: status"]
    assert all(body["ch"] == f"bilive:{LIVE}" for _, body in events) and events[1][1]["d"]["generation"] == 13
    assert backend.sse_paths == [f"/bi/reports/{LIVE.replace(':', '%3A')}/live"]


def test_the_hub_forwards_the_two_new_events():
    hub = (CONSOLE / "web/static/js/live-hub.js").read_text(encoding="utf-8")
    assert "'tick'" in hub and "'status'" in hub


# ---- the settings and the code ------------------------------------------------------------------------------------------------

def test_the_live_settings_reach_the_page_and_are_bounded(live):
    page = live.get(f"/bi/r/{PLAIN}").text
    assert 'data-live-min="250"' in page and 'data-live-stale="30"' in page and 'data-rotate="30"' in page
    settings = load_settings(CONSOLE / "config")
    settings.as_dict()["bi"]["live"] = {"min_interval_ms": 5, "stale_seconds": 99999}
    settings.as_dict()["bi"]["board"] = {"rotate_seconds": "soon"}
    app = create_app(settings)
    app.state.backend = LiveBackend()
    text = TestClient(app).get(f"/bi/r/{PLAIN}").text
    assert 'data-live-min="100"' in text and 'data-live-stale="600"' in text and 'data-rotate="30"' in text


def test_the_new_scripts_open_no_stream_no_blob_and_no_markup_of_their_own():
    js = CONSOLE / "web/static/js"
    for name in ("bi-live.js", "bi-report-board.js"):
        text = (js / name).read_text(encoding="utf-8")
        assert "EventSource(" not in text and "createObjectURL" not in text and "new Blob" not in text and "innerHTML" not in text and "eval(" not in text, name
        assert "http://" not in text and "https://" not in text, name                                         # nothing from outside
    assert not (js / "bi-perspective.js").exists() and not (CONSOLE / "web/static/vendor/perspective").exists()
