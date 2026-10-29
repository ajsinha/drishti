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

"""The data samples (docs/contracts/samples/, tools/testdata/sample_docs.py) and the contracts against ALL the committed test data.

Every test-data folder has its page; every page is what the tool writes from the data and the contracts now (so a page never names a
field the data does not have, and a changed contract or file shows); every contract matches every row of its test data (contracts.py
check reads a sample of the rows): no field in the data missing from the contract, no contract field in no row, no required field null,
no type or enum that differs. One scan of the 150 MB of test data serves all the checks (about 25 s, four worker processes).

    python3 -m pytest -q tools/test_sample_docs.py
"""
from __future__ import annotations

import functools
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("drishti_sample_docs", ROOT / "tools" / "testdata" / "sample_docs.py")
S = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = S
_spec.loader.exec_module(S)


@functools.lru_cache(maxsize=1)
def _scans():
    return S.scan_all(S.pages())


@functools.lru_cache(maxsize=1)
def _files():
    return S.build(_scans())


def test_every_test_data_folder_has_its_page():
    folders = sorted(str(d.relative_to(S.TESTDATA)) for d in S.TESTDATA.glob("*/*/") if any(d.glob("*.jsonl.gz")))
    assert len(folders) >= 69
    missing = [f for f in folders if not (S.OUT / f"{f}.md").is_file()]
    assert not missing, f"no data-sample page for {missing}: run python3 tools/testdata/sample_docs.py"


def test_every_page_is_current():
    probs = S.check(_files())
    assert not probs, "\n".join(probs)


def test_no_page_lists_a_field_the_data_does_not_have():
    for f, text in _files().items():
        if f.name == "README.md":
            continue
        name = str(f.relative_to(S.OUT).with_suffix(""))
        known = set(_scans()[name].order)
        named = set(re.findall(r"^\| `([A-Za-z_][\w.\[\]]*)` \|", text, re.M))
        assert named and named <= known, f"{name}: fields the data does not have: {sorted(named - known)}"


def test_every_contract_matches_all_its_test_data():
    probs = S.audit(_scans())
    assert not probs, "\n".join(probs)


def test_the_index_lists_every_page_and_the_computed_kinds():
    index = _files()[S.OUT / "README.md"]
    for p in S.pages():
        assert f"]({p['name']}.md)" in index, p["name"]
    for x in S.computed():
        assert f"`{x['kind']}`" in index, x["kind"]
    assert "**VaR: required**" in index and "**Exposure: required**" in index


def test_the_pages_name_no_release():
    for f, text in _files().items():
        prose = re.sub(r"```json\n.*?```", "", text, flags=re.S)
        found = re.findall(r"(?<![\w.-])\d\.\d+\.\d+(?![\w.-])", prose)
        assert not found, f"{f.relative_to(ROOT)}: release numbers {found[:5]} (the pages describe the data as it is)"


def test_the_forms_of_pv_and_pnl_are_all_shown():
    spv = _files()[S.OUT / "market-risk/scenario-pv.md"]
    pnl = _files()[S.OUT / "market-risk/pnl-vector.md"]
    for text, what in ((spv, "Scenario PVs"), (pnl, "P&L vector")):
        assert f"### {what} by trade, full run" in text and f"### {what} by position, full run" in text
    assert "### Scenario PVs by trade, intraday update, cancel" in spv and "### Scenario PVs by trade, intraday update" in spv
    assert '"… 506 more numbers (one per scenario' in pnl
