#!/usr/bin/env python3
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

"""Tests for `drishti.py pack make` (tools/packmake.py) in temporary folders: key and date detection, the folder layout, the README's values.

    python3 -m unittest -q tools/test_packmake.py

The folder-layout tests run the whole command (files store, so no deltalake needed) and need the server jar for lint and test:
they are skipped when there is none (DRISHTI_JAR, or drishti-server/target/*-exec.jar).
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
import packmake as PM  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
HAVE_JAR = bool(os.environ.get("DRISHTI_JAR") or glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")))


def trades(n=12):
    return [{"tradeId": f"T-{i:03d}", "ref": "same", "productType": "FX_FWD" if i % 2 else "IRS", "businessDate": "2026-09-17" if i < n // 2 else "2026-09-18",
             "tradeDate": f"2026-0{1 + i % 8}-0{1 + i % 9}", "notional": i * 1000} for i in range(n)]


def write_jsonl(folder: pathlib.Path, docs, name="trade.jsonl") -> pathlib.Path:
    folder.mkdir(parents=True, exist_ok=True)
    p = folder / name
    p.write_text("".join(json.dumps(d) + "\n" for d in docs), encoding="utf-8")
    return p


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = D.main([str(a) for a in argv])
    return code, out.getvalue(), err.getvalue()


class Detect(unittest.TestCase):
    def test_key_prefers_kind_id_and_explains(self):
        key, why = PM.detect_key(trades(), "trade")
        self.assertEqual(key, "tradeId")
        self.assertIn("unique in all 12", why)

    def test_key_falls_back_to_id_then_idlike(self):
        docs = [{"a": 1, "id": f"x{i}", "other": i} for i in range(5)]
        self.assertEqual(PM.detect_key(docs, "thing")[0], "id")
        docs = [{"flag": "y", "rowKey": i} for i in range(5)]
        self.assertEqual(PM.detect_key(docs, "thing")[0], "rowKey")

    def test_key_refused_when_nothing_unique(self):
        with self.assertRaises(PM.MakeError) as e:
            PM.detect_key([{"a": 1}, {"a": 1}, {"a": 2}], "x")
        self.assertIn("--key", str(e.exception))

    def test_key_must_be_in_every_document(self):
        docs = [{"id": 1}, {"id": 2}, {"other": 3}]
        with self.assertRaises(PM.MakeError):
            PM.detect_key(docs, "x")

    def test_date_prefers_business_date_over_other_dates(self):
        field, why = PM.detect_date(trades())
        self.assertEqual(field, "businessDate")
        self.assertIn("business date", why)

    def test_date_needs_95_percent_presence(self):
        docs = [{"id": i, "asOf": "2026-01-01"} for i in range(10)] + [{"id": 99}] * 5
        self.assertIsNone(PM.detect_date(docs)[0])
        docs = [{"id": i, "asOf": "2026-01-01"} for i in range(96)] + [{"id": 99}] * 4
        self.assertEqual(PM.detect_date(docs)[0], "asOf")

    def test_no_date_field(self):
        field, why = PM.detect_date([{"id": 1, "name": "x"}])
        self.assertIsNone(field)
        self.assertIn("no dated store", why)


@unittest.skipUnless(HAVE_JAR, "needs the drishti-server exec jar (DRISHTI_JAR) for sutra lint and test")
class Make(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        t = pathlib.Path(cls.tmp.name)
        cls.src = write_jsonl(t / "in", trades())
        cls.out = t / "out"
        cls.code, cls.stdout, cls.stderr = run("pack", "make", cls.src, "--kind", "trade", "--match", "productType", "--name", "pm-test",
                                               "--store", "files", "--version", "2.1.0", "--out", cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_succeeds_and_says_what_it_chose(self):
        self.assertEqual(self.code, 0, self.stdout + self.stderr)
        self.assertIn("key:   tradeId", self.stdout)
        self.assertIn("date:  businessDate", self.stdout)
        self.assertIn("pack check passed, pack verify passed", self.stdout)

    def test_folder_layout(self):
        for rel in ("README.txt", "MANIFEST.json", "run-server.sh", "run-server.ps1", "config/drishti-site.yaml", "pack/pm-test/pack.yaml",
                    "pack/pm-test/config/about.yaml", "bundle/pm-test-2.1.0.tar.gz", "bundle/pm-test-2.1.0.tar.gz.sha256"):
            self.assertTrue((self.out / rel).is_file(), rel)
        self.assertTrue(any((self.out / "pack/pm-test/sutras").rglob("*.sutra.yaml")))
        self.assertTrue((self.out / "data/files/pm-test/2026-09-17/trade.jsonl").is_file())
        self.assertTrue((self.out / "data/files/pm-test/2026-09-18/trade.jsonl").is_file())
        self.assertTrue(os.access(self.out / "run-server.sh", os.X_OK))

    def test_readme_has_real_values(self):
        text = (self.out / "README.txt").read_text(encoding="utf-8")
        for needle in ("pm-test 2.1.0", "kind          trade", "tradeId", "businessDate", "2026-09-17", "2026-09-18", "T-000", "12",
                       "pack/pm-test/", "DRISHTI_FILES_ROOT", "drishti.packs.installed-dir", "/api/v1/views/trade/T-000?asOf=2026-09-17", "pm-test-2.1.0.tar.gz"):
            self.assertIn(needle, text)
        self.assertNotIn("{", text.replace("${DRISHTI_FILES_ROOT:${drishti.data.dir:./data}/files}", "").replace('{"ref":{"kind":"trade","id":"T-000"}', ""))

    def test_manifest_records_choices_and_checksums(self):
        m = json.loads((self.out / "MANIFEST.json").read_text(encoding="utf-8"))
        self.assertEqual(m["key"]["field"], "tradeId")
        self.assertEqual(m["date"]["field"], "businessDate")
        self.assertEqual(m["documents"], {"trade": 12})
        self.assertEqual(m["documentsPerDate"], {"2026-09-17": 6, "2026-09-18": 6})
        self.assertTrue(m["checks"]["packCheck"] and m["checks"]["packVerify"])
        self.assertEqual(m["bundle"]["sha256"], (self.out / "bundle/pm-test-2.1.0.tar.gz.sha256").read_text().split()[0])
        self.assertIn("pack/pm-test/pack.yaml", m["files"])

    def test_refuses_existing_out_without_force(self):
        code, _, err = run("pack", "make", self.src, "--kind", "trade", "--match", "productType", "--name", "pm-test", "--out", self.out)
        self.assertEqual(code, 2)
        self.assertIn("--force", err)

    def test_refuses_when_no_key(self):
        t = pathlib.Path(self.tmp.name)
        src = write_jsonl(t / "dup", [{"a": 1, "productType": "x"}] * 3)
        code, _, err = run("pack", "make", src, "--kind", "trade", "--match", "productType", "--name", "pm-dup", "--out", t / "dup-out")
        self.assertEqual(code, 2)
        self.assertIn("no field is present and unique", err)


if __name__ == "__main__":
    unittest.main()
