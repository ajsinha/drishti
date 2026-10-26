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


"""The command catalog (docs/guides/COMMAND_CATALOG.md) is current: every pack mnemonic is in it with what it does, and it is what
tools/command_catalog.py writes from the packs, their contracts and tools/commands.builtin.yaml."""
import importlib.util
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("command_catalog", ROOT / "tools" / "command_catalog.py")
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


def test_the_catalog_is_current():
    assert catalog.OUT.read_text(encoding="utf-8") == catalog.render(), "run python3 tools/command_catalog.py"


def test_every_mnemonic_is_in_the_catalog_with_what_it_does():
    text = catalog.OUT.read_text(encoding="utf-8")
    for path in ROOT.glob("config/packs/*/pack.yaml"):
        for code in (yaml.safe_load(path.read_text(encoding="utf-8")).get("mnemonics") or {}):
            row = next((ln for ln in text.splitlines() if ln.startswith(f"| `{code}` |")), None)
            assert row, f"{code} is not in the command catalog"
            assert row.split("|")[3].strip(), f"{code} has no description: give its contract one"
