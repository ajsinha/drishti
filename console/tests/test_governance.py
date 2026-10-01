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

    async def proposals(ident=None, status="", name=""):
        return {"enabled": True, "proposals": [_proposal()]}
    saved = {}

    async def save_sutra(text, ident=None, note=""):
        saved.update(text=text, note=note)
        return {"proposal": {"id": "P-000008", "name": "irs-vanilla", "version": 3, "status": "pending"}}
    backend.studio_settings, backend.proposals, backend.save_sutra = settings, proposals, save_sutra
    page = client.get("/studio").text
    assert "Submit for review" in page and "data-note" in page and 'href="/studio/reviews"' in page and '<span class="bell-count">1</span>' in page
    r = client.post("/studio/save", json={"yaml": "x", "note": "why"})
    assert r.json()["proposal"]["id"] == "P-000008" and saved == {"text": "x", "note": "why"}
    assert "res.body.proposal" in client.get("/static/js/studio.js").text


def test_reviews_list_diff_and_decisions(client, backend):
    async def proposals(ident=None, status="", name=""):
        return {"enabled": True, "proposals": [_proposal(), _proposal(id="P-000006", status="approved", reviewer="rui")]}

    async def proposal(id_, ident=None):
        return _proposal(id=id_)
    decided = {}

    async def decide(id_, action, ident=None, comment=""):
        decided.update(id=id_, action=action, comment=comment)
        return _proposal(id=id_, status="approved")
    backend.proposals, backend.proposal, backend.decide = proposals, proposal, decide
    page = client.get("/studio/reviews").text
    assert "P-000007" in page and "clearer title" in page and "Waiting" in page
    review = client.get("/studio/reviews/P-000007").text
    assert '<span class="d-del">-description: old</span>' in review and '<span class="d-add">+description: new</span>' in review
    assert "Approve and publish" in review and "Reject" in review
    r = client.post("/studio/reviews/P-000007/approve", data={"comment": "ok"}, follow_redirects=False)
    assert r.status_code == 303 and decided == {"id": "P-000007", "action": "approve", "comment": "ok"}
    assert client.post("/studio/reviews/P-000007/delete", follow_redirects=False).headers["location"] == "/studio/reviews/P-000007"


def test_a_refused_decision_says_why(client, backend):
    from core.backend import BackendError

    async def proposal(id_, ident=None):
        return _proposal(id=id_, stale=True, mayApprove=False)

    async def decide(id_, action, ident=None, comment=""):
        raise BackendError(409, "DRS-2006", "irs-vanilla@3 changed after P-000007 was proposed")
    backend.proposal, backend.decide = proposal, decide
    page = client.post("/studio/reviews/P-000007/approve", data={"comment": ""}).text
    assert "DRS-2006" in page and "changed after" in page and "cannot be approved" in page
