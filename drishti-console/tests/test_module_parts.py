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
"""A module's console part hosted by the console (KERNEL_AND_MODULES.md sections 4.7, 9, 12, 14.2.1), over a stand-in of the server's
module registry (tests/module_parts_fake.py, the SDK archetype as the module ``greeter``): installed, its page, menu entry, Home card, Help
and AI tool appear and its word opens its page; removed, every one goes, its paths answer 410 with the Module removed A page and saved work
that uses it says "needs module greeter"; switched off, 404; reinstalled, all of it is back. A part that carries a copy of Drishti Elements,
code, or a template reaching past ``kernel/`` is refused; the economy module's World page is served from its own folder."""
from __future__ import annotations

import re

from fastapi.testclient import TestClient

from module_parts_fake import Registry


def _console(client, tmp_path, reg: Registry, backend, monkeypatch) -> TestClient:
    reg.install(backend, monkeypatch)
    _fresh(client, tmp_path, monkeypatch)
    return client


def _fresh(client, tmp_path, monkeypatch):
    """A hosted-parts registry of this test's own, put back after it (the session's apps are reused: none may see another test's modules)."""
    from core.module_parts import ModuleParts

    old = client.app.state.module_parts
    parts = ModuleParts(tmp_path / "parts", old.web, old.shipped, 0.0)
    monkeypatch.setattr(client.app.state, "module_parts", parts)
    monkeypatch.setattr(client.app.state.editions.modules, "parts", parts)
    return parts


def _menu_links(page: str) -> list[str]:
    return re.findall(r'class="mega-item[^"]*" href="([^"]+)"', page)


def test_install_remove_and_reinstall_take_every_surface_with_them(client, backend, monkeypatch, tmp_path):
    reg = Registry()
    c = _console(client, tmp_path, reg, backend, monkeypatch)

    async def settings(ident=None):                                                    # a saved search (FORM) over the module's kind
        return {"savedSearches": [{"name": "hellos", "path": "/form/greeter-pack/greeter-greeting?q=a"}]}
    monkeypatch.setattr(backend, "settings", settings, raising=False)

    async def form(pack, kind, ident=None):
        from core.backend import BackendError
        raise BackendError(404, "DRS-4040", "no form here")
    monkeypatch.setattr(backend, "form", form, raising=False)
    assert c.get("/m/greeter/hello/asha").status_code == 404                        # never installed: the ordinary 404
    assert "/m/greeter/hello/world" not in c.get("/t").text

    reg.state = "active"                                                               # installed
    page = c.get("/m/greeter/hello/asha")
    assert page.status_code == 200, page.text[:400]
    assert "Hello, asha" in page.text and 'name="drishti-module-page" content="greeter"' in page.text
    assert "/m/greeter/hello/world" in _menu_links(c.get("/t").text)                   # its menu entry
    assert 'data-home-card="GREETER world"' in c.get("/").text                         # its Home card
    assert "greeter_greet" in c.get("/api/ai/module-tools").text                       # its AI tool
    assert "greeter_greet" in c.get("/admin/ai").text
    assert "/help/section/module-greeter" in c.get("/help").text                       # its Help: a section
    assert "greeter: the guide" in c.get("/help/section/module-greeter").text
    go = c.get("/go?q=GREETER%20asha%20%3CGO%3E", follow_redirects=False)
    assert go.headers["location"] == "/m/greeter/hello/asha"                           # its word
    assert c.get("/m/greeter/static/1.0.0/drishti_module.css").headers["cache-control"].endswith("immutable")
    assert c.get("/api/modules/hosted").json()["hosted"]["greeter"]["version"] == "1.0.0"
    assert c.get("/build?type=sutras").text.count("needs module greeter") == 0

    reg.state = "removed"                                                              # removed: every door goes
    gone = c.get("/m/greeter/hello/asha")
    assert gone.status_code == 410 and "no longer installed" in gone.text and "needs module greeter" in gone.text
    assert c.get("/m/greeter/static/1.0.0/drishti_module.css").status_code == 410
    assert "/m/greeter/hello/world" not in c.get("/t").text
    assert 'data-home-card="GREETER world"' not in c.get("/").text
    assert "greeter_greet" not in c.get("/api/ai/module-tools").text
    assert "/help/section/module-greeter" not in c.get("/help").text
    said = c.get("/go?q=GREETER%20asha%20%3CGO%3E", follow_redirects=False).headers["location"]
    assert "needs%20module%20greeter" in said or "needs+module+greeter" in said
    build = c.get("/build?type=sutras").text                                           # the saved Sutra: kept, marked, not openable
    assert 'data-needs-module="greeter"' in build and "greetings-board" in build and "/studio?sutra=greetings-board" not in build
    assert c.get("/api/modules/hosted").json()["gone"]["greeter"]["state"] == "removed"
    assert 'data-needs-module="greeter"' in c.get("/forms").text                      # the saved search: kept, marked

    reg.state = "off"                                                                  # switched off: 404, not 410
    assert c.get("/m/greeter/hello/asha").status_code == 404

    reg.state = "active"                                                               # reinstalled: all back
    assert c.get("/m/greeter/hello/asha").status_code == 200
    assert "/m/greeter/hello/world" in _menu_links(c.get("/t").text)
    assert "greeter: the guide" in c.get("/help/section/module-greeter").text
    assert 'data-needs-module="greeter"' not in c.get("/build?type=sutras").text


def test_a_part_carrying_elements_code_or_a_private_template_is_refused(client, backend, monkeypatch, tmp_path):
    from module_parts_fake import ROOT

    grid = (ROOT / "drishti-console" / "web" / "static" / "elements" / "drishti-grid" / "drishti_grid.js").read_bytes()
    reg = Registry({"console/static/drishti_greeter-grid.js": grid, "console/handler.py": b"print(1)\n",
                    "console/templates/peek.html": b'{% include "terminal/_topbar.html" %}'})
    reg.state = "active"
    c = _console(client, tmp_path, reg, backend, monkeypatch)
    assert c.get("/m/greeter/hello/asha").status_code == 404
    why = " ".join(c.get("/api/modules/hosted").json()["refused"]["greeter"])
    assert "a copy of a Drishti Elements" in why and "runs no code" in why and "reaches terminal/_topbar.html" in why


def test_the_economy_world_page_is_served_from_the_module(client, tmp_path, monkeypatch):
    parts = _fresh(client, tmp_path, monkeypatch)
    client.get("/t")
    p = parts.part("economy")
    assert p is not None and p.source == "shipped" and (p.folder / "console" / "templates" / "world_landing.html").is_file()
    js = client.get(f"/m/economy/static/{p.version}/js/drishti_world-landing.js")
    assert js.status_code == 200 and "Drishti.elements.grid" in js.text
    html = (p.folder / "console" / "templates" / "world_landing.html").read_text(encoding="utf-8")
    assert "<table" not in html and "elements.grid(" in html and 'extends "kernel/page.html"' in html
