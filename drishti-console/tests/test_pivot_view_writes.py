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

"""The pivot screen's write routes to saved views and their comments (routes/pivot_routes.py): a delete reaches the server as the person and the
view is gone afterwards, a missing one is refused with the server's DRS code, a comment is resolved or reopened and then deleted, and an action
the screen does not offer is refused. The server keeps the views (a small stateful stand-in here); the console passes each call on as the person."""
from __future__ import annotations

import pytest

from core.backend import BackendError
from test_play import RISK_ABOUT, _app


@pytest.fixture
def views(backend, monkeypatch):
    """A stand-in server: views {name: owner}, comments {id: {"open": bool}}; every call kept in ``sent`` with the person it was made as."""
    state = {"views": {"desk-risk": "alice"}, "comments": {"c-1": {"open": True}}, "sent": []}

    async def pivot(verb, path, ident, body=None, raw=False, **params):
        user = getattr(ident, "user", None)
        state["sent"].append((verb, path, user))
        if verb == "DELETE" and path.startswith("/views/") and "/comments/" not in path:
            name = path.split("/")[2]
            if state["views"].get(name) != user:
                raise BackendError(404, "DRS-5704", f"you have no saved pivot view {name}")
            del state["views"][name]
            return None
        parts = path.split("/")                                  # /views/<owner>/<name>/comments/<id>[/<op>]
        if len(parts) >= 6 and parts[4] == "comments":
            cid = parts[5]
            if cid not in state["comments"]:
                raise BackendError(404, "DRS-5704", f"no comment {cid} on this view")
            if verb == "DELETE":
                del state["comments"][cid]
                return {"deleted": cid}
            state["comments"][cid]["open"] = parts[6] == "reopen"
            return {"id": cid, "resolved": None if parts[6] == "reopen" else {"by": user}}
        raise BackendError(404, "DRS-1001", f"the stand-in has no pivot {verb} {path}")

    monkeypatch.setattr(backend, "pivot", pivot, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    return state, c


def test_deleting_a_saved_view_removes_it_for_its_owner_and_is_refused_for_anyone_else(views):
    state, c = views
    name = "desk-risk"
    assert c.delete(f"/api/pivot/views/{name}").status_code == 204
    assert state["sent"][-1] == ("DELETE", "/views/desk-risk", "alice")          # as the signed-in person, never as a service
    assert "desk-risk" not in state["views"]                                       # the effect: the server's view is gone
    again = c.delete(f"/api/pivot/views/{name}")                                   # a second delete is a clean 404 with the server's code
    assert again.status_code == 404 and again.json()["code"] == "DRS-5704"
    other = c.delete(f"/api/pivot/views/{'never-saved'}")
    assert other.status_code == 404 and other.json()["code"] == "DRS-5704"


def test_a_comment_is_resolved_reopened_and_deleted_as_the_person(views):
    state, c = views
    owner, name, cid = "alice", "desk-risk", "c-1"
    resolved = c.post(f"/api/pivot/views/{owner}/{name}/comments/{cid}/resolve")
    assert resolved.status_code == 200 and resolved.json()["resolved"]["by"] == "alice" and state["comments"][cid]["open"] is False
    reopened = c.post(f"/api/pivot/views/{owner}/{name}/comments/{cid}/reopen")
    assert reopened.status_code == 200 and reopened.json()["resolved"] is None and state["comments"][cid]["open"] is True
    assert c.post(f"/api/pivot/views/{owner}/{name}/comments/{cid}/reopen").status_code == 200       # idempotent: still open
    assert state["sent"][-1] == ("POST", "/views/alice/desk-risk/comments/c-1/reopen", "alice")
    gone = c.delete(f"/api/pivot/views/{owner}/{name}/comments/{cid}")
    assert gone.status_code == 200 and gone.json() == {"deleted": "c-1"} and cid not in state["comments"]
    assert c.delete(f"/api/pivot/views/{owner}/{name}/comments/{cid}").json()["code"] == "DRS-5704"
    missing = c.post(f"/api/pivot/views/{owner}/{name}/comments/{cid}/resolve")
    assert missing.status_code == 404 and missing.json()["code"] == "DRS-5704"


def test_an_action_the_screen_does_not_offer_never_reaches_the_server(views):
    state, c = views
    owner, name, cid, op = "alice", "desk-risk", "c-1", "shred"
    r = c.post(f"/api/pivot/views/{owner}/{name}/comments/{cid}/{op}")
    assert r.status_code == 404 and state["sent"] == [] and state["comments"][cid]["open"] is True
