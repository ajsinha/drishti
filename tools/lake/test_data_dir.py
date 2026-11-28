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

"""The data folder is ONE answer for every lake writer and reader (tools/lake/lakeconfig.py data_dir): a scratch ``--data-dir`` build writes only
under that folder, never under the repository's own data/.

    python3 -m pytest -q tools/lake/test_data_dir.py
"""
from __future__ import annotations

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import lakeconfig  # noqa: E402

try:
    import deltalake  # noqa: F401
    import yaml  # noqa: F401
    HAVE = True
except ImportError:
    HAVE = False


def listing(folder: Path) -> set:
    return {p.relative_to(folder).as_posix() for p in folder.rglob("*")} if folder.is_dir() else set()


class DataDir(unittest.TestCase):
    def setUp(self):
        self.saved = {k: os.environ.get(k) for k in ("DRISHTI_DATA_DIR", "DRISHTI_DELTA_ROOT")}
        self.override = lakeconfig._OVERRIDE
        for k in self.saved:
            os.environ.pop(k, None)
        lakeconfig._OVERRIDE = ""

    def tearDown(self):
        for k, v in self.saved.items():
            os.environ.pop(k, None) if v is None else os.environ.__setitem__(k, v)
        lakeconfig._OVERRIDE = self.override
        lakeconfig._table.cache_clear()

    def test_use_data_dir_is_the_data_folder_of_this_process_and_its_children(self):
        with tempfile.TemporaryDirectory() as tmp:
            got = lakeconfig.use_data_dir(Path(tmp) / "scratch")
            self.assertEqual(lakeconfig.data_dir(), got)
            self.assertEqual(os.environ["DRISHTI_DATA_DIR"], str(got))          # a child (lake build, a loader) inherits it
            self.assertEqual(lakeconfig.default_root("risklake"), got / "delta" / "risklake")
            self.assertTrue(lakeconfig.data_named())

    def test_a_lake_files_root_follows_the_data_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = lakeconfig.use_data_dir(Path(tmp) / "scratch")
            self.assertEqual(Path(lakeconfig.root("demo")).resolve(), (data / "delta" / "demo").resolve())

    @unittest.skipUnless(HAVE, "needs deltalake and PyYAML")
    def test_lake_demo_with_a_scratch_data_dir_writes_only_under_it(self):
        import demo
        repo_data = ROOT / "data"
        before = {s: listing(repo_data / s) for s in ("delta", "iceberg", "build", "bi-store", "dataload")}
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            td, data = tmp / "testdata", tmp / "scratch"
            subprocess.run([sys.executable, str(ROOT / "tools" / "testdata" / "make_testdata.py"), "--out", str(td), "--per-system", "40", "--days", "7"],
                           check=True, capture_output=True)
            a = demo.build_parser().parse_args(["--testdata", str(td), "--data-dir", str(data), "--connectors-dir", str(tmp / "connectors"),
                                                "--only", "core/desk", "--as-of", "2026-10-30", "-q", "--server", "http://127.0.0.1:9"])
            (tmp / "connectors").mkdir()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(demo.run(a), 0)
            self.assertTrue(any((data / "delta" / "demo").rglob("_delta_log")), "the demo lake is under the --data-dir")
        after = {s: listing(repo_data / s) for s in before}
        self.assertEqual(after, before, "nothing appeared under the repository's data/")


if __name__ == "__main__":
    unittest.main()
