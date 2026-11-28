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


"""The product's packs live in the module that owns them (docs/architecture/KERNEL_AND_MODULES.md section 18.4): every pack a module's
manifest lists is in that module's packs folder (modules/<id>/packs/<pack>, banking's in modules/banking/packs), only the listed
packs count, config/packs holds a site's own packs and the kernel's ``core`` folder (core, gleif, compute-demo), and the one resolver
(the Java resolver, com.ash.drishti.api.resolution, read through tools/resolved.py) finds exactly those folders."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tools") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools"))
import resolved as R  # noqa: E402
from resolved import duplicates, manifests, pack_dir, pack_dirs  # noqa: E402

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

#: the kernel keeps no domain pack: core and gleif are the reference-data module's, compute-demo is an example
KERNEL_PACKS: set[str] = set()
REPO = R.repository()
OWNED = [(m.id, m.folder / name, name) for m in REPO.modules.values() if m.folder is not None for name in m.packs]


def test_there_are_module_packs():
    assert len(OWNED) >= 10, OWNED


@pytest.mark.parametrize("mid,folder,name", OWNED, ids=[n for _, _, n in OWNED])
def test_every_pack_a_module_lists_is_in_that_module(mid, folder, name):
    manifest = folder / "pack.yaml"
    assert manifest.is_file(), f"module {mid} lists pack {name}, which is not at {folder.relative_to(ROOT)}"
    m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
    assert m.get("pack") in (None, name), f"{manifest.relative_to(ROOT)}: pack {m.get('pack')} in folder {name}"
    assert (folder / "README.md").is_file(), f"{folder.relative_to(ROOT)}: no README.md (the README standard)"
    assert REPO.packs[name].origin == f"module:{mid}"


def test_the_site_holds_no_shipped_pack_and_the_kernel_holds_its_three():
    """config/packs holds a site's own packs and the kernel's core folder: no site pack of the checkout, nothing not read."""
    assert not REPO.packs.of_origin(R.SITE), [p.name for p in REPO.packs.of_origin(R.SITE)]
    assert {p.name for p in REPO.packs.of_origin(R.KERNEL)} == KERNEL_PACKS
    assert not REPO.problems, REPO.problems


def test_the_resolver_finds_exactly_the_modules_packs_and_the_kernels():
    found = pack_dirs()
    expected = {name: folder for _, folder, name in OWNED} | {p.name: p.folder for p in REPO.packs.of_origin(R.KERNEL)}
    assert found == expected
    assert manifests() == [found[n] / "pack.yaml" for n in sorted(found)]
    for name, d in found.items():
        assert pack_dir(name) == d == pack_dir(name, R.REPO_PACKS)


def test_the_resolver_reads_a_flat_root_and_a_group_root_alike(tmp_path):
    for rel in ("flat-pack", "risk/deep-pack"):
        (tmp_path / rel).mkdir(parents=True)
        (tmp_path / rel / "pack.yaml").write_text(f"pack: {Path(rel).name}\n", encoding="utf-8")
    assert pack_dirs(tmp_path) == {"deep-pack": tmp_path / "risk" / "deep-pack", "flat-pack": tmp_path / "flat-pack"}
    assert pack_dir("deep-pack", tmp_path) == tmp_path / "risk" / "deep-pack"
    assert pack_dir("absent", tmp_path) == tmp_path / "absent"


def test_a_pack_name_in_two_site_folders_is_refused_never_one_of_them(tmp_path):
    for rel in ("banking/twin", "economy/twin", "banking/single"):
        (tmp_path / rel).mkdir(parents=True)
        (tmp_path / rel / "pack.yaml").write_text(f"pack: {Path(rel).name}\n", encoding="utf-8")
    assert pack_dirs(tmp_path) == {"single": tmp_path / "banking" / "single"}            # the twin is in neither
    problem = duplicates(tmp_path)["twin"]
    assert str(tmp_path / "banking" / "twin") in problem and str(tmp_path / "economy" / "twin") in problem
    with pytest.raises(ValueError, match="is in two places"):
        pack_dir("twin", tmp_path)
    assert duplicates() == {}, "the shipped packs hold each name once"


def test_a_folder_a_module_does_not_list_is_not_read():
    listed = {name for _, _, name in OWNED}
    for m in REPO.modules.values():
        if m.folder is not None and m.folder.is_dir():
            for d in m.folder.iterdir():
                if (d / "pack.yaml").is_file() and d.name not in m.packs:
                    assert d.name not in listed or REPO.packs[d.name].folder != d, d
