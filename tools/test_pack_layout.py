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

"""The product's packs live in the module that owns them (docs/architecture/KERNEL_AND_MODULES.md; the plan's "a pack lives in its
module"): every pack a module.yaml lists is in that module's packs folder (modules/<id>/packs/<pack>, banking's in modules/banking/dmod/packs),
no first-party pack remains in config/packs (a site's own packs and overrides, and the kernel's own ``core`` packs), and the one resolver
(drishti-console/core/pack_paths.py, the server's twin PackFolders) finds exactly those folders."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
PACKS = ROOT / "config" / "packs"
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import duplicates, manifests, pack_dir, pack_dirs  # noqa: E402

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

#: the kernel's own reference packs: no module owns them, they stay in config/packs/core
KERNEL_PACKS = {"core", "gleif", "compute-demo"}


def _module_homes():
    """Every first-party module folder with a packs: section: (module id, its folder, the packs folder name, the packs it lists)."""
    out = []
    for manifest in sorted(ROOT.glob("modules/*/module.yaml")) + sorted(ROOT.glob("modules/*/dmod/module.yaml")):
        m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
        packs = m.get("packs")
        if isinstance(packs, dict) and packs.get("list"):
            out.append((m["id"], manifest.parent, packs.get("folder") or "packs", list(packs["list"])))
    return out


HOMES = _module_homes()
OWNED = [(mid, home / folder / name, name) for mid, home, folder, names in HOMES for name in names]


def test_there_are_module_packs():
    assert len(OWNED) >= 10, OWNED


@pytest.mark.parametrize("mid,folder,name", OWNED, ids=[n for _, _, n in OWNED])
def test_every_pack_a_module_lists_is_in_that_module(mid, folder, name):
    manifest = folder / "pack.yaml"
    assert manifest.is_file(), f"module {mid} lists pack {name}, which is not at {folder.relative_to(ROOT)}"
    m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
    assert m.get("pack") in (None, name), f"{manifest.relative_to(ROOT)}: pack {m.get('pack')} in folder {name}"
    assert (folder / "README.md").is_file(), f"{folder.relative_to(ROOT)}: no README.md (the README standard)"


def test_no_first_party_pack_remains_in_the_old_location():
    """config/packs holds a site's own packs and the kernel's core ones: nothing a module lists, no domain folder of shipped packs."""
    owned = {name for _, _, name in OWNED}
    left = sorted(p.parent.relative_to(ROOT).as_posix() for p in list(PACKS.glob("*/pack.yaml")) + list(PACKS.glob("*/*/pack.yaml"))
                  if p.parent.name in owned)
    assert left == [], f"a module's pack is still under config/packs: {left}"
    domains = sorted(d.name for d in PACKS.iterdir() if d.is_dir() and not (d / "pack.yaml").is_file())
    assert domains == ["core"], f"config/packs holds only the kernel's core folder, not {domains}"
    assert {p.parent.name for p in PACKS.glob("core/*/pack.yaml")} == KERNEL_PACKS


def test_the_resolver_finds_exactly_the_modules_packs_and_the_kernels():
    found = pack_dirs(PACKS)
    expected = {name: folder for _, folder, name in OWNED} | {p.parent.name: p.parent for p in PACKS.glob("core/*/pack.yaml")}
    assert found == expected
    assert manifests(PACKS) == [found[n] / "pack.yaml" for n in sorted(found)]
    for name, d in found.items():
        assert pack_dir(name) == d == pack_dir(name, PACKS)


def test_the_resolver_reads_a_flat_root_and_a_domain_root_alike(tmp_path):
    for rel in ("flat-pack", "risk/deep-pack"):
        (tmp_path / rel).mkdir(parents=True)
        (tmp_path / rel / "pack.yaml").write_text(f"pack: {Path(rel).name}\n", encoding="utf-8")
    assert pack_dirs(tmp_path) == {"deep-pack": tmp_path / "risk" / "deep-pack", "flat-pack": tmp_path / "flat-pack"}
    assert pack_dir("deep-pack", tmp_path) == tmp_path / "risk" / "deep-pack"
    assert pack_dir("absent", tmp_path) == tmp_path / "absent"


def test_a_pack_name_in_two_domain_folders_is_refused_never_one_of_them(tmp_path):
    for rel in ("banking/twin", "economy/twin", "banking/single"):
        (tmp_path / rel).mkdir(parents=True)
        (tmp_path / rel / "pack.yaml").write_text(f"pack: {Path(rel).name}\n", encoding="utf-8")
    assert pack_dirs(tmp_path) == {"single": tmp_path / "banking" / "single"}            # the twin is in neither
    problem = duplicates(tmp_path)["twin"]
    assert str(tmp_path / "banking" / "twin") in problem and str(tmp_path / "economy" / "twin") in problem
    with pytest.raises(ValueError, match="is in two folders"):
        pack_dir("twin", tmp_path)
    assert duplicates(PACKS) == {}, "the shipped packs hold each name once"
