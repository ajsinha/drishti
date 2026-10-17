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

"""Lake maintenance as Admin -> Lakes and the CLI share it: the YAML round trip, the ownership rule (only tables lake build built),
the scheduler (cron text, the friendly words, catch-up after a stop, after-build), the history, dry-run estimates, cancel and the lock.

    uv run --with deltalake --with pyarrow --with pyyaml python -m unittest tools/lake/test_maintain_admin.py
"""
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import maintain  # noqa: E402
import maintconfig  # noqa: E402
import maintschedule as ms  # noqa: E402
import ownership  # noqa: E402

NY = ZoneInfo("America/New_York")


def quiet(fn, *a, **kw):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*a, **kw)


class YamlTest(unittest.TestCase):

    def test_round_trip_keeps_every_key_and_per_table_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "lakes" / "lake-maintenance.yaml"
            cfg = {"schedule": {"at": "02:30", "zone": "America/New_York"}, "custom": {"kept": True},
                   "lakes": [{"root": tmp, "domains": ["*"], "vacuum-hours": 200}]}
            maintconfig.set_table_settings(cfg, tmp, "trading/trade", {"keep-business-days": 30, "target-file-mb": 64})
            maintconfig.lake_entry(cfg, tmp)["schedules"] = [{"name": "nightly", "when": "30 2 * * 2-6", "tables": ["trading/trade"], "paused": False}]
            maintconfig.save(path, cfg)
            again = maintconfig.load(path)
            self.assertEqual(again, cfg)
            self.assertTrue(path.read_text().startswith("# Delta Lake maintenance"))
            s = maintconfig.table_settings(again, tmp, "trading/trade")
            self.assertEqual((s["keep-business-days"], s["target-file-mb"], s["checkpoint"], s["vacuum-hours"]), (30, 64, True, 200))
            self.assertEqual(s["own"], ["keep-business-days", "target-file-mb"])
            self.assertEqual(maintconfig.table_settings(again, tmp, "risk/var")["keep-business-days"], None)     # defaults
            self.assertEqual(maintconfig.schedules(again)[0]["root"], tmp)

    def test_settings_are_checked_and_short_vacuum_warns(self):
        out, warn = maintconfig.clean_settings({"keep-business-days": "", "target-file-mb": "64", "checkpoint": "false", "vacuum-hours": "24"})
        self.assertEqual(out, {"keep-business-days": None, "target-file-mb": 64, "checkpoint": False, "vacuum-hours": 24})
        self.assertTrue(warn and "168" in warn[0])
        self.assertEqual(maintconfig.clean_settings({"vacuum-hours": "168"})[1], [])
        with self.assertRaises(ValueError):
            maintconfig.clean_settings({"keep-business-days": "0"})

    def test_the_cli_command_for_a_selection(self):
        line = maintconfig.command(Path("data/lakes/lake-maintenance.yaml"), {"data/delta": ["trading/trade", "risk/var"]}, dry_run=True)
        self.assertEqual(line, "python3 tools/drishti.py lake maintain --config data/lakes/lake-maintenance.yaml --once "
                               "--tables trading/trade,risk/var --dry-run")


class ScheduleTest(unittest.TestCase):

    def test_cron_and_friendly_words(self):
        c = ms.Cron("30 2 * * 2-6")
        self.assertEqual(ms.friendly("30 2 * * 2-6"), "Tue–Sat 02:30")
        self.assertEqual(ms.friendly("0 7 * * 1-5"), "Mon–Fri 07:00")
        self.assertEqual(ms.friendly("0 7 * * *"), "every day 07:00")
        self.assertEqual(ms.friendly("*/15 * * * *"), "cron */15 * * * *")
        self.assertEqual(ms.cron_of(["tue", "wed", "thu", "fri", "sat"], "02:30"), "30 2 * * 2,3,4,5,6")
        sat = datetime(2026, 10, 17, 12, 0, tzinfo=NY)                             # a Saturday
        self.assertEqual(c.next(sat), datetime(2026, 10, 20, 2, 30, tzinfo=NY))     # Sunday and Monday skipped
        self.assertEqual(c.previous(sat), datetime(2026, 10, 17, 2, 30, tzinfo=NY))
        for bad in ("30 2 * *", "61 2 * * *", "30 2 * * funday"):
            with self.assertRaises(ms.CronError):
                ms.Cron(bad)

    def test_a_missed_run_is_caught_up_once_at_start_and_paused_never_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            s = {"root": "/lake", "name": "nightly", "when": "30 2 * * *", "tables": ["trading/trade"]}
            sched = ms.Scheduler(Path(tmp) / "state.json")
            t0 = datetime(2026, 10, 14, 12, 0, tzinfo=NY)
            self.assertEqual(sched.due([s], t0, {}), [])                            # new: remembered, runs from its next time
            sched.started = False
            first = datetime(2026, 10, 15, 2, 30, 20, tzinfo=NY)
            (got, why), = sched.due([s], first, {})
            self.assertTrue(why.startswith("scheduled"))
            sched.ran(s, first, {})
            self.assertEqual(sched.due([s], first, {}), [])                         # not twice for the same time
            later = ms.Scheduler(Path(tmp) / "state.json")                          # the console was down two nights
            (got, why), = later.due([s], datetime(2026, 10, 17, 9, 0, tzinfo=NY), {})
            self.assertTrue(why.startswith("catch-up"), why)
            later.ran(s, datetime(2026, 10, 17, 9, 0, tzinfo=NY), {})
            self.assertEqual(later.due([s], datetime(2026, 10, 17, 9, 1, tzinfo=NY), {}), [])   # one catch-up for both nights
            self.assertEqual(later.due([{**s, "paused": True}], datetime(2026, 10, 18, 3, 0, tzinfo=NY), {}), [])

    def test_after_build_runs_when_a_table_is_built_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            s = {"root": "/lake", "name": "after", "when": "after-build", "tables": ["trading/trade"]}
            sched = ms.Scheduler(Path(tmp) / "state.json")
            now = datetime(2026, 10, 17, 9, 0, tzinfo=NY)
            built = {"/lake": {"trading/trade": "2026-10-17T08:00:00", "risk/var": "x"}}
            self.assertEqual(sched.due([s], now, built), [])
            self.assertEqual(sched.due([s], now, built), [])
            built["/lake"]["trading/trade"] = "2026-10-17T09:30:00"
            (got, why), = sched.due([s], now, built)
            self.assertIn("trading/trade", why)
            sched.ran(s, now, built)
            self.assertEqual(sched.due([s], now, built), [])


class LakeTest(unittest.TestCase):
    """Tiny lakes made with lake build itself (deltalake), so the build record is the real one."""

    def build(self, root: Path, kind: str, n: int = 40, days=("2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16")) -> str:
        src = root.parent / f"{kind}.jsonl"
        for k, d in enumerate(days):                                     # one build per day: several small files
            src.write_text("\n".join(json.dumps({"TRADE_ID": f"{kind}-{d}-{i}", "TRADE_DATE": d, "QUANTITY": i}) for i in range(n)))
            code = quiet(build.main, ["--from", str(src), "--lake", str(root), "--domain", "trading", "--kind", kind, "--key", "TRADE_ID",
                                      "--date", "TRADE_DATE", "--engine", "deltalake", "--pack-name", "trading", "--mode", "append", "-q"])
            self.assertEqual(code, 0)
        return str(root / "trading" / kind)

    def foreign(self, root: Path) -> str:
        import pyarrow as pa
        from deltalake import write_deltalake
        uri = str(root / "risk" / "published")
        for d in (date(2026, 10, 15), date(2026, 10, 16)):
            write_deltalake(uri, pa.table({"id": ["A", "B"], "doc": ["{}", "{}"], "business_date": pa.array([d, d], pa.date32())}),
                            mode="append", partition_by=["business_date"])
        return uri

    def test_only_tables_lake_build_built_are_maintained(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "lake"
            self.build(root, "trade")
            pub = self.foreign(root)
            reg = ownership.built(root)
            self.assertEqual(sorted(reg), ["trading/trade"])
            self.assertEqual(reg["trading/trade"]["pack"], "trading")
            cfg = {"lakes": [{"root": str(root), "checkpoint": False, "vacuum-hours": None}]}
            report = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16))
            by = {t["name"]: t for t in report["tables"]}
            self.assertEqual(report["failures"], 0)
            self.assertIn("maintained by its publisher", by["risk/published"]["skipped"])
            self.assertEqual(by["trading/trade"]["after"]["files"], 4)               # one file per date after compaction
            from deltalake import DeltaTable
            self.assertEqual(len(maintain.add_actions(DeltaTable(pub))), 2)            # the foreign table untouched
            named = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16), tables_spec=["risk/published"])
            self.assertEqual(named["failures"], 1)
            self.assertIn("refused", named["tables"][0]["error"])
            forced = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16), tables_spec=["risk/published"], force_foreign=True)
            self.assertEqual(forced["failures"], 0)
            self.assertTrue(forced["tables"][0]["forced"])

    def test_dry_run_estimates_and_changes_nothing_then_the_run_signals_and_keeps_history(self):
        from deltalake import DeltaTable
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "lake"
            uri = self.build(root, "trade")
            self.assertEqual(len(maintain.add_actions(DeltaTable(uri))), 4)
            self.build(root, "trade", days=("2026-10-15", "2026-10-16"))                  # two more small files
            cfg = {"history": str(Path(tmp) / "data" / "lakes" / "maintenance-history.jsonl"),
                   "lakes": [{"root": str(root), "max-drop-share": 0.9, "tables": {"trading/trade": {"keep-business-days": 1, "vacuum-hours": 0}}}]}
            dry = quiet(maintain.run_detailed, cfg, True, date(2026, 10, 16))
            t = dry["tables"][0]
            self.assertEqual(t["before"]["files"], 6)
            self.assertEqual(t["retention"]["dates"], ["2026-10-13", "2026-10-14"])     # one business day back from Friday: Thu kept
            self.assertEqual((t["estimate"]["files"], t["estimate"]["dates"]), (2, 2))
            self.assertEqual(len(maintain.add_actions(DeltaTable(uri))), 6)             # nothing changed
            real = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16))
            t = real["tables"][0]
            self.assertEqual(t["after"]["files"], 2)
            self.assertTrue(maintain.changed(t))
            (pack, table, body), = maintain.signal_bodies(real["tables"], "run-1")
            self.assertEqual((pack, table, body["kind"], body["businessDate"], body["rows"], body["source"]),
                             ("trading", "trading/trade", "trade", "2026-10-16", 80, "lake maintain"))
            maintconfig.record(maintconfig.history_path(cfg), maintain.history_entry(real, False, "ash", "nightly"))
            maintconfig.record(maintconfig.history_path(cfg), maintain.history_entry(dry, True, "ash"))
            rows = maintconfig.history(maintconfig.history_path(cfg))
            self.assertEqual([r["dryRun"] for r in rows], [True, False])                  # newest first
            self.assertEqual(rows[1]["outcome"], "ok")
            self.assertIn("schedule nightly", maintconfig.history_text(rows))
            cfg_file = Path(tmp) / "m.yaml"
            maintconfig.save(cfg_file, cfg)
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(maintain.main(["--config", str(cfg_file), "--history", "--json"]), 0)
            self.assertEqual(len(json.loads(out.getvalue())), 2)

    def test_cancel_stops_between_tables_and_the_lock_keeps_runs_apart(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "lake"
            self.build(root, "trade", n=5, days=("2026-10-16",))
            self.build(root, "deal", n=5, days=("2026-10-16",))
            cfg = {"lakes": [{"root": str(root), "checkpoint": False, "vacuum-hours": None}]}
            seen = []
            report = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16), cancel=lambda: bool(seen),
                           on_event=lambda ev, r: seen.append((ev, r["name"])) if ev == "done" else None)
            self.assertTrue(report["cancelled"])
            self.assertEqual(len(report["tables"]), 1)
            self.assertEqual(maintain.outcome(report), "cancelled")
            with build.LakeLock(root):
                held = quiet(maintain.run_detailed, cfg, False, date(2026, 10, 16))
            self.assertEqual(held["failures"], 2)
            self.assertIn("locked", held["tables"][0]["error"])


if __name__ == "__main__":
    unittest.main()
