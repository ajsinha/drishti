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

"""The guard of the data standard (2.9.4, docs/guides/DATA_FORMATS.md): every generator under tools/ writes JSON Lines and nothing
else as data. Generating and loading are separate steps, so a generator never writes Delta, Parquet, CSV or a JSON array of records.

Two checks:
* run the cheap generators into a temporary folder and look at every file they write (comswap_synth, note_synth, make_trades,
  bulk_trades --no-lake, make_data --lake --no-load and its feed, samplegen/lake.py --no-load, a retired pack's make.py with
  DRISHTI_PACKS_OUT, the samples and Sutra-case writers): every data file is .jsonl or .jsonl.gz and every line one JSON object;
* read the source of every generator (the GENERATORS globs) for what a data write would look like: a Delta or Parquet writer, a CSV
  writer, or a file written under a .csv, .tsv, .parquet, .ndjson or .json name that is not on the allow-list below.

The allow-list is what is not data and stays as it is: manifests, application state, download caches, test oracles, map GeoJSON,
run reports, the Iceberg conformance fixtures, the loaders (they write the stores, which is their job) and human-facing exports.

    python3 -m unittest -q tools/test_generators_write_jsonl.py
"""
from __future__ import annotations

import gzip
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PY = sys.executable

#: the generators whose source is read (data generators; tests and fixtures under them are skipped)
GENERATORS = ["tools/packgen/**/*.py", "tools/samplegen/*.py", "tools/testdata/*.py", "tools/realdata/*.py", "tools/publicdata/*.py",
              "tools/sutragen.py", "tools/schemapack.py", "tools/bench/trades.py", "tools/convertcmd.py", "config/packs/*/tools/*.py",
              "examples/packs/*/tools/*.py"]

#: files that write a store on purpose: the loaders (generating and loading are separate steps; these are the loading step)
LOADERS = {"tools/samplegen/layout.py", "tools/samplegen/load_rows.py"}

#: written file names that are not data: manifests, state, download caches, test oracles and run summaries (they stay as they are)
ALLOWED_NAMES = {"MANIFEST.json", "manifest.json", "meta.json", "cache-index.json", "testdata.yaml", "trackers.json", "factbook_ref.json",
                 "fetch.json", "rr.json", "wikidata.json", "reference.json", "last-run.json", "help-coverage.txt", "expect.yaml"}

#: non-data files a pack generator writes beside its samples (the pack itself, its docs)
NON_DATA_SUFFIXES = (".yaml", ".yml", ".md", ".txt", ".sutra.yaml", ".svg", ".png", ".jpg", ".html", ".css", ".js", ".py")

#: exempt from the rule altogether, with the reason (docs/guides/DATA_FORMATS.md section 5)
EXEMPT = {"tools/iceberg/fixtures.py": "writes real Iceberg tables: it tests the Iceberg reader itself",
          "tools/maps/build_regions.py": "map GeoJSON for the console's maps (not data records)",
          "tools/packi18n.py": "a translation sheet for people (CSV), not data",
          "tools/historycmd.py": "human-facing export (--csv beside --jsonl)",
          "tools/biruns.py": "human-facing export (csv beside jsonl)"}

FORBIDDEN_CALL = re.compile(r"write_deltalake\(|write_days\(|write_partition\(|write_file\(|\.to_parquet\(|pq\.write_table\(|csv\.writer\(|"
                            r"csv\.DictWriter\(")
WRITE_CALL = re.compile(r"write_text\(|write_bytes\(|open\([^)]*['\"](?:w|wt|wb|a|at|x)['\"]|json\.dump\(")
DATA_NAME = re.compile(r"['\"]([^'\"]*\.(?:csv|tsv|json|parquet|ndjson))['\"]")
FILE_MAP = re.compile(r"\[[^\]]*['\"][^'\"]*\.(?:json|csv|parquet|tsv)['\"]\]\s*=[^=]")


def data_files(folder: Path) -> list[Path]:
    return sorted(p for p in folder.rglob("*") if p.is_file() and not p.name.endswith(NON_DATA_SUFFIXES) and p.name not in ALLOWED_NAMES)


class SourceRule(unittest.TestCase):
    def test_no_generator_writes_a_data_file_other_than_jsonl(self):
        files = sorted({f for g in GENERATORS for f in ROOT.glob(g)})
        self.assertGreater(len(files), 50)
        bad = []
        for f in files:
            rel = f.relative_to(ROOT).as_posix()
            if f.name.startswith("test_") or "/tests/" in rel or rel in LOADERS or rel in EXEMPT:
                continue
            for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                code = line.split("#", 1)[0]
                if FORBIDDEN_CALL.search(code) or FILE_MAP.search(code):
                    bad.append(f"{rel}:{n}: {line.strip()[:120]}")
                elif WRITE_CALL.search(code):
                    names = [Path(m.group(1)).name for m in DATA_NAME.finditer(code)]
                    if any(n_ not in ALLOWED_NAMES for n_ in names):
                        bad.append(f"{rel}:{n}: {line.strip()[:120]}")
        self.assertEqual(bad, [], "a generator writes data that is not JSON Lines (DATA_FORMATS.md); write .jsonl and load it as a "
                                  "separate step, or add a non-data file to ALLOWED_NAMES with a reason")


class RunTheGenerators(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_tool(self, *args, env=None, timeout=600):
        r = subprocess.run([PY, *map(str, args)], cwd=ROOT, capture_output=True, text=True, timeout=timeout, env={**os.environ, **(env or {})})
        self.assertEqual(r.returncode, 0, r.stdout[-2000:] + r.stderr[-2000:])
        return r

    def assert_jsonl(self, folder: Path, at_least: int = 1):
        files = data_files(folder)
        self.assertGreaterEqual(len(files), at_least, f"nothing written under {folder}")
        for f in files:
            self.assertTrue(f.name.endswith((".jsonl", ".jsonl.gz")), f"{f.relative_to(folder)} is not JSON Lines")
            opener = gzip.open if f.name.endswith(".gz") else open
            with opener(f, "rt", encoding="utf-8") as fh:
                for n, line in enumerate(fh, 1):
                    if line.strip():
                        self.assertIsInstance(json.loads(line), dict, f"{f.name}:{n} is not one JSON object")

    def test_the_synthetic_exports(self):
        out = self.d / "synth"
        out.mkdir()
        self.run_tool("tools/packgen/comswap_synth.py", "--deals", "3", "--out", out / "deals.jsonl")
        self.run_tool("tools/packgen/note_synth.py", "--notes", "2", "--out", out / "notes.jsonl")
        self.assert_jsonl(out, 2)

    def test_the_trade_books(self):
        self.run_tool("tools/packgen/trades/make_trades.py", "--trades", "3", "--days", "1", "--systems", "ENDUR", "--out", self.d / "trades")
        self.run_tool("tools/samplegen/bulk_trades.py", "--trades", "20", "--days", "1", "--workers", "1", "--no-lake", "--out", self.d / "bulk",
                      "--root", self.d / "lake")
        self.assert_jsonl(self.d / "trades")
        self.assert_jsonl(self.d / "bulk")
        self.assertFalse((self.d / "lake").exists(), "bulk_trades --no-lake loaded the lake")

    def test_the_banking_history_and_feed_are_rows_then_loaded(self):
        self.run_tool("tools/packgen/banking/make_data.py", "--lake", self.d / "delta", "--no-load", "--days", "1", "--rows", self.d / "rows",
                      "--data-dir", self.d)
        self.assert_jsonl(self.d / "rows", 2)
        self.assert_jsonl(self.d / "feeds")
        self.assertFalse((self.d / "delta").exists(), "the generator wrote the lake itself")
        self.run_tool("tools/samplegen/lake.py", "--samples", "examples/packs/finance/samples", "--root", self.d / "delta2", "--domain", "finance",
                      "--days", "1", "--as-of", "2026-09-30", "--no-load", "--rows", self.d / "rows2")
        self.assert_jsonl(self.d / "rows2", 2)

    def test_a_pack_generator_writes_jsonl_samples_and_sutra_cases(self):
        self.run_tool("tools/packgen/genomics/make.py", env={"DRISHTI_PACKS_OUT": str(self.d / "packs")})
        pack = self.d / "packs" / "genomics"
        self.assert_jsonl(pack / "samples", 3)
        self.assertTrue((pack / "samples" / "catalog.jsonl").is_file())
        if (pack / "tests").is_dir():
            self.assert_jsonl(pack / "tests")

    def test_the_writers_of_samples_and_cases(self):
        sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
        import packsamples
        import sutracases
        names = packsamples.files({"trade": {"T-1": {"TRADE_ID": "T-1"}}}, [{"kind": "trade", "id": "T-1", "title": "T-1", "subtitle": ""}])
        self.assertEqual(sorted(names), ["catalog.jsonl", "trade.jsonl"])
        self.assertEqual(json.loads(sutracases.text([("T-1", {"TRADE_ID": "T-1"})]))["case"], "T-1")


if __name__ == "__main__":
    unittest.main()
