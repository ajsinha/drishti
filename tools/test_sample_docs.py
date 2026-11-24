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

"""The data of the contracts (tools/testdata/sample_docs.py) against ALL the committed test data, and the examples on the contract pages.

Every contract matches every row of its test data (contracts.py check reads a sample of the rows): no field in the data missing from
the contract, no contract field in no row, no required field null, no type or enum that differs. One scan of the 150 MB of test data
serves the check (about 25 s, four worker processes). Every test-data folder has its example on its pack's contract page
(docs/contracts/packs/<pack>.md, written by tools/contracts.py docs; a stale page is the contract check's, test_contracts.py).

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


def test_every_test_data_folder_has_its_example():
    native = {d["folder"] for d in S.manifest().get("datasets") or [] if d.get("native")}   # a mocked source system's own style: no example
    folders = sorted(str(d.relative_to(S.TESTDATA)) for d in S.TESTDATA.glob("*/*/")          # its feed files: dated .jsonl.gz, or tars
                     if (any(d.glob("*.jsonl.gz")) or any(d.glob("*.tar"))) and str(d.relative_to(S.TESTDATA)) not in native)
    assert len(folders) >= 69
    text = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "docs" / "contracts" / "packs").glob("*.md"))
    missing = [f for f in folders if f'<a id="example-{f.replace("/", "-")}"></a>' not in text]
    assert not missing, f"no example on a contract page for {missing}: run python3 tools/contracts.py docs"


def test_every_contract_matches_all_its_test_data():
    probs = S.audit(_scans())
    assert not probs, "\n".join(probs)


def test_an_example_names_no_release_and_folds_long_arrays():
    ex = S.examples()
    assert len(ex) >= 69
    pnl = ex["market-risk/pnl-vector"]["record"]
    assert any(isinstance(v, list) and v and isinstance(v[-1], str) and v[-1].startswith("… ") for v in pnl.values()), "a long vector is folded"
