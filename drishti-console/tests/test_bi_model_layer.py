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

"""The model layer's console side (HOW_IT_FITS.md §A28 › The model layer: the specification of wave W1, sections 1 to 3, 5 and 9; docs/guides/BI_GUIDE.md "Models"): the pages are shells
that need the bi power; the proxy passes the model query paths on; a model document is written as YAML in the spec's order and reads back unchanged;
relationships are suggested from the packs' references (a second path and a fact-to-fact one proposed inactive, dismissed ones gone); a measure gets the
right default from its column; a new model infers star roles and never reuses a name; a report is made bound to one model; Make it a model rebinds it; the
Fields pane is the model as described; an Analyse extract of a model is one model query of shape rows."""
import pytest
import yaml

from bi_backend import BiBackend, BackendError
from conftest import CONSOLE
from core import model_layer as ml
from core.app import create_app
from core.config import load_settings
from fastapi.testclient import TestClient

TRADE = """rachana: 2
dataset: pack.trading.trade
version: 1.0.0
title: Trade
generated: {by: pack, pack: trading}
source: {pack: trading, kind: trade}
grain: {says: One row per trade per business date, keys: [id], date: businessDate}
time: {mode: snapshot, as-of: business-date}
columns:
  desk: {type: string, role: dimension, references: desk}
  currency: {type: string, role: dimension}
  notional: {type: decimal, role: amount, meaning: notional, unit: {currency: currency}, additive: true, aggregate: sum, format: amount0}
  eePeak: {type: decimal, role: amount, additive: {over-time: last}, aggregate: sum}
  utilisation: {type: decimal, role: ratio, additive: false}
"""
DESK = """rachana: 2
dataset: pack.core.desk
version: 1.0.0
title: Desk
generated: {by: pack, pack: core}
source: {pack: core, kind: desk}
grain: {says: One row per desk per business date, keys: [id]}
columns:
  name: {type: string, role: dimension}
  var99: {type: integer, role: amount, aggregate: sum}
"""
DESCRIBED = {"id": "implicit:ds_pack_trading_trade@1", "name": "pack.trading.trade", "version": "1.0.0", "implicit": True, "title": "Implicit model of Trade",
             "datasets": [{"alias": "trade", "dataset": "pack.trading.trade", "version": "1.0.0", "role": "fact", "mode": "cached",
                           "fields": [{"field": "trade.desk", "type": "string", "role": "dimension", "references": "desk", "masked": False}]},
                          {"alias": "desk", "dataset": "pack.core.desk", "version": "1.0.0", "role": "dimension", "fields": [{"field": "desk.name", "type": "string", "masked": True}]}],
             "measures": [{"name": "Sum of notional", "expr": "sum(trade.notional)", "implicit": True, "additive": True, "masked": False}], "hierarchies": {}}


class LayerBackend(BiBackend):
    def __init__(self, powers=("bi", "bi-author")):
        super().__init__(powers)
        self.items, self.models = {}, [{"id": "bi-m1", "name": "trades-and-desks", "kind": "model", "version": "1.0.0", "title": "Taken"}]

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/pack-datasets":
            self.bi_calls.append((method, path, body, params))
            return {"datasets": [{"name": "pack.trading.trade", "kind": "trade", "pack": "trading", "title": "Trade"}, {"name": "pack.core.desk", "kind": "desk", "pack": "core", "title": "Desk"}]}
        if path.startswith("/pack-datasets/"):
            self.bi_calls.append((method, path, body, params))
            name = path.rsplit("/", 1)[-1]
            if name not in ("pack.trading.trade", "pack.core.desk"):
                raise BackendError(404, "DRS-9001", "no generated dataset")
            return {"name": name, "yaml": TRADE if name.endswith("trade") else DESK, "readOnly": True}
        if path == "/models":
            self.bi_calls.append((method, path, body, params))
            return {"models": self.models}
        if path == "/models/validate":
            self.bi_calls.append((method, path, body, params))
            return {"kind": "model", "valid": True, "problems": [], "exposures": [], "measures": ["Count of trade"], "echo": body["yaml"]}
        if path.endswith("/describe"):
            self.bi_calls.append((method, path, body, params))
            return DESCRIBED
        if method == "POST" and path.startswith("/workspaces/") and path.endswith("/items"):
            self.bi_calls.append((method, path, body, params))
            iid = f"bi-new{len(self.items) + 1}"
            self.items[iid] = dict(body, id=iid, workspace=path.split("/")[2])
            return self.items[iid]
        if path.startswith("/items/") and path[7:] in self.items:
            self.bi_calls.append((method, path, body, params))
            if method == "PUT":
                self.items[path[7:]].update(body)
            return dict(self.items[path[7:]], etag="e1")
        return await super().bi(method, path, ident, body, **params)


def make(powers=("bi", "bi-author")):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = LayerBackend(powers)
    return TestClient(app), app.state.backend


MODEL = {"rachana": 2, "model": "trading-risk", "version": "1.0.0", "title": "Trading risk",
         "datasets": {"trade": {"dataset": "pack.trading.trade@^1", "role": "fact"}, "desk": {"dataset": "pack.core.desk@^1", "role": "dimension"}},
         "relationships": [], "measures": {"MTM": {"expr": "sum(trade.mtm)", "format": "signed0"}}}


# ---- pages and the proxy ------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("url,script", [("/bi/models", "drishti_bi-models.js"), ("/bi/ds/pack.trading.trade", "drishti_bi-models.js")])
def test_the_pages_are_shells_with_their_scripts_and_need_the_bi_power(url, script):
    client, _ = make()
    r = client.get(url)
    assert r.status_code == 200 and script in r.text and "drishti_bi-layer.js" in r.text and "drishti_bi-layer.css" in r.text
    assert make(powers=())[0].get(url).status_code == 403


@pytest.mark.parametrize("url,to", [("/bi/md/bi-m1", "/build/tables?kind=model&item=bi-m1"), ("/bi/datasets/new-layer", "/build/tables?new=table"),
                                    ("/bi/datasets/new", "/build/tables?new=table"), ("/bi/datasets/new?edit=bi-d1", "/build/tables?kind=table&item=bi-d1")])
def test_the_removed_designer_and_wizards_lead_to_build_tables_and_models(url, to):
    """The model designer and the dataset wizards are gone (one way per job): their old addresses open Build › Tables & models."""
    r = make()[0].get(url, follow_redirects=False)
    assert r.status_code == 301 and r.headers["location"] == to


def test_the_proxy_passes_the_model_query_paths_on():
    client, backend = make()
    assert client.get("/bi/api/models").json() == {"models": backend.models}
    assert client.get("/bi/api/pack-datasets").status_code == 200
    client.post("/bi/api/mq", json={"mq": 1, "model": "dataset:pack.trading.trade", "select": {"measures": ["Count of trade"]}})
    assert any(c[1] == "/mq" and c[2]["model"] == "dataset:pack.trading.trade" for c in backend.bi_calls)


# ---- the model document -------------------------------------------------------------------------------------------------

def test_a_model_is_written_in_the_spec_order_one_line_per_entry_and_reads_back():
    doc = dict(MODEL, relationships=[{"from": "trade.desk", "to": "desk.id", "suggested": {"from": "pack-reference"}}],
               security={"rows": {"trade": [{"role": "desk-head", "where": "trade.desk in user.desks"}]}, "may-see-all": {"desk": ["desk-head"]}},
               layout={"positions": {"trade": [24, 24]}}, future={"kept": True})
    text = ml.to_yaml(doc)
    assert text.index("rachana") < text.index("model:") < text.index("datasets:") < text.index("relationships:") < text.index("measures:") < text.index("security:") < text.index("layout:")
    assert "  trade: {dataset: pack.trading.trade@^1, role: fact}" in text
    assert "  - {from: trade.desk, to: desk.id, suggested: {from: pack-reference}}" in text
    assert "      - {role: desk-head, where: trade.desk in user.desks}" in text and "future:" in text
    assert yaml.safe_load(text) == doc


# ---- new models, reports, promotion -------------------------------------------------------------------------------------


def test_make_it_a_model_writes_the_implicit_model_and_rebinds_the_report():
    client, backend = make()
    rep = client.post("/bi/model-api/report", json={"dataset": "pack.trading.trade", "title": "Trades"}).json()
    body = backend.items[rep["id"]]["body"]
    assert "model: dataset:pack.trading.trade" in body and backend.items[rep["id"]]["kind"] == "report"
    assert any(c[1].endswith("/describe") for c in backend.bi_calls)                        # the person may read it, before a draft names it
    r = client.post("/bi/model-api/promote", json={"report": rep["id"]}).json()
    model = yaml.safe_load(backend.items[r["model"]]["body"])
    assert model["implicit-from"] == "pack.trading.trade" and model["rachana"] == 3 and model["tables"]["desk"]["role"] == "dimension"
    assert model["relationships"] == [{"from": "trade.desk", "to": "desk.id", "suggested": {"from": "pack-reference"}}]
    assert {"Count of trade", "Sum of notional", "Average of utilisation"} <= set(model["measures"])
    assert f"model: {r['ref']}" in backend.items[rep["id"]]["body"] and "dataset:pack" not in backend.items[rep["id"]]["body"]


def test_the_fields_pane_reads_the_model_as_described():
    client, _ = make()
    f = client.get("/bi/model-api/fields", params={"ref": "dataset:pack.trading.trade"}).json()
    assert [t["name"] for t in f["tables"]] == ["trade", "desk"] and f["tables"][1]["columns"][0] == {"name": "name", "type": "string", "format": None, "masked": True,
                                                                                                    "description": "", "meaning": "", "role": "", "references": ""}
    assert f["measures"][0]["name"] == "Sum of notional" and f["model"]["implicit"] and f["model"]["dataset"] == "pack.trading.trade"
    assert client.get("/bi/model-api/fields").json()["code"] == "DRS-9140"


def test_a_dataset_page_reads_a_pack_dataset_as_facts():
    client, _ = make()
    d = client.get("/bi/model-api/dataset", params={"name": "pack.trading.trade"}).json()
    i = d["info"]
    assert i["readOnly"] and i["generated"] and i["grain"]["says"] == "One row per trade per business date" and i["time"]["mode"] == "snapshot"
    assert {c["name"]: c["references"] for c in i["columns"]}["desk"] == "desk" and d["yaml"] == TRADE
    assert client.get("/bi/model-api/dataset", params={"name": "pack.x.y"}).status_code == 404


def test_an_analyse_extract_of_a_model_is_one_model_query_of_rows():
    pytest.importorskip("pyarrow")
    import io

    import pyarrow as pa

    client, backend = make()
    sent = []

    async def arrow(path, ident, body, **params):
        sent.append((path, body))
        t = pa.table({"trade.desk": ["FX"], "trade.notional": [1.0]})
        sink = io.BytesIO()
        with pa.ipc.new_stream(sink, t.schema) as w:
            w.write_table(t)
        return sink.getvalue(), {"X-Bi-Masked": ""}

    backend.bi_arrow = arrow
    r = client.post("/bi/notebook/extract", json={"model": "dataset:pack.trading.trade", "fields": ["trade.desk", "trade.notional"]})
    assert r.status_code == 200, r.text
    assert sent[0][0] == "/mq" and sent[0][1]["shape"] == "rows" and sent[0][1]["select"] == {"fields": ["trade.desk", "trade.notional"], "measures": []}


def test_make_it_a_model_reads_the_implicit_dataset_however_the_designer_wrote_it():
    """The designer saves ``model: "dataset:..."`` quoted (W1 QA P1-2): Make it a model reads it quoted, single-quoted or plain, and nothing else."""
    for line in ('model: "dataset:pack.trading.trade"', "model: 'dataset:pack.trading.trade'", "model: dataset:pack.trading.trade"):
        assert ml.implicit_source("report: r\n" + line + "\npages: []\n") == "pack.trading.trade"
    assert ml.implicit_source("report: r\nmodel: trades@1\n") is None
    assert ml.implicit_source("report: r\n") is None
    assert ml.rebind_report('report: r\nmodel: "dataset:pack.trading.trade"\n', "trade-model@1") == "report: r\nmodel: trade-model@1\n"
