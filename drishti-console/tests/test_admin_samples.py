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

"""Admin → Packs: sample packs (badge, "Hide samples from business users", the saved setting) and live pack reload (a pack's problem is
shown; Load and Unload take effect with no restart). The server decides who sees what; the console forwards and shows."""
from core.backend import BackendError
from test_admin_packs import with_admin

TRADING = {"name": "trading", "title": "Trading", "description": "", "version": "1.1.0", "loaded": True, "added": False, "enabled": True, "sample": False,
           "extends": [], "requiredBy": [], "kinds": ["trade"], "connectors": [], "mnemonics": ["TRD"]}
GENOMICS = {"name": "genomics", "title": "Genomics", "description": "", "version": "1.0.0", "loaded": True, "added": True, "enabled": True, "sample": True,
            "extends": [], "requiredBy": [], "kinds": ["gene"], "connectors": [], "mnemonics": ["GENE"],
            "problem": "pack.yaml is not valid YAML (the last good version keeps running)"}


def serving(mode="visible", overridden=False):
    def extra(method, path, body, params):
        if path == "/packs" and method == "GET":
            return [TRADING, GENOMICS]
        if path == "/packs/samples" and method == "GET":
            return {"mode": mode, "configured": "visible", "overridden": overridden, "modes": ["visible", "developers", "hidden"], "samples": ["genomics"]}
        return None
    return extra


def test_sample_packs_are_badged_and_the_page_offers_to_hide_them_from_business_users(client, backend, monkeypatch):
    with_admin(backend, monkeypatch, [], serving())
    page = client.get("/admin/packs").text
    assert page.count("data-sample-badge") == 1                                                 # only genomics is a sample
    for text in ("Sample packs", "visible to everyone", "Hide samples from business users", 'data-samples-set="developers"', 'data-samples-set="hidden"',
                 "pack.yaml is not valid YAML", "with no restart"):
        assert text in page, text
    assert 'data-samples-set="visible"' not in page                                             # already visible: no button for the current mode
    with_admin(backend, monkeypatch, [], serving("developers", True))
    page = client.get("/admin/packs").text
    assert "developers only" in page and "saved here" in page and 'data-samples-set="default"' in page and 'data-samples-set="visible"' in page


def test_no_samples_section_when_no_pack_is_a_sample(client, backend, monkeypatch):
    with_admin(backend, monkeypatch, [])
    assert "data-samples" not in client.get("/admin/packs").text


def test_the_samples_setting_is_forwarded_to_the_server(client, backend, monkeypatch):
    calls = []
    with_admin(backend, monkeypatch, calls, lambda m, p, b, q: {"mode": b["mode"], "samples": ["genomics"]} if (m, p) == ("PUT", "/packs/samples") else None)
    r = client.post("/admin/api/pack-samples", json={"mode": "developers"})
    assert r.status_code == 200 and r.json()["mode"] == "developers"
    assert [(m, p, b) for m, p, b, q in calls if p == "/packs/samples"] == [("PUT", "/packs/samples", {"mode": "developers"})]


def test_a_refused_samples_setting_reaches_the_page_as_a_problem(client, backend, monkeypatch):
    async def refuse(method, path, ident, body=None, timeout=None, **params):
        raise BackendError(400, "DRS-5001", "mode is one of [visible, developers, hidden] or default, not 'sometimes'")
    monkeypatch.setattr(backend, "admin", refuse)
    r = client.post("/admin/api/pack-samples", json={"mode": "sometimes"})
    assert r.status_code == 400 and "mode is one of" in r.json()["detail"]


def test_load_and_unload_are_forwarded_and_report_no_restart(client, backend, monkeypatch):
    calls = []
    note = {"name": "genomics", "restarting": False, "loaded": ["genomics"], "problems": {}, "note": "Applied now; no restart."}
    with_admin(backend, monkeypatch, calls, lambda m, p, b, q: note if m == "POST" and p.startswith("/packs/genomics/") else None)
    for action in ("load", "unload"):
        r = client.post(f"/admin/api/packs/genomics/{action}")
        assert r.status_code == 200 and r.json()["restarting"] is False and "no restart" in r.json()["note"]
    assert [(m, p) for m, p, b, q in calls if "genomics" in p] == [("POST", "/packs/genomics/load"), ("POST", "/packs/genomics/unload")]
