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

"""The loader contract (lake/drishti-lakeload/src/test/resources/loader-contract/cases.yaml) on every engine that exists.

    uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/lake/test_loader_contract.py -q

The Java loader must meet every case; a case it fails is a finding for the owner, not a change to the case. The pure-Python path must
meet every case except those that name it in ``known`` (the design's section 2.4), and a ``known`` case it now passes fails the test, so
the list shrinks as the path is brought up to the contract or retired. The Polars engine joins ENGINES when it is built."""
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import loadercontract as lc  # noqa: E402

_STORE = None


def setUpModule():
    global _STORE
    _STORE = tempfile.TemporaryDirectory()
    os.environ["DRISHTI_BI_STORE"] = _STORE.name


def tearDownModule():
    os.environ.pop("DRISHTI_BI_STORE", None)
    _STORE.cleanup()


class CasesTest(unittest.TestCase):

    def test_the_cases_are_well_formed(self):
        cases = lc.load_cases()
        self.assertGreaterEqual(len(cases), 60)
        rules = {r for c in cases for r in c.get("rules") or []}
        for c in cases:
            self.assertIn("expect", c, c["name"])
            self.assertTrue(c.get("rules"), c["name"])
            for engine in c.get("known") or {}:
                self.assertIn(engine, lc.ENGINES, c["name"])
        for r in ("C1", "C3", "C9", "C11", "C14", "C16", "C19", "C20", "C23", "C24", "C25", "C31"):
            self.assertIn(r, rules)

    def run_engine(self, engine: str):
        import filedate
        today = filedate.business_today().isoformat()
        for case in lc.load_cases():
            with self.subTest(case=case["name"], engine=engine):
                with tempfile.TemporaryDirectory(prefix="loader-contract-") as tmp:
                    differences = lc.check(case, lc.run_case(case, engine, pathlib.Path(tmp)), today)
                known = (case.get("known") or {}).get(engine)
                if known:
                    self.assertTrue(differences, f"{engine} now meets this case ({known}): remove it from `known`")
                else:
                    self.assertEqual(differences, [])

    def test_the_java_loader_meets_the_contract(self):
        self.run_engine("java")

    def test_the_python_path_meets_the_contract_but_for_what_is_known(self):
        self.run_engine("python")

    @unittest.skipIf(lc.ENGINES["polars"] is None, "the Polars engine is not built (POLARS_LOADER_DESIGN.md, P3)")
    def test_the_polars_engine_meets_the_contract(self):
        self.run_engine("polars")


if __name__ == "__main__":
    unittest.main()
