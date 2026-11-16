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
"""The visual snapshots' groups (snapshot_pages.py): every key page in exactly one group, every group its own test file, and every golden
folder a key page's, so splitting the snapshots across the lane's workers never drops a page. A quick file check."""
from pathlib import Path

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

TESTS = Path(__file__).resolve().parent


def _pages():
    import ast

    tree = ast.parse((TESTS / "snapshot_pages.py").read_text(encoding="utf-8"))
    found = {}
    for node in tree.body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.target.id in ("KEY_SNAPSHOTS", "GROUPS"):
            found[node.target.id] = node.value
    keys = [k.value for k in found["KEY_SNAPSHOTS"].keys]
    groups = {k.value: [e.value for e in v.elts] for k, v in zip(found["GROUPS"].keys, found["GROUPS"].values)}
    return keys, groups


def test_every_key_page_is_in_exactly_one_group():
    keys, groups = _pages()
    placed = [p for g in groups.values() for p in g]
    assert sorted(placed) == sorted(keys), (set(keys) ^ set(placed), [p for p in placed if placed.count(p) > 1])


def test_every_group_has_its_test_file_and_no_other_file_captures():
    _keys, groups = _pages()
    files = {p.name for p in TESTS.glob("test_snapshots_*_browser.py")}
    assert files == {f"test_snapshots_{g}_browser.py" for g in groups}
    for g in groups:
        assert f'run_group("{g}"' in (TESTS / f"test_snapshots_{g}_browser.py").read_text(encoding="utf-8")


def test_every_golden_is_a_key_page():
    keys, _groups = _pages()
    golden = {p.name for p in (TESTS / "snapshots").iterdir() if p.is_dir()}
    assert golden <= set(keys), golden - set(keys)
