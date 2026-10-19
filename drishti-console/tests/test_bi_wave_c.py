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

"""Wave C (docs/guides/PYTHON_BI.md, REPORTS.md): Vega-Lite as the output of a Python visual and as a report visual type, the vendored libraries the page draws it with,
the page's policy, and the Python item's own tests."""
import re

import pytest

pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from conftest import CONSOLE  # noqa: E402
from test_bi_python import VISUAL, make, run  # noqa: E402

VENDOR = CONSOLE / "web" / "static" / "vendor" / "vega" / "6.4.0"


def _vega_item(source):
    return {**VISUAL, "id": "file:python:vega", "manifest": {**VISUAL["manifest"], "name": "vega", "output": "vega-lite", "source": source}}


def test_a_visual_may_return_a_vega_lite_specification():
    src = ("def render(table, params):\n    return {'mark': 'bar', 'data': {'values': [{'d': 'A', 'v': float(table['pnl1d'].sum())}]},"
           " 'encoding': {'x': {'field': 'd', 'type': 'nominal'}, 'y': {'field': 'v', 'type': 'quantitative'}}}\n")
    client, _ = make(_vega_item(src))
    r = run(client, visual="vol")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["kind"] == "vega-lite" and body["spec"]["mark"] == "bar" and body["spec"]["data"]["values"][0]["v"] == 8.0


def test_a_vega_lite_specification_that_loads_a_url_is_refused_by_the_sandbox():
    src = "def render(table, params):\n    return {'mark': 'bar', 'data': {'url': 'http://example.org/x.json'}}\n"
    client, _ = make(_vega_item(src))
    r = run(client, visual="vol")
    assert r.status_code == 403 and r.json()["code"] == "DRS-9073" and "URL" in r.json()["detail"]


def test_an_echarts_item_still_returns_an_option():
    client, _ = make(VISUAL)
    assert run(client, visual="vol").json()["kind"] == "echarts"


def test_the_vega_libraries_are_vendored_with_their_licences():
    for f in ("vega.min.js", "vega-lite.min.js", "vega-embed.min.js", "vega-interpreter.min.js", "LICENSE-vega", "LICENSE-vega-lite", "LICENSE-vega-embed", "LICENSE-vega-interpreter"):
        assert (VENDOR / f).is_file(), f
    assert "BSD 3-Clause" in (VENDOR / "LICENSE-vega").read_text() or "Redistribution" in (VENDOR / "LICENSE-vega").read_text()


def test_the_page_script_draws_with_the_interpreter_and_a_loader_that_refuses_urls():
    """The strict policy holds because the expression syntax tree is built by vega-embed and walked by the interpreter, and because nothing may be loaded."""
    js = (CONSOLE / "web/static/js/bi-vega.js").read_text(encoding="utf-8")
    assert "ast: true" in js and "expr: window.vegaInterpreter" in js
    assert "loader.load = refuse" in js and "loader.sanitize = refuse" in js and "actions: false" in js
    assert not re.search(r"\beval\(|new Function", js)


def test_the_report_and_designer_pages_load_the_vega_script_but_not_the_libraries():
    for page in ("report", "design"):                    # a board is a report layout (2.4.0): it opens in report.html
        html = (CONSOLE / f"web/templates/bi/{page}.html").read_text(encoding="utf-8")
        assert "/static/js/bi-vega.js" in html, page
        assert "vendor/vega/" not in html, page      # the libraries load the first time a vega-lite visual is drawn
