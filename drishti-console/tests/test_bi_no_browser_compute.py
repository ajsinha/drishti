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

"""Wave B, console lane task 1 (docs/architecture/BI_DESIGN.md principle P3 and section 12.2, acceptance 3): no BI page computes. No BI page loads WebAssembly,
Pyodide, DuckDB-Wasm or a worker; every BI page has the strict policy without 'wasm-unsafe-eval'; the Slice of Analyse is worked out by the console and a Python
cell runs on the console, never in the browser."""
import re

import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import CSP, create_app
from core.config import load_settings

TEMPLATES = CONSOLE / "web/templates/bi"
JS = CONSOLE / "web/static/js"
COMPUTE = re.compile(r"WebAssembly|new Worker\(|pyodide|duckdb|calc-worker|bi_driver|\.wasm\b|importScripts", re.I)


def scripts_of(template: str) -> list[str]:
    text = (TEMPLATES / template).read_text(encoding="utf-8")
    return re.findall(r'src="/static/js/([A-Za-z0-9_.-]+\.js)', text)


def test_no_bi_template_names_a_compute_engine_or_a_wasm_policy():
    for t in sorted(TEMPLATES.glob("*.html")):
        text = t.read_text(encoding="utf-8")
        assert not re.search(r"pyodide|duckdb-wasm|data-duckdb|calc-worker|bi_driver|vendor/perspective|wasm-unsafe|\.wasm\b|data-py-runtime", text, re.I), t.name
        for name in scripts_of(t.name):
            body = (JS / name).read_text(encoding="utf-8")
            code = re.sub(r"/\*.*?\*/", "", body, flags=re.S)                    # comments may say what is gone
            code = "\n".join(line for line in code.splitlines() if not line.lstrip().startswith("//"))
            assert not COMPUTE.search(code), f"{t.name} loads {name}, which starts a compute engine in the browser"


def test_the_removed_engines_are_gone():
    for gone in ("web/static/js/bi-explore-duck.js", "web/static/js/bi-perspective.js", "web/static/calc/bi_driver.py", "core/perspective_csp.py",
                 "web/static/vendor/perspective", "web/static/vendor/duckdb-wasm"):
        assert not (CONSOLE / gone).exists(), gone
    assert "wasm" not in CSP


class Backend(BiBackend):
    async def bi(self, method, path, ident, body=None, **params):
        if path.startswith("/reports/") and method == "GET":
            return {"report": {"pages": [{"id": "p", "visuals": [{"id": "g", "type": "perspective-grid"}]}]}}
        if path.startswith("/boards/") and method == "GET":
            return {"tiles": {"p/t": {"type": "perspective-pivot"}}}
        return await super().bi(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def client():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = Backend(("bi", "bi-author"))
    return TestClient(app)


@pytest.mark.parametrize("path", ["/bi", "/bi/r/r1", "/bi/b/b1", "/bi/explore", "/bi/designer", "/bi/design/d1", "/bi/datasets", "/bi/models", "/bi/catalogue"])
def test_every_bi_page_has_the_strict_policy(client, path):
    r = client.get(path)
    assert r.status_code == 200, path
    csp = r.headers["content-security-policy"]
    assert csp == CSP and "wasm-unsafe-eval" not in csp and "unsafe-eval" not in csp, path
    assert "data-py-runtime" not in r.text and "data-duckdb" not in r.text and "data-perspective" not in r.text, path
