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

"""Tests for data profile, pack regenerate (merge and plan), pack diff, pack catalogue and pack i18n. No Java needed."""
import argparse
import contextlib
import csv
import io
import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import drishti  # noqa: E402
import packregen  # noqa: E402

PACK_YAML = """pack: demo
version: 1.0.0
kinds: [trade]
sutras: sutras
mnemonics:
  TRD: {kind: trade, label: Trade}
columns: {trade: [id, type]}
"""
SUTRA = """rachana: 1
sutra: %s
version: 1
description: "Sutra for %s."
match: { kind: trade, where: "$.type == '%s'", priority: %d }
strip:
  - { label: "Notional", bind: "$.notional", fmt: "amount0" }
panels:
  - id: details
    kind: kv
    title: "Details"
    columns:
      - { label: "Type", bind: "$.type" }
"""
ABOUT = """about: 1
kinds:
  trade:
    title: Trade
    about: 'A trade ${$.id}.'
    glossary:
      notional: {term: Notional, means: 'The face amount.', unit: USD}
    panels:
      details: {about: 'The booking details.'}
"""


def make_pack(root: pathlib.Path, version="1.0.0", sutras=("irs", "fra"), mnemonic="TRD", priority=10) -> pathlib.Path:
    (root / "sutras/trade").mkdir(parents=True)
    (root / "config").mkdir()
    (root / "pack.yaml").write_text(PACK_YAML.replace("1.0.0", version).replace("TRD", mnemonic))
    for s in sutras:
        (root / f"sutras/trade/trade-{s}.v1.sutra.yaml").write_text(SUTRA % (f"trade-{s}", s, s.upper(), priority))
    (root / "config/about.yaml").write_text(ABOUT)
    return root


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        rc = drishti.main(list(map(str, argv)))
    return rc, out.getvalue(), err.getvalue()


class Merge3(unittest.TestCase):
    BASE = "a\nb\nc\nd\ne\n"

    def test_disjoint_edits_merge(self):
        text, n = packregen.merge3(self.BASE, "a\nB\nc\nd\ne\n", "a\nb\nc\nd\nE\n")
        self.assertEqual((text, n), ("a\nB\nc\nd\nE\n", 0))

    def test_same_edit_on_both_sides_is_not_a_conflict(self):
        self.assertEqual(packregen.merge3(self.BASE, "a\nX\nc\nd\ne\n", "a\nX\nc\nd\ne\n"), ("a\nX\nc\nd\ne\n", 0))

    def test_conflict_keeps_both_with_markers(self):
        text, n = packregen.merge3(self.BASE, "a\nMINE\nc\nd\ne\n", "a\nTHEIRS\nc\nd\ne\n")
        self.assertEqual(n, 1)
        self.assertIn("<<<<<<< yours", text)
        self.assertIn("MINE", text)
        self.assertIn("THEIRS", text)
        self.assertIn(">>>>>>> regenerated", text)

    def test_insertion_and_deletion_merge(self):
        text, n = packregen.merge3(self.BASE, "a\nb\nc\nnew\nd\ne\n", "b\nc\nd\ne\n")
        self.assertEqual((text, n), ("b\nc\nnew\nd\ne\n", 0))


class Plan(unittest.TestCase):
    def acts(self, base, ours, theirs):
        return {a["path"]: a["action"] for a in packregen.plan(base, ours, theirs)}

    def test_every_case(self):
        base = {"u": b"1\n", "e": b"1\n", "g": b"1\n", "r": b"1\n", "re": b"1\n", "m": b"a\nb\nc\n", "d": b"1\n"}
        ours = {"u": b"1\n", "e": b"edited\n", "g": b"1\n", "r": b"1\n", "re": b"edited\n", "m": b"A\nb\nc\n", "mine": b"x\n"}
        theirs = {"u": b"2\n", "e": b"1\n", "g": b"1\n", "new": b"n\n", "m": b"a\nb\nC\n", "d": b"2\n"}
        got = self.acts(base, ours, theirs)
        self.assertEqual(got, {"u": "refresh", "e": "keep", "g": "same", "new": "add", "r": "remove", "re": "stale", "m": "merge", "mine": "keep", "d": "deleted"})

    def test_no_baseline_never_overwrites(self):
        acts = packregen.plan(None, {"f": b"mine\n"}, {"f": b"theirs\n", "g": b"x\n"})
        self.assertEqual({a["path"]: a["action"] for a in acts}, {"f": "no-baseline", "g": "add"})
        self.assertTrue(all(a["data"] is None for a in acts if a["path"] == "f"))

    def test_user_edit_survives_snapshot_roundtrip(self):
        with tempfile.TemporaryDirectory() as t:
            pack = make_pack(pathlib.Path(t) / "demo")
            packregen.snapshot(pack, argparse.Namespace(name="demo", match="type", samples=5))
            base, opts = packregen.read_baseline(pack)
            self.assertEqual(set(base), set(packregen.pack_files(pack)))
            self.assertEqual(opts["match"], "type")
            self.assertFalse(list(pack.glob("**/*.sutra.yaml.base")) == [] or list(pack.glob(".generated/**/*.sutra.yaml")))


class Diff(unittest.TestCase):
    def test_breaking_selection_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            old = make_pack(t / "old")
            new = make_pack(t / "new", version="1.1.0", sutras=("irs", "swap"), mnemonic="TRX", priority=20)
            rc, out, _ = run("pack", "diff", old, new, "--json")
            rep = json.loads(out)
            self.assertEqual(rc, 1)
            whats = {(f["level"], f["what"]) for f in rep["findings"]}
            self.assertIn(("breaking", "mnemonic renamed"), whats)
            self.assertIn(("selection", "sutra removed"), whats)
            self.assertIn(("selection", "sutra added"), whats)
            self.assertIn(("selection", "screen selection changes"), whats)
            self.assertEqual(run("pack", "diff", old, new, "--fail-on", "none")[0], 0)
            same = run("pack", "diff", old, old, "--fail-on", "any")
            self.assertEqual(same[0], 0)
            self.assertIn("no differences", same[1])
            self.assertEqual(run("pack", "diff", old, t / "missing")[0], 2)

    def test_kind_rename_is_breaking(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            old, new = make_pack(t / "old"), make_pack(t / "new")
            (new / "pack.yaml").write_text(PACK_YAML.replace("[trade]", "[deal]").replace("kind: trade", "kind: deal"))
            rc, out, _ = run("pack", "diff", old, new)
            self.assertEqual(rc, 1)
            self.assertIn("kind renamed", out)


class I18n(unittest.TestCase):
    def test_export_import_roundtrip_and_reports(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            pack = make_pack(t / "demo")
            self.assertEqual(run("pack", "i18n", "export", pack, "--lang", "fr", "--out", t / "fr.csv")[0], 0)
            with open(t / "fr.csv", encoding="utf-8") as fh:
                rows = list(csv.DictReader(fh))
            keys = {r["key"] for r in rows}
            self.assertIn("kinds/trade/glossary/notional/means", keys)
            self.assertIn("kinds/trade/panels/details/about", keys)
            for r in rows:
                if r["key"] == "kinds/trade/title":
                    r["translation"] = "Opération"
                if r["key"] == "kinds/trade/about":
                    r["translation"] = "Une opération ${$.id}."
                if r["key"] == "kinds/trade/glossary/notional/means":
                    r["translation"] = "Le montant."
            with open(t / "fr.csv", "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, ["key", "source", "translation"])
                w.writeheader()
                w.writerows(rows)
                w.writerow({"key": "kinds/ghost/title", "source": "", "translation": "x"})
            rc, out, _ = run("pack", "i18n", "import", pack, t / "fr.csv", "--lang", "fr", "--json")
            rep = json.loads(out)
            self.assertEqual(rc, 1)
            self.assertEqual(rep["extra"], ["kinds/ghost/title"])
            self.assertGreater(len(rep["missing"]), 0)
            import yaml
            ov = yaml.safe_load((pack / "config/about.fr.yaml").read_text(encoding="utf-8"))
            self.assertEqual(ov["kinds"]["trade"]["title"], "Opération")
            self.assertEqual(ov["kinds"]["trade"]["glossary"]["notional"]["means"], "Le montant.")
            # a clean file exits 0; a translation that drops the expression is reported
            with open(t / "fr.csv", "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, ["key", "source", "translation"])
                w.writeheader()
                w.writerows([r for r in rows if r["key"] != "kinds/trade/about"] + [{"key": "kinds/trade/about", "source": "", "translation": "Sans expression."}])
            rc, out, _ = run("pack", "i18n", "import", pack, t / "fr.csv", "--lang", "fr", "--dry-run", "--json")
            self.assertEqual(rc, 1)
            self.assertEqual(json.loads(out)["expressionChanged"], ["kinds/trade/about"])

    def test_bad_language_is_usage_error(self):
        with tempfile.TemporaryDirectory() as t:
            pack = make_pack(pathlib.Path(t) / "demo")
            self.assertEqual(run("pack", "i18n", "export", pack, "--lang", "not a lang", "--out", pathlib.Path(t) / "x.csv")[0], 2)


class Catalogue(unittest.TestCase):
    def test_md_and_html(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            pack = make_pack(t / "demo")
            self.assertEqual(run("pack", "catalogue", pack, "--out", t / "c")[0], 0)
            md = (t / "c/catalogue.md").read_text()
            for needle in ("## Trade (`trade`, mnemonic TRD)", "The face amount.", "#### trade-irs", "`$.type == 'IRS'`", "Details (kv)"):
                self.assertIn(needle, md)
            self.assertEqual(run("pack", "catalogue", pack, "--out", t / "h", "--format", "html")[0], 0)
            self.assertIn("<h3>trade-fra</h3>", (t / "h/index.html").read_text())
            self.assertEqual(run("pack", "catalogue", t / "nope")[0], 2)


class Profile(unittest.TestCase):
    def test_candidates(self):
        with tempfile.TemporaryDirectory() as t:
            f = pathlib.Path(t) / "trade.jsonl"
            with open(f, "w") as fh:
                for i in range(120):
                    fh.write(json.dumps({"tradeId": f"T{i}", "asOf": f"2026-09-{1 + i % 3:02d}", "productType": ["IRS", "FRA", "CAP", "FLR", "OIS", "XCCY", "ZC", "BOND", "FUT", "SWPT"][i % 10],
                                         "notional": i * 1.5, "cp": {"id": "C" + str(i % 7)}, "legs": [{"rate": 0.1}, {"rate": 0.2}],
                                         **({"rare": 1} if i % 10 == 0 else {})}) + "\n")
                fh.write("not json\n")
            rc, out, _ = run("data", "profile", f, "--json")
            self.assertEqual(rc, 0)
            rep = json.loads(out)
            self.assertEqual(rep["badLines"], 1)
            p = rep["kinds"][0]
            self.assertEqual(p["documents"], 120)
            by = {r["field"]: r for r in p["fields"]}
            self.assertIn("cp.id", by)
            self.assertEqual(by["legs[].rate"]["distinct"], 2)
            self.assertAlmostEqual(by["rare"]["coverage"], 0.1)
            self.assertEqual(p["keyCandidates"][0]["field"], "tradeId")
            self.assertEqual(p["dateCandidates"][0]["field"], "asOf")
            self.assertEqual(p["matchCandidates"][0]["field"], "productType")
            self.assertNotIn("rare", [m["field"] for m in p["matchCandidates"]])
            self.assertIn("--match productType --key tradeId --date asOf", p["suggestedCommand"])
            rc, out, _ = run("data", "profile", f, "--max-distinct", "10")
            self.assertIn(">=10", out)
            self.assertEqual(run("data", "profile", f.parent / "missing")[0], 2)


def jar_available() -> bool:
    try:
        drishti.find_jar(None)
        drishti.find_java(None)
        return True
    except Exception:  # CliError: no jar / no java in this checkout
        return False


@unittest.skipUnless(jar_available(), "needs the drishti-server exec jar and a JDK")
class RegenerateEndToEnd(unittest.TestCase):
    def test_edit_kept_new_group_added_conflict_listed(self):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            data, pack = t / "trade.jsonl", t / "pk"
            docs = [{"tradeId": f"T{i}", "type": ["IRS", "FRA"][i % 2], "notional": 100 + i} for i in range(6)]
            data.write_text("".join(json.dumps(d) + "\n" for d in docs))
            self.assertEqual(run("pack", "new", data, "--name", "pk", "--key", "tradeId", "--match", "type", "--out", pack)[0], 0)
            self.assertTrue((pack / ".generated" / "pack.yaml.base").is_file())
            irs = pack / "sutras/trade/trade-irs.v1.sutra.yaml"
            irs.write_text(irs.read_text().replace('label: "Notional"', 'label: "Face amount"'))
            about = pack / "config/about.yaml"
            about.write_text(about.read_text().replace("TODO: one sentence about a trade", "A trade", 1))
            data.write_text(data.read_text() + json.dumps({"tradeId": "T9", "type": "CAP", "notional": 5}) + "\n")
            rc, out, _ = run("pack", "regenerate", pack, data, "--dry-run")
            self.assertEqual(rc, 0, out)
            self.assertFalse((pack / "sutras/trade/trade-cap.v1.sutra.yaml").exists())
            rc, out, _ = run("pack", "regenerate", pack, data)
            self.assertEqual(rc, 0, out)
            self.assertTrue((pack / "sutras/trade/trade-cap.v1.sutra.yaml").is_file())
            self.assertIn("Face amount", irs.read_text())
            self.assertIn("A trade", about.read_text())
            # both sides change the match line: yours edited it, the generator now uses priority 20
            irs.write_text(irs.read_text().replace("priority: 10", "priority: 15"))
            rc, out, _ = run("pack", "regenerate", pack, data, "--priority", "20")
            self.assertEqual(rc, 1, out)
            text = irs.read_text()
            self.assertIn("<<<<<<<", text)
            self.assertIn("priority: 15", text)
            self.assertIn("priority: 20", text)
            self.assertIn("Face amount", text)


if __name__ == "__main__":
    unittest.main()
