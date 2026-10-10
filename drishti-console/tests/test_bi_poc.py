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

"""RUPAKA PHASE 0 PROOF OF CONCEPT (docs/architecture/RUPAKA_POC.md), the console's routes and policy: off by default (no page, no
top-bar entry, RUPAKA <GO> says so); on, the page, the reserved keyword, the Arrow pass-through, and the content security policy
(WebAssembly allowed on /bi/poc and its two workers only; no inline script anywhere)."""
import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import POC_STYLE_HASHES, create_app
from core.config import load_settings
from poc_backend import PocBackend


@pytest.fixture(scope="module")
def on():
    settings = load_settings(CONSOLE / "config")
    settings.as_dict().setdefault("bi", {})["poc_enabled"] = True
    app = create_app(settings)
    app.state.backend = PocBackend()
    return TestClient(app)


def test_it_is_off_by_default(client):
    assert client.get("/bi/poc").status_code == 404
    assert client.post("/bi/poc/query", json={"groupBy": ["desk"]}).status_code == 404
    top = client.get("/t").text
    assert "/bi/poc" not in top and "bar-chart-line" not in top
    r = client.get("/go", params={"q": "RUPAKA <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/t?error=") and "not%20switched%20on" in r.headers["location"]


def test_the_page_and_the_top_bar_entry_exist_when_on(on):
    page = on.get("/bi/poc")
    assert page.status_code == 200
    assert 'data-duckdb="' in page.text and "perspective-viewer" in page.text
    assert 'href="/bi/poc"' in on.get("/t").text and "bar-chart-line" in on.get("/t").text


def test_rupaka_go_opens_it_in_any_case_and_form(on):
    for q in ("RUPAKA", "rupaka", "RUPAKA <GO>", "  Rupaka  "):
        r = on.get("/go", params={"q": q}, follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"] == "/bi/poc", q


def test_the_libraries_load_on_this_page_only(on):
    import re

    page = on.get("/bi/poc").text
    assert '<script type="module" src="/static/js/bi-poc.js' in page
    assert not re.search(r"<script[^>]*vendor/(perspective|duckdb-wasm|apache-arrow)", page)       # bi-poc.js imports them, on demand
    for other in ("/t", "/s", "/help", "/about", "/"):
        text = on.get(other).text
        assert "bi-poc" not in text.replace('href="/bi/poc"', "") and "vendor/perspective" not in text and "duckdb-wasm" not in text, other


def test_webassembly_is_allowed_on_the_poc_page_and_its_workers_only(on):
    page = on.get("/bi/poc").headers["content-security-policy"]
    assert "script-src 'self' 'wasm-unsafe-eval';" in page and "'unsafe-eval'" not in page and "worker-src 'self'" in page
    assert "style-src 'self' " in page and "'unsafe-inline'" not in page
    for h in POC_STYLE_HASHES:
        assert f"'{h}'" in page                      # exactly Perspective's own <style> elements, no blanket permission
    for other in ("/t", "/s", "/about"):
        csp = on.get(other).headers["content-security-policy"]
        assert "wasm" not in csp and "sha256" not in csp, other
    for worker in ("/static/vendor/perspective/3.8.0/perspective/cdn/perspective-server.worker.js",
                   "/static/vendor/duckdb-wasm/1.33.1-dev57.0/duckdb-browser-eh.worker.js"):
        csp = on.get(worker, headers={"host": "console.example"}).headers["content-security-policy"]
        assert csp == "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src console.example/static/ console.example/pyodide/", worker


def test_no_blob_worker_and_no_inline_in_the_page_code():
    from pathlib import Path

    js = (CONSOLE / "web/static/js/bi-poc.js").read_text(encoding="utf-8")
    assert "createObjectURL" not in js and "new Blob" not in js and "innerHTML" not in js and "eval(" not in js
    assert "SharedArrayBuffer" not in js


def test_the_query_passes_the_arrow_answer_through(on):
    r = on.post("/bi/poc/query", json={"groupBy": ["trader"], "preview": "masked"})
    assert r.status_code == 200 and r.headers["content-type"] == "application/vnd.apache.arrow.stream"
    assert r.headers["x-poc-masked"] == "trader" and r.headers["server-timing"] == "query;dur=1.5" and r.headers["cache-control"] == "no-store"
    assert r.content[:4] == b"\xff\xff\xff\xff" and r.content[-8:] == b"\xff\xff\xff\xff\x00\x00\x00\x00"        # an Arrow IPC stream
    assert b"\xe2\x80\xa2\xe2\x80\xa2\xe2\x80\xa2" in r.content                                              # the mask, in the bytes
    assert on.app.state.backend.queries[-1] == {"groupBy": ["trader"], "preview": "masked"}


def test_the_query_takes_json_only(on):
    assert on.post("/bi/poc/query", content="groupBy=desk", headers={"content-type": "text/plain"}).status_code == 415


def test_a_refusal_from_the_server_is_passed_on(on, monkeypatch):
    from core.backend import BackendError

    async def refuse(body, ident):
        raise BackendError(403, "DRS-5002", "u is not an administrator")

    monkeypatch.setattr(on.app.state.backend, "poc_query", refuse)
    r = on.post("/bi/poc/query", json={"groupBy": ["desk"]})
    assert r.status_code == 403 and r.json() == {"code": "DRS-5002", "detail": "u is not an administrator"}
