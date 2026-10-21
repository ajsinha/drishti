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

"""The lake tools on S3 simulators: a Delta table written and read through deltalake's storage options, and lake publish's
conditional-PUT commit (a new table, a day restated, a missing file refused, two publishes at once), on moto (pure Python, runs on
Windows) and on the MinIO server binary (Linux only; skipped with the reason elsewhere or when no binary can be had). Each suite
prints which conditional writes its simulator honours and skips the cases it does not. Small synthetic books only.

    drishti-console/.venv/bin/python -m unittest -v tools/lake/test_s3_simulators.py
    uv run --with deltalake --with pyarrow --with pyyaml --with "moto[server]==5.2.3" python -m unittest -v tools/lake/test_s3_simulators.py
"""
import argparse
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import publish  # noqa: E402
import s3sim  # noqa: E402

BUCKET = "lake"


def deal(i: int, day: str, version: int = 1) -> dict:
    return {"TRADE_ID": f"T-{100000 + i}", "TRADE_DATE": day, "PRODUCT_TYPE": ["SWAP", "FUTURE"][i % 2], "VERSION": version,
            "NOTIONAL": 1000.0 * (i + 1)}


def quiet(*_):
    pass


class S3Suite:
    """The cases; a subclass starts its simulator in start()."""

    sim = None
    writes = None

    @classmethod
    def start(cls, root: Path):
        raise NotImplementedError

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        try:
            cls.sim = cls.start(cls.root)
        except s3sim.Skip as e:
            print(f"[S3 conditional writes] {cls.__name__}: skipped: {e}")
            cls.tmp.cleanup()
            raise unittest.SkipTest(str(e))
        cls.ep = cls.sim.endpoint
        cls.ep.bucket(BUCKET)
        cls.writes = s3sim.probe(cls.ep, BUCKET)
        print(cls.writes.report())
        cls._env = {k: os.environ.get(k) for k in ("DRISHTI_BI_STORE", "DRISHTI_DELTA_ROOT")}
        os.environ["DRISHTI_BI_STORE"] = str(cls.root / "bi-store")         # never the repository's data/
        os.environ["DRISHTI_DELTA_ROOT"] = str(cls.root / "served")

    @classmethod
    def tearDownClass(cls):
        for k, v in cls._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        cls.sim.close()
        cls.tmp.cleanup()

    def need(self, ok: bool, what: str):
        if not ok:
            self.skipTest(self.writes.not_honoured(what))

    # -- helpers ---------------------------------------------------------------------------------------------------------------

    def scratch(self, run: str, days: dict) -> Path:
        """lake build into a fresh scratch lake on local disk (the deltalake engine), as load-day.sh does."""
        src = self.root / f"in-{run}.jsonl"
        src.write_text("".join(json.dumps(d) + "\n" for docs in days.values() for d in docs), encoding="utf-8")
        a = build.build_parser().parse_args(["--from", str(src), "--kind", "trade", "--key", "TRADE_ID", "--date", "TRADE_DATE",
                                             "--partition-by", "PRODUCT_TYPE", "--promote", "NOTIONAL", "--lake", str(self.root / f"build-{run}"),
                                             "--domain", "trading", "--table", "trade_endur", "--engine", "deltalake", "--no-stamp", "-q"])
        code, s = build.run(a, quiet)
        self.assertEqual(code, 0, s.get("error"))
        return self.root / f"build-{run}" / "trading" / "trade_endur"

    def upload(self, table: Path, prefix: str):
        """aws s3 sync <table>/ s3://lake/<prefix>/ --exclude '_delta_log/*'."""
        c = self.ep.client()
        for f in table.rglob("*.parquet"):
            if "_delta_log" not in f.parts:
                c.upload_file(str(f), BUCKET, f"{prefix}/{f.relative_to(table).as_posix()}")

    def publish(self, table: Path, prefix: str, *extra) -> tuple[int, dict]:
        opts = [x for k, v in self.ep.storage_options().items() for x in ("--storage-option", f"{k}={v}")]
        a = publish.add_arguments(argparse.ArgumentParser()).parse_args(
            ["--from", str(table), "--to", f"s3://{BUCKET}/{prefix}", *opts, *extra])
        return publish.run(a, quiet)

    def table(self, prefix: str):
        from deltalake import DeltaTable
        return DeltaTable(f"s3://{BUCKET}/{prefix}", storage_options=self.ep.storage_options())

    def rows(self, prefix: str) -> dict:
        t = self.table(prefix).to_pyarrow_table(columns=["id", "doc", "business_date"]).to_pylist()
        return {(str(r["business_date"]), r["id"]): json.loads(r["doc"])["VERSION"] for r in t}

    # -- cases -----------------------------------------------------------------------------------------------------------------

    def test_a_delta_table_written_and_read_on_s3(self):
        self.need(self.writes.create_only, "If-None-Match: * (the commit's create-only PUT)")
        import pyarrow as pa
        from deltalake import write_deltalake
        uri, opts = f"s3://{BUCKET}/delta/plain", self.ep.storage_options()
        write_deltalake(uri, pa.table({"id": [1, 2, 3], "v": ["a", "b", "c"]}), storage_options=opts)
        write_deltalake(uri, pa.table({"id": [4], "v": ["d"]}), mode="append", storage_options=opts)
        t = self.table("delta/plain")
        self.assertEqual(t.version(), 1)
        self.assertEqual(sorted(t.to_pyarrow_table().column("id").to_pylist()), [1, 2, 3, 4])

    def test_lake_publish_a_new_table_then_a_day_restated(self):
        self.need(self.writes.create_only, "If-None-Match: * (the commit's create-only PUT)")
        prefix = "lake/trading/restated"
        first = self.scratch("r1", {"d1": [deal(i, "2026-10-19") for i in range(4)], "d2": [deal(i, "2026-10-20") for i in range(4)]})
        self.upload(first, prefix)
        code, s = self.publish(first, prefix, "--dry-run")
        self.assertEqual((code, s["status"]), (0, "ok"), s.get("error"))
        code, s = self.publish(first, prefix)
        self.assertEqual(code, 0, s.get("error"))
        self.assertTrue(s["create"])
        self.assertEqual(len(self.rows(prefix)), 8)
        again = self.scratch("r2", {"d2": [deal(i, "2026-10-20", version=2) for i in range(3)]})
        self.upload(again, prefix)
        before = self.table(prefix).version()
        code, s = self.publish(again, prefix, "--dates", "2026-10-20")
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(self.table(prefix).version(), before + 1)
        got = self.rows(prefix)
        self.assertEqual(sorted(v for (d, _), v in got.items() if d == "2026-10-20"), [2, 2, 2])
        self.assertEqual(len([1 for (d, _) in got if d == "2026-10-19"]), 4)

    def test_lake_publish_refuses_a_file_missing_in_s3(self):
        prefix = "lake/trading/missing"
        t = self.scratch("m1", {"d": [deal(i, "2026-10-20") for i in range(4)]})
        code, s = self.publish(t, prefix)                      # nothing uploaded: the files are checked at this endpoint
        self.assertEqual(code, 1, s)
        self.assertIn("not in the target", s["error"])
        self.assertNotIn("Contents", self.ep.client().list_objects_v2(Bucket=BUCKET, Prefix=f"{prefix}/_delta_log/"))

    def test_two_publishes_at_once_lose_nothing(self):
        self.need(self.writes.create_only and self.writes.atomic, "conditional creates atomically under a race")
        prefix = "lake/trading/at-once"
        base = self.scratch("a0", {"d": [deal(i, "2026-10-16") for i in range(2)]})
        self.upload(base, prefix)
        self.assertEqual(self.publish(base, prefix)[0], 0)
        days = ["2026-10-17", "2026-10-18", "2026-10-19"]
        tables = [self.scratch(f"a{n}", {"d": [deal(100 * n + i, d) for i in range(2)]}) for n, d in enumerate(days, 1)]
        for t in tables:
            self.upload(t, prefix)
        results = [None] * len(tables)

        def go(n):
            results[n] = self.publish(tables[n], prefix, "--mode", "append")

        threads = [threading.Thread(target=go, args=(n,)) for n in range(len(tables))]
        for th in threads:
            th.start()
        for th in threads:
            th.join(120)
        self.assertTrue(all(r and r[0] == 0 for r in results), results)
        self.assertEqual(self.table(prefix).version(), len(tables))
        self.assertEqual(sorted({d for d, _ in self.rows(prefix)}), ["2026-10-16", *days])


class MotoS3Test(S3Suite, unittest.TestCase):
    """moto's S3 server (python -m moto.server): pure Python, so it runs on the Windows QA machine too."""

    @classmethod
    def start(cls, root):
        return s3sim.Moto(root / "moto")


class MinioS3Test(S3Suite, unittest.TestCase):
    """The MinIO server binary: Linux only."""

    @classmethod
    def start(cls, root):
        return s3sim.Minio(Path(__file__).resolve().parents[2] / "tools" / "lake" / "target" / "minio" / "data")


if __name__ == "__main__":
    unittest.main()
