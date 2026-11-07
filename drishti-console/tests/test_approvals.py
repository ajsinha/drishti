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

"""The one Approvals queue in the console (routes/approval_routes.py): the page shell, the JSON it reads and writes passed to the server as
the signed-in person, the old per-kind review pages redirected here, and the menus and top bar pointing here."""


def _items(backend):
    backend.approvals_kept = {
        ("sutra", "P-000007"): {"kind": "sutra", "id": "P-000007", "group": "screens", "title": "irs-vanilla v3 (change)", "maker": "ana", "at": "2026-11-07T09:00:00Z",
                                "status": "pending", "reason": "clearer title", "due": None, "approvers": ["*"], "link": "/build/design"},
        ("var-report", "annual-report-var:FY2026:v1"): {"kind": "var-report", "id": "annual-report-var:FY2026:v1", "group": "risk", "title": "Annual VaR FY2026 v1",
                                                        "maker": "rao", "at": "2026-11-06T09:00:00Z", "status": "pending", "reason": "", "due": "2026-11-09",
                                                        "approvers": ["*"], "link": "/var/reports/annual-report-var?period=FY2026", "stage": "with checker"},
        ("change", "chg-1"): {"kind": "change", "id": "chg-1", "group": "data", "title": "trade MX-1: 1 field(s)", "maker": "@me", "at": "2026-11-07T08:00:00Z",
                              "status": "pending", "reason": "typo", "due": None, "approvers": ["kim"], "link": "/v/trade/MX-1"},
    }
    backend.approval_questions = {}


def test_the_page_is_a_shell_the_script_fills(client):
    page = client.get("/approvals?box=sent&group=risk").text
    assert 'data-approvals' in page and 'data-box="sent"' in page and 'data-group="risk"' in page
    assert "/static/js/drishti_approvals.js" in page and "/static/css/drishti_approvals.css" in page
    for hook in ("data-ap-box=\"inbox\"", "data-ap-group=\"access\"", "data-ap-kind", "data-ap-act=\"approve\"", "data-ap-act=\"reject\"",
                 "data-ap-act=\"withdraw\"", "data-ap-ask", "data-ap-diff", "data-ap-effect", "data-ap-rules", "data-ap-thread"):
        assert hook in page, hook
    one = client.get("/approvals/sutra/P-000007").text
    assert 'data-item-kind="sutra"' in one and 'data-item-id="P-000007"' in one
    assert client.get("/approvals/sutra/bad id", follow_redirects=False).status_code == 303
    assert 'data-box="inbox"' in client.get("/approvals?box=everything").text          # an unknown box is the inbox


def test_the_queue_is_read_and_decided_through_the_console(client, backend):
    _items(backend)
    lst = client.get("/approvals/api/list").json()
    assert lst["inbox"] == 2 and lst["dueSoon"] == 1
    assert [e["item"]["id"] for e in lst["items"]][0] == "annual-report-var:FY2026:v1"         # due soon first
    assert [e["item"]["id"] for e in client.get("/approvals/api/list?box=sent").json()["items"]] == ["chg-1"]
    assert client.get("/approvals/api/list?box=nope").status_code == 400
    assert client.get("/approvals/api/count").json() == {"inbox": 2, "dueSoon": 1}
    item = client.get("/approvals/api/item/sutra/P-000007").json()
    assert item["actions"] == ["approve", "reject", "ask"] and item["detail"]["effect"]
    assert client.get("/approvals/api/item/sutra/P-404").status_code == 404
    asked = client.post("/approvals/api/item/sutra/P-000007/questions", json={"text": "Why the new title?"}).json()
    assert asked[0]["text"] == "Why the new title?"
    assert client.post("/approvals/api/item/sutra/P-000007/reject", json={"comment": ""}).status_code == 400
    done = client.post("/approvals/api/item/sutra/P-000007/approve", json={"comment": "fine"}).json()
    assert done["status"] == "approved"
    assert client.post("/approvals/api/item/change/chg-1/approve", json={}).status_code == 403    # never your own
    assert client.post("/approvals/api/item/sutra/P-000007/delete", json={}).status_code == 400
    assert client.post("/approvals/api/item/Bad Kind/x/approve", json={}).status_code in (400, 404)


def test_the_old_review_pages_redirect_here(client):
    for old, new in (("/build/reviews", "/approvals?kind=sutra"), ("/build/reviews/P-000042", "/approvals/sutra/P-000042"),
                     ("/review/changes", "/approvals?kind=change")):
        r = client.get(old, follow_redirects=False)
        assert r.status_code == 301 and r.headers["location"] == new, old


def test_the_menu_and_the_top_bar_point_here(client):
    page = client.get("/approvals").text
    assert 'href="/approvals"' in page and "data-approvals-count" in page and "data-approvals-top" in page
    assert 'href="/build/reviews"' not in page and 'href="/review/changes"' not in page
    js = client.get("/static/js/drishti_app.js").text
    assert "/approvals/api/count" in js and "/build/review-count" not in js
