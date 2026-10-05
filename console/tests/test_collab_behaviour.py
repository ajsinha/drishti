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

"""QA behaviour pass 2026-10-05 (B3), the console's side: one zone rule for collaboration times, one code in an error line,
Retry-After passed on, the bridge Test waiting for the bridge, the outbox and hidden-comment views, the export streamed."""
import sys
from pathlib import Path
from types import SimpleNamespace

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from core.backend import BackendError  # noqa: E402
from routes.common import local_zone, localise  # noqa: E402
from test_collab_polish import FakeAdmin, _app, JS  # noqa: E402


def _request(settings, business_zone=None):
    state = SimpleNamespace(settings=settings, business_date={"zone": business_zone} if business_zone else None)
    app = SimpleNamespace(state=SimpleNamespace(templates=SimpleNamespace(env=SimpleNamespace(globals={"CLOCK_TZ": "America/New_York"}))))
    return SimpleNamespace(state=state, app=app)


def test_revision_rows_are_localised_like_every_other_time():          # B3-02
    rows = localise([{"revision": 1, "at": "2026-10-05T07:24:00Z"}], "America/New_York", "New York")
    assert rows[0]["atLocal"] == "2026-10-05 03:24 New York"


def test_the_users_clock_zone_applies_to_collaboration_times_else_the_business_zone():      # B3-10
    assert local_zone(_request({"clockZone": "Asia/Kolkata"}, "America/New_York")) == ("Asia/Kolkata", "Kolkata")
    assert local_zone(_request({}, "Europe/London")) == ("Europe/London", "London")
    assert local_zone(_request(None)) == ("America/New_York", "New York")


def test_an_error_line_carries_the_code_once_and_retry_after_reaches_the_browser():     # B3-06, B3-07
    e = BackendError(429, "DRS-7003", "DRS-7003 too many shares; try again in 35 s")
    assert e.detail == "too many shares; try again in 35 s"
    c, fake, _ = _app()
    e.retry_after = "35"

    async def refuse(body, ident):
        raise e
    fake.send = refuse
    r = c.post("/api/share", json={"kind": "trade", "id": "X", "to": {"users": ["ravi"], "roles": []}})
    assert r.status_code == 429 and r.headers["retry-after"] == "35" and r.json() == {"code": "DRS-7003", "detail": "too many shares; try again in 35 s"}


def test_the_bridge_test_waits_for_the_bridge_and_names_the_right_setting():       # B3-05
    c, _, backend = _app()
    seen = {}

    async def slow(method, path, ident, body=None, timeout=None, **params):
        seen["timeout"] = timeout
        raise BackendError(504, "DRS-1004", "the server did not answer in time; raise the server's drishti.sources.fetch-timeout")
    backend.admin = slow
    r = c.post("/admin/collab/api/bridges/ops-chat/test")
    assert seen["timeout"] and seen["timeout"] > 10                                  # longer than the bridge's own 10 s
    assert r.status_code == 504 and r.json()["code"] == "DRS-7013" and "drishti.collab.bridges.timeout" in r.json()["detail"]
    assert "fetch-timeout" not in r.json()["detail"]


def test_the_outbox_and_hidden_comments_reach_the_server_with_local_times():       # B3-04, B3-13
    c, _, backend = _app()

    class Admin(FakeAdmin):
        async def __call__(self, method, path, ident, body=None, **params):
            self.calls.append((method, path, body, params))
            if path == "/collab/outbox":
                return {"enabled": True, "available": True, "counts": {"pending": 1}, "items": [
                    {"seq": 4, "recipient": "ravi", "template": "share", "state": "pending", "attempts": 2, "nextAt": "2026-10-05T07:30:00Z",
                     "lastError": "Could not connect"}]}
            if path == "/collab/threads/hidden":
                return {"items": [{"commentId": "cm_1", "threadId": "th_1", "kind": "trade", "entityId": "X", "author": "ann", "reason": "off topic",
                                   "createdAt": "2026-10-05T07:00:00Z"}], "next": None}
            return {"ok": True}
    backend.admin = admin = Admin()
    out = c.get("/admin/collab/api/outbox").json()
    assert out["items"][0]["attempts"] == 2 and out["items"][0]["nextAtLocal"] == "2026-10-05 03:30 New York"
    assert c.post("/admin/collab/api/outbox/4/retry").status_code == 200 and admin.calls[-1][:2] == ("POST", "/collab/outbox/4/retry")
    hidden = c.get("/admin/collab/api/hidden").json()
    assert hidden["items"][0]["reason"] == "off topic" and hidden["items"][0]["createdAtLocal"] == "2026-10-05 03:00 New York"
    assert c.get("/admin/collab/api/hidden", params={"after": "../x"}).status_code == 400
    html = c.get("/admin/collab").text
    assert "data-acol-outbox" in html and "data-acol-hidden-list" in html and "Last problem" in html


def test_the_compliance_export_is_streamed_not_held_whole():           # B3-12
    src = (CONSOLE / "routes" / "collab_admin_routes.py").read_text()
    assert "StreamingResponse" in src and "open_download" in src and "raw=True" not in src.split("def export_download")[1].split("@router")[0]


def test_the_edit_button_goes_when_the_window_closes_and_the_phone_box_fits():        # B3-08, B3-09
    js = (JS / "discussion-view.js").read_text()
    assert "editWindow" in js and "setTimeout" in js
    css = (CONSOLE / "web" / "static" / "css" / "collab.css").read_text()
    assert "box-sizing: border-box" in css.split(".disc-box textarea")[1].split("}")[0] and "minmax(0, 1fr)" in css
