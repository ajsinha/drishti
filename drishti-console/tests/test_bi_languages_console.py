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


PUBLISHED = {"rachana": 2, "model": "trading-risk", "version": "1.2.0", "title": "Trading risk",
             "datasets": {"trade": {"dataset": "pack.trading.trade@^1", "role": "fact"}, "desk": {"dataset": "pack.banking-core.desk@^1", "role": "dimension"}},
             "relationships": [{"from": "trade.desk", "to": "desk.id"}],
             "measures": {"MTM": {"expr": "sum(trade.mtm)", "format": "signed0"}, "Notional": {"expr": "sum(trade.notional)"}},
             "fields": {"trade.mtm": {"description": "Mark to market"}}, "perspectives": {"desk-view": {"fields": ["trade.desk"], "measures": ["MTM", "Notional"]}}}


def _with(**changes):
    import copy
    d = copy.deepcopy(PUBLISHED)
    for k, v in changes.items():
        d[k] = v
    return d


def test_the_contract_diff_names_each_change_and_the_version_it_needs():
    same = ml.contract_diff(PUBLISHED, PUBLISHED)
    assert same["changes"] == [] and same["bump"] == "none" and same["ok"]
    added = ml.contract_diff(PUBLISHED, _with(measures={**PUBLISHED["measures"], "Trades": {"expr": "count(trade)"}}))
    assert added["bump"] == "minor" and added["next"] == "1.3.0" and not added["ok"] and "adds the measure Trades" in added["changes"][0]["text"]
    patch = ml.contract_diff(PUBLISHED, _with(fields={"trade.mtm": {"description": "Mark to market, in the trade's currency", "folder": "Risk"}}))
    assert patch["bump"] == "patch" and patch["next"] == "1.2.1"
    broken = ml.contract_diff(PUBLISHED, _with(measures={"MTM": {"expr": "sum(trade.pnl1d)"}}, version="2.0.0"))
    levels = {c["text"].split(" ")[0] + " " + c["what"]: c["level"] for c in broken["changes"]}
    assert broken["bump"] == "major" and broken["next"] == "2.0.0" and broken["ok"]
    assert levels["removes measure"] == "major" and any(c["level"] == "major" and c["text"].startswith("changes what MTM computes") for c in broken["changes"])
    hidden = ml.contract_diff(PUBLISHED, _with(fields={"trade.mtm": {"description": "Mark to market", "hidden": True}}))
    assert hidden["bump"] == "major" and "hides trade.mtm from consumers" in [c["text"] for c in hidden["changes"]]
    sec = ml.contract_diff(PUBLISHED, _with(security={"hidden-for": [{"roles": ["external"], "fields": ["trade.book"]}]}))
    assert sec["bump"] == "none" and sec["changes"][0]["what"] == "security"


def test_the_contract_route_compares_with_the_published_version():
    client, backend = make()
    body = ml.to_yaml(PUBLISHED)
    backend.items["bi-m9"] = {"id": "bi-m9", "kind": "model", "publishedVersion": 3, "body": body}
    orig = backend.bi

    async def bi(method, path, ident, body=None, **params):
        if path == "/items/bi-m9/versions/3":
            return {"version": 3, "body": ml.to_yaml(PUBLISHED)}
        if path.endswith("/consumers"):
            return {"consumers": [{"kind": "report", "id": "bi-r1", "title": "Daily risk"}]}
        return await orig(method, path, ident, body, **params)
    backend.bi = bi
    r = client.post("/bi/model-api/contract", json={"id": "bi-m9", "doc": _with(measures={"MTM": PUBLISHED["measures"]["MTM"]})}).json()
    assert r["published"] and r["bump"] == "major" and r["ref"] == "trading-risk@1" and r["consumers"][0]["title"] == "Daily risk"
    backend.items["bi-m8"] = {"id": "bi-m8", "kind": "model", "publishedVersion": 0, "body": body}
    assert client.post("/bi/model-api/contract", json={"id": "bi-m8", "doc": PUBLISHED}).json()["published"] is False


def test_field_properties_and_perspectives_are_written_one_line_each():
    text = ml.to_yaml(PUBLISHED)
    assert "fields:\n  trade.mtm: {description: Mark to market}\n" in text
    assert "perspectives:\n  desk-view: {fields: [trade.desk], measures: [MTM, Notional]}\n" in text
    assert text.index("measures:") < text.index("fields:") < text.index("perspectives:")
