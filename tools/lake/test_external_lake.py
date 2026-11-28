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

"""A bank's external lake (docs/admin/TABLE_CATALOGUE.md, "Binding a bank's external lake"): tables at arbitrary folders outside Drishti's
layout, different names and depths, Delta and Iceberg, known to the tools ONLY by symbolic name -> uri/format/contract. The tools write
them where the lake file says and read them back; nothing lands under the lake's root or anywhere else.

    python3 -m pytest -q tools/lake/test_external_lake.py
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import lakeconfig  # noqa: E402

ROWS = {f"T{i}": {"ID": f"T{i}", "NOTIONAL": 100.0 * i, "DESK": "FX"} for i in range(1, 4)}


def lines(domain: str, kind: str):
    for id_, doc in ROWS.items():
        yield json.dumps({"domain": domain, "kind": kind, "id": id_, "date": "2026-09-30", "doc": json.dumps(doc),
                          "columns": {"DESK": "FX", "NOTIONAL": doc["NOTIONAL"]}})


@pytest.fixture
def bank(tmp_path, monkeypatch):
    pytest.importorskip("deltalake")
    import javaload
    if not javaload.command(None, None):
        pytest.skip("the lake reader and the loader need the server jar or LAKE_CLASSPATH")
    ext = tmp_path / "bank-estate"
    unused_root = tmp_path / "drishti-root-never-used"
    lakes = tmp_path / "lakes"
    lakes.mkdir()
    where = {"trading.trade": ext / "fo-archive" / "2019" / "mx_trades_v7",                 # Delta, three folders deep
             "risk.scenario-pv": ext / "mart" / "risk" / "deep" / "a" / "scn_pv_v3"}       # Iceberg, five deep
    (lakes / "bank.yaml").write_text(f"""lake: bank
format: delta
root: {unused_root}
tables:
  trading.trade:
    uri: {where['trading.trade']}
    format: delta
    contract: trade@1
    promote: [DESK, NOTIONAL]
  risk.scenario-pv:
    uri: {where['risk.scenario-pv']}
    format: iceberg
    contract: scenario-pv@1
    promote: [DESK, NOTIONAL]
  market.curve: {{}}
""", encoding="utf-8")
    monkeypatch.setenv("LAKE_LAKES_DIR", str(lakes))
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    lakeconfig._table.cache_clear()
    return {"lakes": lakes, "where": where, "root": unused_root, "tmp": tmp_path}


def test_symbolic_names_resolve_to_the_banks_uris_and_the_rest_is_a_fallback(bank):
    t = lakeconfig.entry("bank", "trading.trade")
    assert t["location"] == str(bank["where"]["trading.trade"]) and t["how"] == "configured"
    assert (t["format"], t["contract"]) == ("delta", "trade@1")
    s = lakeconfig.entry("bank", "risk.scenario-pv")
    assert (s["format"], s["contract"], s["how"]) == ("iceberg", "scenario-pv@1", "configured")
    c = lakeconfig.entry("bank", "market.curve")
    assert c["how"] == "fallback" and c["location"] == f"{bank['root']}/market/curve"
    b = lakeconfig.bind("bank", "risk.scenario-pv")
    assert (b["domain"], b["table"]) == ("a", "scn_pv_v3") and b["root"] == str(bank["where"]["risk.scenario-pv"].parent.parent)
    with pytest.raises(lakeconfig.LakeConfigError, match="no table"):
        lakeconfig.entry("bank", "trading.nothing")


def test_the_tools_write_and_read_the_external_tables_by_symbolic_name_alone(bank):
    import loaderows
    from deltalake import DeltaTable
    for name, fmt in (("trading.trade", "delta"), ("risk.scenario-pv", "iceberg")):
        b = lakeconfig.bind("bank", name)
        assert b["format"] == fmt
        out = loaderows.load(lines(b["domain"], b["table"]), b["root"], b["domain"], b["table"], bank["tmp"] / f"work-{fmt}", fmt=b["format"])
        assert out["rows"] == 3
        where = bank["where"][name]
        assert where.is_dir(), f"{name} is written where its uri says"
        assert (where / ("_delta_log" if fmt == "delta" else "metadata")).is_dir()
    rows = DeltaTable(str(bank["where"]["trading.trade"])).to_pyarrow_table().to_pylist()
    assert {r["id"]: json.loads(r["doc"]) for r in rows} == ROWS
    assert not bank["root"].exists(), "nothing is written under the lake's own root: every table has its own uri"
    assert not (bank["tmp"] / "data" / "delta").exists(), "nor under the data folder's layout"
