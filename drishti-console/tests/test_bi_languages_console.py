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


WIZARD_DOC = {"rachana": 2, "dataset": "live-trades", "version": "1.0.0", "title": "Live trades", "extends": "pack.trading.trade@^1",
              "columns": ["tradeId", "desk", "currency", "notional", "mtm", "status", "updatedAt"], "where": "status == 'LIVE'",
              "grain": {"one-row-per": "tradeId", "keep": "latest by updatedAt"},
              "derive": {"notionalUsd": {"expr": "notional * fx(currency, 'USD')", "type": "decimal", "meaning": "amount"},
                         "tenorBucket": {"python": "buckets.by_tenor", "inputs": ["maturityDate"], "type": "string"}},
              "group": {"by": ["desk", "currency"], "columns": {"notionalUsd": "sum", "mtm": "sum", "trades": "count"},
                        "grain": {"says": "One row per desk and currency", "keys": ["desk", "currency"]}},
              "security": {"masked": ["desk"]}}


def test_the_wizard_writes_the_derivation_keys_in_the_order_they_apply_and_reads_them_back():
    text = ml.dataset_yaml(WIZARD_DOC)
    keys = [line.split(":")[0] for line in text.splitlines() if line and not line.startswith(" ")]
    assert keys == ["rachana", "dataset", "version", "title", "extends", "columns", "where", "grain", "derive", "group", "security"]
    assert "  notionalUsd: " in text and "  tenorBucket: " in text                          # one worked-out column per line, for a review that reads well
    import yaml
    assert yaml.safe_load(text) == WIZARD_DOC
    info = ml.dataset_info(text)
    assert info["subset"][:2] == ["tradeId", "desk"] and info["where"] == "status == 'LIVE'"
    assert info["grain"]["keys"] == ["tradeId"] and info["grain"]["keep"] == "latest by updatedAt"
    names = [c["name"] for c in info["columns"]]
    assert names == ["desk", "currency", "notionalUsd", "mtm", "trades"]                    # grouped: the by columns, then each grouped column
    by_name = {c["name"]: c for c in info["columns"]}
    assert by_name["trades"]["type"] == "integer" and by_name["notionalUsd"]["aggregate"] == "sum" and by_name["desk"]["role"] == "dimension"


def test_an_sql_view_dataset_reads_as_its_source():
    text = ml.dataset_yaml({"rachana": 2, "dataset": "desk-mtm", "version": "1.0.0", "title": "Desk MTM",
                            "source": {"sql": "SELECT desk.name AS desk, MEASURE('MTM') AS mtm FROM trade JOIN desk GROUP BY desk.name", "model": "trading-risk@1"}})
    info = ml.dataset_info(text)
    assert info["source"]["type"] == "sql" and info["source"]["model"] == "trading-risk@1" and "MEASURE('MTM')" in info["source"]["sql"]


def test_the_wizard_page_has_every_step_and_the_sql_view():
    client, _ = make()
    text = client.get("/bi/datasets/new-layer").text
    for step in ("root", "columns", "filter", "grain", "derive", "group", "preview", "save"):
        assert f'data-step="{step}"' in text
    assert 'data-src="sql"' in text and "bi-dataset-steps.js" in text and "bi-query-panel.js" in text and "data-dedup=\"latest\"" in text
