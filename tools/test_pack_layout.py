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

"""The shipped packs' layout, as a quick file check: every pack sits in its domain's folder, config/packs/<domain>/<pack>, where
<domain> is its pack.yaml ``domain:`` (docs/architecture/DOMAIN_MODULES.md section 8); no pack sits directly in config/packs; and the
one resolver (drishti-console/core/pack_paths.py, the server's twin PackPaths) finds exactly those folders."""
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

SHIPPED = sorted(PACKS.glob("*/*/pack.yaml"))


def test_there_are_shipped_packs():
    assert len(SHIPPED) >= 10, SHIPPED


@pytest.mark.parametrize("manifest", SHIPPED, ids=lambda p: p.parent.name)
def test_every_shipped_pack_is_in_the_folder_of_its_domain(manifest):
    m = yaml.safe_load(manifest.read_text(encoding="utf-8")) or {}
    domain, folder = m.get("domain"), manifest.parent.parent.name
    assert domain, f"{manifest.relative_to(ROOT)}: no domain: (the folder it sits in, {folder})"
    assert domain == folder, f"{manifest.relative_to(ROOT)}: domain {domain} but in config/packs/{folder}"
    assert m.get("pack") in (None, manifest.parent.name), f"{manifest.relative_to(ROOT)}: pack {m.get('pack')} in folder {manifest.parent.name}"


def test_no_pack_sits_directly_in_config_packs():
    assert sorted(PACKS.glob("*/pack.yaml")) == [], "a pack goes in its domain's folder: config/packs/<domain>/<pack>"


def test_the_resolver_finds_exactly_the_packs_two_levels_down():
    found = pack_dirs(PACKS)
    assert sorted(found.values()) == sorted(p.parent for p in SHIPPED)
    assert set(found) == {p.parent.name for p in SHIPPED}, "two packs of one name in different domain folders"
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
