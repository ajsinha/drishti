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

"""The standard data pattern for every public pack (owner rule, 2026-10-24 and 2026-11-27): the data is in data/testdata, built into the
pack's lake by `drishti.py lake demo` and read by the pack's Delta connector, and documented; never kept in the pack.

* no manifest entry of a public pack points at a pack's samples/ (only the declared fixtures below do);
* a public pack's samples/ keep reference documents only (a few a kind), never the data;
* every kind of a public pack has its committed snapshot, listed in testdata.yaml with the lake it is written to, and its connector is a
  Delta connector that reads that lake (the six public-sector packs: their own lake, worldlake or newslake, as their feeds fill it);
* the one table of data/testdata/README.md names every snapshot file, and each pack's README says where its data comes from.
"""
import sys
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
sys.path.insert(0, str(ROOT / "tools"))
import publicsnap as SNAP  # noqa: E402
from resolved import pack_dir  # noqa: E402

#: pack folders whose samples/ are a declared test fixture (served by the demo from there): the compute demo's synthetic path strips
DECLARED_FIXTURES = {"compute-demo"}
#: a reference document set: at most this many lines in any samples/*.jsonl of a public pack (the README and docs show them)
REFERENCE_LINES = 40
PUBLIC_SECTOR = {"central-banks": "worldlake", "national-statistics": "worldlake", "oecd": "worldlake", "world-development": "worldlake",
                 "official-news": "newslake", "gdelt-events": "newslake"}
M = yaml.safe_load((ROOT / "data" / "testdata" / "testdata.yaml").read_text(encoding="utf-8"))


class PublicDataPattern(unittest.TestCase):
    def test_only_declared_fixtures_are_served_from_a_packs_samples(self):
        served = {ds["pack"] for ds in M["datasets"] if "samples" in ds}
        self.assertLessEqual(served, DECLARED_FIXTURES, "a pack's data belongs in data/testdata (publicsnap.py PACKS), not in its samples/")

    def test_public_packs_samples_hold_reference_documents_only(self):
        for pack in SNAP.PACKS:
            samples = pack_dir(pack) / "samples"
            for f in samples.glob("*.jsonl"):
                lines = sum(1 for x in f.read_text(encoding="utf-8").splitlines() if x.strip())
                self.assertLessEqual(lines, REFERENCE_LINES, f"{f.relative_to(ROOT)}: {lines} lines: the data is data/testdata/{pack}")

    def test_every_kind_of_a_public_pack_has_its_snapshot_in_the_manifest(self):
        have = {(ds["pack"], ds["kind"]): ds for ds in M["datasets"] if ds.get("snapshot")}
        for pack, spec in SNAP.PACKS.items():
            for kind in spec["keys"]:
                ds = have.get((pack, kind))
                self.assertIsNotNone(ds, f"{pack}/{kind}: no snapshot in testdata.yaml")
                self.assertTrue(ds["files"] and ds["documents"] > 0, ds)
                for name in ds["files"]:
                    self.assertTrue((ROOT / "data" / "testdata" / ds["folder"] / name).is_file(), ds["folder"] + "/" + name)

    def test_the_public_sector_packs_connectors_read_the_lake_their_data_is_loaded_into(self):
        for pack, lake in PUBLIC_SECTOR.items():
            spec = yaml.safe_load((pack_dir(pack) / "pack.yaml").read_text(encoding="utf-8"))
            for kind in SNAP.PACKS[pack]["keys"]:
                conn = spec["connectors"][spec["routes"][kind]]
                self.assertEqual(conn["plugin"], "delta", f"{pack}/{kind} is read by the {conn['plugin']} connector")
                self.assertEqual(conn["settings"]["lake"], lake, pack)
                self.assertEqual(conn["settings"].get(f"mode.{kind}"), "effective", f"{pack}/{kind}: a snapshot is loaded once")
                ds = next(d for d in M["datasets"] if d["pack"] == pack and d["kind"] == kind)
                self.assertEqual((ds["lake"], ds["dates"], ds["connector"]), (lake, "last", spec["routes"][kind]), ds)

    def test_the_other_public_packs_connectors_are_delta_connectors_of_the_demo_lake(self):
        for pack in ("world-economy", "news-and-markets"):
            spec = yaml.safe_load((pack_dir(pack) / "pack.yaml").read_text(encoding="utf-8"))
            for kind in SNAP.PACKS[pack]["keys"]:
                ds = next(d for d in M["datasets"] if d["pack"] == pack and d["kind"] == kind)
                conn = spec["connectors"][ds["connector"]]
                self.assertEqual((conn["plugin"], conn["settings"]["lake"]), ("delta", "demo"), f"{pack}/{kind}")
                self.assertIn(kind, conn["kinds"])

    def test_the_testdata_readme_table_lists_every_snapshot_with_its_refresh_command(self):
        text = (ROOT / "data" / "testdata" / "README.md").read_text(encoding="utf-8")
        table = text.split(SNAP.TABLE_BEGIN)[1].split(SNAP.TABLE_END)[0]
        self.assertEqual(table.strip(), SNAP.readme_table(ROOT / "data" / "testdata").strip(), "run a --snapshot, or publicsnap.update_readme")
        for ds in M["datasets"]:
            if ds.get("snapshot"):
                self.assertIn(f"`{ds['folder']}/{ds['files'][0]}`", table)
        for pack in PUBLIC_SECTOR:
            self.assertIn(f"feed run {pack} --snapshot", table)

    def test_each_public_sector_packs_readme_says_where_its_data_comes_from(self):
        for pack in PUBLIC_SECTOR:
            readme = (pack_dir(pack) / "README.md").read_text(encoding="utf-8")
            self.assertIn(f"data/testdata/{pack}/", readme, pack)
            self.assertIn(f"feed run {pack} --snapshot", readme, pack)
            self.assertIn("lake demo", readme, pack)

    def test_the_lake_a_dataset_names_is_a_lake_file(self):
        for ds in M["datasets"]:
            if ds.get("lake"):
                self.assertTrue((ROOT / "config" / "lakes" / f"{ds['lake']}.yaml").is_file(), ds)

    def test_lake_demo_writes_a_dataset_into_its_own_lake(self):
        sys.path.insert(0, str(ROOT / "tools" / "lake"))
        import demo
        d = demo.DemoLake.__new__(demo.DemoLake)
        d.lake_name, d.lake = "demo", (Path("/scratch/delta/demo"))
        d.resolve = lambda root: Path("/elsewhere")             # the demo lake is not the default one: the other lake goes beside it
        self.assertEqual(d.lake_of({"lake": "worldlake"}), ("worldlake", Path("/scratch/delta/worldlake")))
        self.assertEqual(d.lake_of({}), ("demo", Path("/scratch/delta/demo")))


if __name__ == "__main__":
    unittest.main()
