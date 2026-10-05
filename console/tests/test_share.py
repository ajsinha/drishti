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

"""Share with a note, the console (COLLABORATION.md, build step 3): the dialog's calls, the link a share opens, the clean no-access page,
the as-shared banner and the header that tells the server a view came through a share."""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core import collab as collab_core  # noqa: E402
from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

SID = "sh_01J9ABCDEFGHJKMNPQRSTVWXYZ"


class FakeCollab:
    """Stands in for the server's collaboration API."""

    def __init__(self):
        self.sent, self.calls, self.shares, self.rows, self.read = [], [], {}, [], []
        self.config_answer = {"enabled": True, "collaborate": True, "maxText": 2000, "minQuery": 2}
        self.seen_asof = None

    async def config(self, ident):
        return self.config_answer

    async def directory(self, q, ident, limit=10, kind=""):
        self.calls.append(("directory", q, kind))
        people = [{"type": "user", "name": "ravi", "displayName": "Ravi Kumar", "desk": "Rates", "email": True},
                  {"type": "role", "name": "risk", "size": 7}]
        return [p for p in people if q.lower() in p["name"] or q.lower() in (p.get("displayName") or "").lower()]

    async def send(self, body, ident):
        from core import asof
        self.seen_asof = (asof.current(), asof.known_at())
        self.sent.append(body)
        if not body["to"]["users"] and not body["to"]["roles"]:
            raise BackendError(422, "DRS-7003", "no recipients")
        return {"id": SID, "link": "http://x/share/" + SID, "delivered": len(body["to"]["users"]), "skipped": [], "warnings": []}

    async def share(self, share_id, ident):
        if share_id not in self.shares:
            raise BackendError(404, "DRS-7001", "no share")
        return self.shares[share_id]


def _app():
    fake = FakeCollab()
    app = create_app(load_settings(CONSOLE / "config"))
    from conftest import FakeBackend
    app.state.backend = FakeBackend()
    app.state.collab = fake
    return TestClient(app), fake, app.state.backend


def _share(**over):
    s = {"id": SID, "access": True, "sender": "ashutosh", "senderName": "Ashutosh Sinha", "createdAt": "2026-09-30T14:02:11Z", "kind": "trade",
         "entityId": "IRS-48213", "panel": None, "role": "recipient", "note": "MTM moved after the fixing: can you confirm?",
         "pin": {"businessDate": "2026-09-29", "live": False, "knownAt": "2026-09-29T14:30:00.250Z", "generation": 1742, "source": "aero-risk"}}
    s.update(over)
    return s


def test_the_link_of_a_share_opens_the_view_as_it_was_pinned():
    c, fake, _ = _app()
    fake.shares[SID] = _share()
    r = c.get(f"/share/{SID}", follow_redirects=False)
    assert r.status_code == 303
    loc = r.headers["location"]
    assert loc.startswith("/v/trade/IRS-48213?asOf=2026-09-29&knownAt=2026-09-29T14%3A30%3A00Z&gen=1742&share=" + SID)   # whole seconds only


def test_a_live_share_and_a_panel_share_link():
    c, fake, _ = _app()
    fake.shares[SID] = _share(panel="cashflows", pin={"live": True, "generation": 1742})
    loc = c.get(f"/share/{SID}", follow_redirects=False).headers["location"]
    assert loc == f"/v/trade/IRS-48213?gen=1742&share={SID}#p-cashflows"          # no asOf: the reader's own live view, the panel scrolled to


def test_a_recipient_without_the_right_gets_the_clean_page():
    c, fake, _ = _app()
    fake.shares[SID] = {"id": SID, "access": False, "reason": "no-access", "pack": "finance", "role": "recipient", "sender": "ashutosh",
                        "senderName": "Ashutosh Sinha", "createdAt": "2026-09-30T14:02:11Z", "kind": "trade"}
    r = c.get(f"/share/{SID}", follow_redirects=False)
    assert r.status_code == 403
    html = r.text
    assert "A shared view you cannot open" in html
    assert "Ashutosh Sinha shared" in html and "Your roles do not open trade views" in html and "this notice stays in your inbox" in html
    assert "IRS-48213" not in html and "MTM" not in html and "data-switch-on" not in html      # nothing about what it holds


def test_a_pack_switched_off_offers_to_switch_it_on():
    c, fake, _ = _app()
    fake.shares[SID] = {"id": SID, "access": False, "reason": "pack-off", "pack": "finance", "sender": "a", "senderName": "A. Sender", "kind": "trade",
                        "createdAt": "2026-09-30T14:02:11Z", "role": "recipient"}
    r = c.get(f"/share/{SID}", follow_redirects=False)
    assert 'data-switch-on="finance"' in r.text and "Switch on" in r.text


def test_a_stranger_and_a_malformed_id_learn_nothing():
    c, fake, _ = _app()
    for sid in ("sh_NOSUCHSHARE000000000000000", "nonsense", "sh_%3Cscript%3E"):
        r = c.get(f"/share/{sid}", follow_redirects=False)
        assert r.status_code == 404 and "does not open anything for you" in r.text
        page = r.text.split("data-share-denied", 1)[1].split("</main>", 1)[0]
        assert "Ashutosh" not in page and "with you" not in page


def test_the_share_page_needs_sign_in():
    from conftest import FakeBackend
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60, "session_hours": 1, "secure_cookie": False}
    from core.config import Settings
    app = create_app(Settings(data))
    app.state.backend = FakeBackend()
    c = TestClient(app)
    for path in (f"/share/{SID}", "/inbox"):
        r = c.get(path, follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"].startswith("/login?next=" + path[:6])


def test_the_view_shows_who_shared_it_and_their_note_and_tells_the_server():
    c, fake, backend = _app()
    fake.shares[SID] = _share()
    seen = []
    orig = backend.view

    async def view(kind, id_, user):
        seen.append(collab_core.headers())            # what every call to the server carries while this view is built
        return await orig(kind, id_, user)

    backend.view = view
    html = c.get(f"/v/trade/IRS-48213?asOf=2026-09-29&gen=1742&share={SID}").text
    assert 'data-share-banner="' + SID + '"' in html and "Shared by Ashutosh Sinha" in html
    assert "MTM moved after the fixing: can you confirm?" in html
    assert seen == [{"X-Drishti-Share": SID}]
    c.get("/v/trade/IRS-48213")                       # an ordinary view carries nothing
    assert seen[-1] == {} and "data-share-banner" not in c.get("/v/trade/IRS-48213").text


def test_a_bad_share_id_in_a_view_link_sends_no_header_and_no_banner():
    c, fake, backend = _app()
    html = c.get("/v/trade/IRS-48213?share=evil%0d%0aX-Injected:1").text
    assert "data-share-banner" not in html and collab_core.headers() == {}


def test_a_note_with_markup_is_escaped_in_the_banner():
    c, fake, _ = _app()
    fake.shares[SID] = _share(note="<script>alert(1)</script> and <b>bold</b>")
    html = c.get(f"/v/trade/IRS-48213?share={SID}").text
    assert "<script>alert(1)</script>" not in html and "&lt;script&gt;" in html


def test_the_share_button_carries_what_the_dialog_needs():
    c, fake, _ = _app()
    html = c.get("/v/trade/IRS-48213?asOf=2026-09-29&knownAt=2026-09-29T14:30:00Z").text
    assert 'data-share-asof="2026-09-29"' in html and 'data-share-known="2026-09-29T14:30:00Z"' in html and 'aria-keyshortcuts="Alt+S"' in html
    assert 'data-share-dialog' in html and 'role="dialog" aria-modal="true"' in html and 'role="combobox"' in html
    assert "/static/js/people.js" in html and "/static/js/share.js" in html
    assert "data-share-dialog" not in c.get("/v/trade/IRS-48213?embed=1").text           # a workspace pane has no dialog (one dialog, in the parent)


def test_directory_and_config_calls_are_carried():
    c, fake, _ = _app()
    assert c.get("/api/collab").json()["collaborate"] is True
    found = c.get("/api/directory?q=ra&kind=trade").json()
    assert [f["name"] for f in found] == ["ravi"] and fake.calls[-1] == ("directory", "ra", "trade")
    fake.config_answer = {"enabled": False, "collaborate": False}
    assert c.get("/api/collab").json()["enabled"] is False


def test_sending_carries_the_pin_the_sender_sees_and_reports_problems():
    c, fake, _ = _app()
    body = {"kind": "trade", "id": "IRS-48213", "note": "look", "to": {"users": ["ravi"], "roles": []}, "channels": {"inApp": True},
            "asOf": "2026-09-29", "knownAt": "2026-09-29T14:30:00Z", "generation": 1742}
    r = c.post("/api/share", json=body)
    assert r.status_code == 201 and r.json()["delivered"] == 1
    assert fake.seen_asof == ("2026-09-29", "2026-09-29T14:30:00Z")            # the page's own date, not a cookie
    assert "asOf" not in fake.sent[-1] and "knownAt" not in fake.sent[-1] and fake.sent[-1]["to"]["users"] == ["ravi"]
    bad = c.post("/api/share", json={**body, "to": {"users": [], "roles": []}})
    assert bad.status_code == 422 and bad.json()["code"] == "DRS-7003"


def test_a_live_page_sends_no_date():
    c, fake, _ = _app()
    c.post("/api/share", json={"kind": "trade", "id": "X", "note": "", "to": {"users": ["ravi"], "roles": []}, "asOf": "live", "knownAt": "2026-09-29T14:30:00Z"})
    assert fake.seen_asof == ("live", None)


def test_the_share_id_is_clean_or_nothing():
    assert collab_core.clean_share(SID) == SID
    for bad in (None, "", "sh_", "abc", SID + "\r\nX: y", "sh_a b", "../etc"):
        assert collab_core.clean_share(bad) is None
