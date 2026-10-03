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

"""The workbench's page and the review page (QA 2026-10-01 UX-15, ported from Studio's): a heading, a labelled editor, and an
approved proposal's review page that still shows what it changed."""
from pathlib import Path

JS = (Path(__file__).resolve().parents[1] / "web" / "static" / "js" / "build" / "yamltab.js").read_text()

OLD = "rachana: 1\nsutra: s-test\nversion: 3\nmatch: { kind: trade }\npanels:\n  - id: legs\n    kind: kv\n"


def test_the_workbench_has_one_h1(client):
    d = client.post("/build/designs", json={"name": "H1", "kind": "trade", "empty": True}).json()
    html = client.get(f"/build/d/{d['id']}").text
    assert html.count("<h1") == 1


def test_the_code_editors_own_input_is_labelled():
    assert "getInputField().setAttribute('aria-label'" in JS


def _proposal(status, base="", live=None):
    new = OLD.replace("version: 3", "version: 4") if not base else OLD.replace("kind: kv", "kind: table")
    return {"id": "P-000042", "name": "s-test", "version": 4 if not base else 3, "note": "", "author": "ana", "createdAt": "2026-10-01T09:00:00Z",
            "status": status, "newVersion": not base, "stale": False, "mayApprove": False, "mayWithdraw": False,
            "text": new, "baseText": base, "liveText": new if status == "approved" else "", "previousText": "" if status == "approved" else OLD}


def test_an_approved_new_version_still_shows_its_diff(client, backend, monkeypatch):
    async def proposal(id_, ident=None):
        return _proposal("approved")

    async def sutras(ident=None):
        return [{"name": "s-test", "latest": 4, "versions": [3, 4]}]

    async def sutra_source(name, version, ident=None):
        return OLD if version == 3 else ""
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    monkeypatch.setattr(backend, "sutras", sutras)
    monkeypatch.setattr(backend, "sutra_source", sutra_source)
    html = client.get("/build/reviews/P-000042").text
    assert "No differences" not in html and '+version: 4' in html


def test_an_approved_change_to_a_live_version_is_shown_against_what_it_was_proposed_on(client, backend, monkeypatch):
    async def proposal(id_, ident=None):
        return _proposal("approved", base=OLD)
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    html = client.get("/build/reviews/P-000042").text
    assert "No differences" not in html and "kind: table" in html
