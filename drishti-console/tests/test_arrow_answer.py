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

"""Arrow to the browser (2.9.7, docs/guides/DATA_FORMATS.md section 6), the console's part (core/arrow_answer.py): an answer written as
Arrow reads back as the very JSON answer (nulls, dates as text, decimals, integers past 2^53, unicode, a key left out of some records,
repeated text as a dictionary); what Arrow is not written with goes as JSON; and the negotiation: Accept names the Arrow stream, the
server's Arrow is passed through, a JSON answer is written as Arrow here, anything else (and every old client) gets JSON."""
import json
from pathlib import Path

import pytest

pa = pytest.importorskip("pyarrow")

from core import arrow_answer  # noqa: E402

MEDIA = arrow_answer.MEDIA


def bi_answer(n=40):
    rows = [[f"T-{i}", None if i % 7 == 3 else ["DESK-FX", "DESK-FI", "Zürich ✓"][i % 3], "2026-10-08", i * 1.25 - 3.1, 9_007_199_254_740_000 + i if i == 5 else i]
            for i in range(n)]
    return {"columns": [{"name": "id", "type": "string"}, {"name": "desk", "type": "string"}, {"name": "day", "type": "string"},
                        {"name": "mtm", "type": "decimal"}, {"name": "n", "type": "integer"}],
            "rows": rows, "rowCount": n, "meta": {"layer": "base", "masked": []}, "totals": [{"by": [], "rows": [[1.5]]}]}


def test_a_bi_answer_reads_back_cell_for_cell_with_dictionaries():
    a = bi_answer()
    b = arrow_answer.encode(a, "rows", "rows")
    t = pa.ipc.open_stream(b).read_all()
    assert pa.types.is_dictionary(t.schema.field("desk").type) and pa.types.is_dictionary(t.schema.field("day").type)
    assert pa.types.is_string(t.schema.field("id").type)                   # unique text stays plain
    assert arrow_answer.decode(b) == a


def test_records_keep_a_key_left_out_apart_from_a_null_and_arrays_round_trip():
    a = {"var": 1.2e6, "rows": [{"rank": 1, "date": "2020-03-16", "pnl": -1250000.5, "from": "Rates", "marginalPer1m": None, "partial": True},
                                {"rank": 2, "date": "2020-03-12", "pnl": -980000.0, "marginalPer1m": 12.5, "partial": False}]}
    back = arrow_answer.decode(arrow_answer.encode(a, "records", "rows"))
    assert back == a and "from" not in back["rows"][1]
    p = {"var": 10.0, "series": [-5, 7, 0, 4_000_000_000], "scenarioDates": ["2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05"], "rows": [{"rank": 1}]}
    assert arrow_answer.decode(arrow_answer.encode(p, "arrays", "series,scenarioDates")) == p


@pytest.mark.parametrize("answer,shape,at", [
    ({"columns": [{"name": "ok"}], "rows": [[True], ["yes"]]}, "rows", "rows"),           # a boolean beside text
    ({"columns": [{"name": "x"}], "rows": [["a"], [1]]}, "rows", "rows"),                  # text and numbers in one column
    ({"columns": [{"name": "x"}], "rows": [[float("nan")]]}, "rows", "rows"),
    ({"columns": [{"name": "x"}], "rows": [[{"a": 1}]]}, "rows", "rows"),                  # an object
    ({"rows": [{"a": None}, {"b": 1}]}, "records", "rows"),                                # a null and a key left out, both
    ({"series": [1], "scenarioDates": ["a", "b"]}, "arrays", "series,scenarioDates"),     # arrays of different lengths
])
def test_what_arrow_is_not_written_with_goes_as_json(answer, shape, at):
    assert arrow_answer.encode(answer, shape, at) is None


def test_the_servers_stream_reads_the_same_here():
    """The server's ArrowAnswersTest leaves its stream in drishti-server/target (dictionaries written by ArrowIpcWriter): pyarrow reads it."""
    sample = Path(__file__).resolve().parents[2] / "drishti-server" / "target" / "arrow-answers-sample.arrow"
    if not sample.exists():
        pytest.skip("run the server's ArrowAnswersTest first")
    back = arrow_answer.decode(sample.read_bytes())
    assert len(back["rows"]) == 40 and back["rows"][2][1] == "Zürich ✓" and back["rows"][3][1] is None and back["rows"][1][3] == 1.25
    assert [r[5] for r in back["rows"][:5]] == [True, False, True, False, None]


# ---- negotiation --------------------------------------------------------------------------------------------------------------

class _Headers(dict):
    def get(self, k, d=None):
        return super().get(k.lower(), d)


def test_var_tables_are_negotiated(client, backend, monkeypatch):
    path = None
    seen = []

    async def var(method, p, ident, body=None, raw=False, accept="", **params):
        seen.append((p, raw, accept))
        if raw:
            return json.dumps(path).encode(), _Headers({"content-type": "application/json"})      # an older server: JSON, written as Arrow here
        return path
    monkeypatch.setattr(backend, "var", var, raising=False)
    contrib = {"var": 12.0, "rows": [{"key": "T-1", "componentVar": 4.5, "partial": False}, {"key": "T-2", "componentVar": 7.5, "partial": True}]}
    path = contrib
    r = client.get("/api/var/scope/contributions?scope=BANK&by=trade", headers={"Accept": MEDIA + ", application/json;q=0.9"})
    assert r.headers["content-type"] == MEDIA and seen[-1] == ("/scope/contributions", True, MEDIA)
    assert arrow_answer.decode(r.content) == contrib
    r = client.get("/api/var/scope/contributions?scope=BANK&by=trade", headers={"Accept": "application/json"})
    assert r.headers["content-type"].startswith("application/json") and r.json() == contrib and seen[-1][1] is False
    r = client.get("/api/var/scope/path?scope=BANK", headers={"Accept": MEDIA})                       # the path stays JSON (measured)
    assert r.headers["content-type"].startswith("application/json") and seen[-1][1] is False

    stream = arrow_answer.encode(contrib, "records", "rows")

    async def var_arrow(method, p, ident, body=None, raw=False, accept="", **params):
        return stream, _Headers({"content-type": MEDIA})
    monkeypatch.setattr(backend, "var", var_arrow, raising=False)
    r = client.get("/api/var/scope/contributions?scope=BANK", headers={"Accept": MEDIA})
    assert r.headers["content-type"] == MEDIA and r.content == stream                                   # the server's bytes as they came

    async def var_mixed(method, p, ident, body=None, raw=False, accept="", **params):
        return json.dumps({"rows": [{"x": "a"}, {"x": 1}]}).encode(), _Headers({"content-type": "application/json"})
    monkeypatch.setattr(backend, "var", var_mixed, raising=False)
    r = client.get("/api/var/scope/contributions?scope=BANK", headers={"Accept": MEDIA})
    assert r.headers["content-type"].startswith("application/json") and r.json() == {"rows": [{"x": "a"}, {"x": 1}]}


def test_a_visual_is_negotiated_on_both_bi_paths(client, backend, monkeypatch):
    a = bi_answer(20)
    calls = []

    async def bi_accepting(path, ident, body, accept, wait=None, **params):
        calls.append(("sync", path, accept))
        return json.dumps(a).encode(), _Headers({"content-type": "application/json"})

    async def bi_async(method, path, ident, body, prefer, wait=None, **params):
        calls.append(("run", path))
        return 200, {"run": {"id": "R-1", "title": "Zürich"}, "answer": a}

    async def bi(method, path, ident, body=None, wait=None, **params):
        calls.append(("json", path))
        return a
    monkeypatch.setattr(backend, "bi_accepting", bi_accepting, raising=False)
    monkeypatch.setattr(backend, "bi_async", bi_async, raising=False)
    monkeypatch.setattr(backend, "bi", bi, raising=False)
    body = {"page": "p", "visual": "v", "state": {}}
    r = client.post("/bi/api/reports/file:report:x/query", json=body, headers={"Accept": MEDIA})
    assert r.headers["content-type"] == MEDIA and arrow_answer.decode(r.content) == a and calls[-1][0] == "sync"
    r = client.post("/bi/api/reports/file:report:x/query", json=body, headers={"Accept": MEDIA, "Prefer": "respond-async, wait=2"})
    assert r.headers["content-type"] == MEDIA and arrow_answer.decode(r.content) == a and calls[-1][0] == "run"
    assert json.loads(r.headers["x-drishti-ran-as"]) == {"id": "R-1", "title": "Zürich"}
    r = client.post("/bi/api/reports/file:report:x/query", json=body, headers={"Accept": "application/json"})
    assert r.json() == a and calls[-1][0] == "json"                                                    # an old client: JSON, as before
