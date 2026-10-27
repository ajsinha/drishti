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

"""The bank's own names (config/demo/names.yaml, 2.9.4): `lake demo --names FILE` renames them as it loads the test data. A configured
name replaces the old one in the legal-entity kind and in the risk hierarchy (another kind, loaded on its own dates), a branch keeps its
suffix, LEIs stay, and the defaults change nothing. The committed test data is never rewritten. Needs deltalake, pyarrow and PyYAML
(uv run --with deltalake --with pyarrow --with pyyaml python -m unittest tools/lake/test_names.py).
"""
from __future__ import annotations

import contextlib
import gzip
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools"))

try:
    import deltalake  # noqa: F401  (the lake tests need it)
    import yaml  # noqa: F401
    HAVE = True
except ImportError:
    HAVE = False

import demonames  # noqa: E402

NAMES = """bank: Example Bank
legal-entities:
  LE-LDN: Example Bank plc
  LE-NY: Example Securities Inc.
"""
ONLY = "core/legal-entity,market-risk/risk-hierarchy"


class Mapping(unittest.TestCase):
    def test_exact_values_and_branches_are_renamed_and_nothing_else(self):
        n = demonames.DemoNames(yaml_free(NAMES), "test")
        doc = {"NAME": "Drishti Bank plc", "BRANCH": "Drishti Bank plc, Singapore Branch", "TOP": "Drishti Bank",
               "NOTE": "kept by Drishti Bank plc", "LEI": "213800ABCDEF", "LIST": ["Drishti Bank Securities Inc.", "Drishti Bank Europe AG"]}
        self.assertEqual(n.rewrite(doc), {"NAME": "Example Bank plc", "BRANCH": "Example Bank plc, Singapore Branch", "TOP": "Example Bank",
                                          "NOTE": "kept by Drishti Bank plc", "LEI": "213800ABCDEF",
                                          "LIST": ["Example Securities Inc.", "Drishti Bank Europe AG"]})
        self.assertFalse(demonames.DemoNames.load(ROOT / "config" / "demo" / "names.yaml"))   # the committed file: the defaults
        self.assertEqual(demonames._simple_yaml(NAMES)["legal-entities"]["LE-NY"], "Example Securities Inc.")


def yaml_free(text: str) -> dict:
    return demonames._simple_yaml(text)


@unittest.skipUnless(HAVE, "needs deltalake, pyarrow and PyYAML")
class LakeDemo(unittest.TestCase):
    def build(self, tmp: Path, names: Path | None) -> Path:
        import demo
        td, lake = tmp / "testdata", tmp / ("lake-" + (names.stem if names else "default"))
        if not td.exists():
            shutil.copytree(ROOT / "data" / "testdata" / "core" / "legal-entity", td / "core" / "legal-entity")
            shutil.copytree(ROOT / "data" / "testdata" / "market-risk" / "risk-hierarchy", td / "market-risk" / "risk-hierarchy")
            shutil.copy(ROOT / "data" / "testdata" / "testdata.yaml", td / "testdata.yaml")
            snap = td / "core" / "legal-entity" / "snapshot-2026-07-08.jsonl.gz"     # a branch, as an extract may name one
            with gzip.open(snap, "rt", encoding="utf-8") as f:
                lines = [x for x in f.read().splitlines() if x.strip()]
            branch = dict(json.loads(lines[0]), ENTITY_ID="LE-SGP", NAME="Drishti Bank plc, Singapore Branch")
            with gzip.open(snap, "wt", encoding="utf-8") as f:
                f.write("\n".join(lines + [json.dumps(branch)]) + "\n")
        argv = ["--testdata", str(td), "--lake", str(lake), "--data-dir", str(tmp / "data"), "--connectors-dir", str(tmp / "connectors"),
                "--only", ONLY, "--as-of", "data", "-q", "--no-signal"]
        if names:
            argv += ["--names", str(names)]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(demo.run(demo.build_parser().parse_args(argv)), 0)
        return lake

    @staticmethod
    def docs(table: Path) -> list[dict]:
        from deltalake import DeltaTable
        return [json.loads(r["doc"]) for r in DeltaTable(str(table)).to_pyarrow_table(columns=["doc"]).to_pylist()]

    def test_the_configured_names_replace_the_old_ones_as_the_lake_is_loaded(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            names = tmp / "names.yaml"
            names.write_text(NAMES, encoding="utf-8")
            lake = self.build(tmp, names)
            les = {d["ENTITY_ID"]: d for d in self.docs(lake / "reference" / "legal-entity")} if (lake / "reference" / "legal-entity").exists() \
                else {d["ENTITY_ID"]: d for t in lake.rglob("legal-entity") for d in self.docs(t)}
            self.assertEqual(les["LE-LDN"]["NAME"], "Example Bank plc")
            self.assertEqual(les["LE-NY"]["NAME"], "Example Securities Inc.")
            self.assertEqual(les["LE-FRA"]["NAME"], "Drishti Bank Europe AG")              # not configured: kept
            self.assertEqual(les["LE-SGP"]["NAME"], "Example Bank plc, Singapore Branch")   # the branch keeps its suffix
            before = {json.loads(x)["ENTITY_ID"]: json.loads(x) for x in gzip.open(ROOT / "data/testdata/core/legal-entity/snapshot-2026-07-08.jsonl.gz",
                                                                                    "rt", encoding="utf-8") if x.strip()}
            self.assertEqual(les["LE-LDN"].get("LEI"), before["LE-LDN"].get("LEI"))       # identifiers stay
            hier = [d for t in lake.rglob("risk-hierarchy") if (t / "_delta_log").is_dir() for d in self.docs(t)]
            self.assertIn("Example Bank", {d.get("NAME") for d in hier})                    # another kind: the bank at the top
            self.assertNotIn("Drishti Bank", {d.get("NAME") for d in hier})
            # the committed data is never rewritten
            self.assertEqual(before["LE-LDN"]["NAME"], "Drishti Bank plc")

    def test_the_defaults_change_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            lake = self.build(tmp, None)
            les = {d["ENTITY_ID"]: d["NAME"] for t in lake.rglob("legal-entity") if (t / "_delta_log").is_dir() for d in self.docs(t)}
            self.assertEqual(les["LE-LDN"], "Drishti Bank plc")
            self.assertEqual(les["LE-SGP"], "Drishti Bank plc, Singapore Branch")

    def test_dry_run_prints_the_mapping_and_builds_nothing(self):
        import demo
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            names = tmp / "names.yaml"
            names.write_text(NAMES, encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(demo.run(demo.build_parser().parse_args(["--lake", str(tmp / "lake"), "--data-dir", str(tmp / "data"), "--names",
                                                                         str(names), "--dry-run"])), 0)
            self.assertIn("Drishti Bank plc -> Example Bank plc", out.getvalue())
            self.assertFalse((tmp / "lake").exists())


if __name__ == "__main__":
    os.environ.setdefault("DRISHTI_DATA_DIR", tempfile.gettempdir())
    unittest.main()
