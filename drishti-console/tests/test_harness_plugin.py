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

"""Distribution by test (harness_plugin.py; docs/architecture/TEST_HARNESS.md): under --dist loadgroup a test that is not marked spread is
grouped with its file, the spread tests are units of their own and queued after the groups; under any other --dist nothing changes."""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from harness_plugin import group_and_order


class Item:
    def __init__(self, path, name, marks=()):
        self.fspath, self.name, self.marks = path, name, list(marks)

    def get_closest_marker(self, name):
        return next((m for m in self.marks if m.name == name), None)

    def add_marker(self, mark):
        self.marks.append(mark.mark)


def _config(dist):
    return SimpleNamespace(option=SimpleNamespace(dist=dist, loadgroup=dist == "loadgroup"))


def test_under_loadgroup_files_are_groups_and_spread_tests_go_last():
    items = [Item("/t/test_a_browser.py", "s1", [pytest.mark.spread.mark]), Item("/t/test_b_browser.py", "b1"),
             Item("/t/test_b_browser.py", "b2"), Item("/t/test_a_browser.py", "s2", [pytest.mark.spread.mark])]
    group_and_order(_config("loadgroup"), items)
    assert [i.name for i in items] == ["b1", "b2", "s1", "s2"]
    groups = {i.name: (i.get_closest_marker("xdist_group").kwargs["name"] if i.get_closest_marker("xdist_group") else None) for i in items}
    assert groups == {"b1": "test_b_browser.py", "b2": "test_b_browser.py", "s1": None, "s2": None}


def test_an_explicit_group_is_kept():
    items = [Item("/t/test_c.py", "c1", [pytest.mark.xdist_group(name="mine").mark])]
    group_and_order(_config("loadgroup"), items)
    assert [m.kwargs.get("name") for m in items[0].marks if m.name == "xdist_group"] == ["mine"]


def test_other_distributions_are_untouched():
    items = [Item("/t/test_a.py", "s1", [pytest.mark.spread.mark]), Item("/t/test_b.py", "b1")]
    group_and_order(_config("loadfile"), items)
    assert [i.name for i in items] == ["s1", "b1"] and not items[1].get_closest_marker("xdist_group")
