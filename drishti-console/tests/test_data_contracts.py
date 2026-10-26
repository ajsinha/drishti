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

"""Every command has a data contract (docs/contracts/README.md section 16): it fails when a pack's mnemonic has no
config/packs/<pack>/contracts/<kind>.contract.yaml, when a contract breaks tools/contracts.schema.json or no longer names its pack
and command, and when docs/contracts/packs is stale (`drishti.py contract docs --check`). Packs are listed from the disk, so a new
mnemonic is caught the day it lands. The contracts against the data: tools/test_contracts.py."""
from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("drishti_contracts_for_console", ROOT / "tools" / "contracts.py")
C = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(C)

COMMANDS = C.mnemonics()


@pytest.mark.parametrize("cmd", COMMANDS, ids=lambda c: f"{c['pack']}:{c['code']}")
def test_every_command_has_a_valid_contract(cmd):
    f = C.contract_file(C.PACKS, cmd["pack"], cmd["kind"])
    assert f.is_file(), f"{cmd['code']} has no data contract: run  drishti.py contract bootstrap {cmd['kind']} --write"
    c = C.load(f)
    assert C.schema_problems(c) == []
    assert (c["kind"], c["pack"], c["mnemonic"]) == (cmd["kind"], cmd["pack"], cmd["code"])


def test_the_contracts_match_their_packs():
    assert C.check(data=False) == []


def test_the_contract_pages_are_current():
    for page, text in C.render_docs().items():
        assert page.is_file() and page.read_text(encoding="utf-8") == text, f"{page.relative_to(ROOT)} is stale: run  drishti.py contract docs"


def test_the_pages_are_indexed():
    assert "contracts/packs/README.md" in (ROOT / "docs" / "README.md").read_text(encoding="utf-8")
    assert "packs/README.md" in (ROOT / "docs" / "contracts" / "README.md").read_text(encoding="utf-8")
