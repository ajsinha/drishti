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
"""The fix drill never skips a lane the commits can reach (docs/architecture/TEST_HARNESS.md, "Fix drills"), and the container smoke
test's choice of the full in-container suite. Standard library only: ``python3 -m unittest tools/drillkit/test_drill_lanes.py``."""
from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import container_smoke as C  # noqa: E402
import drill_lanes as L  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


class Reach(unittest.TestCase):
    def test_a_path_no_rule_names_reaches_every_lane(self):
        self.assertEqual(L.lanes_for("a-new-folder/x.txt"), L.ALL)
        self.assertEqual(L.lanes_for("README"), L.ALL)

    def test_the_drill_itself_reaches_every_lane(self):
        for p in ("tools/drill.sh", "tools/drillkit/drill_plan.py", "tools/drill-known.txt"):
            self.assertEqual(L.lanes_for(p), L.ALL, p)

    def test_java_reaches_everything_that_runs_the_jar(self):
        for p in ("kernel/drishti-server/src/main/java/A.java", "modules/banking/banking-service/src/main/java/B.java", "pom.xml",
                  "plugins/drishti-plugin-kafka/src/test/java/KTest.java", "kernel/drishti-engine/src/main/resources/x.yaml", ".java-version"):
            got = L.lanes_for(p)
            self.assertTrue({"java21", "java25", "browser", "tools-a", "tools-b", "containers"} <= got, (p, got))

    def test_the_console_reaches_every_lane_and_its_tests_only_the_console_lanes(self):
        self.assertEqual(L.lanes_for("drishti-console/web/static/js/drishti_x.js"), L.ALL)   # Java and tools tests read the console's files
        self.assertEqual(L.lanes_for("drishti-console/tests/snapshots/var-desk/1366-dark.png"), {"browser", "other"})

    def test_documents_reach_their_readers(self):
        self.assertTrue({"browser", "other", "java21", "tools-a"} <= L.lanes_for("docs/guides/VAR_GUIDE.md"))

    def test_shared_data_and_config_reach_every_lane(self):
        for p in ("config/packs/default-packs.yaml", "data/testdata/core/desk/a.jsonl.gz", "packaging/drishti-elements/pom.xml"):
            self.assertEqual(L.lanes_for(p), L.ALL, p)

    def test_the_dockerfiles_reach_the_containers(self):
        self.assertIn("containers", L.lanes_for("deploy/docker/server.Dockerfile"))
        self.assertIn("containers", L.lanes_for(".dockerignore"))

    def test_every_top_level_folder_reaches_some_lane(self):
        for d in ROOT.iterdir():
            if d.is_dir() and not d.name.startswith(".") and d.name != "data":
                self.assertTrue(L.lanes_for(f"{d.name}/x"), d.name)


class FixPlan(unittest.TestCase):
    STATE = {lane: {"passed_at": "a" * 40} for lane in L.LANES}

    def plan(self, paths, state=None, ancestor=True, reach=None):
        return L.fix_lanes(state if state is not None else self.STATE, diff=lambda base, head: paths,
                           ancestor=lambda c, h: ancestor, reach=lambda b, h: reach)

    def test_a_lane_never_passed_runs(self):
        state = dict(self.STATE)
        del state["java25"]
        self.assertEqual(set(self.plan([], state)), {"java25"})

    def test_a_failed_lane_runs_and_untouched_passed_lanes_do_not(self):
        state = dict(self.STATE)
        state["browser"] = {}                     # stopped (another lane failed first): nothing recorded, the whole lane
        got = self.plan(["drishti-console/tests/test_x_browser.py"], state)
        self.assertEqual(set(got), {"browser", "other"})
        self.assertIsNone(got["browser"][1])

    def test_a_console_lane_that_failed_in_some_files_runs_those_and_what_the_fix_reaches(self):
        state = dict(self.STATE)
        state["browser"] = {"ran_at": "b" * 40, "failed_files": ["drishti-console/tests/test_a_browser.py"]}
        got = self.plan(["drishti-console/tests/test_b_browser.py"], state, reach=["drishti-console/tests/test_b_browser.py"])
        self.assertEqual(got["browser"][1], ["drishti-console/tests/test_a_browser.py", "drishti-console/tests/test_b_browser.py"])
        self.assertIsNone(self.plan(["x"], state, reach=None)["browser"][1])   # the fix reaches every console test: the whole lane

    def test_a_java_fix_reaches_the_java_lanes(self):
        self.assertEqual(set(self.plan(["kernel/drishti-server/src/main/java/A.java"])), L._JAVA_RUNS)

    def test_a_pass_on_another_branch_does_not_count(self):
        self.assertEqual(set(self.plan([], ancestor=False)), L.ALL)

    def test_an_unknown_diff_runs_the_lane(self):
        self.assertEqual(set(L.fix_lanes(self.STATE, diff=lambda b, h: None, ancestor=lambda c, h: True, reach=lambda b, h: None)), L.ALL)

    def test_the_lanes_are_the_drills(self):
        text = (ROOT / "tools" / "drill.sh").read_text(encoding="utf-8")
        for lane in L.LANES:
            self.assertIn(lane, text, lane)

    def test_a_real_diff(self):
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
        if not head:
            self.skipTest("not a repository")
        self.assertEqual(L.changed(head, head), [])


class FullContainerSuite(unittest.TestCase):
    def test_the_dockerfiles_the_jdk_and_native_code_need_the_full_suite(self):
        for p in ("deploy/docker/server.Dockerfile", "pom.xml", ".java-version", "compute/drishti-compute/src/main/java/com/ash/drishti/compute/linalg/Blas.java",
                  ".mvn/wrapper/maven-wrapper.properties"):
            self.assertTrue(C.full_reasons([p]), p)

    def test_a_module_with_container_tests_needs_them(self):
        mods = C.container_test_modules()
        self.assertTrue(mods, "no Testcontainers test found")
        mod = sorted(mods)[0]
        self.assertTrue(C.full_reasons([f"{mod}/src/main/java/X.java"]))

    def test_the_console_alone_needs_only_the_smoke_test(self):
        self.assertEqual(C.full_reasons(["drishti-console/web/static/js/drishti_x.js", "docs/guides/X.md"]), [])


if __name__ == "__main__":
    unittest.main()
