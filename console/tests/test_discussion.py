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

"""Discussion, the console (COLLABORATION.md, build step 6): the view's one side drawer with its About and Discussion tabs, the thread calls
the tab makes (and the link *Open as it was* that the console adds from a comment's pin), the page's own date travelling with a post, ids that
never reach a server path, and the account page's email opt-outs."""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core import threads as threads_core  # noqa: E402
from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

PIN = {"businessDate": "2026-09-29", "live": False, "knownAt": "2026-09-29T14:30:00.250Z", "generation": 1742, "source": "aero-risk"}


class FakeThreads:
    """Stands in for the server's thread API."""

    def __init__(self):
        self.calls, self.seen_asof = [], []

    async def list(self, kind, id_, ident, **query):
        self.calls.append(("list", kind, id_, query))
        t = {"id": "th_1", "kind": kind, "entityId": id_, "anchor": "panel", "panel": "var", "state": "open", "comments": 1,
             "items": [{"id": "cm_1", "threadId": "th_1", "author": "ann", "pin": PIN, "state": "live", "body": "Limit is •••", "editable": True}]}
        return threads_core.annotate([t])

    async def counts(self, kind, id_, ident):
        return {"entity": 1, "panels": {"var": 2}, "fields": {}}

    async def start(self, kind, id_, body, ident):
        from core import asof
        self.seen_asof.append((asof.current(), asof.known_at()))
        self.calls.append(("start", kind, id_, body))
        return {"threadId": "th_2", "comment": {"id": "cm_2", "pin": PIN}, "notified": 1, "skipped": [], "warnings": ["masked value"]}

    async def reply(self, tid, body, ident):
        self.calls.append(("reply", tid, body))
        return {"threadId": tid, "comment": {"id": "cm_3"}, "notified": 0, "skipped": [], "warnings": []}

    async def edit(self, cid, body, ident):
        self.calls.append(("edit", cid, body))
        if body["revision"] == 9:
            raise BackendError(409, "DRS-7009", "someone changed it")
        return {"id": cid}

    async def retract(self, cid, ident):
        self.calls.append(("retract", cid))
        return {"id": cid, "state": "retracted"}

    async def revisions(self, cid, ident):
        return [{"revision": 1, "action": "create", "body": "first"}]

    async def state(self, tid, state, ident):
        self.calls.append(("state", tid, state))
        return {"id": tid, "state": state}

    async def follow(self, tid, muted, ident):
        self.calls.append(("follow", tid, muted))
        return {"following": True, "muted": muted}

    async def unfollow(self, tid, ident):
        self.calls.append(("unfollow", tid))

    async def hide(self, cid, reason, ident):
        self.calls.append(("hide", cid, reason))
        raise BackendError(403, "DRS-4003", "administrators only")

    async def unhide(self, cid, ident):
        return {"id": cid}


def _app():
    fake = FakeThreads()
    app = create_app(load_settings(CONSOLE / "config"))
    from conftest import FakeBackend
    app.state.backend = FakeBackend()
    app.state.threads = fake
    return TestClient(app), fake


def test_the_view_has_one_side_drawer_with_two_tabs_and_no_notes_drawer():
    c, _ = _app()
    page = c.get("/v/trade/IRS-48213").text
    assert page.count('id="aboutDrawer"') == 1 and 'id="notesDrawer"' not in page and "notes.js" not in page
    assert 'role="tablist"' in page and 'data-tab="about"' in page and 'data-tab="discussion"' in page
    assert 'data-tab-panel="about"' in page and 'data-tab-panel="discussion"' in page
    assert 'data-discussion-open' in page and 'aria-keyshortcuts="Alt+N"' in page and 'data-about-open' in page
    for js in ("discussion-compose.js", "discussion-view.js", "discussion.js"):
        assert f"/static/js/{js}" in page
    assert page.index("/static/js/about.js") < page.index("/static/js/discussion.js")        # the host first
    assert 'data-page-asof="live"' in page                                                      # the page's own date, for a post's pin


def test_a_comments_pinned_link_opens_the_page_as_it_was():
    c, fake = _app()
    out = c.get("/api/threads/trade/IRS-48213", params={"panel": "var", "state": "open"}).json()
    assert out[0]["items"][0]["href"] == "/v/trade/IRS-48213?asOf=2026-09-29&knownAt=2026-09-29T14%3A30%3A00Z&gen=1742#p-var"
    assert fake.calls[0][3]["panel"] == "var" and fake.calls[0][3]["state"] == "open"
    assert c.get("/api/thread-counts/trade/IRS-48213").json()["panels"] == {"var": 2}


def test_a_post_carries_the_pages_date_and_the_warnings_come_back():
    c, fake = _app()
    r = c.post("/api/threads/trade/IRS-48213", json={"anchor": "panel", "panel": "var", "body": "hi", "generation": 3, "asOf": "2026-09-29",
                                                      "knownAt": "2026-09-29T14:30:00Z"})
    assert r.status_code == 201 and r.json()["warnings"] == ["masked value"]
    assert r.json()["comment"]["href"].startswith("/v/trade/IRS-48213?asOf=2026-09-29")
    assert fake.seen_asof[0][0] == "2026-09-29"
    sent = fake.calls[-1][3]
    assert "asOf" not in sent and "knownAt" not in sent and sent["generation"] == 3        # the date went in the headers, not the body


def test_replies_edits_retracts_state_and_follow_reach_the_server():
    c, fake = _app()
    assert c.post("/api/thread/th_1/comments", json={"body": "ok", "generation": 1}).status_code == 201
    assert c.patch("/api/comment/cm_1", json={"body": "ok2", "revision": 1}).json() == {"id": "cm_1"}
    late = c.patch("/api/comment/cm_1", json={"body": "ok3", "revision": 9})
    assert late.status_code == 409 and late.json()["code"] == "DRS-7009"                    # the server's refusal is passed on in words
    assert c.post("/api/comment/cm_1/retract").json()["state"] == "retracted"
    assert c.get("/api/comment/cm_1/revisions").json()[0]["body"] == "first"
    assert c.post("/api/thread/th_1/state", json={"state": "resolved"}).json()["state"] == "resolved"
    assert c.put("/api/thread/th_1/follow", json={"muted": True}).json()["muted"] is True
    assert c.delete("/api/thread/th_1/follow").json() == {"ok": True}
    hid = c.post("/api/comment/cm_1/hide", json={"reason": "client name"})
    assert hid.status_code == 403 and ("hide", "cm_1", "client name") in fake.calls         # the server, not the console, decides who moderates


def test_an_id_that_is_not_one_never_reaches_a_server_path():
    c, fake = _app()
    for url in ("/api/thread/th_1..%2Fadmin/comments", "/api/comment/x%20y/retract"):
        assert c.post(url, json={"body": "x"}).status_code in (400, 404)
    assert not [x for x in fake.calls if x[0] in ("reply", "retract")]
    assert threads_core.clean_id("th_01J9ABC-d") == "th_01J9ABC-d" and threads_core.clean_id("../x") is None and threads_core.clean_id("") is None


def test_the_account_page_offers_the_email_opt_outs_and_saves_them(monkeypatch):
    c, _ = _app()
    saved = []

    async def patch_settings(changes, ident):
        saved.append(changes)
        return changes

    async def settings(ident):
        return {"notify": {"email": {"share": True, "mention": False, "reply": True}}}
    monkeypatch.setattr(c.app.state.backend, "patch_settings", patch_settings, raising=False)
    monkeypatch.setattr(c.app.state.backend, "settings", settings, raising=False)
    c.app.state.user_settings.forget("ash")
    page = c.get("/account").text
    assert 'name="notify_email_share" checked' in page and 'name="notify_email_reply" checked' in page
    assert 'name="notify_email_mention" checked' not in page and 'name="notify_email_mention"' in page   # off stays off
    r = c.post("/account/settings", data={"theme": "light", "density": "comfortable", "landing": "/t", "notifyForm": "1",
                                          "notify_email_share": "on"}, follow_redirects=False)
    assert r.status_code == 303
    assert saved[-1]["notify"] == {"email": {"share": True, "mention": False, "reply": False}}           # an unticked box is off
    c.post("/account/settings", data={"theme": "light", "density": "comfortable", "landing": "/t"}, follow_redirects=False)
    assert "notify" not in saved[-1]                                                        # a form without the box leaves it alone


def test_the_thread_client_sends_only_the_filters_that_were_asked_for():
    import asyncio
    sent = {}

    class Backend:
        async def _send(self, method, path, ident, **kw):
            sent.update(method=method, path=path, params=kw.get("params"))
            return []
    asyncio.run(threads_core.Threads(Backend()).list("trade", "A/B", None, panel="var", state="", path="", limit=None, before="", bogus="x"))
    assert sent["path"] == "/threads/trade/~" and sent["params"] == {"id": "A/B", "panel": "var"}   # an id with a slash goes in the query; blanks and unknowns are dropped
