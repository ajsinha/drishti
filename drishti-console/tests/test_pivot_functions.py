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

"""The pivot screen's Why, What-if and Functions cards (pivot-functions.js; docs/guides/PIVOT_GUIDE.md sections 19 to 21): the cards and
their script on the page, and every call passed to the server as the person through the same-origin proxy (explain, what-if, the
functions' list, test, a version, approve and withdraw), the server's refusals passed on in words. The figures, the harness and the
approval rules are the server's and the engine's tests (CustomFunctionsTest, ExplainAddsUpTest, PivotFunctionsApiTest)."""
from __future__ import annotations

from core.backend import BackendError
from test_play import RISK_ABOUT, _app


def test_the_cards_and_their_calls_are_the_persons(backend, monkeypatch):
    sent: list = []

    async def pivot(verb, path, ident, body=None, raw=False, **params):
        sent.append((verb, path, getattr(ident, "user", None), body, dict(params)))
        if path == "/functions/asha/wret@1/approve":
            raise BackendError(403, "DRS-5712", "an author never approves their own function (fn:asha/wret@1): another approver must")
        return {"ok": True}

    monkeypatch.setattr(backend, "pivot", pivot, raising=False)
    app, c = _app(backend, monkeypatch, RISK_ABOUT)
    page = c.get("/pivot?source=trade").text
    assert "pivot-functions.js" in page
    for marker in ('data-pf-open="explain"', 'data-pf-open="whatif"', 'data-pf-open="functions"', 'data-pf-card="explain"',
                   "data-pf-waterfall", "data-pf-by", "data-pf-exclude", "data-pf-change", "data-pf-code", "data-pf-sample", "data-pf-check",
                   "data-pf-save"):
        assert marker in page, marker
    assert c.post("/api/pivot/explain", json={"view": {"source": "trade"}, "by": "DESK"}).status_code == 200
    assert sent[-1][:4] == ("POST", "/explain", "alice", {"view": {"source": "trade"}, "by": "DESK"})
    assert c.post("/api/pivot/whatif", json={"view": {}, "whatIf": {"exclude": {"BOOK": ["B-17"]}}}).status_code == 200
    assert sent[-1][1] == "/whatif"
    assert c.get("/api/pivot/functions").status_code == 200 and sent[-1][:2] == ("GET", "/functions")
    assert c.post("/api/pivot/functions", json={"name": "wret"}).status_code == 200 and sent[-1][:2] == ("POST", "/functions")
    assert c.post("/api/pivot/functions/test", json={"name": "wret"}).status_code == 200 and sent[-1][1] == "/functions/test"
    c.get("/api/pivot/functions/asha/wret", params={"version": "2"})
    assert sent[-1][1] == "/functions/asha/wret" and sent[-1][4] == {"version": "2"}
    no = c.post("/api/pivot/functions/asha/wret@1/approve", json={"reason": "mine", "scope": "everyone"})
    assert no.status_code == 403 and no.json()["code"] == "DRS-5712"
    assert c.post("/api/pivot/functions/asha/wret@1/revoke", json={"reason": "x"}).status_code == 200
    assert c.post("/api/pivot/functions/asha/wret@1/delete", json={}).status_code == 404
