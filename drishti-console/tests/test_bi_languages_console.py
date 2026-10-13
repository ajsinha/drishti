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

"""Wave A of the BI design, console lane (docs/architecture/BI_DESIGN.md section 12.2): the ExpressionEditor on every page that writes an expression, the
report's own measures read and written by the console, the proxy passing the language endpoints (``/expr``, ``/sql``) on, the dataset wizard's derivation
document, the model designer's field properties, the YAML round trip that keeps comments and order, and Analyse's SQL on the server. The browser side is
tests/test_bi_languages_browser.py."""
import pytest

from core import model_layer as ml
from test_bi_model_layer import make


@pytest.mark.parametrize("url", ["/bi/md/bi-m1", "/bi/datasets/new-layer", "/bi/datasets/new", "/bi/design/bi-r1", "/bi/explore"])
def test_every_page_that_writes_an_expression_loads_the_editor(url):
    client, _ = make()
    text = client.get(url).text
    assert "bi-expr-editor.js" in text and "bi-expr.css" in text


def test_the_proxy_passes_the_language_endpoints_on():
    client, backend = make()
    client.get("/bi/api/expr/functions")
    client.post("/bi/api/expr/validate", json={"expr": "sum(trade.mtm)", "scope": "aggregate", "model": "trading-risk@1"})
    client.post("/bi/api/sql", json={"sql": "SELECT MEASURE('MTM') FROM trade", "model": "trading-risk@1"})
    paths = [c[1] for c in backend.bi_calls]
    assert "/expr/functions" in paths and "/expr/validate" in paths and "/sql" in paths


def test_a_reports_own_measures_are_read_from_its_yaml():
    body = 'report: r\nmodel: trading-risk@1\nmeasures:\n  "MTM per trade": { expr: "measure(\'MTM\') / count(trade)", format: signed0 }\n  Plain: "sum(trade.notional)"\npages: []\n'
    assert ml.report_measures(body) == {"MTM per trade": {"expr": "measure('MTM') / count(trade)", "format": "signed0"}, "Plain": {"expr": "sum(trade.notional)"}}
    client, _ = make()
    r = client.post("/bi/model-api/report-measures", json={"yaml": body})
    assert r.status_code == 200 and set(r.json()["measures"]) == {"MTM per trade", "Plain"}
    assert ml.report_measures("report: r\npages: []\n") == {}
    with pytest.raises(ml.LayerError):
        ml.report_measures("measures: [1, 2]\n")
