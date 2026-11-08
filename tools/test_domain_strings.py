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

"""Rule 4 of the domain boundaries, as a quick file check (tools/domain_strings.py): no core file names banking more than the allow list
says, and the list holds no more than the tree needs."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import domain_strings as ds  # noqa: E402

pytestmark = pytest.mark.static


def test_the_core_names_no_more_banking_than_the_allow_list():
    allowed = (yaml.safe_load(ds.ALLOWED.read_text(encoding="utf-8")) or {}).get("allowed") or {}
    assert not ds.problems(ds.counts(), allowed), "\n".join(ds.problems(ds.counts(), allowed))


def test_the_check_catches_a_new_name_and_a_grown_count():
    assert ds.problems({"a.py": 1}, {}) and ds.problems({"a.py": 3}, {"a.py": 2}) and ds.problems({}, {"a.py": 1})
    assert not ds.problems({"a.py": 2}, {"a.py": 2})
    assert ds.NAMES.search('w = "VAR"') and ds.NAMES.search("/api/v1/risk/var") and ds.NAMES.search("com.ash.drishti.banking.var")
    assert not ds.NAMES.search("var x = 1;") and not ds.NAMES.search('"VARIANCE"')
