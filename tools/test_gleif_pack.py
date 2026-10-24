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

"""The gleif pack's generator: make.py --check passes with no cache (the committed samples against what they derive) and with a cache
(a build from the recorded GLEIF cache, checked against itself), and the committed pack keeps its scope: every seed's group, the cap,
the links to us-equities, under its size."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GEN = ROOT / "tools" / "packgen" / "gleif"
PACK = ROOT / "config" / "packs" / "gleif"
FX = ROOT / "tools" / "publicdata" / "tests" / "fixtures" / "gleif"
sys.path.insert(0, str(ROOT / "tools" / "publicdata"))
from gleif import F  # noqa: E402  (the generator's fetch.py by path: other packs have a fetch module too)


def lines(f: Path) -> list[dict]:
    return [json.loads(x) for x in f.read_text(encoding="utf-8").splitlines() if x.strip()]


def test_make_check_passes_without_a_cache(tmp_path):
    r = subprocess.run([sys.executable, str(GEN / "make.py"), "--check", "--cache", str(tmp_path / "none")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "matches its generator" in r.stdout


def test_make_check_passes_with_a_cache(tmp_path):
    cache, pack = tmp_path / "cache", tmp_path / "pack"
    shutil.copytree(FX, cache)
    for args in ([], ["--check"]):
        r = subprocess.run([sys.executable, str(GEN / "make.py"), *args, "--cache", str(cache), "--pack", str(pack)], capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr
    assert "matches its generator" in r.stdout
    assert {f.name for f in (pack / "samples").iterdir()} >= {"catalog.json", "lei-entity.jsonl", "lei-group.jsonl", "lei-country.jsonl"}


def test_the_committed_pack_keeps_its_scope():
    ents = {d["LEI"]: d for d in lines(PACK / "samples" / "lei-entity.jsonl")}
    groups = {d["GROUP_ID"]: d for d in lines(PACK / "samples" / "lei-group.jsonl")}
    assert 5_000 < len(ents) < 50_000
    for _, label, lei in F.SEEDS:                                               # every financial institution is in, with its group
        assert lei in ents, label
        assert ents[lei]["LEI_GROUP"] in groups, label
    assert all(d["MEMBERS"] <= F.GROUP_CAP + len(d.get("SEEDS", [])) for k, d in groups.items() if k != "ALL")
    assert any(d.get("CAPPED") and "at most 50" in d["CAP_NOTE"] for d in groups.values())
    useq = [json.loads(x) for x in (ROOT / "config/packs/us-equities/samples/us-company.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
    with_lei = [c for c in useq if c.get("LEI") in ents]
    assert len(with_lei) > 400 and all(ents[c["LEI"]].get("US_COMPANY") == c["TICKER"] for c in with_lei)
    catalog = json.loads((PACK / "samples" / "catalog.json").read_text(encoding="utf-8"))
    assert {e["kind"] for e in catalog} == {"lei-entity", "lei-group", "lei-relationship", "lei-country", "lei-mapping"}
    size = sum(p.stat().st_size for p in (PACK / "samples").iterdir())
    assert size < 21_000_000, size
