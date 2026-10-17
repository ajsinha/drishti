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

"""Rupaka phase 9, Analyse (docs/guides/ANALYSE.md): the extract is the reader's rows (two readers, two extracts, nothing kept between calls), the SQL cell is read-only
and limited, notebooks check and promote, the quant stand-in prices, a Python cell runs with ``df`` and ``quant``, and the console's and the server's Python allow-lists agree."""
import io
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend, DATASET_MODEL  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402
from core.explore import ExploreError, Limits, from_arrow, parse_notebook, promote_python, run_sql  # noqa: E402
from core.quant import QuantError, get_engine  # noqa: E402

DS = "file:dataset:trades-basic"
FIELDS = ["trade.desk", "trade.notional", "trade.mtm"]
ALL = [("FX", 100.0, 5.0), ("FX", 200.0, -3.0), ("RATES", 300.0, 7.0), ("RATES", 50.0, 1.0), ("EQ", 10.0, 2.0)]


def arrow(rows, masked_mtm=False):
    t = pa.table({"trade.desk": [r[0] for r in rows], "trade.notional": [r[1] for r in rows],
                  "trade.mtm": [None if masked_mtm else r[2] for r in rows], "trade.id": [str(i) for i in range(len(rows))]})
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, t.schema) as w:
        w.write_table(t)
    return sink.getvalue()


class ExploreBackend(BiBackend):
    """Plays the server: what a dataset query returns depends on who asks (row rules and masks are the server's job)."""

    def __init__(self, powers=("bi", "bi-author")):
        super().__init__(powers)
        self.reader, self.queries = "ann", []

    async def bi_arrow(self, path, ident, body, **params):
        self.queries.append((path, body, ident.user))
        if self.reader == "bob":                                   # bob: only FX rows, mtm masked
            return arrow([r for r in ALL if r[0] == "FX"], masked_mtm=True), {"X-Bi-Rows": "2", "X-Bi-Masked": "trade.mtm"}
        return arrow(ALL), {"X-Bi-Rows": str(len(ALL)), "X-Bi-Masked": ""}


def make(powers=("bi", "bi-author"), **explore):
    s = load_settings(CONSOLE / "config")
    s.as_dict().setdefault("bi", {}).setdefault("explore", {}).update(explore)
    app = create_app(s)
    app.state.backend = ExploreBackend(powers)
    return TestClient(app), app.state.backend


def extract(client, **extra):
    return client.post("/bi/explore/extract", json={"dataset": DS, "fields": FIELDS, **extra})


# ---- the extract ---------------------------------------------------------------------------------------------------------------
def test_the_slice_is_worked_out_by_the_console_as_the_reader():
    """Wave B: the browser adds nothing up; the console pivots the reader's extract (POST /bi/explore/pivot)."""
    client, backend = make()
    spec = {"dataset": DS, "fields": FIELDS}
    r = client.post("/bi/explore/pivot", json={"extract": spec, "slice": {"rows": ["desk"], "columns": [], "values": [{"field": "notional", "agg": "sum"}, {"field": "mtm", "agg": "count"}],
                                                                            "filters": [{"field": "desk", "value": ""}]}}).json()
    assert r["engine"] == "console" and r["used"] == 5 and r["header"] == ["desk", "sum notional", "count mtm"]
    assert r["body"] == [["EQ", 10.0, 1], ["FX", 300.0, 2], ["RATES", 350.0, 2]] and r["distinct"] == {"desk": ["EQ", "FX", "RATES"]}
    backend.reader = "bob"                                         # the same slice for a reader with fewer rows and mtm masked
    b = client.post("/bi/explore/pivot", json={"extract": spec, "slice": {"rows": ["desk"], "values": [{"field": "mtm", "agg": "sum"}]}}).json()
    assert b["body"] == [["FX", ""]] and b["masked"] == ["mtm"]
    by_col = client.post("/bi/explore/pivot", json={"extract": spec, "slice": {"columns": ["desk"], "values": [{"field": "notional", "agg": "max"}]}}).json()
    assert by_col["header"] == ["(all rows)", "FX"] and by_col["body"] == [["(all)", 200.0]]


def test_two_readers_get_different_extracts_and_nothing_is_kept():
    client, backend = make()
    a = extract(client).json()
    backend.reader = "bob"
    b = extract(client).json()
    assert a["rowCount"] == 5 and b["rowCount"] == 2
    assert {r[0] for r in b["rows"]} == {"FX"} and all(r[2] is None for r in b["rows"])                  # bob's rows only, mtm masked
    assert b["masked"] == ["mtm"] and a["masked"] == []
    backend.reader = "ann"
    assert extract(client).json()["rowCount"] == 5                                                          # rebuilt for the reader on every call
    assert [c["name"] for c in a["columns"]] == ["desk", "notional", "mtm"]                                 # short names; the row id is dropped
    path, body, _ = backend.queries[0]
    assert path == "/mq" and body["model"] == "trades-basic@1" and body["shape"] == "rows"                   # the table's own rows, as one model query
    assert body["select"] == {"fields": FIELDS, "measures": []}


def test_the_row_limit_cuts_the_extract():
    client, _ = make(max_rows=10)
    ex = Limits.from_settings(client.app.state.settings if hasattr(client.app.state, "settings") else load_settings(CONSOLE / "config"))
    assert ex.max_rows >= 10
    out = from_arrow(arrow(ALL * 5), 10)
    assert len(out.rows) == 10 and out.total == 25 and out.truncated and out.arrow


def test_a_bad_extract_request_is_refused_with_a_code():
    client, _ = make()
    assert client.post("/bi/explore/extract", json={"dataset": DS}).json()["code"] == "DRS-9070"
    assert client.post("/bi/explore/extract", json={"dataset": DS, "fields": ["a;drop"]}).status_code == 422
    assert client.post("/bi/explore/extract", json={"fields": FIELDS}).status_code == 422
    assert client.post("/bi/explore/extract", data="x", headers={"content-type": "text/plain"}).status_code in (403, 415, 422)


# ---- SQL -------------------------------------------------------------------------------------------------------------------------
def sql(client, text, **extra):
    return client.post("/bi/explore/sql", json={"extract": {"dataset": DS, "fields": FIELDS}, "sql": text, **extra})


def test_sql_runs_a_select_over_the_extract():
    client, backend = make()
    r = sql(client, "SELECT desk, SUM(notional) AS n FROM extract GROUP BY desk ORDER BY n DESC").json()
    assert r["columns"] == ["desk", "n"] and r["rows"][0] == ["RATES", 350.0] and r["inputRows"] == 5
    backend.reader = "bob"
    assert sql(client, "SELECT COUNT(*) FROM extract").json()["rows"] == [[2]]                              # the reader's rows, not the table's


@pytest.mark.parametrize("text", ["DROP TABLE extract", "DELETE FROM extract", "INSERT INTO extract VALUES (1,2,3)", "ATTACH DATABASE 'x.db' AS x",
                                  "PRAGMA table_info(extract)", "CREATE TABLE t(a)", "SELECT load_extension('x')", "UPDATE extract SET desk='x'"])
def test_sql_is_read_only(text):
    client, _ = make()
    r = sql(client, text)
    assert r.status_code in (403, 422) and r.json()["code"] == "DRS-9073"


def test_sql_takes_one_statement_and_one_table():
    client, _ = make()
    assert sql(client, "SELECT 1; DROP TABLE extract").json()["code"] == "DRS-9070"
    assert sql(client, "SELECT * FROM sqlite_master").status_code in (200, 403, 422)                          # the catalogue holds only the extract's own definition
    assert sql(client, "").json()["code"] == "DRS-9070"


def test_sql_limits_rows_text_and_time():
    client, _ = make(sql_max_rows=2, sql_max_chars=100, sql_max_seconds=0.5)
    r = sql(client, "SELECT * FROM extract").json()
    assert r["rowCount"] == 2 and r["truncated"] is True                                                      # cut to sql_max_rows
    assert sql(client, "SELECT " + "1," * 60 + "1 FROM extract").json()["code"] == "DRS-9072"                 # too long
    slow = sql(client, "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c) SELECT COUNT(*) FROM c")
    assert slow.status_code == 504 and slow.json()["code"] == "DRS-9072"                                      # the deadline stops it


def test_run_sql_on_an_empty_extract_works():
    ex = from_arrow(arrow([]), 10)
    assert run_sql(ex, "SELECT COUNT(*) FROM extract", Limits())["rows"] == [[0]]
    with pytest.raises(ExploreError):
        run_sql(ex, "SELECT * FROM nope", Limits())


# ---- Python cells -------------------------------------------------------------------------------------------------------------------
def cell(client, source, **extra):
    return client.post("/bi/explore/python", json={"extract": {"dataset": DS, "fields": FIELDS}, "source": source, **extra})


def test_a_python_cell_sees_df_and_quant_and_returns_a_table():
    client, _ = make()
    r = cell(client, "print(len(df))\np = quant.price('npv', rate=0.1, cashflows=[-100, 110])\nresult = df.groupby('desk')['notional'].sum().reset_index()\nprint(round(abs(p), 6))")
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["kind"] == "cell" and d["stdout"].split() == ["5", "0.0"] and d["target"] == "server" and d["inputRows"] == 5
    assert d["table"]["columns"] == ["desk", "notional"] and ["FX", 300.0] in d["table"]["rows"]


def test_a_python_cell_over_a_masked_extract_sees_the_mask():
    client, backend = make()
    backend.reader = "bob"
    d = cell(client, "result = [int(df['mtm'].isna().sum()), len(df)]").json()
    assert d["value"] == [2, 2] and d["masked"] == ["mtm"]


def test_a_python_cell_can_draw_a_chart_and_cannot_use_the_network_or_files():
    client, _ = make()
    d = cell(client, "result = {'xAxis': {'data': list(df['desk'])}, 'series': [{'type': 'bar', 'data': list(df['notional']), 'tooltip': {'formatter': 'x'}}]}").json()
    assert d["option"]["xAxis"]["data"][0] == "FX" and "formatter" not in str(d["option"])
    assert cell(client, "import socket").json()["code"] == "DRS-9073"
    assert cell(client, "open('/etc/passwd').read()").json()["code"] == "DRS-9073"
    assert cell(client, "x = 1/0").json()["code"] == "DRS-9071"
    assert cell(client, "quant.price('nope')").json()["code"] == "DRS-9071"


def test_python_cells_need_the_author_power_and_respect_the_text_limit():
    client, _ = make(powers=("bi",))
    assert cell(client, "print(1)").json()["code"] == "DRS-9073"
    client, _ = make(cell_max_chars=100)
    assert cell(client, "x = 1\n" * 40).json()["code"] == "DRS-9072"


# ---- the quant stand-in -----------------------------------------------------------------------------------------------------------
def test_the_quant_stand_in_prices():
    q = get_engine("stand-in")
    call = q.price("black_scholes", spot=100, strike=100, rate=0.05, vol=0.2, years=1)
    put = q.price("black_scholes", spot=100, strike=100, rate=0.05, vol=0.2, years=1, kind="put")
    assert call == pytest.approx(10.4506, abs=1e-3) and put == pytest.approx(5.5735, abs=1e-3)
    assert call - put == pytest.approx(100 - 100 * 2.718281828459045 ** -0.05, abs=1e-9)                       # put-call parity
    assert q.price("bond", coupon=0.05, yield_=0.05, years=10, freq=2) == pytest.approx(100.0, abs=1e-9)         # a par bond
    assert q.price("npv", rate=0.1, cashflows=[-100, 110]) == pytest.approx(0.0, abs=1e-9)
    assert set(q.instruments()) == {"black_scholes", "bond", "npv"} and q.describe()["engine"] == "stand-in"
    for bad in (("nope", {}), ("black_scholes", {"spot": 1}), ("npv", {"rate": 0.1, "cashflows": "x"}), ("black_scholes", {"spot": -1, "strike": 1, "rate": 0, "vol": 1, "years": 1})):
        with pytest.raises(QuantError):
            q.price(bad[0], **bad[1])
    with pytest.raises(QuantError):
        get_engine("qlrisk")                                                                                     # not installed: the slot is documented, not wired


# ---- notebooks and promotion ---------------------------------------------------------------------------------------------------------
NOTEBOOK = {"rachana": 1, "notebook": "desk-look", "title": "Desk look", "dataset": DS, "fields": FIELDS,
            "cells": [{"type": "slice", "slice": {"rows": ["desk"], "columns": [], "values": [{"field": "notional", "agg": "sum"}], "filters": []}},
                      {"type": "sql", "source": "SELECT 1"}, {"type": "python", "source": "print(1)", "target": "server"}]}


def test_a_notebook_checks_and_round_trips():
    client, _ = make()
    c = client.post("/bi/explore/notebook/check", json={"notebook": NOTEBOOK}).json()
    assert c["valid"] and c["cells"] == 3 and "notebook: desk-look" in c["yaml"]
    again = client.post("/bi/explore/notebook/check", json={"yaml": c["yaml"]}).json()
    assert again["notebook"]["cells"][2]["source"] == "print(1)"
    bad = client.post("/bi/explore/notebook/check", json={"notebook": {**NOTEBOOK, "cells": [{"type": "shell", "source": "ls"}]}}).json()
    assert not bad["valid"] and "cell 1" in bad["problems"][0]["message"]
    assert not client.post("/bi/explore/notebook/check", json={"notebook": {**NOTEBOOK, "surprise": 1}}).json()["valid"]
    with pytest.raises(ExploreError):
        parse_notebook("cells: " + str([{"type": "sql", "source": "x"}] * 3), Limits(max_cells=2))


def test_promote_a_python_cell_to_a_measure_and_a_visual_draft():
    client, _ = make()
    m = client.post("/bi/explore/promote", json={"to": "measure", "name": "Desk spread", "source": "import numpy\nresult = float(df['mtm'].std())", "columns": ["mtm"]}).json()
    assert m["kind"] == "python" and "interface: measure" in m["yaml"] and "def compute(table, params):" in m["yaml"] and "numpy" in m["yaml"]
    ns = {}
    import yaml as y
    doc = y.safe_load(m["yaml"])
    exec(compile(doc["source"], "p", "exec"), ns)                                                              # the draft is real code
    import pandas as pd
    assert ns["compute"](pd.DataFrame({"mtm": [1.0, 3.0]}), {}) == pytest.approx(1.4142135, abs=1e-6)
    v = client.post("/bi/explore/promote", json={"to": "visual", "name": "Chart", "source": "result = {}", "columns": []}).json()
    assert "interface: visual" in v["yaml"] and "output: echarts" in v["yaml"] and "render(table, params)" in v["yaml"]
    assert client.post("/bi/explore/promote", json={"to": "board"}).json()["code"] == "DRS-9070"
    assert promote_python("x = 1", "n", "visual", [], []).count("def render") == 1


def test_promote_a_slice_to_a_report_draft_names_the_measures_it_found():
    client, backend = make()
    described = {"datasets": [{"alias": "trade", "dataset": "trades-basic-trade"}], "measures": [{"name": "MTM", "expr": "sum(trade.mtm)"}]}

    async def bi(method, path, ident, body=None, **params):
        return described if path == "/models/trades-basic@1/describe" else await BiBackend.bi(backend, method, path, ident, body, **params)
    backend.bi = bi
    r = client.post("/bi/explore/promote", json={"to": "report", "name": "Desk slice", "dataset": DS,
                                                  "slice": {"rows": ["trade.desk"], "columns": [], "values": [{"field": "trade.mtm", "agg": "sum"}, {"field": "trade.notional", "agg": "sum"}]}}).json()
    assert r["kind"] == "report" and "type: matrix" in r["yaml"] and "model: trades-basic@1" in r["yaml"] and "MTM" in r["yaml"]
    assert len(r["notes"]) == 1 and "sum(trade.notional)" in r["notes"][0]
    assert client.post("/bi/explore/promote", json={"to": "report", "dataset": DS, "slice": {}}).json()["code"] == "DRS-9070"


# ---- the page and the settings ---------------------------------------------------------------------------------------------------------
def test_the_page_is_live_strict_and_wired():
    client, _ = make()
    r = client.get("/bi/explore")
    assert r.status_code == 200 and "Not built yet" not in r.text and 'data-bi-page="explore"' in r.text
    for js in ("bi-explore.js", "bi-explore-cells.js", "bi-python.js"):
        assert "/static/js/" + js in r.text
    assert "wasm-unsafe-eval" not in r.headers.get("content-security-policy", "")                              # strict: browser Python runs in Calc's worker
    assert "<script>" not in r.text.replace("<script src", "") and " onclick=" not in r.text and " style=" not in r.text
    st = client.get("/bi/explore/status").json()
    assert st["limits"]["max_rows"] == 10000 and st["quant"]["engine"] == "stand-in" and st["python"]["mayRun"] is True


def test_python_packages_agree_between_the_server_and_the_console():
    root = Path(__file__).resolve().parents[2]
    java = (root / "drishti-server/src/main/java/com/ash/drishti/server/bi/report/BiReportProperties.java").read_text(encoding="utf-8")
    default = re.search(r'java\.util\.List\.of\(([^)]*)\)\s*:\s*java\.util\.List\.copyOf\(pythonPackages\)', java)
    assert default, "the server's default python-packages list was not found in BiReportProperties"
    server = re.findall(r'"([a-z0-9_]+)"', default.group(1))
    console = load_settings(CONSOLE / "config").get("bi.python.packages")
    assert sorted(server) == sorted(console), f"drishti.bi.report.python-packages {server} and bi.python.packages {console} must list the same packages"
    yaml_text = (root / "drishti-server/src/main/resources/application.yaml").read_text(encoding="utf-8")
    m = re.search(r"python-packages:\s*\[([^\]]*)\]", yaml_text)
    if m:                                                                                                       # when the server's own file lists them, those agree too
        assert sorted(x.strip() for x in m.group(1).split(",")) == sorted(console)
