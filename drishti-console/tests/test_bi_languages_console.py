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

"""Wave A of the BI design, console lane (docs/architecture/ARCHITECTURE.md §28 section 12.2): the ExpressionEditor on every page that writes an expression, the
report's own measures read and written by the console, the proxy passing the language endpoints (``/expr``, ``/sql``) on, the dataset wizard's derivation
document, the model designer's field properties, the YAML round trip that keeps comments and order, and Analyse's SQL on the server. The browser side is
tests/test_bi_languages_browser.py."""
import pytest

from core import model_layer as ml
from test_bi_model_layer import make


@pytest.mark.parametrize("url", ["/bi/design/bi-r1", "/bi/notebook"])
def test_every_page_that_writes_an_expression_loads_the_editor(url):
    client, _ = make()
    text = client.get(url).text
    assert "drishti_bi-expr-editor.js" in text and "drishti_bi-expr.css" in text


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


PUBLISHED = {"rachana": 2, "model": "trading-risk", "version": "1.2.0", "title": "Trading risk",
             "datasets": {"trade": {"dataset": "pack.trading.trade@^1", "role": "fact"}, "desk": {"dataset": "pack.core.desk@^1", "role": "dimension"}},
             "relationships": [{"from": "trade.desk", "to": "desk.id"}],
             "measures": {"MTM": {"expr": "sum(trade.mtm)", "format": "signed0"}, "Notional": {"expr": "sum(trade.notional)"}},
             "fields": {"trade.mtm": {"description": "Mark to market"}}, "perspectives": {"desk-view": {"fields": ["trade.desk"], "measures": ["MTM", "Notional"]}}}


def _with(**changes):
    import copy
    d = copy.deepcopy(PUBLISHED)
    for k, v in changes.items():
        d[k] = v
    return d


def test_field_properties_and_perspectives_are_written_one_line_each():
    text = ml.to_yaml(PUBLISHED)
    assert "fields:\n  trade.mtm: {description: Mark to market}\n" in text
    assert "perspectives:\n  desk-view: {fields: [trade.desk], measures: [MTM, Notional]}\n" in text
    assert text.index("measures:") < text.index("fields:") < text.index("perspectives:")


def test_a_notebook_sql_cell_runs_drishti_sql_on_the_server_over_the_extracts_model():
    from core.notebook import model_ref
    assert model_ref("mq:trading-risk@1") == "trading-risk@1" and model_ref("dataset:pack.trading.trade") == "dataset:pack.trading.trade"
    assert model_ref("file:dataset:trading") == "trading@1" and model_ref("trading-risk") == "trading-risk@1" and model_ref("") is None
    client, backend = make()
    sent = []
    orig = backend.bi

    async def bi(method, path, ident, body=None, **params):
        if path == "/sql":
            sent.append(body)
            return {"columns": [{"name": "desk", "type": "string"}, {"name": "mtm", "type": "decimal"}], "rows": [["FX", 1.0], ["RATES", 2.0], ["EQ", 3.0]],
                    "meta": {"millis": 7, "plan": "p-1234567890abcdef", "masked": []}}
        return await orig(method, path, ident, body, **params)
    backend.bi = bi
    limits = client.app.state.notebook_limits
    r = client.post("/bi/notebook/sql", json={"extract": {"dataset": "mq:trading-risk@1", "fields": ["trade.desk"]}, "sql": "SELECT desk.name, MEASURE('MTM') FROM trade JOIN desk GROUP BY desk.name;"})
    assert r.status_code == 200, r.text
    got = r.json()
    assert sent[0] == {"sql": "SELECT desk.name, MEASURE('MTM') FROM trade JOIN desk GROUP BY desk.name", "model": "trading-risk@1", "limit": limits.sql_max_rows + 1}
    assert got["columns"] == ["desk", "mtm"] and got["rowCount"] == 3 and got["engine"] == "server" and got["plan"] == "p-1234567890abcdef" and got["ms"] == 7
    older = client.post("/bi/notebook/sql", json={"extract": {"dataset": "file:dataset:trading"}, "sql": "SELECT 1"})   # a notebook saved before 2.2: the converter's model
    assert older.status_code == 200 and sent[-1]["model"] == "trading@1"


HAND_WRITTEN = """# Trading risk: the desk heads' model.
# Reviewed by the risk committee.
rachana: 2
model: trading-risk
version: 1.0.0
title: Trading risk
datasets:
  trade: {dataset: pack.trading.trade@^1, role: fact}     # the fact
  desk: {dataset: pack.core.desk@^1, role: dimension}
relationships:
  - {from: trade.desk, to: desk.id}

measures:
  # money
  MTM: {expr: sum(trade.mtm), format: signed0}
  # size
  Notional: {expr: sum(trade.notional)}
security:
  masked: [desk.head]   # PII
"""


def test_a_designer_edit_keeps_the_comments_the_order_and_the_untouched_lines():
    import yaml
    doc = yaml.safe_load(HAND_WRITTEN)
    assert ml.to_yaml_keeping(HAND_WRITTEN, doc) == HAND_WRITTEN                        # no edit, no change: byte for byte
    doc["measures"]["Notional"] = {"expr": "sum(trade.notional)", "format": "amount0"}
    doc["measures"]["Trades"] = {"expr": "count(trade)"}
    doc["fields"] = {"trade.mtm": {"description": "Mark to market"}}
    del doc["relationships"]
    text = ml.to_yaml_keeping(HAND_WRITTEN, doc)
    assert yaml.safe_load(text) == doc
    assert text.startswith("# Trading risk: the desk heads' model.\n# Reviewed by the risk committee.\nrachana: 2\n")
    assert "  trade: {dataset: pack.trading.trade@^1, role: fact}     # the fact\n" in text      # an untouched entry keeps its own comment
    assert "  # money\n  MTM: {expr: sum(trade.mtm), format: signed0}\n  # size\n  Notional: {expr: sum(trade.notional), format: amount0}\n  Trades: {expr: count(trade)}\n" in text
    assert "relationships" not in text and "masked: [desk.head]   # PII" in text
    assert text.index("measures:") < text.index("fields:") < text.index("security:")          # a new key goes where the spec's order puts it
    assert ml.to_yaml_keeping("", doc) == ml.to_yaml(doc)
    assert yaml.safe_load(ml.to_yaml_keeping("{model: x, version: 1.0.0}", {"model": "x", "version": "2.0.0"})) == {"model": "x", "version": "2.0.0"}


def test_the_fields_pane_inherits_the_models_field_properties_and_perspectives():
    described = {"model": {"id": "bi-m1", "name": "trading-risk", "version": "1.0.0"}, "title": "Trading risk",
                 "datasets": [{"alias": "trade", "dataset": "pack.trading.trade", "role": "fact",
                               "fields": [{"field": "trade.mtm", "type": "decimal"}, {"field": "trade.book", "type": "string"}, {"field": "trade.desk", "type": "string"}]}],
                 "measures": [{"name": "MTM", "expr": "sum(trade.mtm)"}]}
    doc = {"fields": {"trade.mtm": {"display": "Mark to market", "folder": "Risk", "description": "MTM in the trade's currency"}, "trade.book": {"hidden": True}},
           "measures": {"MTM": {"expr": "sum(trade.mtm)", "folder": "Risk"}}, "perspectives": {"desk-view": {"title": "Desk view", "fields": ["trade.desk"], "measures": ["MTM"]}}}
    assert ml.needs_model_doc(described)
    f = ml.fields_shape(described, "trading-risk@1", doc)
    cols = {c["name"]: c for c in f["tables"][0]["columns"]}
    assert cols["mtm"]["display"] == "Mark to market" and cols["mtm"]["folder"] == "Risk" and cols["mtm"]["description"] == "MTM in the trade's currency"
    assert cols["book"]["hidden"] is True and "hidden" not in cols["desk"]
    assert f["measures"][0]["folder"] == "Risk" and f["perspectives"] == {"desk-view": {"title": "Desk view", "description": "", "fields": ["trade.desk"], "measures": ["MTM"]}}
    described["datasets"][0]["fields"][0]["display"] = "MTM (server)"                      # a server that describes the properties wins
    assert not ml.needs_model_doc(described) and ml.fields_shape(described, "trading-risk@1", doc)["tables"][0]["columns"][0]["display"] == "MTM (server)"
    described["perspectives"] = [{"name": "desk-view", "title": "Desk view", "description": "For desk heads"}]   # the server names them; ?perspective= lists one
    assert ml.fields_shape(described, "trading-risk@1")["perspectives"] == {"desk-view": {"title": "Desk view", "description": "For desk heads", "fields": None, "measures": None}}


def test_the_fields_route_asks_one_perspective():
    client, backend = make()
    asked = []
    orig = backend.bi

    async def bi(method, path, ident, body=None, **params):
        asked.append((path, params))
        if path.endswith("/impact"):
            return {"consumers": [{"kind": "report", "id": "bi-r1", "title": "Daily risk"}]}
        if path == "/items/bi-m9/versions/3":
            return {"version": 3, "body": ml.to_yaml(PUBLISHED)}
        return await orig(method, path, ident, body, **params)
    backend.bi = bi
    client.get("/bi/model-api/fields?ref=trading-risk@1&perspective=desk-view")
    assert ("/models/trading-risk%401/describe", {"perspective": "desk-view"}) in asked


DATASET_ITEM = """# the desk's trades, refreshed for the morning
rachana: 2
expressions: 2
dataset: trades-daily
version: 1.0.0
source: { connector: trading-store, kind: trade }   # the demo connector
refresh: { on-load: true }
columns:
  desk: { type: string }
"""


def test_set_a_schedule_writes_refresh_into_the_dataset_draft_keeping_its_comments():
    client, backend = make()
    backend.items["bi-d1"] = {"id": "bi-d1", "kind": "dataset", "body": DATASET_ITEM, "can": {"edit": True}}
    r = client.post("/bi/model-api/dataset-refresh", json={"item": "bi-d1", "refresh": "business-days 06:30"})
    assert r.status_code == 200, r.text
    body = backend.items["bi-d1"]["body"]
    assert body.startswith("# the desk's trades, refreshed for the morning\n") and "# the demo connector" in body
    assert "refresh: {schedule: 'business-days 06:30', on-load: true}" in body                # a rebuild on load is kept beside the schedule
    assert client.post("/bi/model-api/dataset-refresh", json={"item": "bi-d1"}).status_code == 422
    backend.items["bi-d2"] = {"id": "bi-d2", "kind": "report", "body": "report: r\npages: []\n"}
    assert client.post("/bi/model-api/dataset-refresh", json={"item": "bi-d2", "refresh": "hourly"}).status_code == 422


def test_an_old_dataset_link_opens_the_page_of_a_model_layer_dataset():
    client, backend = make()
    orig = backend.bi

    async def bi(method, path, ident, body=None, **params):
        if path == "/layer-datasets/file:dataset:trades-basic-trade":
            return {"id": "file:dataset:trades-basic-trade", "yaml": "rachana: 2\ndataset: trades-basic-trade\n"}
        if path.startswith("/layer-datasets/"):
            from core.backend import BackendError
            raise BackendError(422, "DRS-9102", "a 2.1 dataset")
        return await orig(method, path, ident, body, **params)
    backend.bi = bi
    r = client.get("/bi/d/file:dataset:trades-basic-trade", follow_redirects=False)
    assert r.status_code == 307 and r.headers["location"] == "/bi/ds/file:dataset:trades-basic-trade"
    gone = client.get("/bi/d/file:dataset:live-trading", follow_redirects=False)                      # every dataset is a table now: no 2.1 page
    assert gone.status_code == 307 and gone.headers["location"] == "/bi/ds/file:dataset:live-trading"

