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

"""Wave C (docs/guides/ANALYSE.md "Promote to a report"): a slice or a SQL cell of Analyse becomes a visual in a new or an existing report draft, with its dataset, wells and
filters mapped, and notes for what a report cannot say."""
import pytest

pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend, DATASET_MODEL  # noqa: E402
from core.promote_report import add_to_report, matching_measure, sql_to_slice, visual_for  # noqa: E402
from test_bi_explore import DS, make  # noqa: E402

COLS = ["desk", "currency", "notional", "mtm"]
MODEL = {**DATASET_MODEL, "name": "trades-basic", "tables": [{"name": "trade"}],
         "measures": [{"name": "MTM", "expr": "sum(trade.mtm)"}, {"name": "Trades", "expr": "count(trade)"}, {"name": "Avg MTM", "expr": "avg(trade.mtm)"}]}
QUAL = {c: "trade." + c for c in COLS}


def qualify(f):
    return QUAL.get(f, f)


def test_a_group_by_query_reads_as_rows_values_and_filters():
    r = sql_to_slice("SELECT desk, currency, sum(mtm) AS total, count(*) FROM extract WHERE currency IN ('USD','EUR') AND notional > 100 AND desk <> 'EQ' GROUP BY desk, currency ORDER BY 3 DESC LIMIT 10", COLS)
    assert r["rows"] == ["desk", "currency"] and r["values"] == [{"field": "mtm", "agg": "sum"}, {"field": "*", "agg": "count"}]
    assert r["filters"] == [{"field": "currency", "op": "in", "values": ["USD", "EUR"]}, {"field": "notional", "op": "gt", "values": [100]}, {"field": "desk", "op": "ne", "values": ["EQ"]}]
    assert any("ORDER BY and LIMIT were left out" in n for n in r["notes"])


def test_an_implicit_alias_is_accepted_like_as():
    r = sql_to_slice("SELECT desk d, sum(notional) n, count(*) c FROM extract GROUP BY desk", COLS)
    assert r["rows"] == ["desk"] and r["values"] == [{"field": "notional", "agg": "sum"}, {"field": "*", "agg": "count"}]
    assert not any("sum(notional) n" in n for n in r["notes"])


def test_a_plain_select_is_a_table_and_star_is_every_column():
    r = sql_to_slice("select desk, mtm from extract where mtm between 1 and 5 and desk is not null", COLS)
    assert r["rows"] == ["desk", "mtm"] and r["values"] == [] and [f["op"] for f in r["filters"]] == ["between", "not-null"]
    assert sql_to_slice("SELECT * FROM extract", COLS)["rows"] == COLS


def test_what_a_report_cannot_say_is_in_the_notes_and_left_out():
    r = sql_to_slice("SELECT desk, notional * 2 AS twice, sum(mtm) FROM extract WHERE desk LIKE 'F%' GROUP BY desk HAVING sum(mtm) > 3", COLS)
    text = " ".join(r["notes"])
    assert "notional * 2" in text and "LIKE" in text and "HAVING" in text and r["rows"] == ["desk"] and r["values"] == [{"field": "mtm", "agg": "sum"}]
    assert "not a SELECT" in " ".join(sql_to_slice("DROP TABLE extract", COLS)["notes"])
    j = sql_to_slice("SELECT desk FROM extract JOIN other ON 1 = 1", COLS)
    assert any("Only a query over the table extract" in n for n in j["notes"])


def test_the_visual_is_a_matrix_of_dataset_measures_a_table_or_a_card():
    notes = []
    v = visual_for(MODEL, {"rows": ["desk"], "columns": ["currency"], "values": [{"field": "mtm", "agg": "sum"}, {"field": "*", "agg": "count"}, {"field": "notional", "agg": "sum"}],
                           "filters": [{"field": "desk", "op": "in", "values": ["FX"]}]}, "Desks", "slice", qualify, notes)
    assert v["type"] == "matrix" and v["wells"] == {"rows": ["trade.desk"], "values": ["MTM", "Trades"], "columns": ["trade.currency"]}
    assert v["filters"] == [{"field": "trade.desk", "op": "in", "values": ["FX"]}]
    assert notes == ["No dataset measure is sum(trade.notional); add one to the dataset, then put it in the visual's values."]
    assert visual_for(MODEL, {"rows": ["desk", "mtm"]}, "T", "sql", qualify, [])["type"] == "table"
    assert visual_for(MODEL, {"values": [{"field": "mtm", "agg": "avg"}]}, "C", "sql", qualify, [])["type"] == "card"
    assert visual_for(MODEL, {}, "none", "sql", qualify, []) is None
    assert matching_measure(MODEL, "count", "*") == "Trades" and matching_measure(MODEL, "sum", "trade.mtm") == "MTM" and matching_measure(MODEL, "min", "trade.mtm") is None


def test_adding_to_a_report_places_the_visual_below_gives_it_a_free_id_and_names_another_dataset():
    report = {"report": "r", "dataset": "other", "pages": [{"id": "p", "title": "P", "visuals": [{"id": "slice", "type": "card", "at": {"x": 0, "y": 0, "w": 4, "h": 3}, "wells": {}}]}]}
    notes = []
    out = add_to_report(report, {"id": "slice", "type": "table", "at": {"x": 0, "y": 0, "w": 12, "h": 6}, "wells": {"columns": ["trade.desk"]}}, "", "trades-basic", notes)
    added = out["pages"][0]["visuals"][1]
    assert added["id"] == "slice-2" and added["at"]["y"] == 3 and added["dataset"] == "trades-basic" and "names its own dataset" in notes[0]
    again = add_to_report(out, {"id": "x", "type": "table", "at": {"x": 0, "y": 0, "w": 1, "h": 1}, "wells": {}}, "fresh", "other", [])
    assert [p["id"] for p in again["pages"]] == ["p", "fresh"] and "dataset" not in again["pages"][1]["visuals"][0]


class PromoteBackend(BiBackend):
    def __init__(self, can_edit=True, kind="report"):
        super().__init__()
        self.can_edit, self.kind, self.puts = can_edit, kind, []

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/datasets/" + DS:
            return MODEL
        if method == "GET" and path.startswith("/items/file:report:mine"):
            return {"id": "file:report:mine", "kind": self.kind, "title": "Mine", "body": "report: mine\ndataset: trades-basic\n", "can": {"edit": self.can_edit}}
        if path == "/reports/validate":
            return {"valid": True, "problems": [], "report": {"report": "mine", "dataset": "trades-basic",
                                                              "pages": [{"id": "p", "title": "P", "visuals": [{"id": "a", "type": "card", "at": {"x": 0, "y": 0, "w": 4, "h": 2}, "wells": {"values": ["MTM"]}}]}]}}
        if path == "/reports/format":
            self.puts.append(body)
            return {"yaml": "report: mine\n# written by the server\n"}
        return await super().bi(method, path, ident, body, **params)


def _client(backend):
    client, original = make()
    client.app.state.backend = backend
    return client


def test_a_sql_cell_promoted_into_an_existing_report_is_added_after_what_is_there():
    backend = PromoteBackend()
    r = _client(backend).post("/bi/explore/promote", json={"to": "report", "name": "By desk", "dataset": DS, "target": "file:report:mine", "page": "p", "columns": COLS, "qualified": ["trade." + c for c in COLS],
                                                          "sql": "SELECT desk, sum(mtm) FROM extract WHERE currency = 'USD' GROUP BY desk"}).json()
    assert r["kind"] == "report" and r["target"] == "file:report:mine" and r["visual"] == "sql" and r["yaml"].startswith("report: mine")
    added = backend.puts[0]["report"]["pages"][0]["visuals"][1]
    assert added["type"] == "matrix" and added["wells"] == {"rows": ["trade.desk"], "values": ["MTM"]} and added["filters"] == [{"field": "trade.currency", "op": "eq", "values": ["USD"]}]
    assert added["at"]["y"] == 2 and "dataset" not in added and any("comments" in n for n in r["notes"])


def test_a_target_that_is_not_an_editable_report_is_refused():
    body = {"to": "report", "name": "x", "dataset": DS, "target": "file:report:mine", "sql": "SELECT desk FROM extract", "columns": COLS}
    assert _client(PromoteBackend(can_edit=False)).post("/bi/explore/promote", json=body).json()["code"] == "DRS-9070"
    assert _client(PromoteBackend(kind="dataset")).post("/bi/explore/promote", json=body).json()["code"] == "DRS-9070"


def test_a_slice_with_filters_promotes_them_and_a_new_report_is_still_the_default():
    r = _client(PromoteBackend()).post("/bi/explore/promote", json={"to": "report", "name": "Desk slice", "dataset": DS, "columns": COLS, "qualified": ["trade." + c for c in COLS],
                                                                    "slice": {"rows": ["desk"], "columns": [], "values": [{"field": "mtm", "agg": "sum"}]},
                                                                    "filters": [{"field": "currency", "op": "in", "values": ["EUR"]}]}).json()
    assert r["target"] is None and "type: matrix" in r["yaml"] and "dataset: trades-basic" in r["yaml"] and "trade.currency" in r["yaml"] and "EUR" in r["yaml"]
    empty = _client(PromoteBackend()).post("/bi/explore/promote", json={"to": "report", "name": "x", "dataset": DS, "sql": "SELECT 1", "columns": COLS}).json()
    assert empty["code"] == "DRS-9070" and "no fields or measures" in empty["detail"]
