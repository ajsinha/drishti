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

"""Every pack of every module that is on, and every kernel pack, loads in the console from wherever the one resolver finds it, with its
forms where its pack.yaml declares any (FORMS, FORM pages, search, DATAMAP, WHAT and Help all read through core.packs; FORMS listed only
CORE and GLEIF while the console looked for packs under config/packs only)."""
from pathlib import Path

import pytest
import yaml

from conftest import CONSOLE
from core import resolution
from core.packs import Packs

pytestmark = pytest.mark.static


def test_every_pack_of_the_modules_on_and_the_kernel_loads_in_the_console_with_its_forms():
    r = resolution.repository()
    packs = Packs({}, CONSOLE)
    names = r.packs.names()
    assert {"core", "gleif", "market-risk", "trading", "funds", "us-equities", "world-economy"} <= set(names), names
    missing = [n for n in names if packs._load(n) is None]
    assert not missing, f"packs the console cannot load: {missing}"
    for p in r.packs.values():
        declared = (yaml.safe_load(p.manifest.read_text(encoding="utf-8")) or {}).get("form") or {}
        if any(isinstance(v, list) and v for v in declared.values()):
            assert packs._load(p.name).get("forms"), f"pack {p.name} declares forms the console does not see"
    assert packs._load("market-risk").get("forms") and packs._load("trading").get("forms")
    assert Path(packs._load("market-risk")["dir"]).is_relative_to(CONSOLE.parent / "modules")
    assert {p.name for p in r.packs.of_origin(resolution.KERNEL)} == {"gleif", "compute-demo"}
