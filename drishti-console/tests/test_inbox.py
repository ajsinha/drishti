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

"""The inbox and the bell (COLLABORATION.md, build step 3): the page draws the server's rows as the reader may see them now, marks read,
filters, and the bell opens it; notices arrive on the same live connection as alerts."""
import re
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

JS = CONSOLE / "web" / "static" / "js"


class FakeCollab:
    def __init__(self):
        self.read, self.asked, self.fail = [], [], False
        self.rows = [
            {"seq": 9, "at": "2026-09-30T14:02:11Z", "type": "share", "actor": "ashutosh", "actorName": "Ashutosh Sinha", "kind": "trade", "id": "IRS-48213",
             "panel": None, "shareId": "sh_01J9AAAAAAAAAAAAAAAAAAAAAA", "read": False, "access": True,
             "title": "Ashutosh Sinha shared trade IRS-48213", "excerpt": "MTM moved after the fixing"},
            {"seq": 7, "at": "2026-09-29T09:00:00Z", "type": "share", "actor": "rng", "actorName": "Rachel Ng", "kind": "genome", "id": None, "panel": None,
             "shareId": "sh_01J9BBBBBBBBBBBBBBBBBBBBBB", "read": True, "access": False, "title": "(no access) Rachel Ng shared a genome view", "excerpt": None}]

    async def inbox(self, ident, type_="", unread=False, limit=50, before=0):
        if self.fail:
            raise BackendError(503, "DRS-5003", "backend unreachable")
        self.asked.append((type_, unread, before))
        return [r for r in self.rows if (not unread or not r["read"]) and (not type_ or r["type"] == type_)]

    async def unread(self, ident):
        return sum(1 for r in self.rows if not r["read"])

    async def mark_read(self, body, ident):
        self.read.append(body)
        return {"changed": 1, "unread": 0}

    async def config(self, ident):
        return {"enabled": True, "collaborate": True}


def _app():
    from conftest import FakeBackend
    fake = FakeCollab()
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FakeBackend()
    app.state.collab = fake
    return TestClient(app), fake


def test_the_inbox_lists_notices_as_the_server_renders_them():
    c, fake = _app()
    html = c.get("/inbox").text
    assert "Ashutosh Sinha shared trade IRS-48213" in html and "MTM moved after the fixing" in html
    assert 'href="/share/sh_01J9AAAAAAAAAAAAAAAAAAAAAA"' in html                  # a share opens through its link, which checks the right
    assert re.search(r'class="inbox-row unread"[^>]*data-seq="9"', html) and re.search(r'class="inbox-row noaccess"[^>]*data-seq="7"', html)
    assert "(no access) Rachel Ng shared a genome view" in html and "genome" in html and "IRS-48213" in html
    assert fake.asked == [("", False, 0)]


def test_filters_reach_the_server():
    c, fake = _app()
    html = c.get("/inbox?tab=share&unread=1").text
    assert fake.asked[-1] == ("share", True, 0)
    assert "Rachel Ng" not in html and 'aria-current="page"' in html
    c.get("/inbox?tab=mention")
    assert fake.asked[-1] == ("mention", False, 0)
    c.get("/inbox?tab=nonsense")
    assert fake.asked[-1] == ("", False, 0)


def test_an_empty_inbox_says_what_it_is_for_and_a_server_problem_is_shown():
    c, fake = _app()
    fake.rows = []
    assert "When someone shares a view with you" in c.get("/inbox").text
    fake.fail = True
    r = c.get("/inbox")
    assert r.status_code == 200 and "backend unreachable" in r.text


def test_marking_read_and_the_count_go_to_the_server():
    c, fake = _app()
    assert c.get("/api/inbox/count").json() == {"unread": 1}
    assert c.post("/api/inbox/read", json={"seqs": [9], "evil": 1}).json()["unread"] == 0
    assert fake.read == [{"seqs": [9]}]                                            # only the two fields the server knows
    c.post("/api/inbox/read", json={"upTo": 9})
    assert fake.read[-1] == {"upTo": 9}


def test_the_bell_opens_the_inbox_and_alt_i_is_bound():
    c, _ = _app()
    html = c.get("/t").text
    assert 'class="tool bell" href="/inbox"' in html and "data-bell-count" in html
    js = (JS / "alerts.js").read_text()
    assert "notice: onNotice" in js and "/api/inbox/count" in js and "KeyI" in js
    assert "'notice'" in (JS / "live-hub.js").read_text()                        # the live connection passes notices on, beside alerts


def test_the_inbox_page_has_the_phone_and_accessibility_hooks():
    c, _ = _app()
    html = c.get("/inbox").text
    assert 'aria-label="Notices"' in html and 'role="status"' in html and "Unread only" in html and "Mark all read" in html
    css = (CONSOLE / "web" / "static" / "css" / "collab.css").read_text()
    assert "max-width: 640px" in css and "prefers-reduced-motion" in css
    assert not re.search(r"#[0-9a-fA-F]{3,6}\b", re.sub(r"rgba\([^)]*\)", "", css))   # colours only from tokens.css: every theme works
