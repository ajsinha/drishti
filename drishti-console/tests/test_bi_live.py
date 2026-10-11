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
from core.perspective_csp import PERSPECTIVE_VERSION, STYLE_HASHES, board_uses_perspective, policy, uses_perspective

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

def test_a_report_with_a_perspective_visual_gets_the_narrow_policy_and_no_other_page_does(live):
    page = live.get(f"/bi/r/{LIVE}")
    assert page.status_code == 200
    csp = page.headers["content-security-policy"]
    assert csp == policy(CSP)
    assert "script-src 'self' 'wasm-unsafe-eval';" in csp and "'unsafe-eval'" not in csp and "'unsafe-inline'" not in csp and "worker-src 'self'" in csp
    assert "style-src 'self' 'sha256-" in csp and all(f"'{h}'" in csp for h in STYLE_HASHES)
    assert f'data-perspective="/static/vendor/perspective/{PERSPECTIVE_VERSION}"' in page.text
    assert "perspective-viewer/css/pro.css" in page.text and "pro-dark.css" in page.text
    assert "/static/js/bi-perspective.js" in page.text and "/static/js/bi-live.js" in page.text
    # a report without one, and every other page of BI, keep the strict policy
    plain = live.get(f"/bi/r/{PLAIN}")
    assert plain.headers["content-security-policy"] == CSP and 'data-perspective=""' in plain.text and "pro.css" not in plain.text
    for other in ("/bi", "/bi/catalogue", "/bi/datasets", "/bi/designer", "/t"):
        csp = live.get(other).headers["content-security-policy"]
        assert csp == CSP and "wasm" not in csp and "sha256" not in csp, other


def test_when_the_server_cannot_be_asked_the_page_is_strict(live):
    live.app.state.backend.fail_probe = True
    page = live.get(f"/bi/r/{LIVE}")
    assert page.status_code == 200 and page.headers["content-security-policy"] == CSP         # the page then shows the plain table in place of Perspective


def test_a_board_gets_the_narrow_policy_only_with_a_perspective_tile(live):
    strict = live.get(f"/bi/b/{BOARD}")
    assert strict.status_code == 200 and strict.headers["content-security-policy"] == CSP
    assert 'data-bi-page="board"' in strict.text and "/static/js/bi-board.js" in strict.text and "data-kiosk" in strict.text
    wall = dict(BOARD_MODEL, tiles={"p/t1": {"report": LIVE, "type": "perspective-grid", "live": True}})
    orig = live.app.state.backend.bi

    async def with_perspective(method, path, ident, body=None, **params):
        return wall if path == "/boards/" + BOARD else await orig(method, path, ident, body, **params)

    live.app.state.backend.bi = with_perspective
    assert live.get(f"/bi/b/{BOARD}").headers["content-security-policy"] == policy(CSP)


def test_the_probes_read_the_models():
    assert uses_perspective(LIVE_MODEL and {"report": LIVE_MODEL}) and not uses_perspective({"report": PLAIN_MODEL}) and not uses_perspective(None) and not uses_perspective({})
    assert board_uses_perspective({"tiles": {"a": {"type": "perspective-pivot"}}}) and not board_uses_perspective(BOARD_MODEL) and not board_uses_perspective(None)


def test_the_style_hashes_are_sha256_and_the_worker_policy_is_unchanged(live):
    assert len(STYLE_HASHES) == len(set(STYLE_HASHES)) == 4 and all(h.startswith("sha256-") and len(h) == 51 for h in STYLE_HASHES)
    csp = live.get(f"/static/vendor/perspective/{PERSPECTIVE_VERSION}/perspective/cdn/perspective-server.worker.js", headers={"host": "console.example"}).headers["content-security-policy"]
    assert csp == "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src console.example/static/ console.example/pyodide/"


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
    for name in ("bi-live.js", "bi-perspective.js", "bi-board.js"):
        text = (js / name).read_text(encoding="utf-8")
        assert "EventSource(" not in text and "createObjectURL" not in text and "new Blob" not in text and "innerHTML" not in text and "eval(" not in text, name
        assert "http://" not in text and "https://" not in text, name                                         # nothing from outside
    assert "type: 'module'" in (js / "bi-perspective.js").read_text(encoding="utf-8")                       # the engine's worker is a script of this origin


def test_the_vendored_perspective_is_the_version_the_hashes_belong_to():
    assert (CONSOLE / f"web/static/vendor/perspective/{PERSPECTIVE_VERSION}/perspective/cdn/perspective-server.worker.js").exists()
    note = (CONSOLE / "web/static/vendor/VENDOR.md").read_text(encoding="utf-8")
    assert PERSPECTIVE_VERSION in note and "core/perspective_csp.py" in note
