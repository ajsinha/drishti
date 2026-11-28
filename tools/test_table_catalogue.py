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

"""The table catalogue (docs/admin/TABLE_CATALOGUE.md): every table of every lake file is listed by its symbolic name with a resolved
location, a table with its own ``uri:`` is shown as configured, and the genomics feeds' tables are named as the Home reads them.

    python3 -m pytest -q tools/test_table_catalogue.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "docs"))
import table_catalogue as T  # noqa: E402


def test_every_table_of_every_lake_file_is_listed():
    text = T.render()
    for lake in T.lakes():
        for name in lake.get("tables") or {}:
            assert f"| `{name}` |" in text, f"{lake['_file']}: {name} is not in the catalogue"


def test_a_table_with_a_uri_is_configured_and_one_without_is_by_convention():
    lake = {"lake": "bank", "format": "delta", "root": "s3://bank-lake/curated"}
    assert T.location(lake, "trading.trade_murex", {"uri": "s3://fo-data/mx/trades_v7"}) == ("s3://fo-data/mx/trades_v7", "delta", "configured")
    assert T.location(lake, "trading.trade", {}) == ("s3://bank-lake/curated/trading/trade", "delta", "by convention")
    assert T.location(lake, "risk.pnl", {"path": "/mnt/mart/pnl", "format": "iceberg"}) == ("/mnt/mart/pnl", "iceberg", "configured")


def test_the_placeholders_of_the_reference_installation_are_read():
    assert T.reference("${lake.data.dir:${drishti.data.dir}}/delta/risklake") == "<data>/delta/risklake"
    assert T.reference("${DRISHTI_DELTA_ROOT:${lake.data.dir:${drishti.data.dir}}/delta/demo}") == "<data>/delta/demo"


def test_the_genomics_feeds_write_the_tables_the_home_reads():
    text = T.render()
    assert "| `genomics.clinvar-variant` |" in text and "| `genomics.ensembl-gene` |" in text
    import yaml
    feeds = ROOT / "config" / "feeds"
    assert yaml.safe_load((feeds / "clinvar.yaml").read_text(encoding="utf-8"))["datasets"]["variant"]["table"] == "clinvar-variant"
    assert yaml.safe_load((feeds / "ensembl.yaml").read_text(encoding="utf-8"))["datasets"]["gene"]["table"] == "ensembl-gene"
