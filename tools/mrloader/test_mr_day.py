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

"""The kinds and the day: a golden per kind (each committed sample gives exactly its expected records), the guards over the kinds (every
mapping has a sample, an expected output and a current page), the samples equal to the generator's output, the links across kinds and
the daily driver (the sample day resolves every link; its order; the delete plan)."""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import pytest

yaml = pytest.importorskip("yaml")

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from mrloader import csv_load as L, kind_docs, links, mr_gen, mr_run  # noqa: E402
from mrloader.common import lower_keys  # noqa: E402
from mrloader.kindmap import KINDS_DIR, KindMapping  # noqa: E402

HERE = Path(__file__).resolve().parent
SAMPLES = HERE / "samples"
D = "2026-11-18"
RUN = {"computed_at": "2026-11-18T22:00:00Z", "source_system": "SYNTHETIC-RISK"}


def _canonical() -> list[Path]:
    return sorted(p for p in KINDS_DIR.glob("*.yaml") if p.name.count(".") == 1)


@pytest.mark.parametrize("mapping", _canonical(), ids=lambda p: p.stem)
def test_each_sample_gives_exactly_its_expected_records(mapping, tmp_path):
    m = KindMapping(mapping)
    kids = {n: SAMPLES / f"{m.kind}.{n}.csv" for n in m.children}
    s = L.convert(L.LoadConfig(m.kind, SAMPLES / f"{m.kind}.csv", D, children=kids, out=tmp_path, run=dict(RUN)))
    got = (tmp_path / f"mr-{D}" / f"{m.kind}.{D}.jsonl").read_text()
    assert got == (SAMPLES / f"{m.kind}.expected.jsonl").read_text()
    assert s["ROWS_REJECTED"] == 0 and all(not lower_keys(r) for r in s["_RECORDS"])
    assert all(m.contract.check(r, extra_ok=bool(m.pass_columns)) == [] for r in s["_RECORDS"])


def test_every_mapping_has_a_sample_an_expected_output_and_a_page():
    for p in _canonical():
        kind = p.stem
        assert (SAMPLES / f"{kind}.csv").is_file() and (SAMPLES / f"{kind}.expected.jsonl").is_file(), kind
        assert (HERE / "docs" / f"{kind}.md").is_file(), kind
    assert kind_docs.main(["--check"]) == 0


def test_the_samples_are_the_generators_output(tmp_path):
    files = mr_gen.generate(tmp_path, D)
    for f in files.values():
        assert f.read_text() == (SAMPLES / f.name).read_text(), f.name


def test_book_from_the_pvf_portfolio_file_has_the_ids_of_the_canonical_book(tmp_path):
    mr_gen.generate_pvf(tmp_path, D)
    s = L.convert(L.LoadConfig("book", tmp_path / "portfolio_d2.csv", D, source="pvf", out=tmp_path))
    canonical = [json.loads(x) for x in (SAMPLES / "book.expected.jsonl").read_text().splitlines()]
    assert [(r["BOOK_ID"], r["DESK"]) for r in s["_RECORDS"]] == [(r["BOOK_ID"], r["DESK"]) for r in canonical]


def test_sensitivity_children_reject_duplicates_orphans_and_missing_lists(tmp_path):
    parents = "GRAIN,POSITION_ID,LEGAL_ENTITY,DESK,TT_BOOK,PRODUCT_GROUP,PRODUCT_TYPE,TRADE_STATUS,MATURITY_DATE,CURRENCY\n"
    parents += "POSITION,POS-1,LE-A,DESK-A,BOOK-A,Rates,Swap,VALIDATED,2030-01-01,USD\nPOSITION,POS-2,LE-A,DESK-A,BOOK-A,Rates,Swap,VALIDATED,2030-01-01,USD\n"
    parents += "POSITION,POS-3,LE-A,DESK-A,BOOK-A,Rates,Swap,VALIDATED,2030-01-01,USD\n"
    kids = "POSITION_ID,RISK_FACTOR,MEASURE,VALUE,VALUE_CURRENCY\nPOS-1,IR/CURVE/USD/SOFR/5Y,DV01,1,USD\nPOS-2,IR/CURVE/USD/SOFR/5Y,DV01,1,USD\n"
    kids += "POS-2,IR/CURVE/USD/SOFR/5Y,DV01,2,USD\nPOS-9,IR/CURVE/USD/SOFR/5Y,DV01,1,USD\nPOS-1,EQ/SPOT/SPX,CURVATURE_UP,3,USD\n"
    (tmp_path / "p.csv").write_text(parents)
    (tmp_path / "k.csv").write_text(kids)
    with pytest.raises(L.PvfError, match="max-rejects"):
        L.convert(L.LoadConfig("position-sensitivity", tmp_path / "p.csv", D, children={"SENSITIVITIES": tmp_path / "k.csv"}, out=tmp_path,
                               run=dict(RUN)))
    import csv
    rows = list(csv.DictReader(open(tmp_path / f"mr-{D}" / f"position-sensitivity.{D}.rejects.csv")))
    assert sorted(r["REASON"] for r in rows) == ["CONDITION", "DUPLICATE_CHILD", "NO_CHILDREN", "ORPHAN_CHILD"]


@pytest.fixture()
def day(tmp_path):
    pytest.importorskip("polars")
    src = tmp_path / "in"
    mr_gen.generate(src, D)
    mr_gen.generate_pvf(src, D)
    return src


def test_the_sample_day_runs_in_order_and_every_link_resolves(day, tmp_path, capsys):
    a = mr_run.parser().parse_args(["--date", D, "--from", str(day), "--day", str(HERE / "days" / "sample.yaml"), "--out", str(tmp_path / "o")])
    s = mr_run.run(a)
    assert s["ORDER"] == ["legal-entity", "desk", "book", "counterparty-group", "counterparty", "agreement", "csa", "netting-set",
                          "collateral-balance", "credit-limit", "risk-factor", "scenario-pv", "var-limit", "position-sensitivity",
                          "trade-valuation", "desk-pnl"]
    assert not s["REFUSED"] and all(r["STATUS"] == "DONE" for r in s["KINDS"].values())
    checked = [r for r in s["LINKS"] if r["CHECKED"]]
    assert {(r["KIND"], r["FIELD"]) for r in checked} >= {("var-limit", "NODE_ID"), ("position-sensitivity", "POSITION_ID"),
                                                          ("position-sensitivity", "SENSITIVITIES[].RISK_FACTOR"), ("desk", "LEGAL_ENTITY"),
                                                          ("book", "DESK"), ("desk-pnl", "DESK_ID"), ("netting-set", "CSA"),
                                                          ("trade-valuation", "NETTING_SET"), ("collateral-balance", "NETTING_SET"),
                                                          ("credit-limit", "COUNTERPARTY"), ("counterparty", "GROUP")}
    assert all(not r["UNRESOLVED"] for r in s["LINKS"]), [r for r in s["LINKS"] if r["UNRESOLVED"]]
    folder = tmp_path / "o" / f"mr-{D}"
    assert (folder / f"mr-{D}.summary.json").is_file() and (folder / f"links-{D}.csv").is_file()
    assert "var-limit" in capsys.readouterr().out


def test_a_limit_on_a_node_no_position_builds_warns_and_a_refuse_link_refuses(day, tmp_path):
    with open(day / "var-limit.csv", "a") as f:
        f.write("MGMT,DESK-NOWHERE,,100,USD,1D,0.99,2026-01-02,\n")
    a = mr_run.parser().parse_args(["--date", D, "--from", str(day), "--day", str(HERE / "days" / "sample.yaml"), "--out", str(tmp_path / "o")])
    s = mr_run.run(a)
    miss = [r for r in s["LINKS"] if r["UNRESOLVED"]]
    assert [(r["KIND"], list(r["UNRESOLVED"])) for r in miss] == [("var-limit", ["MGMT~DESK-NOWHERE"])] and not s["REFUSED"]
    a.strict_links = True
    with pytest.raises(mr_run.PvfError, match="refused"):
        mr_run.run(a)


def test_a_missing_required_kind_refuses_the_day_and_the_delete_plan_runs_nothing(tmp_path, capsys):
    a = mr_run.parser().parse_args(["--date", D, "--from", str(tmp_path), "--day", str(HERE / "days" / "sample.yaml")])
    with pytest.raises(mr_run.PvfError, match="var-limit"):
        mr_run.run(a)
    a.delete_plan = True
    plan = mr_run.run(a)["DELETE_PLAN"]
    kinds = [x.split("--kind ")[1].split()[0] for x in plan]
    assert kinds[0] == "desk-pnl" and kinds[-1] == "legal-entity"                       # vectors first, masters last
    assert kinds.index("scenario-pv") < kinds.index("scenario-set")


def test_a_refuse_link_refuses_the_day_and_nothing_is_delivered(day, tmp_path):
    with open(day / "trade-valuation.csv", "a") as f:
        f.write("7999999,ENDUR,Swap,Rates,PORT_100000,RATES TRADING-LON,CP-ALDERSHOT-FUND,NS-NOWHERE,B,VALIDATED,USD,1,1,2025-06-20,2030-07-31\n")
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    a = mr_run.parser().parse_args(["--date", D, "--from", str(day), "--day", str(HERE / "days" / "sample.yaml"), "--out", str(tmp_path / "o"),
                                    "--inbox", str(inbox)])
    with pytest.raises(mr_run.PvfError, match="trade-valuation"):
        mr_run.run(a)
    assert not list(inbox.iterdir())


def test_var_nodes_follow_the_pack_hierarchies():
    nodes = links.var_nodes([{"DIVISION": "DIV-M", "DESK": "DESK-A", "TT_BOOK": "BOOK-1", "LEGAL_ENTITY": "LE-X"}])
    assert {"MGMT~DIV-M", "MGMT~DESK-A", "MGMT~BOOK-1", "LEGAL~LE-X", "LEGAL~LE-X/DESK-A", "LEGAL~LE-X/DESK-A/BOOK-1", "MGMT~BANK"} <= nodes


def test_driver_and_tools_by_path(tmp_path):
    import subprocess
    for script in ("mr_run.py", "mr_gen.py", "kind_docs.py", "validate.py", "csv_load.py"):
        h = subprocess.run([sys.executable, str(HERE / script), "--help"], capture_output=True, text=True, timeout=60)
        assert h.returncode == 0 and "example" in h.stdout.lower(), script
    shutil.rmtree(tmp_path, ignore_errors=True)
