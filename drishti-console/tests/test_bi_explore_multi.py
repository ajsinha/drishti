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

"""Analyse with several extracts (docs/guides/ANALYSE.md section 2, RUPAKA.md section 20.10): a notebook loads more than one masked extract and joins them in
pandas (``extract("name")``) or in SQL (a table of that name); each is the rows the reader may see; a join cell is promoted to a Python Transform draft."""
import asyncio
import io
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

sys.path.insert(0, str(Path(__file__).resolve().parent))

from bi_backend import BiBackend  # noqa: E402
from conftest import CONSOLE  # noqa: E402
from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402
from core.explore import ExploreError, Limits, check_extracts, parse_notebook, promote_transform  # noqa: E402
from core.pyrunner import check_source  # noqa: E402

TRADES = "file:dataset:trading-risk"
EXPOSURE = "file:dataset:counterparty-exposure"
PNL = [("CP-1", 100.0), ("CP-1", 50.0), ("CP-2", -30.0), ("CP-3", 10.0)]
EE = [("CP-1", 1000.0), ("CP-2", 500.0), ("CP-4", 70.0)]
FIELDS = ["trades.counterparty", "trades.pnl1d"]
EXTRA = [{"name": "exposure", "dataset": EXPOSURE, "fields": ["netting_sets.counterparty", "netting_sets.eePeak"]}]


def stream(table) -> bytes:
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


class Backend(BiBackend):
    """Plays the server: the rows of each dataset depend on who asks (here bob has only CP-1 trades, and his exposure is masked)."""

    def __init__(self):
        super().__init__(("bi", "bi-author"))
        self.reader, self.asked = "ann", []

    async def bi_arrow(self, path, ident, body, **params):
        self.asked.append((path, ident.user))
        if "counterparty-exposure" in path:
            rows = EE
            ee = [None if self.reader == "bob" else r[1] for r in rows]
            return (stream(pa.table({"netting_sets.counterparty": [r[0] for r in rows], "netting_sets.eePeak": ee, "netting_sets.id": [str(i) for i in range(len(rows))]})),
                    {"X-Bi-Masked": "netting_sets.eePeak" if self.reader == "bob" else ""})
        rows = [r for r in PNL if self.reader != "bob" or r[0] == "CP-1"]
        return stream(pa.table({"trades.counterparty": [r[0] for r in rows], "trades.pnl1d": [r[1] for r in rows], "trades.id": [str(i) for i in range(len(rows))]})), {"X-Bi-Masked": ""}


def make(**explore):
    s = load_settings(CONSOLE / "config")
    s.as_dict().setdefault("bi", {}).setdefault("explore", {}).update(explore)
    app = create_app(s)
    app.state.backend = Backend()
    return TestClient(app), app.state.backend


JOIN = """pnl = df.groupby("counterparty", as_index=False)["pnl1d"].sum()
ee = extract("exposure").groupby("counterparty", as_index=False)["eePeak"].sum()
result = pnl.merge(ee, on="counterparty", how="left").sort_values("counterparty")
"""


def python(client, source, **extra):
    return client.post("/bi/explore/python", json={"extract": {"dataset": TRADES, "fields": FIELDS}, "extracts": EXTRA, "source": source, **extra})


def test_a_python_cell_joins_two_extracts_in_pandas():
    client, backend = make()
    r = python(client, JOIN).json()
    assert r["table"]["columns"] == ["counterparty", "pnl1d", "eePeak"]
    assert r["table"]["rows"] == [["CP-1", 150.0, 1000.0], ["CP-2", -30.0, 500.0], ["CP-3", 10.0, None]]
    assert r["extracts"] == [{"name": "exposure", "rows": 3, "masked": []}] and r["inputRows"] == 4
    assert len(backend.asked) == 2 and len({u for _, u in backend.asked}) == 1                              # both extracts were asked for, for the same person


def test_each_extract_is_the_readers_own_rows_and_masks():
    client, backend = make()
    backend.reader = "bob"
    r = python(client, JOIN).json()
    assert len(r["table"]["rows"]) == 1 and r["table"]["rows"][0][:2] == ["CP-1", 150.0] and r["table"]["rows"][0][2] in (None, 0, 0.0)      # bob: CP-1 trades only; the masked exposure is null, never 1000
    assert r["extracts"][0]["masked"] == ["eePeak"] and r["masked"] == ["eePeak"]
    backend.reader = "ann"
    assert len(python(client, JOIN).json()["table"]["rows"]) == 3                                           # nothing was kept between calls


def test_an_extract_is_read_by_its_name_or_by_its_dataset_and_an_unknown_one_says_what_exists():
    client, _ = make()
    by_dataset = python(client, 'result = extract("' + EXPOSURE + '").head(2)').json()
    assert by_dataset["table"]["rowCount"] == 2
    bad = python(client, 'result = extract("nothing")')
    assert bad.status_code == 422 and bad.json()["code"] == "DRS-9071" and "exposure" in bad.json()["traceback"] + bad.json()["detail"]


def test_a_notebook_of_only_extracts_uses_the_first_as_df():
    client, _ = make()
    body = {"extracts": [{"name": "pnl", "dataset": TRADES, "fields": FIELDS}] + EXTRA, "source": 'result = extract("pnl").merge(extract("exposure"), on="counterparty")'}
    r = client.post("/bi/explore/python", json=body).json()
    assert r["table"]["rowCount"] == 3                                                                      # CP-1 twice, CP-2 once
    assert client.post("/bi/explore/python", json={"source": "result = 1"}).json()["code"] == "DRS-9070"


def test_sql_joins_the_extracts_as_tables_named_after_them():
    client, _ = make()
    sql = ("SELECT p.counterparty, p.n, e.ee FROM (SELECT counterparty, SUM(pnl1d) AS n FROM extract GROUP BY counterparty) p "
           "JOIN (SELECT counterparty, SUM(eePeak) AS ee FROM exposure GROUP BY counterparty) e ON e.counterparty = p.counterparty ORDER BY 1")
    r = client.post("/bi/explore/sql", json={"extract": {"dataset": TRADES, "fields": FIELDS}, "extracts": EXTRA, "sql": sql}).json()
    assert r["rows"] == [["CP-1", 150.0, 1000.0], ["CP-2", -30.0, 500.0]]
    still = client.post("/bi/explore/sql", json={"extract": {"dataset": TRADES, "fields": FIELDS}, "sql": "SELECT COUNT(*) FROM exposure"})
    assert still.status_code == 422                                                                           # without the extract there is no such table


def test_the_notebook_check_accepts_extracts_and_refuses_bad_ones():
    limits = Limits(max_extracts=2)
    text = "rachana: 1\nnotebook: n\ndataset: d\nfields: [a.b]\nextracts:\n  - { name: exposure, dataset: x, fields: [t.c] }\ncells: []\n"
    assert parse_notebook(text, limits)["extracts"][0]["name"] == "exposure"
    for bad in ("extracts: [{name: 'a b;c', dataset: x, fields: [t.c]}]", "extracts: [{name: extract, dataset: x, fields: [t.c]}]", "extracts: [{name: a, fields: [t.c]}]",
                "extracts: [{name: a, dataset: x, fields: []}]", "extracts: [{name: a, dataset: x, fields: [t.c]}, {name: a, dataset: y, fields: [t.c]}]",
                "extracts: [{name: a, dataset: x, fields: [t.c]}, {name: b, dataset: y, fields: [t.c]}, {name: c, dataset: z, fields: [t.c]}]"):
        with pytest.raises(ExploreError) as e:
            parse_notebook(text.replace("extracts:\n  - { name: exposure, dataset: x, fields: [t.c] }", bad), limits)
        assert e.value.code in ("DRS-9070", "DRS-9072")
    assert check_extracts([], limits) == []


def test_promoting_a_join_cell_writes_a_python_transform_draft_that_runs():
    extracts = [{"name": "pnl", "dataset": TRADES, "fields": FIELDS}] + EXTRA
    text = promote_transform(JOIN.replace("df.groupby", 'extract("pnl").groupby'), "pnl vs exposure", extracts, ["pandas"])
    import yaml
    doc = yaml.safe_load(text)
    assert doc["interface"] == "transform" and doc["inputs"] == [TRADES, EXPOSURE] and doc["python"] == "pnl-vs-exposure"
    assert "def run(pnl, exposure):" in doc["source"] and "extract(" not in doc["source"] and doc["source"].rstrip().endswith("return result")
    assert not [p for p in check_source({**doc, "entry": "run"}, ["pandas"]) if p["level"] == "error"]
    # a cell that uses df gets the first extract as df
    again = yaml.safe_load(promote_transform(JOIN, "x", extracts, ["pandas"]))
    assert "df = pnl" in again["source"]
    # and the draft really is a working Transform: run it on the two extracts' rows
    from core.config import Settings
    from core.pyrunner import PyRunner
    runner = PyRunner(Settings({"bi": {"python": {"workers": 1, "queue_seconds": 2, "packages": ["pandas", "numpy", "pyarrow"]}}}))
    frames = [stream(pa.table({"counterparty": [r[0] for r in PNL], "pnl1d": [r[1] for r in PNL]})), stream(pa.table({"counterparty": [r[0] for r in EE], "eePeak": [r[1] for r in EE]}))]
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        out = asyncio.run(runner.run_transform({**doc, "name": doc["python"], "params": {}, "timeout": 10, "memory": 512, "target": "server"}, frames, dest=str(Path(tmp) / "f.parquet")))
    assert out["rows"] == 3 and [c["name"] for c in out["columns"]] == ["counterparty", "pnl1d", "eePeak"]


def test_the_route_promotes_a_cell_and_refuses_an_extract_the_notebook_does_not_have():
    client, _ = make()
    src = 'result = extract("exposure")'
    ok = client.post("/bi/explore/promote", json={"to": "transform", "name": "Join", "source": src, "extracts": EXTRA})
    assert ok.status_code == 200 and "interface: transform" in ok.json()["yaml"] and "derived" in " ".join(ok.json()["notes"])
    bad = client.post("/bi/explore/promote", json={"to": "transform", "name": "Join", "source": 'result = extract("nothing")', "extracts": EXTRA})
    assert bad.status_code == 422 and "nothing" in bad.json()["detail"]
    assert client.post("/bi/explore/promote", json={"to": "transform", "name": "Join", "source": src, "extracts": []}).status_code == 422
