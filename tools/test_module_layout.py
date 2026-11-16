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

"""Every module has ONE folder layout (docs/architecture/KERNEL_AND_MODULES.md section 8.10): its .dmod content at the top of
modules/<module>/ (module.yaml first, then packs/, console/, data/, docs/, connectors/, mappings/, blotters/ ...) and, only when it has
Java, its Maven projects under java/<project>/ with the aggregating pom.xml beside them. No dmod/ or src/dmod/ folder anywhere."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
MODULES = sorted(d for d in (ROOT / "modules").iterdir() if d.is_dir())

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

#: the names a module's top may hold: the .dmod content, then what only builds it (java/ + pom.xml, tools/; never packed)
CONTENT = {"module.yaml", "content.yaml", "dataload.yaml", "packs", "console", "data", "docs", "connectors", "mappings", "blotters",
           "elements", "lenses", "README.md"}
BUILD = {"java", "pom.xml", "tools"}
IGNORED = {"target"}   # build output, never committed


def test_there_are_modules():
    assert len(MODULES) >= 10, MODULES


@pytest.mark.parametrize("module", MODULES, ids=[m.name for m in MODULES])
def test_the_module_uses_the_one_layout(module):
    names = {p.name for p in module.iterdir()} - IGNORED
    assert (module / "module.yaml").is_file(), f"modules/{module.name}: its module.yaml belongs at the module's top"
    stray = sorted(names - CONTENT - BUILD)
    assert not stray, (f"modules/{module.name}: {stray} at the top; allowed: the .dmod content {sorted(CONTENT)} and, for a module with code,"
                       f" {sorted(BUILD)}. A Maven project goes under java/<project>/")
    assert ("java" in names) == ("pom.xml" in names), f"modules/{module.name}: java/ and pom.xml come together (code) or not at all"


@pytest.mark.parametrize("module", [m for m in MODULES if (m / "java").is_dir()], ids=lambda m: m.name)
def test_a_module_with_code_aggregates_exactly_its_maven_projects(module):
    projects = sorted(d.name for d in (module / "java").iterdir() if d.is_dir() and d.name not in IGNORED)
    assert projects, f"modules/{module.name}/java holds no Maven project"
    for p in projects:
        assert (module / "java" / p / "pom.xml").is_file(), f"modules/{module.name}/java/{p}: a folder of java/ is a Maven project (pom.xml)"
    listed = re.findall(r"<module>([^<]+)</module>", (module / "pom.xml").read_text(encoding="utf-8"))
    assert sorted(listed) == [f"java/{p}" for p in projects], f"modules/{module.name}/pom.xml lists {listed}, java/ holds {projects}"
    assert f"<module>modules/{module.name}</module>" in (ROOT / "pom.xml").read_text(encoding="utf-8"), \
        f"the root pom does not build modules/{module.name}"


def test_no_dmod_folder_anywhere():
    found = [p.relative_to(ROOT).as_posix() for m in MODULES for p in m.rglob("dmod")
             if p.is_dir() and "target" not in p.relative_to(m).parts]
    assert not found, f"{found}: a module's .dmod content sits at its folder's top, never in a dmod/ folder"
