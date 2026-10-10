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

"""Tests for `drishti.py sutra design --schema` and `drishti.py pack make --schema` (tools/schemapack.py) in temporary folders.

    python3 -m unittest -q tools/test_schemapack.py

The commands that draft Sutras start the Java auto-designer: those tests are skipped when there is no server jar
(DRISHTI_JAR, or drishti-server/target/*-exec.jar). The argument and error tests need no jar.
"""
import contextlib
import glob
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIX = ROOT / "docs" / "guides" / "examples" / "schemas"
HAVE_JAR = bool(os.environ.get("DRISHTI_JAR") or glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")))


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = D.main([str(a) for a in argv])
    return code, out.getvalue(), err.getvalue()


def write_jsonl(folder: pathlib.Path):
    folder.mkdir(parents=True, exist_ok=True)
    with open(folder / "trades.jsonl", "w", encoding="utf-8") as f:
        for i in range(1, 19):
            f.write(json.dumps({"tradeId": f"TRD-{i:04d}", "counterpartyId": f"CPT-{i % 3 + 1:04d}", "bookId": "BK-0001", "status": ["LIVE", "PENDING"][i % 2],
                                "productType": ["IRS", "FXS", "FUT"][i % 3], "notional": 1000 * i, "currency": "USD", "tradeDate": f"2026-03-0{1 + i % 2}",
                                "mtm": 10.5 * i, "extraField": i}) + "\n")
    with open(folder / "counterparties.jsonl", "w", encoding="utf-8") as f:
        for i in range(1, 4):
            f.write(json.dumps({"id": f"CPT-{i:04d}", "name": f"C{i}", "rating": "AA"}) + "\n")


class Arguments(unittest.TestCase):
    def test_kind_with_several_schemas_is_refused(self):
        code, _, err = run("sutra", "design", "--schema", FIX / "trade.schema.json", FIX / "book.schema.json", "--kind", "x", "--yes")
        self.assertEqual(code, 2)
        self.assertIn("ONE schema", err)

    def test_missing_schema_file(self):
        code, _, err = run("sutra", "design", "--schema", FIX / "nope.json")
        self.assertEqual(code, 2)
        self.assertIn("does not exist", err)

    def test_design_without_paths_or_schema(self):
        code, _, err = run("sutra", "design")
        self.assertEqual(code, 2)
        self.assertIn("--schema", err)

    def test_pack_make_needs_inputs_or_schema(self):
        code, _, err = run("pack", "make", "--name", "x")
        self.assertEqual(code, 2)
        self.assertIn("--schema", err)

    def test_data_folder_after_schema_is_data(self):
        import schemapack as SP
        with tempfile.TemporaryDirectory() as t:
            write_jsonl(pathlib.Path(t))
            s, d = SP.split_arguments([FIX, t, FIX / "trade.schema.json"])
            self.assertEqual([p.name for p in s], ["schemas", "trade.schema.json"])
            self.assertEqual([str(p) for p in d], [t])

    def test_ambiguous_key_is_reported_and_no_key_stops_make(self):
        with tempfile.TemporaryDirectory() as t:
            p = pathlib.Path(t) / "thing.schema.json"
            p.write_text(json.dumps({"title": "Thing", "type": "object", "properties": {"label": {"type": "string"}}}))
            code, _, err = run("pack", "make", "--schema", p, "--name", "thing-pack", "--out", pathlib.Path(t) / "o", "--yes", "--jar", __file__)
            self.assertEqual(code, 2)
            self.assertIn("no key could be chosen for thing", err)


@unittest.skipUnless(HAVE_JAR, "needs the server jar")
class WithJar(unittest.TestCase):
    def test_design_prints_a_labelled_sutra(self):
        code, out, err = run("sutra", "design", "--schema", FIX / "trade.schema.json", "--match", "none", "--yes")
        self.assertEqual(code, 0, err)
        self.assertIn("sutra: trade-default", out)
        self.assertIn('label: "Notional (USD)"', out)
        self.assertIn('label: "Executed at"', out)

    def test_design_splits_by_discriminator_and_writes_tests(self):
        with tempfile.TemporaryDirectory() as t:
            code, out, err = run("sutra", "design", "--schema", FIX / "instrument.schema.json", "--out", t, "--yes")
            self.assertEqual(code, 0, err + out)
            names = sorted(p.name for p in (pathlib.Path(t) / "instrument").iterdir())
            self.assertEqual(names, ["instrument-bond.v1.sutra.yaml", "instrument-default.v1.sutra.yaml", "instrument-equity.v1.sutra.yaml"])
            self.assertTrue((pathlib.Path(t) / "tests" / "instrument-bond" / "sample-synthetic-1.json").is_file())
            self.assertIn("match: { kind: instrument, where: \"$.type == 'BOND'\"", (pathlib.Path(t) / "instrument" / "instrument-bond.v1.sutra.yaml").read_text())

    def test_pack_make_from_schemas_alone(self):
        with tempfile.TemporaryDirectory() as t:
            out = pathlib.Path(t) / "o"
            code, so, err = run("pack", "make", "--schema", FIX, "--name", "sg-test", "--out", out, "--yes")
            self.assertEqual(code, 0, so + err)
            for f in ("README.txt", "MANIFEST.json", "run-server.sh", "config/drishti-site.yaml", "bundle/sg-test-1.0.0.tar.gz", "bundle/sg-test-1.0.0.tar.gz.sha256",
                      "pack/sg-test/pack.yaml", "pack/sg-test/config/about.yaml", "pack/sg-test/sutras/instrument/instrument-bond.v1.sutra.yaml"):
                self.assertTrue((out / f).exists(), f)
            man = json.loads((out / "MANIFEST.json").read_text())
            self.assertEqual(man["checks"], {"packCheck": True, "packVerify": True})
            self.assertEqual(len(man["syntheticSutras"]), 9)
            self.assertEqual(man["kinds"]["trade"]["key"], "tradeId")
            self.assertNotIn("data", [p.name for p in out.iterdir()])
            self.assertIn("verified", so)
            self.assertIn("SYNTHETIC SAMPLES", (out / "README.txt").read_text())
            code2, so2, _ = run("pack", "verify", out / "bundle" / "sg-test-1.0.0.tar.gz")
            self.assertEqual(code2, 0)
            self.assertTrue(so2.rstrip().endswith("verified"))

    def test_pack_make_with_a_jsonl_folder_real_samples_win(self):
        with tempfile.TemporaryDirectory() as t:
            data, out = pathlib.Path(t) / "data", pathlib.Path(t) / "o"
            write_jsonl(data)
            code, so, err = run("pack", "make", "--schema", FIX / "trade.schema.json", FIX / "counterparty.schema.json", FIX / "book.schema.json", data,
                                "--name", "sg-real", "--out", out, "--store", "files", "--yes")
            self.assertEqual(code, 0, so + err)
            man = json.loads((out / "MANIFEST.json").read_text())
            self.assertEqual(sorted(man["syntheticSutras"]), ["book-default"])        # trade and counterparty have real documents
            days = sorted(p.name for p in (out / "data" / "files" / "sg-real").iterdir())
            self.assertEqual(days, ["2026-03-01", "2026-03-02"])
            tests = out / "pack" / "sg-real" / "tests" / "trade-irs"
            self.assertTrue((tests / "sample-1.json").is_file())
            self.assertFalse(any("synthetic" in p.name for p in tests.iterdir()))
            self.assertIn("extraField", (out / "pack" / "sg-real" / "config" / "about.yaml").read_text())     # shown, not in the schema: a TODO entry
            pack = (out / "pack" / "sg-real" / "pack.yaml").read_text()
            self.assertIn("graph:", pack)
            self.assertIn("sg-real-store", pack)


if __name__ == "__main__":
    unittest.main()
