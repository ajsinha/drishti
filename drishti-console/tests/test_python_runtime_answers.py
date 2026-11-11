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

"""One Python runtime answers (docs/guides/BI_GUIDE.md section N19): with the server's compute pool ``python`` on, the shipped Python
model and the shipped Python board answer where they once recorded refusals. The real server of tests/bi_report_stack.py on ports 18973 and 17973
(security on, the cross-pack Delta lake), compute on in the interpreter that runs this test (it carries the libraries pinned in
config/compute/locks/python.lock):

* the model ``desk-pnl-vs-exposure-py`` (a Python Transform over two model queries) answers rows for the risk reader and for a desk head, each made
  from that reader's own inputs, so the desk head's row rule gives her fewer counterparties;
* every tile of the board ``python-wall`` draws: each tile's python visual of ``python-desk-risk`` runs on the pool as the reader.

API only, no browser. The BI foundation parity fixtures are untouched by this: they record BI with compute off.

Skipped, with the reason, where the strict sandbox or the pinned interpreter is missing (Windows, a Linux without Landlock or user namespaces,
versions that differ from the lock: ``GET /bi/python/runtime`` says why), and when the built server jar, the demo lake (uv), pandas or pyarrow is
missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
from __future__ import annotations

import os
import sys

import pytest
import yaml

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18973")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17973")
pytest.importorskip("pandas")
pytest.importorskip("pyarrow")

import bi_report_stack  # noqa: E402
from bi_report_stack import ROOT, api  # noqa: E402

PY_MODEL = "desk-pnl-vs-exposure-py@1"
BOARD = ROOT / "config/bi/reports/banking/python-wall.report.yaml"
SOURCE = ROOT / "config/bi/reports/banking/python-desk-risk.report.yaml"
RISK = ("rita", ["risk"])
HEAD = ("hana", ["desk-head"])


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        from bi_samples import copy_samples
        copy_samples(work / "config/bi", ["trading-risk", "counterparty-exposure", "desk-pnl-vs-exposure-py", "trades-basic"])
        python = work / "config/bi/python"
        python.mkdir(parents=True, exist_ok=True)
        for f in (ROOT / "config/bi/python").glob("*/*.python.yaml"):
            (python / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
        reports = work / "config/bi/reports"
        reports.mkdir(parents=True, exist_ok=True)
        for f in (BOARD, SOURCE):
            (reports / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
        self.server_args += ["--DRISHTI_COMPUTE_ENABLED=true", f"--DRISHTI_PYTHON={sys.executable}", f"--drishti.compute.file={ROOT}/config/compute/compute.yaml"]


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    tmp = tmp_path_factory.mktemp("pyanswers")
    lake = bi_report_stack.make_lake(tmp / "lake")
    if not lake:
        pytest.skip("needs a Delta lake: DRISHTI_BI_DELTA_ROOT, or uv with deltalake, pyarrow and pyyaml (tools/packgen/banking/make_data.py --lake)")
    try:
        s = Stack(tmp, delta_root=lake).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    try:
        status, runtime = api("GET", "/bi/python/runtime", *RISK)
        assert status == 200, runtime
        if not runtime.get("runs"):
            pytest.skip(f"Python does not run on this server: {runtime.get('why')}")
        yield s
    finally:
        s.close()


def py_rows(who):
    s, r = api("POST", "/bi/mq", *who, {"mq": 1, "model": PY_MODEL, "select": {"fields": ["result.counterparty", "result.desk_pnl", "result.ee_peak"], "measures": []},
                                        "shape": "rows"})
    assert s == 200, r
    return r["rows"]


def test_the_python_model_answers_each_reader_from_their_own_inputs(stack):
    everyone, mine = py_rows(RISK), py_rows(HEAD)
    assert everyone and mine, (everyone, mine)
    assert len(mine) < len(everyone)                                                        # the desk head's row rule cut her inputs
    assert {r[0] for r in mine} <= {r[0] for r in everyone}
    s, totals = api("POST", "/bi/mq", *RISK, {"mq": 1, "model": PY_MODEL, "select": {"fields": [], "measures": ["Desk P&L", "Counterparties"]}})
    assert s == 200, totals
    pnl, count = totals["rows"][0]
    assert count == len(everyone) and pnl == pytest.approx(sum(r[1] or 0 for r in everyone))


def test_every_tile_of_the_python_board_draws_on_the_pool(stack):
    board = yaml.safe_load(BOARD.read_text(encoding="utf-8"))
    source = yaml.safe_load(SOURCE.read_text(encoding="utf-8"))
    page_of = {v["id"]: p["id"] for p in source["pages"] for v in p["visuals"]}
    tiles = [v for p in board["pages"] for v in p["visuals"] if v.get("from")]
    assert len(tiles) == 3
    for tile in tiles:
        visual = tile["from"]["visual"]
        s, r = api("POST", "/bi/python/visual", *RISK, {"report": f"file:report:{tile['from']['report']}", "page": page_of[visual], "visual": visual, "state": {}})
        assert s == 200, (tile["id"], r)
        assert r["target"] == "server" and r["inputRows"] > 0, (tile["id"], r)
        assert r.get("kind") in ("echarts", "vega-lite", "measure"), (tile["id"], r)
