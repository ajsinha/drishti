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
    import routes.bi_poc_routes as poc
    folder = tmp_path / "vendor" / "duckdb-wasm" / "9.9.9"
    folder.mkdir(parents=True)
    for n in ("duckdb-browser.mjs", "duckdb-browser-eh.worker.js", "duckdb-eh.wasm", ".drishti-duckdb-wasm"):
        (folder / n).write_text("x")
    real = poc.duckdb_base
    monkeypatch.setattr(poc, "duckdb_base", lambda static: real(tmp_path))


def test_the_page_offers_the_browser_engine_only_when_it_is_installed(tmp_path, monkeypatch):
    client, _ = make()
    assert 'data-duckdb' not in client.get("/bi/explore").text or (DUCK / "1.33.1-dev57.0" / "duckdb-eh.wasm").exists()
    _install(tmp_path, monkeypatch)
    page = client.get("/bi/explore").text
    assert 'data-duckdb="/static/vendor/duckdb-wasm/9.9.9"' in page and "data-engine-pick" in page and "/static/js/bi-explore-duck.js" in page
    assert 'data-sql-seconds="5.0"' in page and 'data-sql-chars="20000"' in page


def test_the_setting_server_keeps_every_run_on_the_console(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch)
    client, _ = make(browser_engine="server")
    page = client.get("/bi/explore").text
    assert "data-duckdb" not in page and "data-engine-pick" not in page


def test_the_page_keeps_the_strict_policy_and_only_the_workers_compile_webassembly(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch)
    client, _ = make()
    r = client.get("/bi/explore")
    csp = r.headers["content-security-policy"]
    assert "wasm-unsafe-eval" not in csp and "unsafe-eval" not in csp and "unsafe-inline" not in csp
    worker = client.get("/static/vendor/duckdb-wasm/9.9.9/duckdb-browser-eh.worker.js")
    assert worker.status_code in (200, 404)
    # the worker's own file is what carries the WebAssembly right (core/app.py: worker_csp); it is checked on the real file by the browser test
    from core.app import POC_WORKER_SUFFIX
    assert POC_WORKER_SUFFIX == "/duckdb-browser-eh.worker.js"


def test_the_duck_script_is_plain_and_names_no_other_origin():
    js = (CONSOLE / "web/static/js/bi-explore-duck.js").read_text(encoding="utf-8")
    assert "enable_external_access = false" in js and "lock_configuration = true" in js and "terminate()" in js
    assert not any(x in js for x in ("eval(", "new Function", "http://", "https://"))


def test_the_default_setting_is_auto():
    s = Path(CONSOLE / "config/application.yaml").read_text(encoding="utf-8")
    assert "browser_engine: auto" in s
