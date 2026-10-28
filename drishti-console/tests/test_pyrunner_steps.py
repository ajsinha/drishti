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

"""Wave C (2.4.0) data prep in the Python runner (docs/guides/PYTHON_BI.md section 17; docs/architecture/BI_DESIGN.md section 11.1, items P1 and P3): the step-by-step run of a Transform (the
frame after each ``# step:`` or each statement, its rows, columns, first rows and profile; a failing step stops the run there with its line), the profile of
a frame and the key hints between inputs. Real worker processes in the sandbox on small Arrow tables: the sandbox still refuses what it refuses."""
import asyncio
import io
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

pa = pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from core.config import Settings  # noqa: E402
from core.pyrunner import PyError, PyRunner  # noqa: E402


def arrow(**cols) -> bytes:
    table = pa.table(cols)
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as w:
        w.write_table(table)
    return sink.getvalue()


@pytest.fixture(scope="module")
def runner():
    r = PyRunner(Settings({"bi": {"python": {"workers": 2, "queue_seconds": 5, "packages": ["pandas", "numpy", "pyarrow"], "max_output_bytes": 2_000_000}}}))
    yield r
    asyncio.run(r.close())


def manifest(source: str) -> dict:
    return {"name": "prep", "interface": "transform", "entry": "run", "inputs": ["trades", "desks"], "packages": ["pandas"], "target": "server", "timeout": 10,
            "memory": 512, "params": {}, "source": source}


TRADES = arrow(TRADE_ID=["T1", "T2", "T3", "T4"], DESK=["FX", "FX", "RATES", None], NOTIONAL=["1,000", "2500", "300", "40"])
DESKS = arrow(DESK=["FX", "RATES", "CREDIT"], HEAD=["Ann", "Bob", "Cy"])

MARKED = '''
import pandas as pd

def run(trades, desks, params):
    # step: Notional as a number
    df = trades.copy()
    df["NOTIONAL"] = pd.to_numeric(df["NOTIONAL"].str.replace(",", ""), errors="coerce")
    # step: Keep rows with a desk
    df = df.dropna(subset=["DESK"])
    # step: Add the desk head
    df = df.merge(desks, on="DESK", how="left")
    return df
'''


def steps(runner, source, frames=(TRADES, DESKS), names=("trades", "desks")):
    return asyncio.run(runner.run_steps(manifest(source), list(frames), list(names)))


def test_each_marked_step_has_its_frame_rows_columns_head_and_profile(runner):
    a = steps(runner, MARKED)
    assert a["kind"] == "steps" and a["complete"] is True
    assert [s["label"] for s in a["planned"]] == ["Notional as a number", "Keep rows with a desk", "Add the desk head"]
    rows = [s["rows"] for s in a["steps"]]
    assert rows == [4, 3, 3]                                        # the last step ends in the return: its frame is what run returned
    first = a["steps"][0]
    assert first["variable"] == "df" and {"name": "NOTIONAL", "type": "integer"} in first["columns"]
    notional = next(c for c in first["profile"]["columns"] if c["name"] == "NOTIONAL")
    assert notional["min"] == 40 and notional["max"] == 2500 and sum(notional["histogram"]["counts"]) == 4
    assert a["steps"][2]["head"]["columns"] == ["TRADE_ID", "DESK", "NOTIONAL", "HEAD"]
    assert a["steps"][2]["head"]["rows"][0] == ["T1", "FX", 1000, "Ann"]
    assert a["steps"][-1]["variable"] == "return"
    assert all(s["ms"] >= 0 for s in a["steps"])


def test_the_inputs_are_profiled_with_a_type_guess_and_a_key_hint(runner):
    a = steps(runner, MARKED)
    trades = a["inputs"][0]
    assert trades["name"] == "trades" and trades["rows"] == 4
    cols = {c["name"]: c for c in trades["profile"]["columns"]}
    assert cols["NOTIONAL"]["type"] == "string" and cols["NOTIONAL"]["looksLike"] == "decimal"      # numbers kept as text: the Type fix snippet's job
    assert cols["DESK"]["nulls"] == 1 and cols["DESK"]["nullPct"] == 25.0 and cols["DESK"]["distinct"] == 2
    assert cols["TRADE_ID"]["unique"] is True and cols["DESK"]["unique"] is False
    assert ["FX", 2] in cols["DESK"]["top"]
    assert {"input": "trades", "column": "DESK", "keyOf": "desks", "key": "DESK", "matched": 2, "of": 2} in a["keys"]


def test_without_marks_each_statement_is_a_step(runner):
    a = steps(runner, "def run(trades, desks):\n    df = trades.head(3)\n    df = df[df['DESK'] == 'FX']\n    return df\n")
    assert [s["label"] for s in a["planned"]] == ["df = trades.head(3)", "df = df[df['DESK'] == 'FX']", "return df"]
    assert [s["rows"] for s in a["steps"]] == [3, 2, 2]


def test_a_failing_step_stops_the_run_there_with_its_line_and_keeps_the_steps_before(runner):
    source = MARKED.replace('df = df.dropna(subset=["DESK"])', 'df = df.dropna(subset=["NO_SUCH"])')
    a = steps(runner, source)
    assert a["complete"] is False
    assert a["failed"]["index"] == 1 and a["failed"]["label"] == "Keep rows with a desk"
    assert "KeyError" in a["failed"]["error"] and a["failed"]["line"] == source.splitlines().index('    df = df.dropna(subset=["NO_SUCH"])') + 1
    assert len(a["steps"]) == 1 and a["steps"][0]["rows"] == 4


def test_a_step_that_makes_no_table_says_so(runner):
    a = steps(runner, "def run(trades, desks):\n    n = len(trades)\n    return trades.head(n - 1)\n")
    assert a["steps"][0].get("note") == "this step makes no table" or a["steps"][0]["variable"] is None
    assert a["steps"][-1]["rows"] == 3


def test_the_sandbox_still_refuses_in_a_step(runner):
    a = steps(runner, "def run(trades, desks):\n    df = trades\n    open('/etc/passwd').read()\n    return df\n")
    assert a["complete"] is False and "not allowed" in a["failed"]["error"]
    with pytest.raises(PyError) as e:
        steps(runner, "import socket\ndef run(trades, desks):\n    return trades\n")
    assert e.value.code in ("DRS-9073", "DRS-9071")


def test_an_identifier_of_digits_is_not_offered_a_number_type(runner):
    p = asyncio.run(runner.profile(arrow(TRADE_ID=["1001", "1002", "1003"], AMOUNT=["1", "2", "3"])))
    cols = {c["name"]: c for c in p["columns"]}
    assert "looksLike" not in cols["TRADE_ID"] and cols["AMOUNT"]["looksLike"] == "decimal"      # ids are text by design


def test_a_frame_is_profiled_without_author_code(runner):
    p = asyncio.run(runner.profile(arrow(id=["a", "b", "c"], AMOUNT=[1.0, None, 3.0], WHEN=pa.array([None, None, None], pa.date32()))))
    assert p["kind"] == "profile" and p["rows"] == 3
    cols = {c["name"]: c for c in p["columns"]}
    assert "id" not in cols
    assert cols["AMOUNT"]["nulls"] == 1 and cols["AMOUNT"]["min"] == 1.0 and cols["AMOUNT"]["max"] == 3.0
    assert cols["WHEN"]["nullPct"] == 100.0
