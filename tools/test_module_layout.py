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
import struct
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import module_layout  # noqa: E402



def _git_dir(root: Path) -> Path:
    """The checkout's git folder: .git itself, or the folder a worktree's .git file names (gitdir: ...)."""
    dot = root / ".git"
    if dot.is_file():
        return Path(dot.read_text(encoding="utf-8").split("gitdir:", 1)[1].strip())
    return dot


def tracked(root: Path, prefix: str) -> list[str]:
    """The paths git tracks under ``prefix``, read from the checkout's index file (format versions 2, 3 and 4): what
    ``git ls-files <prefix>`` prints, without starting git (a static test starts nothing: TEST_HARNESS.md, rule 11)."""
    data = (_git_dir(root) / "index").read_bytes()
    sig, version, count = struct.unpack(">4sII", data[:12])
    assert sig == b"DIRC" and version in (2, 3, 4), f"{root}: a git index of version {version} is not read here"
    out, pos, last = [], 12, b""
    for _ in range(count):
        start = pos
        flags = struct.unpack(">H", data[pos + 60:pos + 62])[0]
        pos += 62 + (2 if version >= 3 and flags & 0x4000 else 0)
        if version == 4:                                     # prefix compression: drop N bytes of the last path, add the rest
            c = data[pos]
            pos += 1
            strip = c & 0x7F
            while c & 0x80:
                c = data[pos]
                pos += 1
                strip = ((strip + 1) << 7) | (c & 0x7F)
            end = data.index(b"\0", pos)
            last = last[:len(last) - strip] + data[pos:end]
            pos = end + 1
        else:
            end = data.index(b"\0", pos)
            last = data[pos:end]
            pos = start + ((end - start) // 8 + 1) * 8           # NUL padding to a multiple of 8
        out.append(last.decode("utf-8"))
    head = prefix.rstrip("/") + "/"
    return [f for f in out if f.startswith(head)]


#: what git tracks under modules/: an older checkout keeps git-ignored leftovers (old build folders) that are no part of the layout
TRACKED = tracked(ROOT, "modules")
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
