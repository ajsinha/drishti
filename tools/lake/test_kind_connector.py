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

"""A kind loads into the table of the connector that serves it (netting-set: credit/netting-set), not the pack's first Delta connector."""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools" / "lake")]
import build  # noqa: E402

PACK = ROOT / "modules" / "banking" / "packs" / "counterparty-risk"


def test_each_kind_goes_to_its_own_connectors_domain():
    assert build.pack_defaults(PACK, "agreement")["lake_domain"] == "legal"
    for kind in ("netting-set", "credit-limit"):
        assert build.pack_defaults(PACK, kind)["lake_domain"] == "credit", kind


def test_the_demo_lake_file_has_the_entries_those_tables_need():
    text = (ROOT / "config" / "lakes" / "demo.yaml").read_text(encoding="utf-8")
    assert "credit.netting-set:" in text and "credit.credit-limit:" in text
