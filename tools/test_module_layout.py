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
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import module_layout  # noqa: E402

#: what git tracks under modules/: an older checkout keeps git-ignored leftovers (old build folders) that are no part of the layout
TRACKED = subprocess.run(["git", "ls-files", "modules"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
#: each module's tracked names at its top
TOPS: dict[str, set] = {}
for _f in TRACKED:
    _parts = _f.split("/")
    if len(_parts) >= 3:
        TOPS.setdefault(_parts[1], set()).add(_parts[2])
MODULES = sorted(ROOT / "modules" / m for m in TOPS)

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

#: the names a module's top may hold: the ONE list (module-layout.yaml, read by the packer too): the .dmod content, then what only
#: builds it (java/ + pom.xml, tools/, content.yaml; never packed)
CONTENT, BUILD = module_layout.lists()


def test_there_are_modules():
    assert len(MODULES) >= 10, MODULES


@pytest.mark.parametrize("module", MODULES, ids=[m.name for m in MODULES])
def test_the_module_uses_the_one_layout(module):
    names = TOPS[module.name]
    assert "module.yaml" in names, f"modules/{module.name}: its module.yaml belongs at the module's top"
    stray = sorted(names - CONTENT - BUILD)
    assert not stray, (f"modules/{module.name}: {stray} at the top; allowed: the .dmod content {sorted(CONTENT)} and, for a module with code,"
                       f" {sorted(BUILD)}. A Maven project goes under java/<project>/")
    assert ("java" in names) == ("pom.xml" in names), f"modules/{module.name}: java/ and pom.xml come together (code) or not at all"


def _projects(module: Path) -> list:
    """The Maven projects under java/ that git tracks (a folder of java/ with tracked files)."""
    return sorted({f.split("/")[3] for f in TRACKED if f.startswith(f"modules/{module.name}/java/") and f.count("/") >= 4})


@pytest.mark.parametrize("module", [m for m in MODULES if "java" in TOPS[m.name]], ids=lambda m: m.name)
def test_a_module_with_code_aggregates_exactly_its_maven_projects(module):
    projects = _projects(module)
    assert projects, f"modules/{module.name}/java holds no Maven project"
    for p in projects:
        assert f"modules/{module.name}/java/{p}/pom.xml" in TRACKED, f"modules/{module.name}/java/{p}: a folder of java/ is a Maven project (pom.xml)"
    listed = re.findall(r"<module>([^<]+)</module>", (module / "pom.xml").read_text(encoding="utf-8"))
    assert sorted(listed) == [f"java/{p}" for p in projects], f"modules/{module.name}/pom.xml lists {listed}, java/ holds {projects}"
    assert f"<module>modules/{module.name}</module>" in (ROOT / "pom.xml").read_text(encoding="utf-8"), \
        f"the root pom does not build modules/{module.name}"


def test_no_dmod_folder_anywhere():
    found = sorted({"/".join(f.split("/")[:i + 1]) for f in TRACKED for i, part in enumerate(f.split("/")[:-1]) if part == "dmod"})
    assert not found, f"{found}: a module's .dmod content sits at its folder's top, never in a dmod/ folder"


def test_leftovers_are_named_never_failed(tmp_path):
    """An older checkout's ignored leftovers (modules/dist, modules/banking/banking-compute) are reported in words (drishti.py setup)."""
    (tmp_path / "dist" / "target").mkdir(parents=True)
    (tmp_path / "banking" / "banking-compute" / "target").mkdir(parents=True)
    (tmp_path / "banking" / "packs").mkdir()
    (tmp_path / "banking" / "module.yaml").write_text("id: banking\n", encoding="utf-8")
    notes = module_layout.leftovers(tmp_path)
    assert notes == ["modules/banking/banking-compute: not part of the module layout, left out (an old build folder? delete it)",
                     "modules/dist: no module.yaml, not a module (an old build folder? delete it)"], notes
