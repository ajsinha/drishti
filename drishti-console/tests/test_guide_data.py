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

"""The guides' data sections (tools/docs/guide_data.py, tools/docs/guide_data.yaml): every section is current, each kind links to its data
contract, the sections hold no record of their own (the contracts are the one home of data), and the contracts' examples shorten long
arrays the documented way (tools/docs/examples.py)."""
from __future__ import annotations

import importlib.util

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent


def _load(name: str, rel: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / rel)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _tool():
    return _load("drishti_guide_data", "tools/docs/guide_data.py")


@pytest.mark.static
def test_every_guide_data_section_is_current_and_links_to_its_contracts():
    tool = _tool()
    assert tool.run(check=True) == 0, "stale or invalid: run python3 tools/docs/guide_data.py (the errors are on stderr)"
    ids = {g["id"] for g in tool.load()}
    assert {"var", "exposure", "frtb-sa", "simm", "mr-cube", "funds", "groupings", "pivot"} <= ids


@pytest.mark.static
def test_a_kind_without_a_contract_is_reported_and_a_kind_links_to_its_contract():
    tool = _tool()
    cons = tool.contracts()
    guide = {"id": "t", "number": 9, "file": "docs/guides/VAR_GUIDE.md", "datasets": [
        {"kind": "var-limit", "required": "yes", "for": "t"},
        {"kind": "no-such-kind", "required": "yes", "for": "t"},
        {"kind": "a-file", "contract": None, "required": "yes", "for": "t"}]}
    block, problems = tool.build(guide, cons)
    text = "\n".join(problems)
    assert "no data contract for kind 'no-such-kind'" in text and "needs a contract_note" in text
    assert "(../contracts/packs/market-risk.md#vlim-" in block


@pytest.mark.static
def test_the_data_sections_hold_no_record():
    """One home for data: a guide's data section links to the contracts and shows no JSON record or field table of its own."""
    tool = _tool()
    for g in tool.load():
        text = (ROOT / g["file"]).read_text(encoding="utf-8")
        block = text.split(tool.BEGIN.format(id=g["id"]), 1)[1].split(tool.END, 1)[0]
        assert "```" not in block and "contracts/packs/" in block, g["file"]


@pytest.mark.static
def test_long_arrays_are_shortened_as_documented():
    ex = _load("drishti_doc_examples", "tools/docs/examples.py")
    out = ex.fmt({"V": list(range(20)), "O": [{"a": 1}, {"a": 2}, {"a": 3}, {"a": 4}], "S": [1, 2, 3]})
    assert "/* … 12 more … */," in out and '"V": [\n    0,' in out and "    19\n  ]" in out
    assert "/* … 2 more … */\n  ]" in out and "\n    3\n  ]" in out
