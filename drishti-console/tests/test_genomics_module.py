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

"""The genomics sample module's console part (DISTRIBUTIONS.md section 4; modules/genomics, the SDK's layout): hosted from its folder
folder, its one page folder drawn with the Elements macros only, its pages reading the module's API through the console, and its own
visual type, genomics.lollipop (the custom-visual SDK): listed for the Elements gallery while the module is hosted, gone when it is
removed. Drishti Genomics' file compiles to the Research lens with no finance command."""
from __future__ import annotations

from pathlib import Path

import server_answers

CONSOLE = Path(__file__).resolve().parents[1]
MODULE = CONSOLE.parent / "modules" / "genomics" / "src" / "dmod"     # the module project's .dmod folder, as the server hands it over
SHIPPED = {"modules": [{"id": "genomics", "version": "0.1.0", "state": "active", "title": "Genomics", "console": True, "claims": []}],
           "fingerprint": "shipped"}


def _fresh(client, tmp_path, monkeypatch, listed: dict | None):
    from core.module_parts import ModuleParts

    old = client.app.state.module_parts
    parts = ModuleParts(tmp_path / "parts", old.web, 0.0)
    monkeypatch.setattr(client.app.state, "module_parts", parts)
    monkeypatch.setattr(client.app.state.editions.modules, "parts", parts)
    backend = client.app.state.backend

    async def public(path, raw=False, timeout=30.0):        # the server lists the shipped module while it is loaded, and hands it over
        if path == "/public/modules/console":
            return listed if listed is not None else SHIPPED
        if path == "/public/modules/console/genomics/0.1.0" and listed is None:
            return server_answers.module_zip(MODULE)
        from core.backend import BackendError
        raise BackendError(404, "DRS-4040", path)
    monkeypatch.setattr(backend, "public", public, raising=False)
    return parts


def test_the_module_is_hosted_its_pages_use_elements_only_and_read_its_api(client, tmp_path, monkeypatch):
    _fresh(client, tmp_path, monkeypatch, None)
    client.get("/t")
    p = client.app.state.module_parts.part("genomics")
    assert p is not None and p.source == "server" and p.folder.name == "0.1.0"
    page = client.get("/m/genomics/genes/TP53")
    assert page.status_code == 200, page.text[:400]
    t = page.text
    assert 'data-genomics-route="gene"' in t and 'data-genomics-id="TP53"' in t and 'data-genomics-api="/m/genomics/api/"' in t
    assert "/m/genomics/elements/drishti-genomics/0.1.0/drishti_genomics.js" in t
    html = (p.folder / "console" / "pages" / "explorer.html").read_text(encoding="utf-8")
    assert "<table" not in html and "<canvas" not in html and "<svg" not in html and "elements.grid(" in html
    go = client.get("/go?q=GENE%20TP53%20%3CGO%3E", follow_redirects=False)
    assert go.headers["location"] == "/m/genomics/genes/TP53"
    asked = []

    async def send(method, path, ident=None, **kw):
        asked.append((method, path, kw.get("params")))
        return {"GENE": "TP53", "variants": []}
    monkeypatch.setattr(client.app.state.backend, "_send", send)
    api = client.get("/m/genomics/api/genes/TP53?x=1")
    assert api.status_code == 200 and api.json()["GENE"] == "TP53"
    assert asked == [("GET", "/genomics/genes/TP53", {"x": "1"})]


def test_its_visual_is_in_the_gallery_while_hosted_and_gone_when_removed(client, tmp_path, monkeypatch):
    _fresh(client, tmp_path, monkeypatch, None)
    client.get("/t")
    listed = client.get("/api/elements/visuals").json()["visuals"]
    v = next(x for x in listed if x["kind"] == "genomics.lollipop")
    assert v["module"] == "genomics" and v["library"] == "drishti-genomics" and v["title"] == "Variant lollipop"
    js = client.get(v["script"])
    assert js.status_code == 200 and "registerKind" in js.text and "buildScatter" in js.text

    removed = {"modules": [{"id": "genomics", "version": "0.1.0", "state": "removed", "title": "Genomics", "console": False,
                            "claims": ["function GENE", "function VARIANT", "function COHORT"]}], "fingerprint": "removed"}
    _fresh(client, tmp_path / "again", monkeypatch, removed)
    client.get("/t")
    assert not [x for x in client.get("/api/elements/visuals").json()["visuals"] if x["module"] == "genomics"]
    assert client.get(v["script"]).status_code == 410
    assert client.get("/m/genomics/genes/TP53").status_code in (404, 410)          # the server's route table says which


def test_drishti_genomics_is_the_research_lens_with_no_finance():
    d = server_answers.bootstrap("genomics")["distribution"]          # compiled by the server (recorded by ConsoleAnswersRecordTest)
    assert d["title"] == "Drishti Genomics" and d["modules"] == ["genomics"]
    ed = d["filter"]
    assert ed["packs"] == {"none": True}
    allow = set(ed["commands"]["allow"])
    assert {"VARIANT", "GENE", "COHORT"} <= allow and not allow & {"CPTY", "BOOK", "DESK", "CRV", "VRES", "TRD"}
    assert ed["commands"]["words"].get("VAR") == "off" and ed["commands"]["words"].get("EXP") == "off"
    assert "domains" in ed["menus"]["top"]
