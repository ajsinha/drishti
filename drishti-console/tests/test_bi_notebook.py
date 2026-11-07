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

"""The Notebook (docs/guides/USER_GUIDE.md › The Notebook): the extract is the reader's rows (two readers, two extracts, nothing kept between calls), a SQL
cell is Drishti SQL run by the server, Python cells run on the server's compute pool (the server reads the extracts; the console runs none), notebooks
check and promote, an older notebook's slice is left out with a note, the cut routes are gone and the old addresses lead here, and the server's Python
packages are pinned."""
import io
import re
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402
from core.notebook import Limits, NotebookError, check_extracts, from_arrow, parse_notebook, promote_python, promote_transform  # noqa: E402
sys.path.insert(0, str(CONSOLE.parent / "drishti-compute" / "src" / "main" / "python"))
from drishti_compute.items import check_source  # noqa: E402  (the compute pool's static check, parsed here: nothing runs)

DS = "file:dataset:trades-basic"
FIELDS = ["trade.desk", "trade.notional", "trade.mtm"]
ALL = [("FX", 100.0, 5.0), ("FX", 200.0, -3.0), ("RATES", 300.0, 7.0), ("RATES", 50.0, 1.0), ("EQ", 10.0, 2.0)]
EXTRA = [{"name": "exposure", "dataset": "file:dataset:counterparty-exposure", "fields": ["netting_sets.counterparty", "netting_sets.eePeak"]}]


def arrow(rows, masked_mtm=False):
    t = pa.table({"trade.desk": [r[0] for r in rows], "trade.notional": [r[1] for r in rows],
                  "trade.mtm": [None if masked_mtm else r[2] for r in rows], "trade.id": [str(i) for i in range(len(rows))]})
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, t.schema) as w:
        w.write_table(t)
    return sink.getvalue()


class NotebookBackend(BiBackend):
    """Plays the server: what a model query returns depends on who asks (row rules and masks are the server's job)."""

    def __init__(self, powers=("bi", "bi-author")):
        super().__init__(powers)
        self.reader, self.queries, self.python_runs = "ann", [], True

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/python/runtime":
            return {"runs": self.python_runs, "why": None if self.python_runs else "compute is off", "packages": ["pandas", "numpy", "pyarrow", "scipy"]}
        if path == "/python/cell":
            self.queries.append((path, body, ident.user))
            return {"kind": "cell", "stdout": "5\n", "table": {"columns": ["desk"], "rows": [["FX"]], "rowCount": 1}, "ms": 3, "inputRows": 5, "masked": [], "target": "server"}
        return await super().bi(method, path, ident, body, **params)

    async def bi_arrow(self, path, ident, body, **params):
        self.queries.append((path, body, ident.user))
        if self.reader == "bob":                                   # bob: only FX rows, mtm masked
            return arrow([r for r in ALL if r[0] == "FX"], masked_mtm=True), {"X-Bi-Rows": "2", "X-Bi-Masked": "trade.mtm"}
        return arrow(ALL), {"X-Bi-Rows": str(len(ALL)), "X-Bi-Masked": ""}


def make(powers=("bi", "bi-author"), **notebook):
    s = load_settings(CONSOLE / "config")
    s.as_dict().setdefault("bi", {}).setdefault("notebook", {}).update(notebook)
    app = create_app(s)
    app.state.backend = NotebookBackend(powers)
    return TestClient(app), app.state.backend


def extract(client, **extra):
    return client.post("/bi/notebook/extract", json={"dataset": DS, "fields": FIELDS, **extra})


# ---- the extract ---------------------------------------------------------------------------------------------------------------
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
    assert client.app.state.notebook_limits.max_rows == 10
    out = from_arrow(arrow(ALL * 5), 10)
    assert len(out.rows) == 10 and out.total == 25 and out.truncated and out.arrow


def test_a_bad_extract_request_is_refused_with_a_code():
    client, _ = make()
    assert client.post("/bi/notebook/extract", json={"dataset": DS}).json()["code"] == "DRS-9070"
    assert client.post("/bi/notebook/extract", json={"dataset": DS, "fields": ["a;drop"]}).status_code == 422
    assert client.post("/bi/notebook/extract", json={"fields": FIELDS}).status_code == 422
    assert client.post("/bi/notebook/extract", data="x", headers={"content-type": "text/plain"}).status_code in (403, 415, 422)


# ---- one analysis path: what was cut is gone, and the old addresses lead here ----------------------------------------------------
@pytest.mark.parametrize("path", ["/bi/explore/pivot", "/bi/explore/sql", "/bi/explore/python", "/bi/explore/dsql", "/bi/notebook/pivot",
                                  "/pyconsole/extract", "/pyconsole/promote"])
def test_the_console_runs_no_slice_no_sqlite_and_no_python_of_the_readers(path):          # /bi/notebook/python passes a cell to the server
    client, _ = make()
    assert client.post(path, json={"extract": {"dataset": DS, "fields": FIELDS}, "source": "result = 1", "sql": "SELECT 1"}).status_code in (404, 405)


def test_the_old_addresses_lead_to_the_notebook():
    client, _ = make()
    r = client.get("/bi/explore?item=bi-nb-1&x=1", follow_redirects=False)
    assert r.status_code == 308 and r.headers["location"] == "/bi/notebook?item=bi-nb-1"
    assert client.get("/bi/explore", follow_redirects=False).headers["location"] == "/bi/notebook"
    p = client.get("/pyconsole", follow_redirects=False)
    assert p.status_code == 308 and p.headers["location"] == "/bi/notebook"


def test_the_sliced_and_the_sqlite_code_is_deleted():
    import core.notebook as nb
    for gone in ("run_sql", "pivot", "PIVOT_AGGS", "table_name"):
        assert not hasattr(nb, gone)
    assert "sqlite3" not in Path(nb.__file__).read_text(encoding="utf-8")
    for gone in ("routes/explore_routes.py", "routes/pyconsole_routes.py", "core/explore.py", "web/templates/pyconsole/console.html",
                 "web/static/js/drishti_pyconsole.js", "web/static/js/drishti_bi-explore.js", "web/templates/bi/explore.html"):
        assert not (CONSOLE / gone).exists(), gone
    assert load_settings(CONSOLE / "config").get("bi.pyconsole") is None and load_settings(CONSOLE / "config").get("bi.explore") is None


# ---- SQL: on the server ------------------------------------------------------------------------------------------------------------
def test_a_sql_cell_is_drishti_sql_run_by_the_server_as_the_reader():
    client, backend = make()
    asked = []

    async def bi(method, path, ident, body=None, **params):
        if path == "/sql":
            asked.append((body, ident.user))
            return {"columns": [{"name": "DESK"}, {"name": "MTM"}], "rows": [["FX", 2.0]], "meta": {"millis": 3, "plan": "p_1", "masked": []}}
        return await BiBackend.bi(backend, method, path, ident, body, **params)
    backend.bi = bi
    r = client.post("/bi/notebook/sql", json={"extract": {"dataset": "mq:trading-risk@1", "fields": ["trade.desk"]}, "sql": "SELECT trade.DESK FROM trade"}).json()
    assert r["engine"] == "server" and r["model"] == "trading-risk@1" and r["rows"] == [["FX", 2.0]] and r["plan"] == "p_1"
    assert asked[0][0]["model"] == "trading-risk@1" and asked[0][0]["sql"] == "SELECT trade.DESK FROM trade"
    assert client.post("/bi/notebook/sql", json={"extract": {"dataset": "mq:"}, "sql": "SELECT 1"}).json()["code"] == "DRS-9070"


# ---- Python: on the server's compute pool -------------------------------------------------------------------------------------------
def test_python_cells_run_on_the_server_which_reads_the_extracts_as_the_reader():
    client, backend = make()
    page = client.get("/bi/notebook").text
    assert 'data-python-runs="true"' in page and "python-table-runs" not in page and "compute pool" in page
    st = client.get("/bi/notebook/status").json()
    assert st["python"]["runs"] is True and st["python"]["where"] == "server" and st["python"]["mayRun"] is True
    spec = {"dataset": DS, "fields": FIELDS}
    r = client.post("/bi/notebook/python", json={"extract": spec, "extracts": EXTRA, "source": "print(len(df))\nresult = df[['desk']]"})
    assert r.status_code == 200 and r.json()["kind"] == "cell" and r.json()["target"] == "server"
    path, body, user = backend.queries[-1]
    assert path == "/python/cell" and user == "ash" and body["extract"] == spec and body["extracts"] == EXTRA and body["limit"] == 10000 and body["timeout"] == 20
    assert not [q for q in backend.queries if q[0] != "/python/cell"]                 # the console read no rows for it: the server does
    assert client.post("/bi/notebook/python", json={"extract": spec, "source": " "}).status_code == 413
    js = (CONSOLE / "web/static/js/drishti_bi-notebook-cells.js").read_text(encoding="utf-8")
    assert "/bi/notebook/python" in js and "/bi/explore" not in js and "pyodide" not in js.lower()
    backend.python_runs = False                                                       # a server that cannot run Python safely says why
    off = client.get("/bi/notebook").text
    assert 'data-python-runs="false"' in off and "compute is off" in off


# ---- notebooks and promotion ---------------------------------------------------------------------------------------------------------
NOTEBOOK = {"rachana": 1, "notebook": "desk-look", "title": "Desk look", "dataset": DS, "fields": FIELDS,
            "cells": [{"type": "sql", "source": "SELECT 1"}, {"type": "python", "source": "print(1)", "target": "server"}]}


def test_a_notebook_checks_and_round_trips():
    client, _ = make()
    c = client.post("/bi/notebook/check", json={"notebook": NOTEBOOK}).json()
    assert c["valid"] and c["cells"] == 2 and "notebook: desk-look" in c["yaml"] and c["notes"] == []
    again = client.post("/bi/notebook/check", json={"yaml": c["yaml"]}).json()
    assert again["notebook"]["cells"][1]["source"] == "print(1)"
    bad = client.post("/bi/notebook/check", json={"notebook": {**NOTEBOOK, "cells": [{"type": "shell", "source": "ls"}]}}).json()
    assert not bad["valid"] and "cell 1" in bad["problems"][0]["message"]
    assert not client.post("/bi/notebook/check", json={"notebook": {**NOTEBOOK, "surprise": 1}}).json()["valid"]
    with pytest.raises(NotebookError):
        parse_notebook("cells: " + str([{"type": "sql", "source": "x"}] * 3), Limits(max_cells=2))


def test_an_older_notebooks_slice_is_left_out_with_a_note_and_the_rest_opens():
    client, _ = make()
    older = {**NOTEBOOK, "cells": [{"type": "slice", "slice": {"rows": ["desk"], "values": [{"field": "notional", "agg": "sum"}]}}] + NOTEBOOK["cells"]}
    c = client.post("/bi/notebook/check", json={"notebook": older}).json()
    assert c["valid"] and c["cells"] == 2 and [x["type"] for x in c["notebook"]["cells"]] == ["sql", "python"]
    assert len(c["notes"]) == 1 and "Pivot" in c["notes"][0] and "cell 1" in c["notes"][0]
    assert "notes" not in c["notebook"]


def test_the_notebook_check_accepts_extracts_and_refuses_bad_ones():
    limits = Limits(max_extracts=2)
    text = "rachana: 1\nnotebook: n\ndataset: d\nfields: [a.b]\nextracts:\n  - { name: exposure, dataset: x, fields: [t.c] }\ncells: []\n"
    assert parse_notebook(text, limits)["extracts"][0]["name"] == "exposure"
    for bad in ("extracts: [{name: 'a b;c', dataset: x, fields: [t.c]}]", "extracts: [{name: extract, dataset: x, fields: [t.c]}]", "extracts: [{name: a, fields: [t.c]}]",
                "extracts: [{name: a, dataset: x, fields: []}]", "extracts: [{name: a, dataset: x, fields: [t.c]}, {name: a, dataset: y, fields: [t.c]}]",
                "extracts: [{name: a, dataset: x, fields: [t.c]}, {name: b, dataset: y, fields: [t.c]}, {name: c, dataset: z, fields: [t.c]}]"):
        with pytest.raises(NotebookError) as e:
            parse_notebook(text.replace("extracts:\n  - { name: exposure, dataset: x, fields: [t.c] }", bad), limits)
        assert e.value.code in ("DRS-9070", "DRS-9072")
    assert check_extracts([], limits) == []


def test_promote_a_python_cell_to_a_measure_and_a_visual_draft():
    client, _ = make()
    m = client.post("/bi/notebook/promote", json={"to": "measure", "name": "Desk spread", "source": "import numpy\nresult = float(df['mtm'].std())", "columns": ["mtm"]}).json()
    assert m["kind"] == "python" and "interface: measure" in m["yaml"] and "def compute(table, params):" in m["yaml"] and "numpy" in m["yaml"]
    import yaml as y
    doc = y.safe_load(m["yaml"])
    ns = {}
    exec(compile(doc["source"], "p", "exec"), ns)                                                              # the draft is real code (run here by the test, not by the console)
    import pandas as pd
    assert ns["compute"](pd.DataFrame({"mtm": [1.0, 3.0]}), {}) == pytest.approx(1.4142135, abs=1e-6)
    v = client.post("/bi/notebook/promote", json={"to": "visual", "name": "Chart", "source": "result = {}", "columns": []}).json()
    assert "interface: visual" in v["yaml"] and "output: echarts" in v["yaml"] and "render(table, params)" in v["yaml"]
    assert client.post("/bi/notebook/promote", json={"to": "board"}).json()["code"] == "DRS-9070"
    assert client.post("/bi/notebook/promote", json={"to": "report", "dataset": DS, "slice": {"rows": ["desk"]}}).json()["code"] == "DRS-9070"   # a slice is the Pivot's
    assert promote_python("x = 1", "n", "visual", [], []).count("def render") == 1


def test_promoting_a_join_cell_writes_a_python_transform_draft():
    client, _ = make()
    src = 'pnl = df.groupby("counterparty", as_index=False)["pnl1d"].sum()\nresult = pnl.merge(extract("exposure"), on="counterparty")'
    ok = client.post("/bi/notebook/promote", json={"to": "transform", "name": "Join", "source": src, "extracts": EXTRA})
    assert ok.status_code == 200 and "interface: transform" in ok.json()["yaml"] and "inputs: [exposure]" in ok.json()["yaml"]
    bad = client.post("/bi/notebook/promote", json={"to": "transform", "name": "Join", "source": 'result = extract("nothing")', "extracts": EXTRA})
    assert bad.status_code == 422 and "nothing" in bad.json()["detail"]
    import yaml
    doc = yaml.safe_load(promote_transform(src, "x", [{"name": "pnl", "dataset": DS, "fields": FIELDS}] + EXTRA, ["pandas"]))
    assert "def run(pnl, exposure):" in doc["source"] and "df = pnl" in doc["source"]
    assert not [p for p in check_source({**doc, "entry": "run"}, ["pandas"]) if p["level"] == "error"]


# ---- the page and the settings ---------------------------------------------------------------------------------------------------------
def test_the_page_is_live_strict_and_wired():
    client, _ = make()
    r = client.get("/bi/notebook")
    assert r.status_code == 200 and 'data-bi-page="notebook"' in r.text and "<h1>Notebook</h1>" in r.text
    for js in ("drishti_bi-notebook.js", "drishti_bi-notebook-cells.js", "drishti_bi-notebook-profile.js"):
        assert "/static/js/" + js in r.text
    assert 'data-tab="slice"' not in r.text and 'href="/pivot"' in r.text and 'href="/pyconsole"' not in r.text
    assert "wasm-unsafe-eval" not in r.headers.get("content-security-policy", "")
    assert "<script>" not in r.text.replace("<script src", "") and " onclick=" not in r.text and " style=" not in r.text
    assert client.get("/bi/notebook/status").json()["limits"]["max_rows"] == 10000
    rail = client.get("/bi").text
    assert 'href="/bi/notebook"' in rail and 'href="/pivot"' in rail and "/pyconsole" not in rail and "/bi/explore" not in rail


def test_the_servers_python_packages_are_all_pinned():
    root = Path(__file__).resolve().parents[2]
    java = (root / "drishti-server/src/main/java/com/ash/drishti/server/bi/report/BiReportProperties.java").read_text(encoding="utf-8")
    default = re.search(r'java\.util\.List\.of\(([^)]*)\)\s*:\s*java\.util\.List\.copyOf\(pythonPackages\)', java)
    assert default, "the server's default python-packages list was not found in BiReportProperties"
    server = re.findall(r'"([a-z0-9_]+)"', default.group(1))
    lock = (root / "config/compute/locks/python.lock").read_text(encoding="utf-8")
    pinned = re.findall(r"(?m)^([A-Za-z0-9_.-]+)==", lock)
    assert set(server) <= set(pinned), f"drishti.bi.report.python-packages {server}: each must be pinned in config/compute/locks/python.lock ({pinned})"
    assert load_settings(CONSOLE / "config").get("bi.python.packages") is None                                # the console holds no Python runtime
