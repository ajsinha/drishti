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
import os
import random
import signal
import subprocess
import sys
import time
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


class MaskCoverageTest(unittest.TestCase):
    """SOAK-09: 35 distinct history requests, and no viewer trade history had a masked field ('trader' names no field of the trades)."""

    def soak(self, **kw):
        import net_soak
        s = net_soak.Soak.__new__(net_soak.Soak)
        s.a = SimpleNamespace(**{"history_share": 0.15, "history_sample": 40, "trades": 1500, "counterparties": 300, "equities": "", **kw})
        s.dates, s.known_at = [f"2026-10-{d:02d}" for d in (5, 6, 7, 8, 9, 12, 13, 14, 15, 16)], "2026-10-20T10:00:00Z"
        return s

    def test_the_soak_masks_fields_its_trades_hold_and_its_history_shows(self):
        import gzip
        import json
        import net_soak
        snap = sorted((Path(__file__).resolve().parents[2] / "data" / "testdata" / "trades" / "murex").glob("snapshot-*.jsonl.gz"))
        if snap:
            with gzip.open(snap[0], "rt", encoding="utf-8") as fh:
                doc = json.loads(fh.readline())
            self.assertTrue(set(net_soak.MUST_MASK["trade"]) <= set(doc) and "TRADER_ID" in doc)        # what trades.py writes
        self.assertTrue(set(net_soak.MUST_MASK["trade"]) | set(net_soak.MUST_MASK["counterparty"]) <= set(net_soak.REDACT))
        self.assertNotIn("trader", net_soak.REDACT)

    def test_many_distinct_histories_in_both_roles_with_every_range_and_peer_only_murex_ids(self):
        import net_soak
        s = self.soak()
        reqs = s.history_requests(random.Random(20261019))
        ids = {(q["kind"], q["id"]) for q in reqs}
        self.assertGreaterEqual(len(ids), 140)
        self.assertEqual(40, sum(1 for k, i in ids if k == "trade" and i.startswith("MX-")))
        self.assertEqual(len(ids) * 4, len(reqs))                      # two ranges, each in both roles
        viewer = [q for q in reqs if q["role"] == "viewer"]
        self.assertTrue(any(q["id"].startswith("MX-") for q in viewer))
        for role in ("admin", "viewer"):
            asked = {tuple(sorted(q["params"])) for q in reqs if q["role"] == role}
            self.assertEqual({tuple(sorted(a)) for a in net_soak.HISTORY_ASKS}, asked, role)
        windows = [q["params"] for q in reqs if "from" in q["params"]]
        self.assertTrue(windows and all(p["from"] <= p["to"] and p["to"] in s.dates for p in windows))
        self.assertEqual("2026-10-20T10:00:00Z", next(q["params"]["knownAt"] for q in reqs if "knownAt" in q["params"]))
        self.assertEqual(("PRICE",), s.must_mask({"what": "history", "kind": "trade", "role": "viewer"}))
        self.assertEqual((), s.must_mask({"what": "history", "kind": "trade", "role": "admin"}))
        self.assertEqual([], self.soak(history_share=0).history_requests(random.Random(1)))

    def test_each_range_is_asked_in_both_roles_and_the_checker_asks_the_twin_at_once_in_either_order(self):
        """SOAK2-01: the admin and the viewer must meet on the same entity, range and node, both orders, back to back."""
        import net_soak
        s = self.soak()
        reqs = s.history_requests(random.Random(20261019))
        twins = s.twins(reqs)
        self.assertEqual(len(reqs), len(twins))
        for q in reqs:
            other = twins[(q["pair"], "viewer" if q["role"] == "admin" else "admin")]
            self.assertEqual((q["kind"], q["id"], q["params"]), (other["kind"], other["id"], other["params"]))
            self.assertNotEqual(q["role"], other["role"])
        for q in reqs:
            q["ref"] = {"rows": []}
        s.reqs, s.procs, s.down, s.back, s.errors, s.t0, s.pairs = reqs, {"a": 1, "b": 1, "c": 1}, {}, {}, [], time.time(), {}
        s.a.check_every, s.a.kill_grace = 0, 30
        asked = []

        class Enough(Exception):
            pass

        def ask(base, q):
            asked.append((base, q["pair"], q["role"]))
            if len(asked) >= 4000:
                raise Enough
            return 200, {}
        s.ask, s.url, s.essence = ask, (lambda n: n), (lambda q, body: None)
        s.judge_why = lambda q, status, body: ("wrong", "x") if q["role"] == "admin" and q["id"].startswith("MX-") else ("right", "equal")
        with self.assertRaises(Enough):
            s.checker(time.time() + 3600, {}, sc.WrongLog())
        for (b1, p1, r1), (b2, p2, r2) in zip(asked[::2], asked[1::2]):
            self.assertEqual((b1, p1), (b2, p2))                              # the twin: same node, entity and range, at once
            self.assertNotEqual(r1, r2)
        self.assertGreater(s.pairs["adminFirst"]["pairs"], 100)
        self.assertGreater(s.pairs["viewerFirst"]["pairs"], 100)
        self.assertGreater(s.pairs["viewerFirst"]["peerHeld"], 0)
        self.assertGreater(s.pairs["viewerFirst"]["wrongSecond"], 0)     # an admin answer after a viewer's is counted when wrong
        self.assertEqual(0, s.pairs["adminFirst"]["wrongSecond"])
        self.assertTrue(s.peer_held("a", {"kind": "trade", "id": "MX-50000001"}))
        self.assertFalse(s.peer_held("c", {"kind": "trade", "id": "MX-50000001"}))
        self.assertTrue(s.peer_held("c", {"kind": "trade", "id": "CLY-30000001"}))
        self.assertTrue(s.peer_held("b", {"kind": "counterparty", "id": "CP-00001"}))
        self.assertFalse(s.peer_held("a", {"kind": "counterparty", "id": "CP-00001"}))

    def test_a_viewer_history_must_show_the_field_masked_on_every_row_and_in_every_diff(self):
        must = ("TRADER_ID",)
        self.assertEqual([], sc.mask_missing(answer(FULL), must))
        self.assertEqual("right", sc.judge_history(REF, 200, answer(FULL), [], must))
        self.assertEqual(["TRADER_ID: column not masked"], sc.mask_missing(answer(FULL, masked=False), must))
        shown = [row("2026-10-14", 100.0, trader="T1"), row("2026-10-15", 120.0, ["QUANTITY"], trader="T1"), row("2026-10-16", 120.0, trader="T1")]
        self.assertEqual(["TRADER_ID: shown on 3 of 3 rows"], sc.mask_missing(answer(shown), must))
        self.assertEqual("wrong", sc.judge_history(sc.history_essence(answer(shown)), 200, answer(shown), [], must))   # even equal to the ref
        diff = copy.deepcopy(FULL)
        diff[1]["changes"] = [{"path": "TRADER_ID", "kind": "changed", "before": "T0", "after": "T1"}]
        self.assertEqual(["TRADER_ID: shown in a diff"], sc.mask_missing(answer(diff), must))
        self.assertEqual(["PRICE: not a column"], sc.mask_missing(answer(FULL), ("PRICE",)))
        self.assertEqual([], sc.mask_missing({"code": "DRS-5052"}, must))

    def test_the_verdict_fails_when_no_viewer_history_masked_anything(self):
        self.assertTrue(sc.verdict(0, [], [], {}, {}, 0, {"viewerHistories": 140, "masked": 140})["pass"])
        self.assertFalse(sc.verdict(0, [], [], {}, {}, 0, {"viewerHistories": 140, "masked": 0})["pass"])
        self.assertFalse(sc.verdict(0, [], [], {}, {}, 0, {"viewerHistories": 0, "masked": 0})["pass"])
        self.assertTrue(sc.verdict(0, [], [], {}, {})["pass"])


class SoakConfigurationTest(unittest.TestCase):
    """SOAK-10: retry warnings for pack stores the lake does not hold, and `deprecated` warnings from the soak's own
    --drishti.sources.connectors.*.enabled=false flags."""

    def lake(self, root: Path, tables) -> Path:
        for t in tables:
            (root / t / "_delta_log").mkdir(parents=True)
        return root

    def test_each_node_switches_off_with_connector_files_the_delta_connectors_its_lake_lacks(self):
        import tempfile
        import yaml
        import net_soak
        with tempfile.TemporaryDirectory() as tmp:
            murex = self.lake(Path(tmp) / "murex", ["trading/trade_murex", "reference/counterparty", "credit/netting-set"])
            files = net_soak.connector_files(murex, "trading,counterparty-risk")
            self.assertEqual({"trade-endur", "trade-calypso", "market-store", "legal-store", "collateral-store"}, set(files))
            doc = yaml.safe_load(files["trade-endur"])
            self.assertEqual(("delta", False, ["trade"]), (doc["plugin"], doc["enabled"], doc["kinds"]))
            self.assertIn("enabled: false\n", files["trade-endur"])                  # a YAML boolean, never the string "false" (SOAK2-02)
            self.assertEqual("trade_endur", doc["settings"]["table"]["trade"])
            self.assertLessEqual(set(doc), {"plugin", "enabled", "kinds", "description", "settings", "name"})   # CONNECTOR_FILES section 3
            shared = self.lake(Path(tmp) / "shared", ["trading/trade_endur", "trading/trade_calypso", "reference/counterparty"])
            self.assertEqual({"trade-murex", "market-store"}, set(net_soak.connector_files(shared, "trading")))
        self.assertEqual(["core", "market-data", "trading", "counterparty-risk"], net_soak.pack_closure("trading,counterparty-risk", net_soak.ROOT / "config" / "packs"))

    def test_no_node_loads_a_pack_its_lake_holds_nothing_of_nor_uses_deprecated_connector_flags(self):
        import inspect
        import net_soak
        self.assertTrue(all("market-risk" not in packs for _, packs, _ in net_soak.NODES.values()))
        self.assertNotIn("market-risk", net_soak.REF_PACKS)
        self.assertNotIn("drishti.sources.connectors.", inspect.getsource(net_soak.Soak.start_server))


class WrongAnswersTest(unittest.TestCase):
    """SOAK-08: 32 wrong answers, 5 kept, no time for the other 27, and the kept body cut before ``partial`` and ``problems``."""
    P = [{"code": "DRS-5052", "source": "net", "days": ["2026-07-17"] * 60, "message": "the nodes that hold trade cannot be reached now: drishti-c (dead)"}]

    def test_every_wrong_answer_is_kept_with_its_time_request_and_rule_and_the_first_200_in_detail(self):
        log = sc.WrongLog()
        big = answer(FULL * 40, partial=True, problems=self.P)              # a long body: partial and problems come after the rows
        q = {"what": "history", "kind": "trade", "id": "CLY-30001298", "params": {"range": "3M"}, "role": "viewer", "date": "2026-10-16", "ref": REF}
        for i in range(250):
            verdict, why = sc.judge_history_why(REF, 200, answer(FULL, partial=True, problems=self.P), DOWN)
            self.assertEqual(("wrong", "partial yet nothing missing"), (verdict, why))
            log.add(1200.0 + i, "a", q, 200, big, why, sc.history_essence(big), REF)
        log.add(1500.0, "b", {"what": "trade", "id": "MX-1", "date": "2026-10-16", "ref": None}, 500, "boom", "status 500")
        out = log.report()
        self.assertEqual(251, out["count"])
        self.assertEqual(251, len(out["all"]))
        self.assertEqual({"history": {"partial yet nothing missing": 250}, "trade": {"status 500": 1}}, out["byKind"])
        self.assertEqual([1200.0, 1201.0], [w["t"] for w in out["all"][:2]])
        self.assertEqual({"what": "history", "kind": "trade", "id": "CLY-30001298", "params": {"range": "3M"}, "role": "viewer",
                          "date": "2026-10-16"}, out["all"][0]["q"])                               # the request, not its reference
        self.assertEqual(200, len(out["samples"]))
        s = out["samples"][0]
        self.assertTrue(s["body"]["partial"])
        self.assertEqual(self.P, s["body"]["problems"])                    # whole, however long the body
        self.assertLessEqual(len(s["body"]["rest"]), 600)
        self.assertIn({"path": "rows.length", "ref": 3, "got": 120}, s["diff"])
        self.assertLessEqual(len(s["diff"]), 20)

    def test_the_rule_is_named_for_each_kind_of_wrong_history(self):
        self.assertEqual("complete answer differs from the reference",
                         sc.judge_history_why(REF, 200, answer(FULL[:1] + [row("2026-10-15", 121.0, ["QUANTITY"])] + FULL[2:]), [])[1])
        self.assertEqual("partial with DRS-1003", sc.judge_history_why(REF, 200, answer(FULL[1:], partial=True, problems=[{"code": "DRS-1003"}]), DOWN)[1])
        self.assertEqual(("wrong", "DRS-1013 no data"), sc.judge_history_why(REF, 404, {"code": "DRS-1013"}, DOWN))
        self.assertEqual(("partial", "named"), sc.judge_history_why(REF, 502, {"code": "DRS-5052", "message": "drishti-c cannot be reached now"}, DOWN))
        self.assertEqual([{"path": "a[1]", "ref": 2, "got": 3}], sc.compact_diff({"a": [1, 2]}, {"a": [1, 3]}))


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

    def test_a_step_when_the_load_moves_at_a_failover_is_not_growth_but_a_steady_slope_is(self):
        """SOAK-04: b's live heap stepped from 128 to 367 MB when c died and the console moved its sessions to b; flat before and after."""
        w = sc.kill_windows(self.PLAN, {"c": 1530, "a": 3940}, 7200, 30)
        samples = [{"t": t, "node": "b", "liveMb": 128.0 if t < 1800 else 367.0} for t in range(0, 7200, 60)]
        samples += [{"t": t, "node": "c", "liveMb": 340.0 + (200 if t >= 4200 else 0) + t / 36} for t in range(0, 7200, 60)]  # step + 100 MB/h
        m = sc.memory_trend(samples, {"b": 0, "c": 0}, 600, 64, w, 600)
        self.assertGreater(m["b"]["wholeRunSlopeMbPerHour"], 64)            # the old measure: fails on the step
        self.assertAlmostEqual(0.0, m["b"]["slopeMbPerHour"], places=3)
        self.assertTrue(m["b"]["ok"])
        self.assertEqual([[600, 1200], [2160, 3600], [4570, 7140]], [[round(x) for x in s] for s in m["b"]["steadySpans"]])
        self.assertAlmostEqual(100.0, m["c"]["slopeMbPerHour"], delta=0.5)   # a real slope still fails
        self.assertFalse(m["c"]["ok"])
        self.assertEqual([(0, 100)], sc.steady_segments(0, 100, [], 600))
        self.assertIsNone(sc.pooled_slope_per_hour([[(0, 1)], [(60, 2)]]))

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


@unittest.skipUnless(Path("/proc/self/status").exists(), "needs /proc (Linux)")
class ConsoleSampleTest(unittest.TestCase):
    def test_the_console_is_sampled_with_its_workers_summed_and_one_by_one(self):
        """SOAK-07: the soak sampled the uvicorn parent alone (1 thread, 50.7 MB on every sample); the workers hold the console's memory."""
        import net_soak
        worker = "import time; x = bytearray(40_000_000); x[::4096] = b'1' * len(x[::4096]); time.sleep(60)"
        parent = subprocess.Popen([sys.executable, "-c", "import subprocess, sys, time; ps = [subprocess.Popen([sys.executable, '-c', sys.argv[1]]) "
                                   "for _ in range(2)]; time.sleep(60)", worker])
        try:
            for _ in range(100):
                s = net_soak.Soak.tree_status(parent.pid)
                if len(s["workers"]) == 2 and all(w.get("rssMb", 0) > 35 for w in s["workers"]):
                    break
                time.sleep(0.1)
            self.assertEqual(3, s["processes"])
            self.assertTrue(all(w["rssMb"] > 35 for w in s["workers"]), s)
            alone = net_soak.Soak.proc_status(parent.pid)
            self.assertGreater(s["rssMb"], alone["rssMb"] + 70)
            self.assertAlmostEqual(s["rssMb"], alone["rssMb"] + sum(w["rssMb"] for w in s["workers"]), delta=5)
            self.assertEqual(s["threads"], alone["threads"] + sum(w["threads"] for w in s["workers"]))
            self.assertIn("openFiles", s["workers"][0])
        finally:
            for c in net_soak.Soak.descendants(parent.pid):
                os.kill(c, signal.SIGKILL)
            parent.kill()
            parent.wait(10)


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


class ServerLaunchTest(unittest.TestCase):
    """--jvm-arg and --server-env: the production settings of docs/admin/PERFORMANCE.md reach every server."""

    def test_jvm_args_follow_the_heap_in_order(self):
        import net_soak
        self.assertEqual(["-Xmx2g"], net_soak.jvm_args("2g", []))
        self.assertEqual(["-Xmx2g", "-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000"],
                         net_soak.jvm_args("2g", ["-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000"]))

    def test_server_env_parses_key_value_pairs(self):
        import net_soak
        self.assertEqual({}, net_soak.server_env(None))
        self.assertEqual({"MALLOC_ARENA_MAX": "2", "X": "a=b"}, net_soak.server_env(["MALLOC_ARENA_MAX=2", "X=a=b"]))
        with self.assertRaises(SystemExit):
            net_soak.server_env(["MALLOC_ARENA_MAX"])

    def test_the_options_are_parsed_repeatably(self):
        import net_soak
        seen = {}

        class Stop(Exception):
            pass

        def fake(a):
            seen.update(vars(a))
            raise Stop

        real = net_soak.Soak
        net_soak.Soak = fake
        try:
            with self.assertRaises(Stop):
                net_soak.main(["--work", "/nowhere", "--jvm-arg=-XX:MaxDirectMemorySize=256m", "--jvm-arg=-XX:TrimNativeHeapInterval=60000",
                               "--server-env", "MALLOC_ARENA_MAX=2"])
        finally:
            net_soak.Soak = real
        self.assertEqual(["-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000"], seen["jvm_arg"])
        self.assertEqual(["MALLOC_ARENA_MAX=2"], seen["server_env"])


if __name__ == "__main__":
    unittest.main()
