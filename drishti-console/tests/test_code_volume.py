# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
"""The README's "By the numbers" section is generated (tools/docs/code_volume.py) and stays within 10% of the code as it is."""

import importlib.util
from pathlib import Path

import pytest

pytestmark = pytest.mark.static
ROOT = Path(__file__).resolve().parents[2]


def test_the_readme_code_volume_is_current(capsys):
    spec = importlib.util.spec_from_file_location("code_volume", ROOT / "tools" / "docs" / "code_volume.py")
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    assert tool.main(["--check"]) == 0, capsys.readouterr().err
