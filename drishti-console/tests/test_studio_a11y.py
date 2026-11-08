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

JS = (Path(__file__).resolve().parents[1] / "web" / "static" / "elements" / "drishti-inputs" / "src" / "drishti_code-editor.js").read_text()   # the YAML tab is drishti-inputs' CodeEditor

OLD = "rachana: 1\nsutra: s-test\nversion: 3\nmatch: { kind: trade }\npanels:\n  - id: legs\n    kind: kv\n"


def test_the_workbench_has_one_h1(client):
    d = client.post("/build/designs", json={"name": "H1", "kind": "trade", "empty": True}).json()
    html = client.get(f"/build/d/{d['id']}").text
    assert html.count("<h1") == 1


def test_the_code_editors_own_input_is_labelled():
    assert "cm.getInputField()" in JS and "ta.setAttribute('aria-label'" in JS


def test_an_approved_new_version_still_shows_its_diff(client, backend, monkeypatch):
    html = _sutra_view(client, backend, monkeypatch, OLD, OLD.replace("version: 3", "version: 4"))   # the latest earlier version, as the server answers
    assert "No differences" not in html and '+version: 4' in html


def test_an_approved_change_to_a_live_version_is_shown_against_what_it_was_proposed_on(client, backend, monkeypatch):
    html = _sutra_view(client, backend, monkeypatch, OLD, OLD.replace("kind: kv", "kind: table"), new_version=False)   # the text it was proposed on
    assert "No differences" not in html and "kind: table" in html


def _sutra_view(client, backend, monkeypatch, before, after, evidence=None, new_version=True):
    """The Approvals queue's Sutra view (the diff with its moves, the evidence) of a proposal whose texts the server gives as facts."""
    async def approvals_item(kind, id_, ident=None):
        return {"detail": {"item": {"kind": kind, "id": id_}, "facts": {"before": before, "after": after, "evidence": evidence, "newVersion": new_version}},
                "actions": ["ask"]}
    monkeypatch.setattr(backend, "approvals_item", approvals_item, raising=False)
    return client.get("/approvals/api/view/sutra/P-000042").text
