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

"""Tests of tools/publicdata/gleif.py on a recorded cache (Apple's and Moody's ownership groups, cut from GLEIF's real golden copy,
LEI records and mapping files): an offline build, its determinism, --scope through a fake opener, the refusals and the bulk file's
conversion. No network, unless DRISHTI_LIVE_FETCH=1 for the live test."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import pdbase as B  # noqa: E402
import gleif as GL  # noqa: E402
sys.path.insert(0, str(HERE.parents[1] / "testdata"))
import publicsnap as SNAP  # noqa: E402  (a build's data: <out>/testdata/<pack>/<kind>/)

FX = HERE / "fixtures" / "gleif"
APPLE, MOODYS = "HWUPKR0MPOU8FGXBT394", "549300GCEDD8YCF5WU84"


@pytest.fixture(autouse=True)
def data_dir(tmp_path, monkeypatch):
    d = tmp_path / "data"
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(d))
    monkeypatch.setattr(GL.F, "SEEDS", [(GL.F.INFRA, "Moody's", MOODYS)])        # the recorded groups only
    monkeypatch.setattr(GL.F, "US_EQUITIES", FX / "us-company.jsonl")
    return d


@pytest.fixture
def cache(tmp_path):
    c = tmp_path / "cache"
    shutil.copytree(FX, c)
    return c


def ns(cache, **kw):
    base = dict(command="refresh", pause=0, max_age=None, offline=False, scope=None, cache=str(cache), since=None, years=None, out=None,
                dry_run=False, internal_ok=False, no_check=False, sources=[], into_pack=False, max_pages=2)
    base.update(kw)
    return argparse.Namespace(**base)


def snap(pack: Path, kind: str) -> list[dict]:
    """A build's documents of a kind: its snapshot beside the pack folder (<out>/testdata/<pack>/<kind>/snapshot-<date>.jsonl.gz)."""
    return SNAP.kind_docs(pack.name, kind, pack.parent.parent / "testdata")


def built(pack: Path) -> dict[str, bytes]:
    """A build's files: the pack's reference samples and its snapshot, by name."""
    return {str(f.relative_to(pack.parent.parent)): f.read_bytes()
            for f in [*(pack / "samples").glob("*"), *(pack.parent.parent / "testdata").rglob("*.jsonl.gz")]}


def test_offline_build_from_the_recorded_cache_is_complete_and_deterministic(tmp_path, cache, data_dir):
    out = tmp_path / "build"
    assert GL.refresh(ns(cache, offline=True, out=str(out)), log=lambda *_: None) == 0
    pack = out / "packs" / "gleif"
    ents = {d["LEI"]: d for d in snap(pack, "lei-entity")}
    assert {APPLE, MOODYS} <= set(ents)
    assert ents[APPLE]["US_COMPANY"] == "AAPL" and ents[APPLE]["SEGMENT"] == "US equities"
    assert ents[APPLE]["BICS"] == "APLEUS66XXX" and ents[APPLE]["LEI_COUNTRY"] == "US"
    kid = next(k for k, d in ents.items() if d.get("DIRECT_PARENT") == APPLE)
    assert ents[kid]["LEI_GROUP"] == APPLE and ents[kid]["PARENTS"][0]["LEI"] == APPLE
    groups = {d["GROUP_ID"]: d for d in snap(pack, "lei-group")}
    assert groups[APPLE]["TREE"][0]["LEI"] == APPLE and groups[APPLE]["TREE"][0]["CHILDREN"]
    assert "ALL" in groups and groups["ALL"]["GROUPS"] == 2
    rels = {d["RELATIONSHIP_ID"]: d for d in snap(pack, "lei-relationship")}
    assert rels[f"LEIREL-{kid}-DIRECT"]["PARENT_ENTITY"] == APPLE
    maps = {d["IDENTIFIER"]: d for d in snap(pack, "lei-mapping")}
    assert maps["APLEUS66XXX"]["ENTITY_LEI"] == APPLE and maps["APLEUS66XXX"]["TYPE"] == "BIC"
    countries = {d["COUNTRY"] for d in snap(pack, "lei-country")}
    assert {"US", "ALL"} <= countries
    assert (pack / "pack.yaml").is_file() and (pack / "guides" / "gleif.md").is_file()
    first = built(pack)
    assert GL.refresh(ns(cache, offline=True, out=str(out)), log=lambda *_: None) == 0
    assert first == built(pack)           # the same cache, the same bytes
    man = json.loads((data_dir / "publicdata" / "gleif" / "manifest.json").read_text())
    assert set(man["sources"]) == {"golden", "api", "mapping"} and man["runs"][-1]["ok"]
    assert (out / "manifest.json").is_file()


def test_a_group_cap_keeps_the_nearest_levels_and_says_so(cache):
    g = GL.F.Graph(json.loads((cache / "reduced" / "rr.json").read_text())) if (cache / "reduced" / "rr.json").is_file() else None
    if g is None:
        GL.F.reduce_rr(cache)
        g = GL.F.Graph(json.loads((cache / "reduced" / "rr.json").read_text()))
    members, total = g.members(APPLE, 3)
    assert members[0] == APPLE and len(members) == 3 and total > 3
    assert all(g.direct.get(m) == APPLE for m in members[1:])                               # the first level first


def test_scope_name_widens_into_the_data_folder_through_the_api(cache, data_dir):
    calls, logs = [], []
    moodys_kids = [d for d in json.loads((FX / "api" / "records" / "fixture.json").read_text())["data"] if d["id"] != MOODYS][:2]

    def opener(url, accept):
        calls.append(url)
        if "filter[fulltext]" in url:
            return json.dumps({"data": moodys_kids, "meta": {"pagination": {"lastPage": 1}}}).encode()
        raise AssertionError("unexpected " + url)

    assert GL.refresh(ns(cache, offline=False, scope="NAME:moody,CAP:5", sources=["api"]), log=logs.append, opener=opener) == 0
    pack = data_dir / "publicdata" / "gleif" / "packs" / "gleif"
    ents = {d["LEI"] for d in snap(pack, "lei-entity")}
    assert {d["id"] for d in moodys_kids} <= ents
    assert any("filter[fulltext]=moody" in u for u in calls)
    groups = {d["GROUP_ID"]: d for d in snap(pack, "lei-group")}
    assert "at most 5 a group" in groups[MOODYS]["CAP_NOTE"]                                 # CAP:5, the found entities kept beyond it
    assert GL.F.GROUP_CAP == 50                                                                 # put back


def test_refusals(cache, tmp_path):
    assert GL.main(["refresh", "--cache", str(cache), "--out", str(B.ROOT / "config" / "packs" / "core")]) == 3
    assert GL.main(["refresh", "--cache", str(cache), "--scope", "LEI:short"]) == 3
    assert GL.main(["refresh", "--cache", str(cache), "--scope", "WHAT:x"]) == 3
    assert GL.main(["refresh", "golden-lei", "--cache", str(cache), "--into-pack"]) == 3
    assert GL.main(["refresh", "--cache", str(cache), "--out", str(B.ROOT / "config" / "packs" / "gleif"), "--scope", f"GROUP:{APPLE}"]) == 3


def test_dry_run_changes_nothing(cache, capsys):
    before = sorted(p.relative_to(cache).as_posix() for p in cache.rglob("*"))
    assert GL.main(["refresh", "--dry-run", "--cache", str(cache), "--scope", f"GROUP:{APPLE},COUNTRY:LU"]) == 0
    out = capsys.readouterr().out
    assert "dry run: gleif" in out and "widened" in out and "COUNTRY" not in out.split("--scope")[0]
    assert sorted(p.relative_to(cache).as_posix() for p in cache.rglob("*")) == before


def test_the_bulk_file_row_becomes_an_api_record():
    row = {"LEI": APPLE, "Entity.LegalName": "Apple Inc.", "Entity.LegalAddress.FirstAddressLine": "330 N. Brand Blvd",
           "Entity.LegalAddress.City": "Glendale", "Entity.LegalAddress.Country": "US", "Entity.HeadquartersAddress.City": "Cupertino",
           "Entity.HeadquartersAddress.Country": "US", "Entity.LegalForm.EntityLegalFormCode": "H1UM", "Entity.EntityStatus": "ACTIVE",
           "Registration.RegistrationStatus": "ISSUED", "Registration.NextRenewalDate": "2027-03-08T17:27:20Z",
           "Entity.OtherEntityNames.OtherEntityName.1": "Apple Computer, Inc.", "Entity.OtherEntityNames.OtherEntityName.1.type": "PREVIOUS_LEGAL_NAME"}
    a = GL.lei2_attributes(row)
    assert a["entity"]["legalName"]["name"] == "Apple Inc." and a["entity"]["legalAddress"]["addressLines"] == ["330 N. Brand Blvd"]
    assert a["entity"]["legalForm"]["id"] == "H1UM" and a["registration"]["status"] == "ISSUED"
    assert a["entity"]["otherNames"][0]["type"] == "PREVIOUS_LEGAL_NAME"


def test_status_runs_offline(cache, capsys):
    assert GL.main(["status", "--cache", str(cache)]) == 0
    out = capsys.readouterr().out
    assert "committed pack:" in out and "golden" in out and "CC0" in out


@pytest.mark.skipif(os.environ.get("DRISHTI_LIVE_FETCH") != "1", reason="live: set DRISHTI_LIVE_FETCH=1")
def test_live_one_lei_record():
    import urllib.request
    req = urllib.request.Request(f"{GL.F.API}/lei-records/{APPLE}", headers={"User-Agent": GL.F.USER_AGENT, "Accept": "application/vnd.api+json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.loads(r.read())
    assert d["data"]["attributes"]["entity"]["legalName"]["name"] == "Apple Inc."
