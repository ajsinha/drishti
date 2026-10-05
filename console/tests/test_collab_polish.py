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

"""Collaboration console polish (COLLABORATION.md, Console polish): the share dialog's "also start a discussion" tick, the reply box on a shared
view, Admin > Collaboration (sections by role, every call passed to the server and its refusal told in words), times in the top bar's zone,
and a quoted value drawn the way the page shows the field."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core import asof  # noqa: E402
from core.app import create_app  # noqa: E402
from core.auth import Identity  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

JS = CONSOLE / "web" / "static" / "js"
SID = "sh_01J9ABCDEFGHJKMNPQRSTVWXYZ"


class FakeCollab:
    def __init__(self):
        self.sent, self.replies, self.shares = [], [], {}
        self.caps = {"enabled": True, "collaborate": True, "maxText": 2000, "minQuery": 2, "postToThread": True, "compliance": False}

    async def config(self, ident):
        return self.caps

    async def send(self, body, ident):
        self.sent.append(body)
        return {"id": SID, "link": "x", "delivered": 1, "skipped": [], "warnings": []}

    async def share(self, share_id, ident):
        return self.shares[share_id]

    async def reply(self, share_id, note, ident):
        self.replies.append((share_id, note))
        if note == "refuse":
            raise BackendError(404, "DRS-7001", "no such share")
        return {"id": "cm_9", "author": "ravi", "authorName": "Ravi Kumar", "createdAt": "2026-09-30T14:05:00Z", "body": note}

    async def inbox(self, ident, type_="", unread=False, limit=50, before=0):
        return [{"seq": 1, "at": "2026-09-30T14:02:11Z", "type": "share", "read": False, "access": True, "title": "t", "shareId": SID}]

    async def unread(self, ident):
        return 1


def share(role="recipient"):
    return {"id": SID, "access": True, "role": role, "sender": "ann", "senderName": "Ann Author", "createdAt": "2026-09-30T14:00:00Z",
            "kind": "trade", "entityId": "IRS-48213", "pin": {"live": True, "generation": 1742}, "note": "Look at MTM",
            "replies": [{"id": "cm_1", "author": "ann", "authorName": "Ann Author", "createdAt": "2026-09-30T14:01:00Z", "body": "first reply"}]}


class FakeAdmin:
    """The server's admin collaboration calls, as the console's backend.admin makes them."""

    def __init__(self, refuse=None):
        self.calls, self.refuse = [], refuse or {}

    async def __call__(self, method, path, ident, body=None, **params):
        self.calls.append((method, path, body, params))
        if path in self.refuse:
            raise BackendError(403, "DRS-4003", self.refuse[path])
        if path == "/collab/threads":
            return {"items": [{"id": "th_1", "kind": "trade", "entityId": "IRS-48213", "state": "open", "createdBy": "ann",
                               "createdAt": "2026-09-30T14:00:00Z", "lastAt": "2026-09-30T15:00:00Z", "comments": 2}], "next": None}
        if path == "/collab/holds":
            return {"id": 3, "scope": "all"} if method == "POST" else [{"id": 3, "scope": "all", "reason": "case 7", "placedBy": "cmp",
                                                                      "placedAt": "2026-09-30T14:00:00Z"}]
        if path == "/collab/retention/run":
            return {"dryRun": True, "threadsPurged": 2, "sharesPurged": 1, "threadsHeld": 0, "sharesHeld": 0}
        if path == "/collab/bridges":
            return {"enabled": True, "renderAs": "viewer", "perMinute": 30, "bridges": [{"name": "ops-chat", "format": "slack", "usable": True,
                                                                                          "status": "ok", "host": "chat.example", "outbox": {}}]}
        if path.startswith("/collab/exports"):
            return {"id": "ex_1", "state": "queued", "createdAt": "2026-09-30T14:00:00Z"}
        return {"ok": True}


def _app(monkeypatch=None, admin=True):
    fake = FakeCollab()
    app = create_app(load_settings(CONSOLE / "config"))
    from conftest import FakeBackend
    app.state.backend = FakeBackend()
    app.state.collab = fake
    if not admin and monkeypatch is not None:
        monkeypatch.setattr(Identity, "is_admin", property(lambda self: False))
    return TestClient(app), fake, app.state.backend


# ---- 1. the share dialog's tick ----------------------------------------------------------------------------------------------------

def test_the_dialog_has_the_discussion_tick_and_sends_it_with_the_servers_default():
    c, fake, _ = _app()
    html = c.get("/v/trade/IRS-48213").text
    assert 'name="postToThread"' in html and "Also start a discussion on the view" in html
    js = (JS / "share.js").read_text()
    assert "conf.postToThread" in js and "postToThread: !!form.elements.postToThread.checked" in js        # default from /collab, sent in the body
    assert c.get("/api/collab").json()["postToThread"] is True                                              # the server's setting reaches the dialog
    assert c.post("/api/share", json={"kind": "trade", "id": "IRS-48213", "to": {"users": ["ravi"], "roles": []}, "postToThread": True}).status_code == 201
    assert fake.sent[-1]["postToThread"] is True


# ---- 2. the reply box ----------------------------------------------------------------------------------------------------------------

def test_the_parties_of_a_share_get_a_reply_box_and_see_the_replies_in_the_console_zone():
    c, fake, _ = _app()
    fake.shares[SID] = share("recipient")
    html = c.get(f"/v/trade/IRS-48213?share={SID}").text
    assert "data-share-reply-form" in html and "first reply" in html and "share-reply.js" in html
    assert "2026-09-30 10:01 New York" in html                                                              # 14:01 UTC, the top bar's zone and label
    fake.shares[SID] = share("sender")
    assert "data-share-reply-form" in c.get(f"/v/trade/IRS-48213?share={SID}").text
    fake.shares[SID] = share("compliance")
    page = c.get(f"/v/trade/IRS-48213?share={SID}").text
    assert "first reply" in page and "data-share-reply-form" not in page                                    # compliance reads, never replies


def test_a_reply_goes_to_the_server_and_comes_back_with_its_local_time():
    c, fake, _ = _app()
    r = c.post(f"/api/share/{SID}/replies", json={"note": "will do"})
    assert r.status_code == 201 and fake.replies == [(SID, "will do")] and r.json()["createdAtLocal"] == "2026-09-30 10:05 New York"
    refused = c.post(f"/api/share/{SID}/replies", json={"note": "refuse"})
    assert refused.status_code == 404 and refused.json()["code"] == "DRS-7001"
    assert c.post("/api/share/not-an-id/replies", json={"note": "x"}).status_code == 404 and len(fake.replies) == 2


# ---- 3. Admin > Collaboration ---------------------------------------------------------------------------------------------------------

def test_the_admin_page_shows_the_sections_the_roles_open(monkeypatch):
    c, fake, _ = _app()
    html = c.get("/admin/collab").text                                                                      # an administrator, not compliance
    assert "data-acol-search" in html and "data-acol-retention" in html and "data-acol-bridges" in html and 'href="/admin/collab"' in html
    assert "data-acol-hold-form" not in html and "data-acol-export-form" not in html and "data-acol-verify-form" not in html
    fake.caps["compliance"] = True
    html = c.get("/admin/collab").text
    assert "data-acol-hold-form" in html and "data-acol-export-form" in html and "data-acol-verify-form" in html
    c2, fake2, _ = _app(monkeypatch, admin=False)                                                           # compliance only
    fake2.caps["compliance"] = True
    html = c2.get("/admin/collab").text
    assert "data-acol-hold-form" in html and "data-acol-retention" not in html and "data-acol-bridges" not in html
    fake2.caps["compliance"] = False
    assert c2.get("/admin/collab").status_code == 403                                                       # neither: the clean forbidden page


def test_the_admin_calls_reach_the_server_with_their_times_in_the_console_zone():
    c, _, backend = _app()
    admin = FakeAdmin()
    backend.admin = admin
    out = c.get("/admin/collab/api/threads", params={"user": "ann", "kind": "trade", "limit": 25, "bogus": "x"}).json()
    assert out["items"][0]["lastAtLocal"] == "2026-09-30 11:00 New York"
    assert admin.calls[-1] == ("GET", "/collab/threads", None, {"user": "ann", "kind": "trade", "limit": "25"})        # only the known filters
    held = c.get("/admin/collab/api/holds", params={"active": "true"}).json()
    assert held[0]["placedAtLocal"] == "2026-09-30 10:00 New York" and admin.calls[-1][3] == {"active": "true"}
    assert c.post("/admin/collab/api/holds", json={"scope": "all", "reason": "case 7", "evil": "x"}).status_code == 201
    assert admin.calls[-1][2] == {"scope": "all", "reason": "case 7"}
    assert c.delete("/admin/collab/api/holds/3").status_code == 200 and admin.calls[-1][:2] == ("DELETE", "/collab/holds/3")
    assert c.post("/admin/collab/api/retention").json()["threadsPurged"] == 2 and admin.calls[-1][3] == {"dryRun": "true"}      # always a dry run
    assert c.get("/admin/collab/api/bridges").json()["bridges"][0]["name"] == "ops-chat"
    assert c.post("/admin/collab/api/bridges/ops-chat/test").status_code == 200 and admin.calls[-1][1] == "/collab/bridges/ops-chat/test"
    assert c.post("/admin/collab/api/bridges/..%2Fx/test").status_code in (400, 404)


def test_the_export_starts_polls_and_downloads_once():
    c, _, backend = _app()
    admin = FakeAdmin()
    backend.admin = admin
    r = c.post("/admin/collab/api/exports", json={"from": "2026-09-01", "includeShares": True, "includeThreads": False, "x": 1})
    assert r.status_code == 202 and r.json()["id"] == "ex_1" and admin.calls[-1][2] == {"from": "2026-09-01", "includeShares": True, "includeThreads": False}
    assert c.get("/admin/collab/api/exports/ex_1").json()["state"] == "queued"
    seen = []

    async def send(method, path, ident, **kw):
        seen.append((method, path, kw.get("raw")))
        if len(seen) > 1:
            raise BackendError(404, "DRS-7012", "already downloaded")
        return b"PK\x03\x04zip"
    backend._send = send
    z = c.get("/admin/collab/api/exports/ex_1/download")
    assert z.status_code == 200 and z.content.startswith(b"PK") and "attachment" in z.headers["content-disposition"] and seen[0][2] is True
    again = c.get("/admin/collab/api/exports/ex_1/download")
    assert again.status_code == 404 and again.json()["code"] == "DRS-7012"
    assert c.get("/admin/collab/api/exports/bad id!/download").status_code in (400, 404)
    assert c.get("/admin/collab/api/verify", params={"thread": "../x"}).status_code == 400


def test_a_server_refusal_is_told_not_hidden():
    c, _, backend = _app()
    backend.admin = FakeAdmin(refuse={"/collab/holds": "needs the compliance power"})
    r = c.get("/admin/collab/api/holds")
    assert r.status_code == 403 and r.json() == {"code": "DRS-4003", "detail": "needs the compliance power"}


def test_the_admin_script_is_wired_and_keyboard_friendly():
    js = (JS / "admin-collab.js").read_text()
    html = _app()[0].get("/admin/collab").text
    assert "/static/js/admin-collab.js" in html and 'aria-live="polite"' in html and 'aria-label="Threads found"' in html
    assert "innerHTML" not in js and js.count("\n") < 300                                                   # textContent only, the house size rule


# ---- 4. time zones ---------------------------------------------------------------------------------------------------------------------

def test_a_time_is_the_consoles_local_time_with_the_top_bars_zone_label():
    assert asof.local_when("2026-10-05T18:30:00Z", "America/New_York") == "2026-10-05 14:30 New York"
    assert asof.local_when("2026-01-05T18:30:00Z", "Europe/London") == "2026-01-05 18:30 London"
    assert asof.local_when("2026-10-05T18:30:00.250Z", "Asia/Kolkata", "IST") == "2026-10-06 00:00 IST"
    assert asof.local_when("", "America/New_York") == "" and asof.local_when("nonsense", "America/New_York") == ""


def test_the_inbox_the_share_banner_and_comments_show_local_times():
    c, fake, _ = _app()
    assert "2026-09-30 10:02 New York" in c.get("/inbox").text                                              # 14:02 UTC in New York
    fake.shares[SID] = share()
    html = c.get(f"/v/trade/IRS-48213?share={SID}").text
    assert 'data-share-when>2026-09-30 10:00 New York<' in html and " UTC<" not in html.split("data-share-banner")[1].split("</section>")[0]
    js = (JS / "discussion-view.js").read_text()
    assert "c.createdAtLocal" in js and "c.editedAtLocal" in js
    assert "v.atLocal" in (JS / "discussion.js").read_text()
    pin = c.get("/v/trade/IRS-48213?asOf=2026-09-29&knownAt=2026-09-29T14:30:00Z&gen=1742").text
    assert "as known at" in pin and "New York</b>" in pin                                                    # the pin banner names the zone


# ---- 5. quoted values ------------------------------------------------------------------------------------------------------------------

HARNESS = r"""
global.window = global;
const cells = JSON.parse(process.argv[2]);
function node() { return { children: [], attrs: {}, className: '', textContent: '',
  appendChild(c) { this.children.push(c); return c; }, setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k]; } }; }
global.document = { createElement: node, createTextNode: (t) => ({ text: t }),
  querySelectorAll: () => cells.map((c) => ({ getAttribute: () => c.path, textContent: c.text, closest: () => null })) };
eval(require('fs').readFileSync(process.argv[1], 'utf8'));
const c = JSON.parse(process.argv[3]), ctx = JSON.parse(process.argv[4]);
const p = window.DrishtiThreadView.comment(c, {}, ctx);
const q = []; (function walk(n) { (n.children || []).forEach((k) => { if (k.className && k.className.indexOf('disc-quote') >= 0) q.push(k.textContent); walk(k); }); })(p);
console.log(JSON.stringify(q));
"""


def quoted(parts, cells, pin_gen=1742, page_gen="1742"):
    if not shutil.which("node"):
        pytest.skip("needs node")
    c = {"id": "cm_1", "revision": 1, "author": "a", "pin": {"generation": pin_gen}, "state": "live", "parts": parts}
    out = subprocess.run(["node", "-e", HARNESS, str(JS / "discussion-view.js"), json.dumps(cells), json.dumps(c), json.dumps({"pageGen": page_gen})],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def q(v, path="$.mtm"):
    return {"t": "quote", "v": v, "path": path}


def test_a_quoted_value_reads_as_the_page_shows_the_field():
    cells = [{"path": "$.mtm", "text": "1,875,863"}, {"path": "$.rate", "text": "4.25%"}]
    assert quoted([q("1875863")], cells) == ["1,875,863"]                                                   # same data: the page's own text
    assert quoted([q("1800000")], cells, pin_gen=1700) == ["1,800,000"]                                      # older data: the cell's format on the stored number
    assert quoted([q("4.1", "$.rate")], cells, pin_gen=1700) == ["4.10%"]


def test_a_masked_or_missing_value_is_left_as_the_server_sent_it():
    cells = [{"path": "$.mtm", "text": "1,875,863"}]
    assert quoted([q("•••")], cells) == ["•••"]                                # no raw: never replaced by anything
    assert quoted([q("—")], cells) == ["—"]
    assert quoted([q("42", "$.unshown")], cells) == ["42"]                                                  # a field the page does not show: as stored
    assert quoted([q("1875863")], [{"path": "$.mtm", "text": "•••"}]) == ["1875863"]
