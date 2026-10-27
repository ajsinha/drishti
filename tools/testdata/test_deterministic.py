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

"""make_testdata.py writes the same bytes everywhere (QA-OPEN-01): the counterparty and netting-set data generated twice, in two scratch
folders and under two PYTHONHASHSEED values (and with DRISHTI_OTHER_PYTHON=<another Python> the second run on that one), are byte-identical; and the amounts that are sums (a netting set's NET_MTM, a counterparty's)
are whole cents, added by math.fsum, not the built-in sum() whose float result differs at the last bit between Python 3.13 and 3.14.

    python3 -m unittest -q tools/testdata/test_deterministic.py
"""
from __future__ import annotations

import filecmp
import gzip
import json
import math
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TD = ROOT / "data" / "testdata"

#: builds a small universe and writes the counterparty and netting-set histories into argv[1], as make_testdata.py does
GENERATE = """
import sys
sys.path.insert(0, {here!r})
import history as H, make_testdata as MT, universe as UV
out = sys.argv[1]
days = H.Days(MT.business_days(12))
u = UV.Universe(400).build(days)
for kind, folder in (("counterparty", "core/counterparty"), ("netting-set", "counterparty-risk/netting-set")):
    docs = u.docs[kind]
    H.DatasetHistory(__import__("pathlib").Path(out) / folder, MT.key_of(next(iter(docs.values()))), days).write(H.doc_states(kind, docs, days))
"""


class DeterministicTest(unittest.TestCase):
    def test_counterparty_and_netting_set_data_are_byte_identical_across_folders_and_hash_seeds(self):
        with tempfile.TemporaryDirectory(prefix="testdata-det-") as tmp:
            folders = []
            pythons = (sys.executable, os.environ.get("DRISHTI_OTHER_PYTHON") or sys.executable)   # another version, when given
            for n, seed in enumerate(("1", "4242")):
                out = Path(tmp) / f"run{n}"
                env = {**os.environ, "PYTHONHASHSEED": seed, "DRISHTI_DATA_DIR": str(Path(tmp) / f"data{n}")}
                subprocess.run([pythons[n], "-c", GENERATE.format(here=str(HERE)), str(out)], check=True, env=env, cwd=tmp,
                               stdout=subprocess.DEVNULL)
                folders.append(out)
            files = sorted(str(f.relative_to(folders[0])) for f in folders[0].rglob("*.jsonl.gz"))
            self.assertTrue(any(f.startswith("core/counterparty/") for f in files) and any(f.startswith("counterparty-risk/") for f in files))
            self.assertEqual(files, sorted(str(f.relative_to(folders[1])) for f in folders[1].rglob("*.jsonl.gz")))
            differ = [f for f in files if not filecmp.cmp(folders[0] / f, folders[1] / f, shallow=False)]
            self.assertEqual([], differ)

    @unittest.skipUnless((TD / "testdata.yaml").is_file(), "no data/testdata")
    def test_summed_amounts_on_the_last_day_are_whole_cents(self):
        import yaml
        dates = yaml.safe_load((TD / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
        for folder, key in (("counterparty-risk/netting-set", "NETTING_SET_ID"), ("core/counterparty", "COUNTERPARTY_ID")):
            state: dict[str, dict] = {}
            for i, day in enumerate(dates):
                for f in sorted((TD / folder).glob(f"{'snapshot' if i == 0 else 'changes'}-{day}*.jsonl.gz")):
                    with gzip.open(f, "rt", encoding="utf-8") as fh:
                        for line in fh:
                            if line.strip():
                                d = json.loads(line)
                                state[str(d[key])] = d
            amounts = [d["NET_MTM"] for d in state.values() if isinstance(d.get("NET_MTM"), float)]
            self.assertTrue(amounts, folder)
            self.assertEqual([], [x for x in amounts if round(x, 2) != x], folder)

    def test_amount_adds_whole_numbers_exactly_and_others_by_fsum_to_the_cent(self):
        sys.path.insert(0, str(ROOT / "tools" / "packgen" / "banking"))
        sys.path.insert(0, str(ROOT / "tools"))
        import data_results as DR
        self.assertEqual(6, DR.amount([1, 2, 3]))
        self.assertIsInstance(DR.amount([1, 2, 3]), int)
        xs = [0.1] * 10 + [1e8 + 0.07, -3.33]
        self.assertEqual(round(math.fsum(xs), 2), DR.amount(xs))
        self.assertEqual(DR.amount(xs), DR.amount(reversed(xs)))             # whatever the order


if __name__ == "__main__":
    unittest.main()
