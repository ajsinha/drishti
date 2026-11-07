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


"""W19: Studio submits for review; reviewers see the diff and decide; authors withdraw."""


def _proposal(**over):
    p = {"id": "P-000007", "name": "irs-vanilla", "version": 3, "note": "clearer title", "author": "ana", "createdAt": "2026-09-30T14:02:11Z",
         "status": "pending", "reviewer": None, "reviewedAt": None, "comment": None, "newVersion": False, "stale": False,
         "mayApprove": True, "mayWithdraw": False, "text": "rachana: 1\nsutra: irs-vanilla\nversion: 3\ndescription: new\n",
         "baseText": "", "liveText": "rachana: 1\nsutra: irs-vanilla\nversion: 3\ndescription: old\n"}
    p.update(over)
    return p


def test_studio_submits_for_review_when_review_is_on(client, backend):
    async def settings(ident=None):
        return {"save": True, "review": True, "approve": False}

    saved = {}

    async def save_sutra(text, ident=None, note=""):
        saved.update(text=text, note=note)
        return {"proposal": {"id": "P-000008", "name": "irs-vanilla", "version": 3, "status": "pending"}}
    backend.studio_settings, backend.save_sutra = settings, save_sutra
    d = client.post("/build/designs", json={"name": "Gov", "kind": "trade", "sutra": "x"}).json()
    page = client.get(f"/build/d/{d['id']}").text
    assert "Submit for review" in page and "data-note" in page and 'href="/approvals?kind=sutra"' in page       # decided in the one Approvals queue
    r = client.post("/studio/save", json={"yaml": "x", "note": "why"})
    assert r.json()["proposal"]["id"] == "P-000008" and saved == {"text": "x", "note": "why"}
    saved.clear()
    r = client.post(f"/build/designs/{d['id']}/propose", json={"note": "from the workbench"})        # the design's own Sutra, not text sent along
    assert r.status_code == 200 and r.json()["proposal"]["id"] == "P-000008" and saved == {"text": "x", "note": "from the workbench"}
    assert "r.body.proposal" in client.get("/static/js/build/drishti_saving.js").text
