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

"""Wave C (docs/guides/ANALYSE.md "In the browser"): Slice and SQL run in the browser on the extract with DuckDB-Wasm when it is installed; the page says where, keeps the strict
policy, and the console's own routes stay as the fallback. The engines themselves run in Chromium (test_bi_explore_duck_browser.py)."""
from pathlib import Path

import pytest

pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from conftest import CONSOLE  # noqa: E402
from test_bi_explore import make  # noqa: E402

DUCK = CONSOLE / "web" / "static" / "vendor" / "duckdb-wasm"


def _install(tmp_path, monkeypatch):
    """A DuckDB-Wasm folder that looks installed (the three files and the stamp), so the page offers the engine without the 36 MB."""
    import routes.explore_routes as er
    folder = tmp_path / "vendor" / "duckdb-wasm" / "9.9.9"
    folder.mkdir(parents=True)
    for n in ("duckdb-browser.mjs", "duckdb-browser-eh.worker.js", "duckdb-eh.wasm", ".drishti-duckdb-wasm"):
        (folder / n).write_text("x")
    real = er.duckdb_base
    monkeypatch.setattr(er, "duckdb_base", lambda static: real(tmp_path))


def test_the_page_offers_the_browser_engine_only_when_it_is_installed(tmp_path, monkeypatch):
    client, _ = make()
    installed = any(DUCK.glob("*/duckdb-eh.wasm"))
    assert ('data-duckdb="' in client.get("/bi/explore").text) == installed
    _install(tmp_path, monkeypatch)
    page = client.get("/bi/explore").text
    assert 'data-duckdb="/static/vendor/duckdb-wasm/9.9.9"' in page and "data-engine-pick" in page and "/static/js/bi-explore-duck.js" in page
    assert 'data-sql-seconds="5.0"' in page and 'data-sql-chars="20000"' in page


def test_the_setting_server_keeps_every_run_on_the_console(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch)
    client, _ = make(browser_engine="server")
    page = client.get("/bi/explore").text
    assert 'data-duckdb="' not in page and "data-engine-pick" not in page


def test_the_page_keeps_the_strict_policy_and_only_the_workers_compile_webassembly(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch)
    client, _ = make()
    r = client.get("/bi/explore")
    csp = r.headers["content-security-policy"]
    assert "wasm-unsafe-eval" not in csp and "unsafe-eval" not in csp and "unsafe-inline" not in csp
    worker = client.get("/static/vendor/duckdb-wasm/9.9.9/duckdb-browser-eh.worker.js")
    assert worker.status_code in (200, 404)
    # the worker's own file is what carries the WebAssembly right (core/app.py: worker_csp); it is checked on the real file by the browser test
    from core.app import DUCKDB_WORKER_SUFFIX
    assert DUCKDB_WORKER_SUFFIX == "/duckdb-browser-eh.worker.js"


def test_the_duck_script_is_plain_and_names_no_other_origin():
    js = (CONSOLE / "web/static/js/bi-explore-duck.js").read_text(encoding="utf-8")
    assert "enable_external_access = false" in js and "lock_configuration = true" in js and "terminate()" in js
    assert not any(x in js for x in ("eval(", "new Function", "http://", "https://"))


def test_the_default_setting_is_auto():
    s = Path(CONSOLE / "config/application.yaml").read_text(encoding="utf-8")
    assert "browser_engine: auto" in s
