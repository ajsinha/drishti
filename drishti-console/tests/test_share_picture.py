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

"""The share dialog's "Include a picture" option and the shared view's picture (COLLABORATION.md, step 10): the option exists only where the server
says a picture may be offered for the kind, the preview and the picture are passed through as PNGs (never cached), the server's refusal is told in
words, and the banner of a share that carries one shows it."""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402

JS = CONSOLE / "web" / "static" / "js"
SID = "sh_01J9ABCDEFGHJKMNPQRSTVWXYZ"
PNG = b"\x89PNG\r\n\x1a\n" + b"x" * 20


class FakeCollab:
    def __init__(self, snapshots=True):
        self.asked, self.previews = [], []
        self.caps = {"enabled": True, "collaborate": True, "maxText": 2000, "minQuery": 2, "postToThread": False, "compliance": False, "snapshots": snapshots}
        self.refuse = None

    async def config(self, ident, kind=""):
        self.asked.append(kind)
        return self.caps

    async def preview_picture(self, body, ident):
        if self.refuse:
            raise BackendError(403, "DRS-7015", self.refuse)
        self.previews.append(body)
        return PNG

    async def picture(self, share_id, ident):
        return PNG

    async def share(self, share_id, ident):
        return {"id": SID, "access": True, "role": "recipient", "sender": "ann", "senderName": "Ann Author", "createdAt": "2026-09-30T14:00:00Z",
                "kind": "trade", "entityId": "IRS-48213", "pin": {"live": True, "generation": 1742}, "note": "Look", "picture": True, "replies": []}


def _app():
    fake = FakeCollab()
    app = create_app(load_settings(CONSOLE / "config"))
    from conftest import FakeBackend
    app.state.backend = FakeBackend()
    app.state.collab = fake
    return TestClient(app), fake


def test_the_dialog_has_the_picture_option_hidden_until_the_server_allows_it():
    c, _ = _app()
    html = c.get("/v/trade/IRS-48213").text
    assert "data-shr-picture-row hidden" in html and "Include a picture" in html and "data-shr-preview-img" in html
    js = (JS / "share.js").read_text()
    assert "picRow.hidden = !(conf && conf.snapshots)" in js                       # shown only when the server says so for this kind
    assert "picture: !!(picBox.checked && conf && conf.snapshots)" in js          # and sent only when ticked and allowed
    assert "'?kind=' + encodeURIComponent" in js                                  # the server is asked about this view's kind


def test_the_kind_travels_to_the_server_and_the_answer_comes_back():
    c, fake = _app()
    assert c.get("/api/collab?kind=trade").json()["snapshots"] is True
    assert fake.asked[-1] == "trade"
    fake.caps["snapshots"] = False
    assert c.get("/api/collab?kind=trade").json()["snapshots"] is False


def test_the_preview_is_a_png_that_is_never_cached_and_carries_the_pin():
    c, fake = _app()
    r = c.post("/api/share/preview-picture", json={"kind": "trade", "id": "IRS-48213", "to": {"users": ["ravi"], "roles": []}, "asOf": "live", "picture": True})
    assert r.status_code == 200 and r.headers["content-type"] == "image/png" and r.content == PNG
    assert r.headers["cache-control"] == "no-store" and "asOf" not in fake.previews[-1]


def test_a_refused_preview_is_told_in_words():
    c, fake = _app()
    fake.refuse = "pictures are switched off for trade views"
    r = c.post("/api/share/preview-picture", json={"kind": "trade", "id": "IRS-48213", "to": {"users": ["ravi"], "roles": []}})
    assert r.status_code == 403 and r.json()["code"] == "DRS-7015" and "switched off" in r.json()["detail"]


def test_the_shared_banner_shows_the_picture_and_the_proxy_rejects_a_bad_id():
    c, _ = _app()
    html = c.get(f"/v/trade/IRS-48213?share={SID}").text
    assert "data-share-picture" in html and f'/api/share/{SID}/picture' in html
    r = c.get(f"/api/share/{SID}/picture")
    assert r.status_code == 200 and r.headers["content-type"] == "image/png" and r.headers["cache-control"] == "no-store"
    assert c.get("/api/share/not-a-share/picture").status_code == 404
