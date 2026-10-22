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

"""UI Board P4, the console's routes (routes/ui_routes.py): the Boards pages, the /ui/api pass-through (documents as sent, If-Match on, ETag
back, 409 DRS-5057), the render streams relayed event by event (a refusal before the stream as JSON with its problems), the YAML tab's two
calls and the mapping sheet's supplier attributes; against the stand-in server of ui_board_backend.py."""
import json
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient

from conftest import CONSOLE
from ui_board_backend import UiBoardBackend

BOOK = (Path(__file__).resolve().parent / "fixtures" / "ui_book_trades.yaml").read_text()


@pytest.fixture
def ui():
    from core.app import create_app
    from core.config import load_settings
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = UiBoardBackend()
    with TestClient(app) as c:
        yield c, app.state.backend


def events(text: str) -> list[tuple[str, dict]]:
    out = []
    for block in text.split("\n\n"):
        ev = [x[6:].strip() for x in block.split("\n") if x.startswith("event:")]
        data = "\n".join(x[5:].lstrip() for x in block.split("\n") if x.startswith("data:"))
        if ev:
            out.append((ev[0], json.loads(data)))
    return out


def save(c, name="book-trades", text=BOOK, **headers):
    return c.put(f"/ui/api/boards/{name}", content=text, headers={"Content-Type": "application/yaml", **headers})


def test_one_designer_with_two_modes_and_the_viewer(ui):
    c, _ = ui
    for path in ("/build/board/new", "/build/board/book-trades", "/build/chip/new", "/build/chip/my-chip", "/ui/b/book-trades", "/ui/b/book-trades?owner=ann"):
        r = c.get(path)
        assert r.status_code == 200, path
        assert "TRADE_ID" not in r.text
    board = c.get("/build/board/new").text
    assert 'data-mode="board"' in board and 'data-ub-mode="chip"' in board and board.count("data-ub-yaml ") == 1 and board.count("data-ub-palette ") == 1
    assert 'data-mode="chip"' in c.get("/build/chip/new").text
    assert 'data-design="d-42"' in c.get("/build/board/new?design=d-42").text
    assert 'data-owner="ann"' in c.get("/ui/b/book-trades?owner=ann").text
    assert c.get("/ui/b/Bad_Name").status_code == 404 and c.get("/build/board/Bad_Name").status_code == 404


def test_the_old_addresses_lead_to_the_one_designer_and_the_one_list(ui):
    c, _ = ui
    for old, new in (("/ui", "/build"), ("/ui/new", "/build/board/new"), ("/ui/design/book-trades", "/build/board/book-trades"), ("/ui/chip-editor", "/build/chip/new")):
        r = c.get(old, follow_redirects=False)
        assert r.status_code == 307 and r.headers["location"] == new, old


def test_build_is_the_one_entry_point(ui):
    c, _ = ui
    menu = c.get("/build/board/new").text.split('class="tbar-menus"')[1].split("</nav>")[0]
    assert 'href="/build/new"' in menu and "Designer" in menu and 'href="/build"' in menu and "Boards" in menu and "Board designer guide" in menu
    assert 'href="/ui' not in menu                                              # no second menu item for the same job
    new = c.get("/build/new").text
    assert 'href="/build/board/new"' in new and 'href="/build/chip/new"' in new


def test_save_is_compare_and_set_with_the_etag_passed_both_ways(ui):
    c, be = ui
    r = save(c, **{"If-None-Match": "*"})
    assert r.status_code == 200 and r.headers["ETag"].startswith('"')
    etag = r.headers["ETag"]
    got = c.get("/ui/api/boards/book-trades")
    assert got.headers["ETag"] == etag and got.json()["document"]["chips"]["trades"]["use"] == "filterable-table@2"
    assert save(c, **{"If-Match": etag}).status_code == 200                     # revision 2
    stale = save(c, **{"If-Match": etag})
    assert stale.status_code == 409 and stale.json()["code"] == "DRS-5057"
    assert c.get("/ui/api/boards/book-trades/revisions").json() == [{"revision": 1}]
    assert ("ui", "PUT", "/boards/book-trades", {}) in be.calls


def test_a_save_refused_by_the_check_lists_its_problems(ui):
    c, _ = ui
    doc = yaml.safe_load(BOOK)
    doc["traces"].append({"from": "trades.visible", "to": "ticket.id"})          # a rowset into an id pin
    r = c.put("/ui/api/boards/bad", json=doc)
    assert r.status_code == 422 and r.json()["code"] == "DRS-2071"
    assert r.json()["problems"][0]["rule"] == "BC-04" and r.json()["problems"][0]["path"] == "traces[3]"
    chk = c.post("/ui/api/boards/check", json=doc).json()
    assert not chk["ok"] and chk["problems"][0]["rule"] == "BC-04"


def test_only_the_ui_paths_are_passed_on(ui):
    c, _ = ui
    assert c.get("/ui/api/admin/users").status_code == 404
    assert c.get("/ui/api/boards/../admin").status_code in (404, 400)


def test_a_saved_board_streams_layout_first_then_each_chip(ui):
    c, _ = ui
    save(c, **{"If-None-Match": "*"})
    r = c.get("/ui/api/boards/book-trades/render?stream=once&device=desktop")
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/event-stream")
    ev = events(r.text)
    names = [e for e, _ in ev]
    assert names[:2] == ["render", "layout"] and names[-1] == "done"
    lay = ev[1][1]
    assert lay["columns"] == 12 and {a["chip"] for a in lay["areas"]} == {"trades", "ticket", "flows"} and set(lay["frames"]) == {"trades", "ticket", "flows"}
    chips = {d["instance"]: d for e, d in ev if e == "chip"}
    assert chips["ticket"]["state"] == "waiting" and chips["ticket"]["waiting"] == ["id"]
    vm = chips["trades"]["viewmodel"]
    assert vm["pins"]["rows"][0]["measures"][1] == {"name": "MTM_USD", "value": "•••"} and len(vm["keys"]) == 20
    assert any(e == "chip" and d.get("state") == "running" for e, d in ev)       # the flows' BI run card


def test_the_phone_layout_and_a_refusal_before_the_stream(ui):
    c, _ = ui
    save(c, **{"If-None-Match": "*"})
    ev = events(c.get("/ui/api/boards/book-trades/render?stream=once&device=phone").text)
    assert ev[1][1]["order"] == ["trades", "ticket", "flows"] and ev[1][1]["collapsed"] == ["flows"]
    doc = yaml.safe_load(BOOK)
    doc["traces"][0]["to"] = "ticket.nope"
    r = c.post("/ui/api/render", json={"board": doc, "device": "desktop", "stream": "once"})
    assert r.status_code == 422 and r.json()["problems"][0]["rule"] == "BC-06"
    assert c.get("/ui/api/boards/missing/render").status_code == 404


def test_an_unsaved_board_renders_and_pins_go_to_its_render(ui):
    c, be = ui
    doc = yaml.safe_load(BOOK)
    ev = events(c.post("/ui/api/render", json={"board": doc, "device": "desktop", "stream": "once"}).text)
    assert ev[0][0] == "render" and ev[-1][0] == "done"
    r = c.post("/ui/api/renders/rnd-0001/pins", json={"instance": "trades", "pin": "selected", "value": "1000013"})
    assert r.status_code == 410 and r.json()["code"] == "DRS-2079"            # stream=once: the render is over


def test_the_yaml_tab_keeps_comments_and_reports_the_line(ui):
    c, _ = ui
    doc = yaml.safe_load(BOOK)
    doc["title"] = "Book trades, renamed"
    out = c.post("/ui/api-yaml/dump", json={"document": doc, "yaml": BOOK}).json()["yaml"]
    assert "# master to detail: the ticket follows the selection" in out and "title: Book trades, renamed" in out
    assert yaml.safe_load(out)["chips"] == doc["chips"]
    bad = c.post("/ui/api-yaml/load", json={"yaml": "board: x\nchips: [\n"})
    assert bad.status_code == 400 and bad.json()["code"] == "DRS-2070" and bad.json()["line"] >= 2
    assert c.post("/ui/api-yaml/load", json={"yaml": "- a list"}).status_code == 400
    assert c.post("/ui/api-yaml/load", json={"yaml": BOOK}).json()["document"]["board"] == "book-trades"


def test_the_mapping_sheet_gets_typed_attributes(ui):
    c, _ = ui
    a = {x["name"]: x["type"] for x in c.get("/ui/api-suppliers?connector=trading/trade-search").json()["attributes"]}
    assert a["TRADE_ID"] == "id" and a["NOTIONAL"] == "measure" and a["TRADE_DATE"] == "date" and a["COUNTERPARTY_NAME"] == "dimension"
    m = {x["name"]: x["type"] for x in c.get("/ui/api-suppliers?model=trading-risk").json()["attributes"]}
    assert m == {"FLOW_AMOUNT": "measure", "cashflows.TRADE_ID": "id", "cashflows.PAY_DATE": "date", "cashflows.CCY": "dimension"}
    assert c.get("/ui/api-suppliers?connector=nope/x").json() == {"attributes": None}
    assert c.get("/ui/api-suppliers").json() == {"attributes": None}


def test_chips_share_and_copy_pass_through(ui):
    c, _ = ui
    store = c.get("/ui/api/chips?q=table").json()
    assert any(x["chip"] == "filterable-table" and x["latest"] == 2 for x in store)
    man = c.get("/ui/api/chips/entity-card/1/manual").json()
    assert man["manual"]["purpose"] and "doc" in man["pins"]["in"]
    save(c, **{"If-None-Match": "*"})
    assert c.put("/ui/api/boards/book-trades/share", json={"people": ["ann"]}).json()["sharedWith"] == ["ann"]
    assert c.post("/ui/api/boards/book-trades/copy", json={"workspace": "fx-desk"}).json()["id"] == "workspace:fx-desk:book-trades"
