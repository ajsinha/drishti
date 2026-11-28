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

"""Tests of the loader staging folder (R12 of the production-readiness audit): under the data folder, never the system temp."""

from __future__ import annotations

import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import lakeconfig  # noqa: E402


def test_loader_work_dir_is_under_the_data_folder(tmp_path):
    old = lakeconfig._OVERRIDE
    try:
        lakeconfig.use_data_dir(tmp_path)
        d = lakeconfig.loader_work_dir()
        assert d == tmp_path.resolve() / "tmp" / "loaders" and d.is_dir()
    finally:
        lakeconfig._OVERRIDE = old
        lakeconfig._table.cache_clear()


def test_every_mkdtemp_of_the_lake_tools_names_its_folder():
    for f in HERE.glob("*.py"):
        if f.name.startswith("test_"):
            continue
        for m in re.finditer(r"mkdtemp\((.*)\)", f.read_text()):
            assert "dir=" in m.group(1), f"{f.name}: mkdtemp without dir=: {m.group(0)}"
