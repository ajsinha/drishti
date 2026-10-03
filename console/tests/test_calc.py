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

"""Calc, the console side: the panel on a view (offered by a pack, allowed by a role), its routes, the runtime folder,
and the security headers that let only its worker compile WebAssembly."""
from pathlib import Path

import pytest

from core.calc import NOT_INSTALLED, Calc, Runtime

SNIPPETS = {"enabled": True, "snippets": [
    {"title": "Shift MTM", "description": "parallel moves", "kinds": ["trade"], "code": "print(view.id)\n", "file": ""},
    {"title": "Every finance kind", "description": "", "kinds": [], "code": "1\n", "file": "python/any.py"},
    {"title": "Netting sets only", "description": "", "kinds": ["netting-set"], "code": "2\n", "file": ""}]}


@pytest.fixture()
def calc_on(client, backend):
    """The finance pack offers Calc (as the server's pack model says), and the user may use it."""
    backend.python = {"finance": SNIPPETS}
    backend.calc_allowed = True
    backend.calc_kept = {}
    client.app.state.packs.forget_all()
    client.app.state.calc._allowed.clear()
    yield
    backend.python = {}
    backend.calc_allowed = True
    client.app.state.packs.forget_all()
    client.app.state.calc._allowed.clear()


def _deny(client, backend):
    backend.calc_allowed = False
    client.app.state.calc._allowed.clear()


def test_a_view_of_a_kind_whose_pack_enables_calc_has_the_panel_and_its_key(client, calc_on):
    html = client.get("/v/trade/IRS-48213").text
    assert 'id="calcDrawer"' in html and "data-calc-open" in html and "<b>Alt+C</b> Calc" in html
    assert "/static/vendor/codemirror/mode/python/python.js" in html and "/static/js/calc.js" in html
    assert 'data-worker="/static/js/calc-worker.js' in html and 'data-module="/static/calc/drishti.py' in html


def test_without_a_role_with_calc_the_key_is_shown_disabled_with_the_reason(client, backend, calc_on):
    _deny(client, backend)
    html = client.get("/v/trade/IRS-48213").text
    assert 'id="calcDrawer"' not in html and "/static/js/calc.js" not in html
    assert "data-calc-open disabled" in html and "Calc needs a role with calc" in html


def test_a_pack_that_does_not_enable_calc_offers_nothing(client):
    html = client.get("/v/trade/IRS-48213").text
    assert "calcDrawer" not in html and "Alt+C" not in html


def test_a_workspace_pane_has_no_calc(client, calc_on):
    assert "calcDrawer" not in client.get("/v/trade/IRS-48213?embed=1").text


def test_pack_snippets_are_those_for_the_kind_and_mine_come_from_the_server(client, backend, calc_on):
    r = client.get("/api/calc/snippets", params={"kind": "trade"})
    assert r.status_code == 200
    assert [s["title"] for s in r.json()["pack"]] == ["Shift MTM", "Every finance kind"]       # not the netting-set one
    assert r.json()["pack"][0]["code"] == "print(view.id)\n" and r.json()["mine"] == []
    assert [s["title"] for s in client.get("/api/calc/snippets", params={"kind": "netting-set"}).json()["pack"]] == [
        "Every finance kind", "Netting sets only"]
    assert client.get("/api/calc/snippets", params={"kind": "shipment"}).json()["pack"] == []   # logistics enables none


def test_my_snippets_are_saved_listed_and_deleted(client, backend, calc_on):
    r = client.put("/api/calc/snippets/My shift", json={"code": "print(1)", "kind": "trade", "ignored": "x"})
    assert r.status_code == 200 and r.json()["name"] == "My shift"
    assert backend.calc_kept["My shift"] == {"name": "My shift", "code": "print(1)", "description": "", "kind": "trade",
                                             "updatedAt": "2026-10-01T09:00:00Z"}
    assert [s["name"] for s in client.get("/api/calc/snippets", params={"kind": "trade"}).json()["mine"]] == ["My shift"]
    assert client.put("/api/calc/snippets/My shift", json={"code": "  "}).json()["code"] == "DRS-5001"
    assert client.put("/api/calc/snippets/..%2Fetc", json={"code": "1"}).status_code in (400, 404)
    assert client.put("/api/calc/snippets/" + "x" * 81, json={"code": "1"}).status_code == 400
    assert client.delete("/api/calc/snippets/My shift").json() == {"ok": True}
    assert client.delete("/api/calc/snippets/My shift").status_code == 404


def test_every_calc_route_is_refused_without_a_role_with_calc(client, backend, calc_on):
    _deny(client, backend)
    for method, url, body in [("GET", "/api/calc/snippets?kind=trade", None), ("PUT", "/api/calc/snippets/x", {"code": "1"}),
                              ("DELETE", "/api/calc/snippets/x", None), ("GET", "/api/calc/search?q=TRD", None),
                              ("GET", "/api/calc/columns/TRD?paths=mtm", None)]:
        r = client.request(method, url, json=body)
        assert r.status_code == 403 and r.json()["code"] == "DRS-5002", url
        assert "role with calc" in r.json()["detail"]


def test_search_and_columns_answer_json_through_the_users_own_identity(client, backend, calc_on, monkeypatch):
    seen = []

    async def search(q, ident=None):
        seen.append((q, ident.user))
        return {"kind": "trade", "columns": ["$.mtm"], "rows": [{"ref": {"kind": "trade", "id": "T-1"}, "values": {"$.mtm": 5}}]}

    monkeypatch.setattr(backend, "search", search, raising=False)
    r = client.get("/api/calc/search", params={"q": "TRD where mtm > 1"})
    assert r.status_code == 200 and r.json()["rows"][0]["ref"]["id"] == "T-1" and seen == [("TRD where mtm > 1", "ash")]
    assert client.get("/api/calc/search", params={"q": " "}).status_code == 400
    r = client.get("/api/calc/columns/TRD", params={"paths": "mtm,book", "limit": 5})
    assert r.json()["values"] == {"mtm": [1, 2], "book": [1, 2]} and ("columns", "TRD", "mtm,book", 5) in backend.calls
    assert client.get("/api/calc/columns/TRD").json()["available"] == ["book", "mtm"]


def test_calc_is_switched_off_on_the_console(client, calc_on):
    calc = client.app.state.calc
    calc.enabled = False
    try:
        assert "calcDrawer" not in client.get("/v/trade/IRS-48213").text
        assert client.get("/api/calc/snippets?kind=trade").status_code == 403
    finally:
        calc.enabled = True


def test_the_runtime_folder_is_read_from_its_stamp(tmp_path):
    missing = Runtime(tmp_path / "pyodide")
    assert not missing.installed and missing.describe() == {"installed": False, "message": NOT_INSTALLED}
    assert NOT_INSTALLED == "Python runtime not installed: run tools/fetch-pyodide.sh"
    folder = tmp_path / "pyodide"
    folder.mkdir()
    (folder / ".drishti-pyodide").write_text("version=314.0.7\nsha256=abc\npackages=numpy pandas\n")
    assert not Runtime(folder).installed                     # a stamp without the runtime's files is not an install
    (folder / "pyodide.mjs").write_text("x")
    (folder / "pyodide.asm.wasm").write_bytes(b"\0" * 2000)
    r = Runtime(folder)
    assert r.installed and r.base == "/pyodide/314.0.7/" and r.packages == ["numpy", "pandas"]
    assert r.describe()["sizeMb"] == 0.0 and r.describe()["base"] == "/pyodide/314.0.7/"


def test_a_view_says_when_the_runtime_is_missing(client, calc_on, tmp_path):
    calc = client.app.state.calc
    real = calc.runtime
    calc.runtime = Runtime(tmp_path / "none")
    try:
        html = client.get("/v/trade/IRS-48213").text
        assert NOT_INSTALLED in html and 'data-runtime=""' in html
        assert client.get("/healthz").json()["pythonRuntime"] == "not installed: run tools/fetch-pyodide.sh"
        about = client.get("/about").text
        assert "Python runtime (Calc)" in about and NOT_INSTALLED in about
    finally:
        calc.runtime = real


def test_only_the_calc_worker_may_compile_webassembly(client):
    page = client.get("/t").headers["content-security-policy"]
    assert "worker-src 'self'" in page and "wasm" not in page and "unsafe-eval" not in page
    worker = client.get("/static/js/calc-worker.js").headers["content-security-policy"]
    assert worker == "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src testserver/static/ testserver/pyodide/"
    assert "'unsafe-eval'" not in worker


def test_the_calc_worker_cannot_fetch_a_console_route(client):
    """SEC-17: its requests carry the session cookie, so it may reach only static files and the runtime, never /api."""
    worker = client.get("/static/js/calc-worker.js").headers["content-security-policy"]
    connect = worker.split("connect-src ", 1)[1]
    assert "'self'" not in connect and "/api" not in connect
    assert connect.split() == ["testserver/static/", "testserver/pyodide/"]
    odd = client.get("/static/js/calc-worker.js", headers={"host": "evil.example; script-src *"}).headers["content-security-policy"]
    assert "connect-src 'none'" in odd and "script-src *" not in odd


def test_the_runtime_is_served_from_this_origin_versioned_and_cached(client):
    runtime = client.app.state.calc.runtime
    if not runtime.installed:
        assert client.get("/pyodide/314.0.7/pyodide.mjs").status_code == 404
        return
    r = client.get(runtime.base + "pyodide-lock.json")
    assert r.status_code == 200 and r.headers["cache-control"] == "public, max-age=31536000, immutable"
    assert client.get(runtime.base + "pyodide.mjs").headers["content-type"].startswith("text/javascript")
    assert client.get("/healthz").json()["pythonRuntime"] == runtime.version


def test_offered_follows_the_pack_that_owns_the_kind():
    calc = Calc(_Settings(), Path("/nonexistent"))
    packs = [{"name": "finance", "title": "Finance", "kinds": ["trade"], "python": {"enabled": True, "snippets": []}},
             {"name": "logistics", "title": "Logistics", "kinds": ["shipment"], "python": {"enabled": False, "snippets": [
                 {"title": "x", "kinds": ["trade"], "code": "1"}]}}]
    assert calc.offered(packs, "trade") and not calc.offered(packs, "shipment") and not calc.offered(packs, "port")
    assert calc.snippets(packs, "trade") == []                # a pack that does not enable Calc offers no snippets


class _Settings:
    def get(self, key, default=None):
        return default


def test_the_backend_client_sends_a_field_path_and_reads_columns_with_a_longer_wait():
    """drishti.history() reads /api/series, whose field is a query parameter called path (it once collided with the
    client's own argument and failed); drishti.columns() may wait for a day's columns to load."""
    import asyncio

    import httpx

    from core.backend import BackendClient

    seen = []

    def handler(request):
        seen.append((request.url.path, dict(request.url.params), request.extensions.get("timeout", {}).get("read")))
        return httpx.Response(200, json={"ok": True})

    client = BackendClient("http://server")
    client._client = httpx.AsyncClient(base_url="http://server", transport=httpx.MockTransport(handler), timeout=5.0)
    asyncio.run(client.series("trade", "T-1", "risk.dv01", 30))
    asyncio.run(client.columns("TRD", "mtm,book", 10))
    assert seen[0][:2] == ("/api/v1/history/trade/T-1/series", {"path": "risk.dv01", "days": "30"})
    assert seen[1] == ("/api/v1/search/columns/TRD", {"paths": "mtm,book", "limit": "10"}, 45.0)


def test_administrators_give_a_role_calc_in_admin_roles(client, backend):
    page = client.get("/admin/roles").text
    assert 'name="calc"' in page and "Use Calc" in page
    client.post("/admin/api/roles/quant", json={"kinds": ["*"], "calc": True})
    assert ("admin", "PUT", "/role-definitions/quant", {"kinds": ["*"], "calc": True}) in backend.calls
