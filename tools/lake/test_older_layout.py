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

"""The demo lake's older-layout check (tools/lake/stores.py older_layout): a lake folder kept in git with only its README.md counts as
empty, so build_demo_lakes still sees tables left directly in <data>/delta and asks for the one-time move first."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import stores  # noqa: E402


def _lakes(tmp_path):
    d = tmp_path / "lakes"
    d.mkdir()
    (d / "demo.yaml").write_text("lake: demo\nformat: delta\ntables:\n  trading.trade_endur: {}\n  risk.scenario-pv: {}\n", encoding="utf-8")
    return d


def test_a_readme_only_lake_folder_counts_as_empty(tmp_path):
    delta = tmp_path / "data" / "delta"
    (delta / "trading" / "trade_endur").mkdir(parents=True)
    (delta / "demo").mkdir()
    (delta / "demo" / "README.md").write_text("the demo lake\n", encoding="utf-8")
    assert stores.older_layout(delta / "demo", _lakes(tmp_path)) == ["trading"]


def test_a_lake_folder_that_holds_tables_is_left_alone(tmp_path):
    delta = tmp_path / "data" / "delta"
    (delta / "trading" / "trade_endur").mkdir(parents=True)
    (delta / "demo" / "risk").mkdir(parents=True)
    assert stores.older_layout(delta / "demo", _lakes(tmp_path)) == []
