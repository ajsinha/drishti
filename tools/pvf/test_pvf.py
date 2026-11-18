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

"""tools/pvf: the join rules, the fill policies, the audit, the mapping grammar, the UPPER_SNAKE rule, the export layout and the contract
validator, on small hand-made extracts (paths 0..3, so a vector has 4 values); and the scripts run by path as the owner runs them."""
from __future__ import annotations

import gzip
import json
import subprocess
import sys
import tarfile
from pathlib import Path

import pytest

pl = pytest.importorskip("polars")
np = pytest.importorskip("numpy")
yaml = pytest.importorskip("yaml")

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from pvf import common, mapping as M, pvf_export_gz as E, pvf_join as J, pvf_validate as V  # noqa: E402
from pvf.contract import DateCheck, scenario_pv  # noqa: E402

BD = "2026-10-29"
PORTFOLIO = """portfolio_id,system_id,portfolio_nm,portfolio_group_nm,pnl_sheet_nm,desk_nm,limit_letter_nm,business_group_nm,strategy,valid_dt,valid_to_dt,legal_entity
100,7,PORT_100,RATES TRADING-LON,Corporate Treasury,,,,,2019-05-03 00:00:00,9999-12-31 00:00:00,GMO_SPOG
101,8,PORT_101,FX OPTIONS-ZRH,Trade Currency,,LL-7,,,2019-05-03 00:00:00,2026-01-01 00:00:00,CDADMM_H4705
101,8,PORT_101B,FX OPTIONS-ZRH,Trade Currency,,LL-7,,,2026-01-01 00:00:00,9999-12-31 00:00:00,CDADMM_H4705
"""
POSITIONS = """position_id,src_position_id,portfolio_id,currency_cd,portfolio_nm,transit_cd,position_amt,trade_id,tv_product_type,mf1_pnl_type
6,CAL:66.CAL:66,100,USD,G,T1,10.0,66,Swap,StandardBackwardFunding
1,CAL:11.CAL:11,100,USD,G,T1,1000.5,11,Swap,StandardBackwardFunding
2,CAL:22.CAL:22,101,USD,G,T2,-250.0,22,Option,StandardForwardFunding
3,CAL:33.CAL:33,100,USD,G,T3,5.0,33,Future,StandardForwardFunding
4,CAL:44.CAL:44,999,USD,G,T4,5.0,44,Future,StandardForwardFunding
5,CAL:55.CAL:55,100,USD,G,T5,5.0,55,Forward,StandardForwardFunding
5,CAL:55.CAL:55,100,USD,G,T5,5.0,55,Forward,StandardForwardFunding
"""


def _paths_csv() -> str:
    rows = ["position_id,valid_dt,risk_metric_cd,dataset_cd,shock_group_nm,val,path_num,histo_valid_dt"]

    def add(pid, path, val, day=BD, metric="PV"):
        rows.append(f"{pid},{day} 00:00:00,{metric},HistoVAR,ALL,{val},{path},2026-10-06 00:00:00")
    for p, v in ((-1, 100.0), (0, 101.0), (1, 102.0), (2, 103.0), (3, 104.0)):
        add(1, p, v)                                              # 1: complete
    add(1, 9, 999.0)                                              # beyond --max-path 3: dropped
    add(1, 1, 555.0, day="2026-10-28")                            # another date: dropped
    add(1, 2, 777.0, metric="DELTA")                              # another metric: dropped
    for p, v in ((-1, 200.0), (1, 202.0), (1, 202.0)):
        add(2, p, v)                                              # 2: path 0 from base, 2 and 3 forward-filled; a repeated equal row
    for p, v in ((0, 300.0), (1, 301.0)):
        add(3, p, v)                                              # 3: no path -1
    add(4, -1, 1.0)                                               # 4: portfolio 999 missing
    for p, v in ((-1, 50.0), (0, 1.0), (0, 2.0)):
        add(6, p, v)                                              # 6: path 0 twice, different values
    add(5, -1, 9.0)                                               # 5: twice in the positions file
    return "\n".join(rows) + "\n"


@pytest.fixture()
def extract(tmp_path: Path) -> dict[str, Path]:
    files = {"portfolio": tmp_path / "portfolio_d2.csv", "positions": tmp_path / "pos_20261029.csv", "paths": tmp_path / "pvf_path_20261029.csv"}
    files["portfolio"].write_text(PORTFOLIO)
    files["positions"].write_text(POSITIONS)
    files["paths"].write_text(_paths_csv())
    return files


@pytest.fixture()
def small_mapping(tmp_path: Path) -> Path:
    doc = yaml.safe_load(common.DEFAULT_MAPPING.read_text())
    doc["scenario_set"]["count"] = 4
    p = tmp_path / "mapping.yaml"
    p.write_text(yaml.safe_dump(doc, sort_keys=False))
    return p


def _join(extract, tmp_path, **kw) -> tuple[dict, list[dict], list[list[str]]]:
    c = J.JoinConfig(extract["portfolio"], extract["positions"], extract["paths"], tmp_path / "out" / "j.jsonl", max_path=3, **kw)
    s = J.run(c)
    rows = [json.loads(x) for x in c.out.read_text().splitlines()]
    audit = [x.split(",", 2) for x in c.audit.read_text().splitlines()[1:]]
    return s, rows, audit


# ---- join ---------------------------------------------------------------------------------------------------------------------------
def test_join_rules_order_and_audit(extract, tmp_path):
    s, rows, audit = _join(extract, tmp_path)
    assert [r["POSITION_ID"] for r in rows] == [1, 2]                     # POSITION_ID order, whatever the file order
    one, two = rows
    assert one["BASE_PV"] == 100.0 and one["PATH_PV"] == [101.0, 102.0, 103.0, 104.0] and one["PATHS_FILLED"] == 0
    assert two["PATH_PV"] == [200.0, 202.0, 202.0, 202.0]                  # path 0 = base, then forward fill
    assert two["PATH0_FROM_BASE"] is True and two["PATHS_FILLED"] == 3
    assert two["PORTFOLIO_NM_D2"] == "PORT_101B"                           # the portfolio row valid on the date
    assert {(int(a[0]), a[1]) for a in audit} == {(3, "MISSING_BASE_PV"), (4, "MISSING_PORTFOLIO"), (4, "MISSING_BASE_PV") if False else (4, "MISSING_PORTFOLIO"),
                                                 (5, "DUPLICATE_POSITION"), (6, "CONFLICTING_PATH_VALUES")}
    assert s["PATH_ROWS"]["OUT_OF_RANGE_PATHS"] == 1 and s["PATH_ROWS"]["OTHER_DATE_OR_SET"] == 2
    assert s["PATH_ROWS"]["DUPLICATE_PATH_ROWS"] == 2
    assert s["FILLS"]["POSITIONS_PATH0_FROM_BASE"] == 1 and s["FILLS"]["PATHS_FILLED"] == 3
    assert all(not common.lower_keys(r) for r in rows)                    # every attribute name UPPER_SNAKE


def test_fill_policies(extract, tmp_path):
    _, rows, _ = _join(extract, tmp_path, fill_policy="base")
    assert rows[1]["PATH_PV"] == [200.0, 202.0, 200.0, 200.0]
    _, rows, audit = _join(extract, tmp_path, fill_policy="refuse")
    assert [r["POSITION_ID"] for r in rows] == [1]
    assert ["2", "INCOMPLETE_PATHS"] == audit[[a[0] for a in audit].index("2")][:2]


def test_dense_forward_fill():
    m, filled, path0 = J.dense(np.array([5.0, 7.0]), np.array([0, 1, 1]), np.array([2, 0, 3]), np.array([1.0, 2.0, 3.0]), 4)
    assert m.tolist() == [[5.0, 5.0, 1.0, 1.0], [2.0, 2.0, 2.0, 3.0]]
    assert filled.tolist() == [3, 2] and path0.tolist() == [True, False]


def test_join_refuses_clearly(extract, tmp_path):
    with pytest.raises(common.PvfError, match="no usable path rows"):
        J.run(J.JoinConfig(extract["portfolio"], extract["positions"], extract["paths"], tmp_path / "j.jsonl", business_date="2026-10-27", max_path=3))
    with pytest.raises(common.PvfError, match="not a file"):
        J.JoinConfig(tmp_path / "none.csv", extract["positions"], extract["paths"], tmp_path / "j.jsonl")
    assert J.JoinConfig(extract["portfolio"], extract["positions"], extract["paths"], tmp_path / "j.jsonl").business_date == BD


# ---- mapping ------------------------------------------------------------------------------------------------------------------------
def test_mapping_grammar():
    m = M.Mapping(common.DEFAULT_MAPPING)
    fill = m._fill
    assert fill("A-{X}-{$y}", {"X": 1}, {}, {"y": "z"}) == "A-1-z"
    assert fill("A-{X}", {"X": ""}, {}, {}) == ""                         # an empty part empties the template
    rule = {"from": "S", "regex": "^([A-Z]+):", "map": {"CAL": "CALYPSO"}, "else": "SYSTEM-{N}"}
    assert m._value("SOURCE_SYSTEM", rule, {"S": "CAL:1"}, {}, {}) == "CALYPSO"
    assert m._value("SOURCE_SYSTEM", rule, {"S": "ZZ:1", "N": 4}, {}, {}) == "SYSTEM-4"
    with pytest.raises(M.RowProblem):
        m._value("SOURCE_SYSTEM", {"from": "S", "map": {"A": "B"}}, {"S": "Q"}, {}, {})
    assert m._value("DESK", {"from": ["A", "B"], "slug": True, "prefix": "DESK-"}, {"A": "", "B": "rates trading-lon"}, {}, {}) == "DESK-RATES-TRADING-LON"
    sign = {"from": "Q", "sign": {"negative": "SHORT", "zero": "LONG", "positive": "LONG"}}
    assert [m._value("DIRECTION", sign, {"Q": q}, {}, {}) for q in (-1, 0, 2)] == ["SHORT", "LONG", "LONG"]


def test_mapping_refuses_a_bad_file(tmp_path):
    doc = yaml.safe_load(common.DEFAULT_MAPPING.read_text())
    doc["fields"]["NOT_A_FIELD"] = {"const": 1}
    del doc["fields"]["DESK"]
    p = tmp_path / "m.yaml"
    p.write_text(yaml.safe_dump(doc))
    with pytest.raises(common.PvfError) as e:
        M.Mapping(p)
    assert "NOT_A_FIELD is not a field" in str(e.value) and "DESK" in str(e.value)


def test_scenario_dates(tmp_path, small_mapping):
    assert M.weekdays_before("2026-11-02", 3) == ["2026-10-28", "2026-10-29", "2026-10-30"]
    f = tmp_path / "d.txt"
    f.write_text("2026-10-01\n2026-10-02\n2026-10-05\n")
    with pytest.raises(common.PvfError, match="never padded"):
        M.scenario_dates(M.Mapping(small_mapping), BD, str(f))


def test_upper_keys():
    assert common.upper_keys({"a_b": {"Fo_x": [{"c": 1}]}}) == {"A_B": {"FO_X": [{"C": 1}]}}
    assert common.lower_keys({"A": {"b": 1}, "C": [{"D": 1, "e": 2}]}) == ["A.b", "C[0].e"]


# ---- export and validate -------------------------------------------------------------------------------------------------------------
def test_export_layout_and_contract(extract, tmp_path, small_mapping):
    _join(extract, tmp_path)
    joined, out = tmp_path / "out" / "j.jsonl", tmp_path / "x"
    s = E.export(E.ExportConfig(joined, out, small_mapping, jsonl=out / f"scenario_pv.{BD}.jsonl"))
    assert s["RECORDS_WRITTEN"] == 2 and s["ROWS_AUDITED"] == 0
    recs = [json.loads(x) for x in gzip.open(out / "scenario-pv" / BD / "cob.part-001.jsonl.gz", "rt")]
    assert [json.loads(x) for x in (out / f"scenario_pv.{BD}.jsonl").read_text().splitlines()] == recs
    r = recs[1]
    assert r["POSITION_ID"] == "POS-PVF-2" and r["SCENARIO_PV_ID"] == "SPV-POS-PVF-2-HS-PVF-251" and r["SCENARIO_PV"] == [202.0, 202.0, 202.0, 200.0]   # path 0 is the latest day (newest-first): reversed to oldest first
    assert r["DIRECTION"] == "SHORT" and r["QUANTITY"] == -250.0 and r["SOURCE_SYSTEM"] == "CALYPSO" and r["LIMIT_LETTER"] == "LL-7"
    assert r["STATUS"] == "ok" and "MATURITY_DATE" not in r and "TRADE_STATUS" not in r and "TRADE_ID" not in r
    assert r["PVF_POSITION_ID"] == 2 and r["SRC_POSITION_ID"] == "CAL:22.CAL:22" and r["PVF_TRADE_ID"] == "22" and r["PATHS_FILLED"] == 3
    assert all(not common.lower_keys(x) for x in recs)
    tars = [out / f"scenario-pv-{BD}.tar", out / f"scenario-set-{BD}.tar"]
    with tarfile.open(tars[0]) as t:
        assert t.getnames() == [f"scenario-pv/{BD}/cob.part-001.jsonl.gz"]
    with tarfile.open(tars[1]) as t:
        assert t.getnames() == [f"scenario-set/{BD}/cob.jsonl.gz"]
    assert 'folders: {"scenario-pv": "market-risk"}' in Path(f"{tars[0]}.ready").read_text()
    sums = dict(reversed(x.split("  ")) for x in (out / f"pvf-{BD}.sha256").read_text().splitlines())
    assert sums[tars[0].name] == common.sha256_of(tars[0])
    res = V.validate(tars, omitted=V.known_gaps(small_mapping))
    assert res["OK"], res
    assert not V.validate(tars, strict=True)["OK"]                         # the extras and the gaps are errors to the letter
    first = tars[0].read_bytes()
    E.export(E.ExportConfig(joined, out, small_mapping))
    assert tars[0].read_bytes() == first                                   # deterministic


def test_export_refuses_a_wrong_length_and_newest_first(extract, tmp_path, small_mapping):
    _join(extract, tmp_path)
    joined = tmp_path / "out" / "j.jsonl"
    s = E.export(E.ExportConfig(joined, tmp_path / "y", common.DEFAULT_MAPPING))  # 251 expected, 4 given: audited, never padded
    assert s["RECORDS_WRITTEN"] == 0 and s["AUDITED_BY_REASON"] == {"BAD_VALUE": 2}
    doc = yaml.safe_load(small_mapping.read_text())
    doc["scenario_set"]["path_order"] = "newest-first"
    small_mapping.write_text(yaml.safe_dump(doc, sort_keys=False))
    E.export(E.ExportConfig(joined, tmp_path / "z", small_mapping, dry_run=False))
    r = json.loads(gzip.open(tmp_path / "z" / "scenario-pv" / BD / "cob.part-001.jsonl.gz", "rt").readline())
    assert r["SCENARIO_PV"] == [104.0, 103.0, 102.0, 101.0]


def test_validator_catches_breaches():
    c = scenario_pv()
    good = {"VALUE_TYPE": "PV", "GRAIN": "POSITION", "POSITION_ID": "P", "CURRENCY": "USD", "SCENARIO_PV": [1.0, 2.0]}
    assert any("not one of" in b for b in c.check({**good, "CURRENCY": "GBP"}, extra_ok=True))
    assert any("does not match" in b for b in c.check({**good, "DESK": "RATES"}, extra_ok=True))
    assert any("not UPPER_SNAKE" in b for b in c.check({**good, "desk": "x"}, extra_ok=True))
    assert any("finite" in b for b in c.check({**good, "SCENARIO_PV": [1.0, None]}, extra_ok=True))
    d = DateCheck()
    for i, n in enumerate((3, 4)):
        d.add_pv({"BUSINESS_DATE": BD, "SCENARIO_PV_ID": f"S{i}", "SCENARIO_SET_ID": "HS-1", "BATCH_ID": "B", "SCENARIO_PV": [0.0] * n})
    assert any("differ in length" in p for p in d.problems())


# ---- the scripts, by path, as the owner runs them -------------------------------------------------------------------------------------
def test_driver_by_path(extract, tmp_path, small_mapping):
    run = [sys.executable, str(TOOLS / "pvf" / "pvf_run.py"), "--pv", str(extract["paths"]), "--portfolio", str(extract["portfolio"]),
           "--positions", str(extract["positions"]), "--date", BD, "--max-path", "3", "--mapping", str(small_mapping)]
    r = subprocess.run(run + ["--dry-run"], capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stderr
    assert not (tmp_path / f"pvf-{BD}").exists()
    r = subprocess.run(run, capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stderr
    folder = tmp_path / f"pvf-{BD}"
    summary = json.loads((folder / f"pvf-{BD}.summary.json").read_text())
    assert summary["RECORDS_WRITTEN"] == 2 and summary["JOIN_EXCLUDED"] == 4
    assert (folder / f"scenario_pv.{BD}.jsonl").is_file() and (folder / f"pvf_joined.{BD}.fills.csv").is_file()
    bad = subprocess.run(run[:-6] + ["--date", "2026-13-01"], capture_output=True, text=True, timeout=60)
    assert bad.returncode == 2 and "not a date" in bad.stderr
    for script in ("pvf_join.py", "pvf_export_gz.py", "pvf_validate.py", "pvf_gen.py"):
        h = subprocess.run([sys.executable, str(TOOLS / "pvf" / script), "--help"], capture_output=True, text=True, timeout=60)
        assert h.returncode == 0 and "example" in h.stdout.lower(), script


def test_generator_feeds_the_join(tmp_path):
    files = __import__("pvf.pvf_gen", fromlist=["generate"]).generate(tmp_path / "g", 40, 5, BD, seed=3, missing_portfolio=2)
    s = J.run(J.JoinConfig(files["portfolio"], files["positions"], files["paths"], tmp_path / "g" / "j.jsonl"))
    assert s["POSITIONS_READ"] == 40 and s["EXCLUDED_BY_REASON"].get("MISSING_PORTFOLIO") == 2
    assert s["POSITIONS_WRITTEN"] + s["POSITIONS_EXCLUDED"] == 40


def test_quantity_priority_list_and_sign_from(tmp_path):
    doc = yaml.safe_load(common.DEFAULT_MAPPING.read_text())
    m = M.Mapping(common.DEFAULT_MAPPING)
    q = doc["fields"]["QUANTITY"]
    assert q["from"] == ["QUANTITY", "QTY", "POSITION_AMT"] and "sign_from" not in q
    assert m._value("QUANTITY", q, {"QTY": -5, "POSITION_AMT": 9.0}, {}, {}) == -5.0      # a signed quantity column wins
    assert m._value("QUANTITY", q, {"POSITION_AMT": 9.0}, {}, {}) == 9.0                  # else the unsigned amount
    assert dict(m.sources["QUANTITY"]) == {"QTY": 1, "POSITION_AMT": 1}
    signed = {**q, "sign_from": {"column": "BUY_SELL_CD", "negative": ["SELL"], "positive": ["BUY"], "only_for": ["POSITION_AMT"]}}
    assert m._value("QUANTITY", signed, {"POSITION_AMT": 9.0, "BUY_SELL_CD": "sell"}, {}, {}) == -9.0
    assert m._value("QUANTITY", signed, {"QTY": 4, "BUY_SELL_CD": "SELL"}, {}, {}) == 4.0   # only_for: a signed column is left alone
    with pytest.raises(M.RowProblem, match="neither"):
        m._value("QUANTITY", signed, {"POSITION_AMT": 9.0, "BUY_SELL_CD": "X"}, {}, {})
    assert m._value("DIRECTION", doc["fields"]["DIRECTION"], {}, {"QUANTITY": -9.0}, {}) == "SHORT"


def test_inbox_hand_over_puts_the_set_first_and_each_marker_after_its_archive(tmp_path):
    """--inbox: the scenario set is delivered before the PVs, every archive before its .ready, nothing half-written left behind."""
    from pvf import pvf_run
    out, inbox = tmp_path / "out", tmp_path / "inbox"
    out.mkdir(), inbox.mkdir()
    tars = []
    for kind in ("scenario-pv", "scenario-set"):                 # the export's order; the inbox wants the set first
        t = out / f"{kind}-2026-10-29.tar"
        t.write_bytes(kind.encode())
        Path(f"{t}.ready").write_text("rows: 1\n", encoding="utf-8")
        tars.append(str(t))
    assert pvf_run.to_inbox(tars, inbox) == ["scenario-set-2026-10-29.tar", "scenario-pv-2026-10-29.tar"]
    assert sorted(p.name for p in inbox.iterdir()) == ["scenario-pv-2026-10-29.tar", "scenario-pv-2026-10-29.tar.ready",
                                                      "scenario-set-2026-10-29.tar", "scenario-set-2026-10-29.tar.ready"]
    with pytest.raises(pvf_run.PvfError):
        pvf_run.to_inbox(tars, tmp_path / "missing")
