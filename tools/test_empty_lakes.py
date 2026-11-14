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

"""The named lakes besides the demo lake (risklake, newslake, worldlake, regulators, capitalmarket, referencelake) ship as definitions:
nothing shipped points at them (the product owner, 2026-11-07: "do not point anything to any of these lakes except the demo lake"), with two
exceptions by design: the data feeds (config/feeds/*.yaml) name the lake they load (the owner placed them, GLEIF in referencelake), and the
public-sector module's packs read worldlake and newslake, which their daily feeds fill (docs/guides/WORLD_DATA_GUIDE.md). So those two
lake files hold exactly those packs' tables, only those packs' connectors (and the feeds) name them, and the other lakes ship empty."""
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dir  # noqa: E402  (the one rule for where a pack's folder is)
NAMED = ("risklake", "newslake", "worldlake", "regulators", "capitalmarket", "referencelake")
EMPTY = ("risklake", "regulators", "capitalmarket", "referencelake")
WORD = re.compile(r"\b(" + "|".join(NAMED) + r")\b")
# the public-sector packs and the lake each one's connector reads (config/packs/<pack>/pack.yaml)
PUBLIC_SECTOR = {"world-development": "worldlake", "central-banks": "worldlake", "national-statistics": "worldlake", "oecd": "worldlake",
                 "official-news": "newslake", "gdelt-events": "newslake"}


def _connectors_lake_tables(lake: str) -> set[str]:
    out = set()
    for pack, where in PUBLIC_SECTOR.items():
        m = yaml.safe_load((pack_dir(pack) / "pack.yaml").read_text(encoding="utf-8"))
        for c in (m.get("connectors") or {}).values():
            s = c.get("settings") or {}
            if s.get("lake") == lake:
                out |= {t.strip() for t in str(s.get("lake-tables", "")).split(",") if t.strip()}
    return out


def test_the_empty_lakes_ship_as_definitions_with_no_tables():
    for name in NAMED:
        f = ROOT / "config" / "lakes" / f"{name}.yaml"
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        assert doc["lake"] == name, f
        assert (ROOT / "data" / "delta" / name / "README.md").is_file(), name
        if name in EMPTY:
            assert not doc.get("tables"), f


def test_worldlake_and_newslake_hold_exactly_the_public_sector_tables():
    for lake in ("worldlake", "newslake"):
        doc = yaml.safe_load((ROOT / "config" / "lakes" / f"{lake}.yaml").read_text(encoding="utf-8"))
        assert set(doc.get("tables") or {}) == _connectors_lake_tables(lake), lake
        for table, entry in doc["tables"].items():
            assert entry.get("id"), f"{lake}: {table} names no id"


def test_each_public_sector_pack_reads_its_lake_and_its_feed_loads_it():
    for pack, lake in PUBLIC_SECTOR.items():
        m = yaml.safe_load((pack_dir(pack) / "pack.yaml").read_text(encoding="utf-8"))
        assert m["domain"] == "economy", pack
        lakes = {(c.get("settings") or {}).get("lake") for c in (m.get("connectors") or {}).values()}
        assert lakes == {lake}, pack
        feed = yaml.safe_load((ROOT / "config" / "feeds" / f"{pack}.yaml").read_text(encoding="utf-8"))
        assert feed["lake"] == lake and feed["options"]["module"] == "economy", pack
        assert {d["pack"] for d in feed["datasets"].values()} == {pack}, pack
        assert {d["kind"] for d in feed["datasets"].values()} == set(m["kinds"]), pack


def test_nothing_else_shipped_points_at_them():
    places = [*ROOT.glob("config/connectors/*.yaml"), *ROOT.glob("config/engines/*.yaml"), ROOT / "config" / "dataload.yaml",
              *ROOT.glob("config/packs/**/*.yaml"), *ROOT.glob("kernel/drishti-server/src/main/resources/**/*.yaml"),
              *ROOT.glob("deploy/stack/*.yaml")]
    allowed = {pack_dir(p) for p in PUBLIC_SECTOR}         # the public-sector packs' own files
    hits = []
    for f in places:
        if not f.is_file() or any(f.is_relative_to(a) for a in allowed):
            continue
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            code = line.split("#", 1)[0]
            if WORD.search(code) and re.search(r"lake\s*[:=]|DATALOAD_LAKE", code):
                hits.append(f"{f.relative_to(ROOT)}:{n}: {line.strip()}")
    assert not hits, "a shipped file points at a named lake that only the public-sector packs may read:\n" + "\n".join(hits)
