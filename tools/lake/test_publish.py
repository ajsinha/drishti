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

"""lake publish: a table lake build wrote, copied and committed into another table (a local folder standing in for S3), one commit
per run, dates replaced whole, missing files and column changes refused. Small synthetic books only.

    drishti-console/.venv/bin/python -m unittest tools/lake/test_publish.py
    uv run --with deltalake --with pyarrow --with pyyaml python -m unittest tools/lake/test_publish.py
"""
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import publish  # noqa: E402


def deal(i: int, day: str, version: int = 1) -> dict:
    return {"TRADE_ID": f"T-{100000 + i}", "TRADE_DATE": day, "PRODUCT_TYPE": ["SWAP", "FUTURE"][i % 2], "VERSION": version,
            "NOTIONAL": 1000.0 * (i + 1), "LEGS": [{"LEG_ID": n} for n in range(2)]}


def quiet(*_):
    pass


class PublishTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self._env = {k: os.environ.get(k) for k in ("DRISHTI_BI_STORE", "DRISHTI_DELTA_ROOT")}
        os.environ["DRISHTI_BI_STORE"] = str(self.root / "bi-store")         # never the repository's data/
        os.environ["DRISHTI_DELTA_ROOT"] = str(self.root / "served")
        self.target = str(self.root / "s3" / "lake" / "trading" / "trade_endur")

    def tearDown(self):
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        self.tmp.cleanup()

    def scratch(self, run: str, days: dict, promote: str = "NOTIONAL") -> Path:
        """lake build into a fresh scratch lake (the deltalake engine: no server jar needed), as load-day.sh does."""
        src = self.root / f"in-{run}.jsonl"
        src.write_text("".join(json.dumps(d) + "\n" for docs in days.values() for d in docs), encoding="utf-8")
        a = build.build_parser().parse_args(["--from", str(src), "--kind", "trade", "--key", "TRADE_ID", "--date", "TRADE_DATE",
                                             "--partition-by", "PRODUCT_TYPE", "--promote", promote, "--lake", str(self.root / f"build-{run}"),
                                             "--domain", "trading", "--table", "trade_endur", "--engine", "deltalake", "--no-stamp", "-q"])
        code, s = build.run(a, quiet)
        self.assertEqual(code, 0, s.get("error"))
        return self.root / f"build-{run}" / "trading" / "trade_endur"

    def copy(self, table: Path):
        """aws s3 sync <table>/ <target>/ --exclude '_delta_log/*', on local disk."""
        for f in table.rglob("*.parquet"):
            if "_delta_log" not in f.parts:
                dest = Path(self.target) / f.relative_to(table)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dest)

    def publish(self, table: Path, *extra) -> tuple[int, dict]:
        a = publish.add_arguments(__import__("argparse").ArgumentParser()).parse_args(["--from", str(table), "--to", self.target, *extra])
        return publish.run(a, quiet)

    def rows(self) -> dict:
        from deltalake import DeltaTable
        t = DeltaTable(self.target).to_pyarrow_table(columns=["id", "doc", "business_date"]).to_pylist()
        return {(str(r["business_date"]), r["id"]): json.loads(r["doc"])["VERSION"] for r in t}

    def test_a_new_table_then_a_day_replaced_whole_in_one_commit(self):
        from deltalake import DeltaTable
        first = self.scratch("1", {"d1": [deal(i, "2026-10-19") for i in range(6)], "d2": [deal(i, "2026-10-20") for i in range(6)]})
        self.copy(first)
        code, s = self.publish(first)
        self.assertEqual(code, 0, s.get("error"))
        self.assertTrue(s["create"])
        self.assertEqual(s["dates"], ["2026-10-19", "2026-10-20"])
        self.assertEqual(len(self.rows()), 12)
        conf = DeltaTable(self.target).metadata().configuration
        self.assertEqual(conf["delta.checkpointInterval"], "10")
        self.assertIn("id", conf["delta.dataSkippingStatsColumns"])
        import pyarrow as pa
        stats = pa.table(DeltaTable(self.target).get_add_actions(flatten=True)).to_pydict()
        self.assertTrue(stats["min.id"] and all(v is not None for v in stats["min.id"]))   # id statistics carried over: file skipping works

        # a restatement of 2026-10-20 with four trades of version 2: the day's old files go, 2026-10-19 stays
        again = self.scratch("2", {"d2": [deal(i, "2026-10-20", version=2) for i in range(4)]})
        self.copy(again)
        before = DeltaTable(self.target).version()
        code, s = self.publish(again, "--dates", "2026-10-20")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(DeltaTable(self.target).version(), before + 1)         # one commit
        got = self.rows()
        self.assertEqual(sorted(v for (d, _), v in got.items() if d == "2026-10-20"), [2, 2, 2, 2])
        self.assertEqual(len([1 for (d, _) in got if d == "2026-10-19"]), 6)

    def test_overwrite_partitions_replaces_only_the_partitions_carried(self):
        first = self.scratch("1", {"d": [deal(i, "2026-10-20") for i in range(6)]})          # SWAP and FUTURE
        self.copy(first)
        self.assertEqual(self.publish(first)[0], 0)
        swaps = self.scratch("2", {"d": [deal(i, "2026-10-20", version=2) for i in range(0, 6, 2)]})   # SWAP only
        self.copy(swaps)
        code, s = self.publish(swaps, "--mode", "overwrite-partitions")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(sorted(self.rows().values()), [1, 1, 1, 2, 2, 2])                 # FUTURE kept, SWAP replaced

    def test_a_file_missing_in_the_target_commits_nothing(self):
        t = self.scratch("1", {"d": [deal(i, "2026-10-20") for i in range(4)]})
        code, s = self.publish(t)
        self.assertEqual(code, 1)
        self.assertIn("not in the target", s["error"])
        self.assertFalse((Path(self.target) / "_delta_log").exists())

    def test_dry_run_list_files_and_dates_not_held(self):
        t = self.scratch("1", {"d": [deal(i, "2026-10-20") for i in range(4)]})
        self.copy(t)
        code, s = self.publish(t, "--dry-run")
        self.assertEqual((code, s["status"]), (0, "ok"))
        self.assertFalse((Path(self.target) / "_delta_log").exists())
        out = []
        a = publish.add_arguments(__import__("argparse").ArgumentParser()).parse_args(["--from", str(t), "--to", self.target, "--list-files"])
        publish.run(a, out.append)
        self.assertTrue(out[0].startswith("business_date=2026-10-20/") and out[0].endswith(".parquet"))
        self.assertEqual(self.publish(t, "--dates", "2026-01-01")[0], 3)

    def test_other_columns_and_unsafe_rename_are_refused(self):
        t = self.scratch("1", {"d": [deal(i, "2026-10-19") for i in range(4)]})
        self.copy(t)
        self.assertEqual(self.publish(t)[0], 0)
        other = self.scratch("2", {"d": [deal(i, "2026-10-20") for i in range(4)]}, promote="NOTIONAL,VERSION")
        self.copy(other)
        code, s = self.publish(other)
        self.assertEqual(code, 1)
        self.assertIn("columns differ", s["error"])
        code, s = self.publish(t, "--storage-option", "AWS_S3_ALLOW_UNSAFE_RENAME=true")
        self.assertEqual(code, 1)
        self.assertIn("conditional writes", s["error"])


if __name__ == "__main__":
    unittest.main()
