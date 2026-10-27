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

"""Iceberg lakes in lake maintain and Admin -> Lakes (Delta stays the default): the Iceberg tables found beside Delta ones and listed with
their format, the command each gets (the floor of the risk domains raised and passed as --floor-days, orphan files at 72 hours at least,
a dry run), the ownership rule, the history; and, where Java and the server's exec jar (or DRISHTI_ICEBERG_CLASSPATH) are at hand, a real
table built with lake build --format iceberg, an orphan listed by a dry run and removed by the run.

    drishti-console/.venv/bin/python -m pytest tools/lake/test_maintain_iceberg.py -q
"""
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from datetime import date
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import laketables  # noqa: E402
import maintain  # noqa: E402
import maintain_iceberg  # noqa: E402
import maintconfig  # noqa: E402
import ownership  # noqa: E402
from test_vectors import iceberg_loader  # noqa: E402


def quiet(fn, *a, **kw):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*a, **kw)


def fake_table(root: Path, name: str, days=("2026-10-15", "2026-10-16"), owned: bool = True) -> Path:
    """An Iceberg table's folders as the loader leaves them: a metadata file whose current snapshot counts rows and files, a data folder a
    business date."""
    t = root / name
    (t / "metadata").mkdir(parents=True)
    snap = {"snapshot-id": 7, "timestamp-ms": 1_790_000_000_000, "summary": {"operation": "overwrite", "total-records": "40", "total-data-files": "2"}}
    (t / "metadata" / "v2.metadata.json").write_text(json.dumps({"format-version": 2, "table-uuid": "u-1", "location": str(t),
                                                                 "current-snapshot-id": 7, "snapshots": [snap]}))
    (t / "metadata" / "version-hint.text").write_text("2")
    for d in days:
        (t / "data" / f"business_date={d}").mkdir(parents=True)
        (t / "data" / f"business_date={d}" / "00000-a.parquet").write_bytes(b"x" * 100)
    if owned:
        ownership.record(root, {"table": str(t), "pack": "market-risk", "kind": name.split("/")[1], "finishedAt": "2026-10-16T10:00:00", "run": "r1"})
    return t


class FoundAndListedTest(unittest.TestCase):

    def test_an_iceberg_table_is_listed_with_its_format_beside_delta_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fake_table(root, "risk/pnl-vector")
            got = laketables.tables(root)
            self.assertEqual([t["table"] for t in got], ["risk/pnl-vector"])
            t = got[0]
            self.assertEqual((t["format"], t["rows"], t["files"], t["dates"], t["latestDate"]), ("iceberg", 40, 2, 2, "2026-10-16"))
            self.assertEqual(laketables.detail(root / "risk" / "pnl-vector")["format"], "iceberg")
            self.assertEqual(maintain_iceberg.tables(str(root), ["*"]), [str(root / "risk" / "pnl-vector")])
            self.assertEqual(maintain_iceberg.tables(str(root), ["trading"]), [])


class CommandTest(unittest.TestCase):

    def command(self, conf, dry=False, floor=0):
        with mock.patch("stores.loader_command", lambda data, jar=None, java=None, main=None: ["java", main]):
            return maintain_iceberg.command("/l/risk/pnl-vector", conf, date(2026, 10, 16), dry, False, floor, Path("/d"))

    def test_the_settings_become_the_maintenance_options(self):
        cmd = self.command({"keep-business-days": 260, "compact": True, "vacuum-hours": 168, "orphan-hours": 12,
                            "storage-options": {"AWS_ENDPOINT_URL": "http://127.0.0.1:9000", "AWS_REGION": "us-east-1"}}, dry=True, floor=2557)
        self.assertEqual(cmd[:2], ["java", maintain_iceberg.MAINT])
        text = " ".join(cmd)
        for want in ("/l --domain risk --kinds pnl-vector --engine native --as-of 2026-10-16 --json", "--keep-days 260", "--relayout",
                     "--expire-hours 168", "--remove-orphans --older-than-hours 72", "--floor-days 2557", "--dry-run",
                     "--set s3.endpoint=http://127.0.0.1:9000", "--set s3.region=us-east-1"):
            self.assertIn(want, text)
        none = " ".join(self.command({"vacuum-hours": None, "remove-orphans": False}))
        self.assertIn("--expire-hours -1", none)
        self.assertNotIn("--remove-orphans", none)
        self.assertNotIn("--keep-days", none)

    def test_the_floor_days_of_the_risk_domains(self):
        self.assertEqual(maintconfig.floor_days("risk/pnl-vector"), 2557)
        self.assertEqual(maintconfig.floor_days("risk-results/var-official"), 2557)
        self.assertEqual(maintconfig.floor_days("trading/trade_endur"), 0)
        self.assertEqual(maintconfig.floor_days("risk/x", {"risk": 0}), 0)

    def test_orphan_hours_under_72_are_refused(self):
        with self.assertRaisesRegex(ValueError, "at least 72 hours"):
            maintconfig.clean_settings({"orphan-hours": "24"})
        self.assertEqual(maintconfig.clean_settings({"orphan-hours": "96"})[0], {"orphan-hours": 96})


class RunTest(unittest.TestCase):
    """lake maintain over a lake with an Iceberg table: dispatched to the Iceberg maintenance with the floor, kept in the history."""

    def test_dispatch_floor_ownership_and_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "ice"
            fake_table(root, "risk/pnl-vector")
            fake_table(root, "risk/published", owned=False)
            seen = []

            def fake_run(cmd, **kw):
                seen.append(cmd)
                line = {"table": "risk/pnl-vector", "dryRun": "--dry-run" in cmd, "before": {"files": 2, "mb": 1.0, "dates": 2},
                        "after": {"files": 2, "mb": 1.0, "dates": 2, "latestDate": "2026-10-16", "latestRows": 20},
                        "keepDays": {"wouldRemove": []}, "orphans": {"olderThanHours": 72, "files": 1, "wouldRemove": 1, "first": ["x"]},
                        "floor": {"days": 2557, "keepsDatesFrom": "2019-10-17", "expireHours": 61368}, "expireHours": 61368}
                return subprocess.CompletedProcess(cmd, 0, json.dumps(line) + "\n", "")
            cfg = {"lakes": [{"root": str(root), "format": "iceberg", "keep-business-days": 10, "vacuum-hours": 168}],
                   "history": str(Path(tmp) / "history.jsonl")}
            with mock.patch("maintain_iceberg.subprocess.run", fake_run), mock.patch("stores.loader_command", lambda *a, **k: ["java"]):
                report = quiet(maintain.run_detailed, cfg, True, date(2026, 10, 16))
            by = {t["name"]: t for t in report["tables"]}
            self.assertEqual(report["failures"], 0)
            self.assertIn("maintained by its publisher", by["risk/published"]["skipped"])     # the ownership rule, as for Delta
            t = by["risk/pnl-vector"]
            self.assertEqual(t["format"], "iceberg")
            self.assertTrue(any("raised to 1827" in n for n in t["retention-floor"]))         # 7 years of business dates
            self.assertEqual(len(seen), 1)
            text = " ".join(seen[0])
            self.assertIn("--keep-days 1827", text)
            self.assertIn("--floor-days 2557", text)
            self.assertIn("--expire-hours 61368", text)                                       # vacuum-hours raised to 7 years too
            self.assertIn("--dry-run", text)
            self.assertEqual(t["orphans"]["wouldRemove"], 1)
            entry = maintain.history_entry(report, True, "admin")
            kept = {x["name"]: x for x in entry["tables"]}["risk/pnl-vector"]
            self.assertEqual((kept["format"], kept["orphans"]["wouldRemove"], kept["floor"]["days"]), ("iceberg", 1, 2557))


@unittest.skipUnless(iceberg_loader(), "needs Java and the server's exec jar (or DRISHTI_ICEBERG_CLASSPATH)")
class RealTableTest(unittest.TestCase):
    """A table lake build --format iceberg wrote: a dry run lists an old orphan and drops nothing, the run removes it."""

    def test_dry_run_then_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "ice"
            os.environ["DRISHTI_BI_STORE"] = str(Path(tmp) / "bi")
            try:
                src = Path(tmp) / "t.jsonl"
                src.write_text("\n".join(json.dumps({"TRADE_ID": f"T-{d}-{i}", "TRADE_DATE": d, "QUANTITY": i})
                                         for d in ("2026-10-14", "2026-10-15", "2026-10-16") for i in range(10)))
                code = quiet(build.main, ["--from", str(src), "--lake", str(root), "--domain", "trading", "--kind", "trade", "--key", "TRADE_ID",
                                          "--date", "TRADE_DATE", "--format", "iceberg", "--pack-name", "trading", "-q", "--no-stamp"])
                self.assertEqual(code, 0)
            finally:
                os.environ.pop("DRISHTI_BI_STORE", None)
            dead = root / "trading" / "trade" / "data" / "dead.parquet"
            dead.write_bytes(b"left by a load that died")
            old = time.time() - 100 * 3600
            os.utime(dead, (old, old))
            cfg = {"lakes": [{"root": str(root), "keep-business-days": 2, "vacuum-hours": 168}], "history": str(Path(tmp) / "h.jsonl")}
            dry = quiet(maintain.run_detailed, cfg, True, date(2026, 10, 16), force_drop=True)
            t = dry["tables"][0]
            self.assertEqual(dry["failures"], 0, t)
            self.assertEqual((t["format"], t["orphans"]["wouldRemove"], t["retention"]["dates"]), ("iceberg", 1, ["2026-10-14"]))
            self.assertTrue(dead.exists())
            done = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16), force_drop=True)
            t = done["tables"][0]
            self.assertEqual(done["failures"], 0, t)
            self.assertEqual((t["orphans"]["removed"], t["retention"]["removed"], t["after"]["dates"]), (1, 1, 2))
            self.assertFalse(dead.exists())


if __name__ == "__main__":
    unittest.main()
