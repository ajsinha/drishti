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

"""tools/evidence: the comparison says "no difference" for equal captures, catches a planted difference, honours a reviewed line only with a
reason, and the report is generated, the same bytes every time, and stale when an artefact changes (I7B_PRODUCTS_PLAN 3.3, C0)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "evidence"))
import capture  # noqa: E402
import common  # noqa: E402
import compare  # noqa: E402
import report  # noqa: E402


def make(root: Path, label: str, var: int = 100, seconds: float = 0.1) -> Path:
    capture.write(root / "sutras.json", [{"name": "trade", "kind": "trade"}])
    capture.write(root / "viewmodels" / "trade.json", {"sutra": "trade", "viewModel": {"title": {"id": "T1"}, "timings": {"ms": seconds}, "panels": [{"id": "a", "cells": [1, 2]}]}})
    capture.write(root / "answers" / "00-modules.json", {"path": "/api/v1/modules", "status": 200, "body": {"modules": ["core"]}})
    capture.write(root / "figures.json", [{"name": "var", "status": 200, "body": {"var": var}}])
    capture.write(root / "pages.json", [{"path": "/", "status": 200, "bytes": 1000, "seconds": seconds}])
    capture.write(root / "manifest.json", {"label": label, "commit": "abc", "jar": "x.jar", "jarSha256": "0" * 64,
                                          "counts": {"sutras": 1, "viewmodels": 1, "noEntity": 0, "frames": 0, "answers": 1, "pages": 1}})
    return root


class Evidence(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.t = Path(self._t.name)

    def tearDown(self):
        self._t.cleanup()

    def test_equal_captures_report_no_difference_and_a_clock_is_not_one(self):
        a, b = make(self.t / "a", "base"), make(self.t / "b", "new", seconds=0.2)       # timings differ: dropped from the ViewModel, within the page threshold
        out = self.t / "c.json"
        self.assertEqual(compare.main([str(a), str(b), "--out", str(out)]), 0)
        res = json.loads(out.read_text())
        self.assertEqual(res["status"], "no difference")
        self.assertEqual(res["differences"], [])

    def test_a_planted_difference_is_caught_by_its_path(self):
        a, b = make(self.t / "a", "base"), make(self.t / "b", "new", var=101)
        out = self.t / "c.json"
        self.assertEqual(compare.main([str(a), str(b), "--out", str(out)]), 1)
        d = json.loads(out.read_text())["differences"]
        self.assertEqual([(x["artefact"], x["claim"], x["class"]) for x in d], [("figures.json", "EV4", "unlisted")])
        self.assertIn("100 -> 101", d[0]["difference"])

    def test_a_changed_page_and_a_removed_file_are_caught(self):
        a, b = make(self.t / "a", "base"), make(self.t / "b", "new", seconds=2.0)
        (b / "answers" / "00-modules.json").unlink()
        out = self.t / "c.json"
        self.assertEqual(compare.main([str(a), str(b), "--out", str(out)]), 1)
        kinds = {(x["artefact"], x["path"]) for x in json.loads(out.read_text())["differences"]}
        self.assertIn(("answers/00-modules.json", "(file)"), kinds)
        self.assertIn(("pages.json", "/"), kinds)

    def test_a_reviewed_line_needs_a_reason_and_then_passes_only_what_it_names(self):
        a, b = make(self.t / "a", "base"), make(self.t / "b", "new", var=101)
        f = self.t / "r.json"
        f.write_text(json.dumps([{"artefact": "figures.json", "path": "var$", "reason": ""}]))
        self.assertEqual(compare.main([str(a), str(b), "--reviewed", str(f)]), 2)
        f.write_text(json.dumps([{"artefact": "figures.json", "path": "var$", "reason": "the rounding rule changed (listed in the release notes)"}]))
        self.assertEqual(compare.main([str(a), str(b), "--reviewed", str(f)]), 0)
        f.write_text(json.dumps([{"artefact": "answers/*", "path": ".", "reason": "unrelated"}]))
        self.assertEqual(compare.main([str(a), str(b), "--reviewed", str(f)]), 1)

    def test_the_report_is_generated_the_same_every_time_and_stale_when_an_artefact_changes(self):
        a, b = make(self.t / "a", "base"), make(self.t / "b", "new")
        cj = self.t / "c.json"
        compare.main([str(a), str(b), "--out", str(cj)])
        out = self.t / "R.md"
        args = ["--baseline", str(a), "--step", f"C-I The split={cj}", "--out", str(out)]
        self.assertEqual(report.main(args), 0)
        first = out.read_text()
        self.assertEqual(report.main(args + ["--check"]), 0)
        self.assertIn("EV4", first)
        self.assertIn("no difference", first)
        c2 = make(self.t / "c", "new2", var=999)
        compare.main([str(a), str(c2), "--out", str(cj)])
        self.assertEqual(report.main(args + ["--check"]), 1)

    def test_a_capture_packed_twice_is_the_same_bytes_and_compares_as_its_folder(self):
        a = make(self.t / "a", "base")
        common.pack(a, self.t / "1.tar.gz")
        common.pack(a, self.t / "2.tar.gz")
        self.assertEqual((self.t / "1.tar.gz").read_bytes(), (self.t / "2.tar.gz").read_bytes())
        self.assertEqual(compare.main([str(self.t / "1.tar.gz"), str(a)]), 0)

    def test_the_committed_report_is_what_the_committed_artefacts_produce(self):
        root = Path(__file__).resolve().parents[1] / "testing" / "evidence"
        self.assertEqual(report.main(["--baseline", str(root / "baseline-v2.9.11.i_7a.tar.gz"), "--steps-file", str(root / "steps.txt"), "--check"]), 0)

    def test_the_baseline_compared_with_itself_says_no_difference(self):
        root = Path(__file__).resolve().parents[1] / "testing" / "evidence"
        self.assertEqual(compare.main([str(root / "baseline-v2.9.11.i_7a.tar.gz"), str(root / "baseline-v2.9.11.i_7a.tar.gz")]), 0)

    def test_the_owners_ports_are_refused(self):
        self.assertEqual(capture.main(["--server", "http://127.0.0.1:18480", "--out", str(self.t / "x"), "--label", "x"]), 2)

    def test_the_normal_form_drops_what_a_run_changes_and_nothing_else(self):
        self.assertEqual(common.normalise({"a": 1, "timings": {"x": 1}, "p": {"fetchedAt": "t", "k": [{"updatedAt": 1, "v": 2}]}}), {"a": 1, "p": {"k": [{"v": 2}]}})


if __name__ == "__main__":
    unittest.main()
