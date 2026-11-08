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

"""The guides' data sections (tools/docs/guide_data.py, tools/docs/guide_data.yaml): every section is current and links each dataset's
data contract and its example, never a copied record."""
from __future__ import annotations

import importlib.util

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent


def _tool():
    spec = importlib.util.spec_from_file_location("drishti_guide_data", ROOT / "tools" / "docs" / "guide_data.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.mark.static
def test_every_guide_data_section_is_current_and_in_its_contract():
    tool = _tool()
    assert tool.run(check=True) == 0, "stale or invalid: run python3 tools/docs/guide_data.py (the errors are on stderr)"
    ids = {g["id"] for g in tool.load()}
    assert {"var", "exposure", "frtb-sa", "simm", "funds", "groupings", "pivot"} <= ids


@pytest.mark.static
def test_a_missing_sample_and_a_kind_without_a_contract_are_reported():
    tool = _tool()
    cons = tool.contracts()
    guide = {"id": "t", "number": 9, "file": "docs/guides/VAR_GUIDE.md", "datasets": [
        {"kind": "var-limit", "title": "u", "required": "yes", "for": "t", "about": "t", "sample": "market-risk/none/*.jsonl.gz"},
        {"kind": "no-such-kind", "title": "v", "required": "yes", "for": "t", "about": "t"}]}
    _, problems = tool.build(guide, cons)
    text = "\n".join(problems)
    assert "no file matches" in text and "no data contract for kind 'no-such-kind'" in text


@pytest.mark.static
def test_a_dataset_links_its_contract_and_its_example_and_copies_neither():
    tool = _tool()
    guide = {"id": "t", "number": 9, "file": "docs/guides/VAR_GUIDE.md", "datasets": [
        {"kind": "scenario-pv", "title": "t", "required": "yes", "for": "t", "about": "t",
         "sample": "market-risk/scenario-pv/scenario-pv-*.tar"}]}
    block, problems = tool.build(guide, tool.contracts())
    assert not problems
    assert "(../contracts/samples/market-risk.md#scenario-pv)" in block
    assert "(../../config/packs/banking/market-risk/contracts/scenario-pv.contract.yaml)" in block
    assert "```" not in block and "SCENARIO_PV_ID\":" not in block
