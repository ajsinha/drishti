#!/usr/bin/env python3
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

"""The Drishti*net soak's checker without servers (tools/bench/soak_check.py): history answers against the reference (rows, changed
flags, masks, named partial answers, DRS-1013), kill windows, the memory slope and the verdict; and users.py's history requests."""
import copy
import random
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import soak_check as sc  # noqa: E402
import users  # noqa: E402

DOWN = ["drishti-c"]


def row(date, qty, changed=(), trader="•••"):
    return {"date": date, "dataDate": date, "carried": False, "deleted": False, "values": {"QUANTITY": qty, "TRADER_ID": trader},
            "parts": {"LEGS": 2}, "changed": list(changed), "changes": None, "generation": 7, "source": "trade-murex"}


def answer(rows, partial=False, problems=(), masked=True, **extra):
    return {"ref": {"kind": "trade", "id": "MX-50000001"}, "window": {"from": rows[0]["date"] if rows else None, "to": "2026-10-16",
            "baseline": "2026-10-13", "knownAt": None, "missing": []},
            "columns": [{"path": "QUANTITY", "masked": False}, {"path": "TRADER_ID", "masked": masked}],
            "rows": rows, "partial": partial, "problems": list(problems), "tookMs": 12, "cache": "miss", **extra}


FULL = [row("2026-10-14", 100.0), row("2026-10-15", 120.0, ["QUANTITY"]), row("2026-10-16", 120.0)]
REF = sc.history_essence(answer(FULL))


class HistoryJudgeTest(unittest.TestCase):
    def test_an_equal_answer_is_right_whatever_its_timings_generations_and_sources(self):
        other = answer([{**r, "generation": 99, "source": "peer"} for r in FULL], tookMs=900, cache="hit")
        self.assertEqual("right", sc.judge_history(REF, 200, other, []))

    def test_different_rows_changed_flags_or_masks_are_wrong(self):
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL[:1] + [row("2026-10-15", 121.0, ["QUANTITY"])] + FULL[2:]), []))
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL[:1] + [row("2026-10-15", 120.0)] + FULL[2:]), []))
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL, masked=False), []))

    def test_a_silent_short_answer_is_wrong_even_while_a_node_is_down(self):
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL[1:]), DOWN))

    def test_a_partial_answer_naming_the_down_node_with_rows_of_the_reference_is_partial(self):
        p = [{"code": "DRS-5052", "source": "drishti-c", "days": ["2026-10-14"], "message": "drishti-c cannot be reached now"}]
        later = [row("2026-10-15", 120.0), row("2026-10-16", 120.0)]      # changed differs after a gap: not compared
        self.assertEqual("partial", sc.judge_history(REF, 200, answer(later, partial=True, problems=p), DOWN))

    def test_a_partial_answer_naming_a_node_that_is_up_or_no_problem_or_another_code_is_wrong(self):
        p = [{"code": "DRS-5052", "message": "drishti-b cannot be reached now"}]
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL[1:], partial=True, problems=p), DOWN))
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL[1:], partial=True), DOWN))
        p = [{"code": "DRS-1003", "message": "drishti-c failed"}]
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL[1:], partial=True, problems=p), DOWN))

    def test_a_partial_answer_with_rows_the_reference_has_not_or_nothing_missing_is_wrong(self):
        p = [{"code": "DRS-5052", "message": "drishti-c cannot be reached now"}]
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer([row("2026-10-15", 999.0)], partial=True, problems=p), DOWN))
        self.assertEqual("wrong", sc.judge_history(REF, 200, answer(FULL, partial=True, problems=p), DOWN))

    def test_a_peer_time_out_is_a_timeout(self):
        p = [{"code": "DRS-1014", "message": "the node drishti-c did not answer within 10 s"}]
        self.assertEqual("timeout", sc.judge_history(REF, 200, answer(FULL[1:], partial=True, problems=p), DOWN))
        self.assertEqual("timeout", sc.judge_history(REF, 504, {"code": "DRS-1014", "message": "…"}, DOWN))

    def test_refusals_drs_5052_named_is_partial_drs_1013_is_always_wrong(self):
        self.assertEqual("partial", sc.judge_history(REF, 502, {"code": "DRS-5052", "message": "drishti-c cannot be reached now"}, DOWN))
        self.assertEqual("unavailable", sc.judge_history(REF, 502, {"code": "DRS-5052", "message": "drishti-c cannot be reached now"}, []))
        self.assertEqual("wrong", sc.judge_history(REF, 404, {"code": "DRS-1013", "message": "no data"}, DOWN))
        self.assertEqual("unavailable", sc.judge_history(REF, 0, "ConnectionRefusedError()", DOWN))
        self.assertEqual("wrong", sc.judge_history(REF, 500, {"code": "DRS-5001"}, DOWN))

    def test_a_leaked_mask_is_wrong(self):
        leaked = [row("2026-10-14", 100.0, trader="T1"), row("2026-10-15", 120.0, ["QUANTITY"], trader="T2"), row("2026-10-16", 120.0)]
        self.assertTrue(sc.masked_leaks(answer(leaked)))
        self.assertEqual("wrong", sc.judge_history(sc.history_essence(answer(leaked)), 200, answer(leaked), []))
        flagged = copy.deepcopy(FULL)
        flagged[1]["changed"].append("TRADER_ID")
        self.assertEqual(["TRADER_ID: shown as changed"], sc.masked_leaks(answer(flagged)))
        self.assertEqual([], sc.masked_leaks(answer(FULL)))


class WindowsMemoryVerdictTest(unittest.TestCase):
    PLAN = [(1200, "kill", "c"), (1500, "restart", "c"), (3600, "kill", "a"), (3900, "restart", "a")]

    def test_kill_windows_run_from_the_kill_to_the_rejoin_plus_grace(self):
        w = sc.kill_windows(self.PLAN, {"c": 1530, "a": 3940}, 7200, 30)
        self.assertEqual([{"node": "c", "from": 1200, "to": 1560}, {"node": "a", "from": 3600, "to": 3970}], w)
        self.assertEqual(7200, sc.kill_windows(self.PLAN, {"c": 1530}, 7200, 30)[1]["to"])     # a never came back: to the end
        self.assertTrue(sc.inside(1300, w) and sc.inside(1195, w, 10) and not sc.inside(2000, w, 10))

    def test_the_slope_is_per_hour_and_needs_three_points(self):
        self.assertAlmostEqual(60.0, sc.slope_per_hour([(0, 100), (60, 101), (120, 102)]))
        self.assertIsNone(sc.slope_per_hour([(0, 1), (60, 2)]))

    def test_memory_counts_only_after_warmup_since_the_last_start(self):
        samples = [{"t": t, "node": "a", "rssMb": 900.0, "heapMb": 400.0, "liveMb": 200.0 + (50 if t < 600 else 0)} for t in range(0, 3600, 60)]
        samples += [{"t": t, "node": "c", "rssMb": 900.0, "heapMb": 400.0, "liveMb": 100.0 + t / 36} for t in range(0, 3600, 60)]
        samples += [{"t": t, "node": "console", "rssMb": 150.0} for t in range(0, 3600, 60)]
        m = sc.memory_trend(samples, {"a": 0, "c": 0, "console": 0}, 600, 64)
        self.assertTrue(m["a"]["ok"])
        self.assertEqual("liveMb", m["a"]["measure"])
        self.assertFalse(m["c"]["ok"])                            # 100 MB an hour
        self.assertEqual("rssMb", m["console"]["measure"])
        self.assertIsNone(sc.memory_trend(samples, {"a": 3500}, 600, 64)["a"]["ok"])           # restarted late: too few samples

    def test_the_verdict_fails_on_wrong_answers_errors_outside_windows_slow_actions_and_growth(self):
        w = [{"node": "c", "from": 1200, "to": 1560}]
        users_out = {"trade": {"n": 10, "failed": 0, "p50": .05, "p95": .1, "p99": .2}, "history": {"n": 5, "failed": 1, "p50": .1, "p95": .3, "p99": .6}}
        ok = sc.verdict(0, [{"t": 1300, "what": "checker history", "verdict": "partial"}], w, users_out, {"a": {"ok": True}})
        self.assertTrue(ok["pass"])
        self.assertEqual({"p95": .3, "p99": .6}, ok["latency"]["perAction"]["history"])
        self.assertFalse(sc.verdict(1, [], w, users_out, {})["pass"])
        out = sc.verdict(0, [{"t": 2000, "what": "load history", "n": 3, "width": 10}], w, users_out, {})
        self.assertFalse(out["pass"])
        self.assertEqual(3, out["errorsOnlyInKillWindows"]["outside"])
        self.assertFalse(sc.verdict(0, [], w, users_out, {}, max_p99=0.5)["pass"])
        self.assertFalse(sc.verdict(0, [], w, users_out, {"c": {"ok": False}})["pass"])

    def test_the_table_has_history_on_its_own_line(self):
        users_out = {"trade": {"n": 10, "failed": 0, "p50": .05, "p95": .1, "p99": .2}, "history": {"n": 5, "failed": 1, "p50": .1, "p95": .3, "p99": .6},
                     "failedBuckets": {}, "perSecond": 3.0}
        text = sc.table(users_out, {"history:right": 4, "history:partial": 1, "trade:right": 9, "trade:wrong": 1})
        lines = text.splitlines()
        self.assertTrue(any(x.startswith("history") and "0.600" in x for x in lines))
        self.assertTrue(any(x.split()[:6] == ["history", "4", "1", "0", "0", "0"] for x in lines))
        self.assertTrue(any(x.split()[:6] == ["trade", "9", "0", "0", "0", "1"] for x in lines))


class UsersHistoryTest(unittest.TestCase):
    A = SimpleNamespace(ids=100, counterparties=20, dates=["2026-10-15", "2026-10-16"], equities=[], history_share=0.15,
                        console=None, server="http://x", users=1, fail_bucket=10)

    def test_the_history_share_is_kept_and_every_range_is_asked(self):
        r = random.Random(1)
        picks = [users.pick(r, self.A) for _ in range(4000)]
        hist = [p for p in picks if p[0] == "history"]
        self.assertAlmostEqual(0.15, len(hist) / len(picks), delta=0.02)
        asks = {tuple(sorted(k for k in p[1][2])) for p in hist}
        self.assertEqual({("days",), ("range",), ("days", "diff"), ("days", "knownAt")}, asks)
        self.assertEqual({"trade", "counterparty"}, {p[1][0] for p in hist})
        self.assertEqual([], [p for p in picks if p[0] == "history" and p[1][0] == "equity"])
        self.assertEqual(0, sum(1 for _ in range(500) if users.pick(r, SimpleNamespace(**{**vars(self.A), "history_share": 0}))[0] == "history"))

    def test_equities_are_asked_when_given_and_the_addresses_are_the_api_or_the_page(self):
        a = SimpleNamespace(**{**vars(self.A), "equities": ["EQ-ACME"]})
        r = random.Random(2)
        kinds = {users.history_pick(r, a)[0] for _ in range(500)}
        self.assertIn("equity", kinds)
        url, _ = users.request_of("history", ("trade", "MX-50000001", {"days": "30"}), None, "CP-00001", a)
        self.assertEqual("/api/v1/history/trade/~?id=MX-50000001&days=30", url)
        c = SimpleNamespace(**{**vars(a), "console": "http://c"})
        self.assertEqual("/v/trade/MX-50000001/history?days=30&diff=true",
                         users.request_of("history", ("trade", "MX-50000001", {"days": "30", "diff": "true"}), None, "CP-00001", c)[0])

    def test_failures_are_counted_per_bucket_of_seconds(self):
        stats = {"history": [(0.1, True, 1000.0), (0.2, False, 1003.0), (0.3, False, 1012.0)], "trade": [(0.05, True, 1000.0)]}
        out = users.summary(stats, 20.0, self.A)
        self.assertEqual({"history": {"1000": 1, "1010": 1}}, out["failedBuckets"])
        self.assertEqual(2, out["history"]["failed"])


if __name__ == "__main__":
    unittest.main()
