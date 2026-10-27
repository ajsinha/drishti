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

"""The stand-in pricing system's scripts (tools/pvgen; docs/guides/VAR_GUIDE.md section 15.12): each with --help and a --dry-run that
writes nothing; every file they write parses line by line as JSON objects and validates against its kind's contract (exactly its fields);
the morning run is the engine's (its bank figures are VarTestDataPrecisionTest's, and with --all-vectors 2.9.3's); intraday batches are the
same for a seed and different for another, marked INTRADAY with their own BATCH_ID and a COMPUTED_AT after the morning run, amended, new
and cancelled trades in either form; the Kafka publisher's messages are JSONL text of several records, a cancel a cancel line; the checker's
latest-wins, cancels and old updates.

    python -m pytest tools/pvgen/test_pvgen.py -q
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import check  # noqa: E402
import common as C  # noqa: E402
import intraday  # noqa: E402
import morning  # noqa: E402
import publish_kafka  # noqa: E402


def run(main, *argv) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = main(list(argv))
    return rc, out.getvalue()


def every_line(folder: Path) -> list[tuple[str, dict]]:
    """Every line of every JSONL file under folder, parsed: (its kind, the object)."""
    out = []
    for f in sorted(folder.rglob("*.jsonl*")):
        rows = C.read_jsonl(f)
        kind = C.kind_of(rows)
        out += [(kind, r) for r in rows]
    return out


class MorningTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = Path(cls.tmp.name)
        cls.rc, cls.said = run(morning.main, "--out", str(cls.out), "--date", "2026-10-27")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_help_and_a_dry_run_that_writes_nothing(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()):
            morning.main(["--help"])
        with tempfile.TemporaryDirectory() as t:
            rc, said = run(morning.main, "--out", t, "--dry-run")
            self.assertEqual(rc, 0)
            self.assertIn("dry run: nothing written", said)
            self.assertEqual(list(Path(t).iterdir()), [])

    def test_every_line_is_a_json_object_of_its_contract(self):
        self.assertEqual(self.rc, 0)
        rows = every_line(self.out)
        kinds = {k for k, _ in rows}
        self.assertEqual(kinds, {"scenario-pv", "pnl-vector", "fx-scenario"})
        for kind, r in rows:
            self.assertEqual(C.validate(kind, r), [], r.get(C.KEYS[kind]))
            self.assertEqual(r["BUSINESS_DATE"], "2026-10-27")
        trades = [r for k, r in rows if k != "fx-scenario"]
        self.assertEqual(len(trades), 9572)
        self.assertEqual({r["RUN_KIND"] for r in trades}, {"FULL"})
        self.assertEqual({r["VALUATION_DATE"] for k, r in rows if k == "scenario-pv"}, {"2026-10-27"})
        self.assertEqual(sum(1 for k, _ in rows if k == "scenario-pv"), 1339)

    def test_the_checker_agrees_with_the_engine_on_the_morning_run(self):
        """The bank of the test data's date as the engine computes it (VarTestDataPrecisionTest, VAR_GUIDE.md section 15.9)."""
        rows = check.load_morning(self.out / "morning-2026-10-27")
        var, es = C.measures(C.expected(rows, [], "2026-09-30")["BANK"])      # the trades live on the test data's date
        self.assertEqual((round(var), round(es)), (112_041_646, 160_143_809))

    def test_every_system_as_p_and_l_vectors_is_2_9_3(self):
        with tempfile.TemporaryDirectory() as t:
            rc, said = run(morning.main, "--out", t, "--all-vectors", "--gzip")
            self.assertEqual(rc, 0)
            folder = Path(t) / "morning-2026-09-30"
            self.assertEqual(sorted(f.name for f in folder.iterdir()), ["fx-scenario.jsonl.gz", "pnl-vector.jsonl.gz"])
            rows = check.load_morning(folder)
            self.assertEqual(len(rows["pnl-vector"]), 9572)
            var, es = C.measures(C.expected(rows, [], "2026-09-30")["BANK"])
            self.assertEqual((round(var), round(es)), (112_035_906, 160_132_107))


class GrainTest(unittest.TestCase):
    """Per position (--grain position, --position-systems): each record a position summed from its trades, naming them; every record
    says what it is first (VALUE_TYPE, GRAIN, its id, CURRENCY, SOURCE_SYSTEM); the bank and every desk the same as per trade, to the
    cent (docs/guides/VAR_GUIDE.md section 15.13)."""

    def test_the_same_trades_as_their_positions_give_the_same_bank_and_desks(self):
        with tempfile.TemporaryDirectory() as t:
            rc, _ = run(morning.main, "--out", t + "/trade")
            rc2, said = run(morning.main, "--out", t + "/position", "--grain", "position")
            self.assertEqual((rc, rc2), (0, 0))
            self.assertIn("per position: ENDUR, MUREX, CALYPSO", said)
            by_trade = check.load_morning(Path(t) / "trade" / "morning-2026-09-30")
            by_position = check.load_morning(Path(t) / "position" / "morning-2026-09-30")
            for kind in ("scenario-pv", "pnl-vector"):
                for r in by_position[kind]:
                    self.assertEqual(list(r)[:5], ["VALUE_TYPE", "GRAIN", "POSITION_ID", "CURRENCY", "SOURCE_SYSTEM"])
                    self.assertEqual(C.validate(kind, r), [])
                    self.assertTrue(r["TRADE_IDS"])
            self.assertLess(len(by_position["pnl-vector"]) + len(by_position["scenario-pv"]), 9572)
            a = C.expected(by_trade, [], "2026-09-30")
            b = C.expected(by_position, [], "2026-09-30")
            self.assertEqual(sorted(a), sorted(b))
            for scope in a:
                for x, y in zip(a[scope], b[scope]):
                    self.assertAlmostEqual(x, y, delta=0.01)
            # the intraday updates follow each record's grain
            rc, said = run(intraday.main, "--out", t + "/i", "--grain", "position", "--batches", "1", "--trades-per-batch", "6")
            rows = every_line(Path(t) / "i")
            self.assertTrue(rows)
            for kind, r in rows:
                self.assertEqual(r["GRAIN"], "POSITION")
                self.assertEqual(C.validate(kind, r), [])

    def test_the_generator_prints_the_pins_of_its_data(self):
        import make_var_data as M
        text = M.inputs_config(("ENDUR",), ("MUREX",))
        self.assertIn("ENDUR:   { form: pv,  grain: trade }", text)
        self.assertIn("MUREX:   { form: pnl, grain: position }", text)
        self.assertIn("default: { form: any, grain: any }", text)


class IntradayTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        base = Path(cls.tmp.name)
        cls.a, cls.b, cls.c = base / "a", base / "b", base / "c"
        run(intraday.main, "--out", str(cls.a), "--batches", "4", "--trades-per-batch", "12", "--seed", "3")
        run(intraday.main, "--out", str(cls.b), "--batches", "4", "--trades-per-batch", "12", "--seed", "3")
        run(intraday.main, "--out", str(cls.c), "--batches", "4", "--trades-per-batch", "12", "--seed", "4")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def files(self, d: Path) -> dict[str, str]:
        return {f.name: f.read_text(encoding="utf-8") for f in sorted(d.rglob("*.jsonl"))}

    def test_the_same_seed_makes_the_same_batches_and_another_does_not(self):
        self.assertTrue(self.files(self.a))
        self.assertEqual(self.files(self.a), self.files(self.b))
        self.assertNotEqual(self.files(self.a), self.files(self.c))

    def test_every_line_is_an_intraday_row_of_its_contract(self):
        rows = every_line(self.a)
        m = C.Morning(C.ROOT / "data" / "testdata", ("ENDUR",))
        batches, at = set(), []
        for kind, r in rows:
            self.assertEqual(C.validate(kind, r), [], r)
            self.assertEqual(r["RUN_KIND"], "INTRADAY")
            self.assertTrue(r["BATCH_ID"].startswith("I-2026-09-30-"))
            self.assertGreater(r["COMPUTED_AT"], m.at)
            batches.add(r["BATCH_ID"])
            at.append(r["COMPUTED_AT"])
            self.assertTrue(r[C.KEYS[kind]].endswith(r["BATCH_ID"]))
            if r["TRADE_STATUS"] == "CANCELLED":
                self.assertIsNone(r[C.VECTORS[kind]])
            else:
                self.assertEqual(len(r[C.VECTORS[kind]]), 509)
            # the form follows the system: Endur as scenario PVs, the others as P&L vectors
            if r["TRADE_STATUS"] != "CANCELLED":
                self.assertEqual(kind, "scenario-pv" if r["SOURCE_SYSTEM"] == "ENDUR" else "pnl-vector", r["TRADE_ID"])
        self.assertEqual(len(batches), 4)
        self.assertEqual({r["TRADE_STATUS"] for _, r in rows} >= {"AMENDED"}, True)

    def test_a_plan_amended_new_and_cancelled_in_either_form_and_a_dry_run(self):
        with tempfile.TemporaryDirectory() as t:
            rc, said = run(intraday.main, "--out", t, "--batches", "1", "--plan", "amend,new,cancel", "--all-vectors", "--systems", "ENDUR")
            self.assertEqual(rc, 0)
            rows = every_line(Path(t))
            self.assertEqual([r["TRADE_STATUS"] for _, r in rows][1:], ["NEW", "CANCELLED"])
            self.assertEqual({k for k, r in rows if r["TRADE_STATUS"] != "CANCELLED"}, {"pnl-vector"})   # Endur's updates as vectors
            self.assertEqual(rows[2][0], "scenario-pv")                    # a cancel in the form the trade has (Endur's: scenario PVs)
            self.assertTrue(rows[1][1]["TRADE_ID"].startswith("9"))         # a new trade in Endur's range
            rc, said = run(intraday.main, "--out", t + "/none", "--batches", "2", "--dry-run")
            self.assertIn("dry run: nothing written", said)
            rc, said = run(intraday.main, "--out", t + "/none", "--batches", "2", "--first", "21", "--dry-run")   # a second run goes on
            self.assertIn("I-2026-09-30-021:", said)
            self.assertIn("I-2026-09-30-022:", said)
            self.assertFalse(Path(t, "none").exists())

    def test_the_committed_sample_is_a_batch_of_the_contract(self):
        f = C.ROOT / "config" / "packs" / "market-risk" / "samples" / "intraday" / "I-2026-09-30-001.scenario-pv.jsonl"
        rows = C.read_jsonl(f)
        self.assertEqual([r["TRADE_STATUS"] for r in rows].count("CANCELLED"), 1)
        self.assertEqual([r["TRADE_STATUS"] for r in rows].count("NEW"), 1)
        for r in rows:
            self.assertEqual(C.validate("scenario-pv", r), [])
        with tempfile.TemporaryDirectory() as t:                          # and it is what the script writes, byte for byte
            rc, _ = run(intraday.main, "--batches", "1", "--trades-per-batch", "5", "--systems", "ENDUR",
                        "--plan", "amend,amend,amend,new,cancel", "--out", t)
            self.assertEqual(rc, 0)
            self.assertEqual(Path(t, "intraday-2026-09-30", f.name).read_bytes(), f.read_bytes())


class PublisherAndCheckerTest(unittest.TestCase):

    def test_each_message_is_jsonl_of_several_records_a_cancel_a_cancel_line(self):
        with tempfile.TemporaryDirectory() as t:
            save = Path(t) / "sent.jsonl"
            rc, said = run(publish_kafka.main, "--dry-run", "--rate", "10", "--seconds", "4", "--per-message", "3",
                           "--mix", "amend=1,cancel=1", "--save", str(save))
            self.assertEqual(rc, 0)
            lines = [x for x in said.splitlines() if x.startswith("drishti.risk.pv-updates key=P-2026-09-30-")]
            self.assertEqual(len(lines), 40)                                  # 40 messages of 3 records
            sent = C.read_jsonl(save)
            self.assertEqual(len(sent), 120)
            self.assertTrue(any(r["TRADE_STATUS"] == "CANCELLED" and (r.get("SCENARIO_PV") or r.get("PNL")) is None for r in sent))
        morning = C.Morning(C.ROOT / "data" / "testdata", ("ENDUR",))
        ups = C.Updates(morning, 7, C.mix_of("amend=1,new=1,cancel=1"), ("ENDUR",))
        for key, value, rows in publish_kafka.messages(ups, 5, 4, morning.day):
            self.assertTrue(key.startswith("P-2026-09-30-"))                  # the key names the message, never a trade
            parsed = [json.loads(x) for x in value.splitlines()]               # JSONL text: one JSON object a line
            self.assertEqual(parsed, rows)
            self.assertEqual(len(parsed), 4)
            for r in parsed:
                kind = "scenario-pv" if "SCENARIO_PV_ID" in r else "pnl-vector"
                self.assertEqual(C.validate(kind, r), [])

    def test_the_checker_latest_wins_a_cancel_removes_an_old_update_is_ignored(self):
        v = [float(j) for j in range(509)]
        morning = {"pnl-vector": [
            {"TRADE_ID": "T1", "VECTOR_ID": "PV-T1", "PNL": v, "TRADE_STATUS": "NEW", "DESK": "D", "COMPUTED_AT": "2026-09-30T22:30:00Z",
             "MATURITY_DATE": "2030-01-01"},
            {"TRADE_ID": "T2", "VECTOR_ID": "PV-T2", "PNL": v, "TRADE_STATUS": "NEW", "DESK": "D", "COMPUTED_AT": "2026-09-30T22:30:00Z",
             "MATURITY_DATE": "2030-01-01"}]}
        upd = [{"TRADE_ID": "T1", "VECTOR_ID": "a", "PNL": [2 * x for x in v], "TRADE_STATUS": "AMENDED", "DESK": "D",
                "COMPUTED_AT": "2026-09-30T23:00:00Z", "MATURITY_DATE": "2030-01-01"},
               {"TRADE_ID": "T1", "VECTOR_ID": "b", "PNL": [9 * x for x in v], "TRADE_STATUS": "AMENDED", "DESK": "D",
                "COMPUTED_AT": "2026-09-30T22:59:00Z", "MATURITY_DATE": "2030-01-01"},                                  # older: ignored
               {"TRADE_ID": "T2", "VECTOR_ID": "c", "PNL": None, "TRADE_STATUS": "CANCELLED", "DESK": "D",
                "COMPUTED_AT": "2026-09-30T23:01:00Z", "MATURITY_DATE": "2030-01-01"},
               {"TRADE_ID": "T3", "VECTOR_ID": "d", "PNL": [5 * x for x in v], "TRADE_STATUS": "NEW", "DESK": "D",
                "COMPUTED_AT": "2026-09-30T10:00:00Z", "MATURITY_DATE": "2030-01-01"}]                                   # before the morning
        bank = C.expected(morning, upd, "2026-09-30")["BANK"]
        self.assertEqual(bank, [2 * x for x in v])


if __name__ == "__main__":
    unittest.main()
