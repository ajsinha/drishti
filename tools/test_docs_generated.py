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

"""Every generated reference is current (i7k): one test runs every generator's --check, so a
changed YAML, contract, mapping or registry that was not followed by its generator fails here with the command that fixes it.

    python3 -m pytest -q tools/test_docs_generated.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[1]

#: (the reference, the generator's --check command from the repository root). The pack, module and connector READMEs are checked by
#: tools/test_readmes.py, the function catalogue by FunctionCataloguePageTest (Java).
CHECKS = [
    ("docs/admin/CONFIGURATION.md", ["tools/settings_catalogue.py", "--check"]),
    ("docs/admin/UNDER_CONSTRUCTION.md", ["tools/under_construction.py", "--check"]),
    ("docs/guides/COMMAND_CATALOG.md", ["tools/command_catalog.py", "--check"]),
    ("docs/contracts/packs/*.md", ["tools/contracts.py", "docs", "--check"]),
    ("docs/contracts/CSV_FORMATS.md", ["tools/mrloader/kind_docs.py", "--check"]),
    ("config/feeds/*.md", ["tools/feeds/feedreadme.py", "--check"]),
    ("docs/README.md (the Help map)", ["tools/docs/help_map.py", "--check"]),
    ("the guides' data sections", ["tools/docs/guide_data.py", "--check"]),
]


@pytest.mark.parametrize("what,cmd", CHECKS, ids=[c[0] for c in CHECKS])
def test_a_generated_reference_is_current(what, cmd):
    r = subprocess.run([sys.executable, *cmd], cwd=ROOT, capture_output=True, text=True, timeout=600)
    assert r.returncode == 0, f"{what} is stale: run  python3 {' '.join(c for c in cmd if c != '--check')}\n{(r.stdout + r.stderr)[-1500:]}"
