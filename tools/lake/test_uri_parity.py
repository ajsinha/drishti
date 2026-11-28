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

"""Storage parity of a table bound by a location (docs/admin/TABLE_CATALOGUE.md): the same rows written to ``<root>/<domain>/<table>`` through
Delta, Iceberg and the file store land where ``lakeconfig.locate`` says, and read back the same documents (the Java side's twin is
OfficialSinkTest, a table bound by ``uri:``).

    python3 -m pytest -q tools/lake/test_uri_parity.py
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import lakeconfig  # noqa: E402

DOCS = {f"T{i}": {"ID": f"T{i}", "NOTIONAL": 100.0 * i, "DESK": "FX"} for i in range(1, 4)}
DAY = date(2026, 9, 30)


def lines():
    for id_, doc in DOCS.items():
        yield json.dumps({"domain": "official", "kind": "bound", "id": id_, "date": DAY.isoformat(), "doc": json.dumps(doc),
                          "columns": {"DESK": "FX", "NOTIONAL": doc["NOTIONAL"]}})


def test_a_table_bound_by_location_is_the_same_in_delta_iceberg_and_the_file_store(tmp_path):
    pytest.importorskip("deltalake")
    import loaderows
    import javaload
    from deltalake import DeltaTable
    sys.path.insert(0, str(HERE.parent))
    import ingest_jsonl
    if not javaload.command(None, None):
        pytest.skip("the parallel loader needs the server jar or LAKE_CLASSPATH")
    read = {}
    for fmt in ("delta", "iceberg"):
        root = tmp_path / fmt
        out = loaderows.load(lines(), root, "official", "bound", tmp_path / f"work-{fmt}", fmt=fmt)
        where = Path(lakeconfig.locate(root, "official", "bound"))
        assert out["rows"] == 3 and where.is_dir(), f"{fmt}: the table is where locate says"
        assert (where / ("_delta_log" if fmt == "delta" else "metadata")).is_dir()
        if fmt == "delta":
            t = DeltaTable(str(where)).to_pyarrow_table().to_pylist()
            read[fmt] = {r["id"]: json.loads(r["doc"]) for r in t}
    files = tmp_path / "files"
    report = ingest_jsonl.Report()
    ingest_jsonl.write_files(files, "official", {"bound": {DAY: [(i, json.dumps(d), None) for i, d in DOCS.items()]}}, "replace", report, say=lambda *_: None)
    target = lakeconfig.locate_path(files, "official") / DAY.isoformat() / "bound.jsonl"
    assert target.is_file()
    read["files"] = {r["id"]: json.loads(r["doc"]) for r in map(json.loads, target.read_text(encoding="utf-8").splitlines())}
    assert read["delta"] == read["files"] == DOCS
