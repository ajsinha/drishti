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

"""lake build: streamed reading, partitions, modes, the lock, exit codes, the summary and the signal, on small synthetic books.

    uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/lake/test_build.py -q
"""
import gzip
import http.server
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import jsonstream  # noqa: E402
import scan  # noqa: E402

DATES = ["2026-10-01", "2026-10-02", "2026-10-05"]
PRODUCTS = ["Swap", "Future", "Option"]


def deal(i: int, day: str) -> dict:
    """A synthetic deal: nested legs with cash flows, a classifier, a desk, numbers and blanks."""
    return {"TRADE_ID": 100000 + i, "TRADE_DATE": day, "PRODUCT_TYPE": PRODUCTS[(i // 3) % 3], "DESK": ["RATES", "ENERGY"][i % 2],
            "NOTIONAL": 1000.0 * (i + 1), "BOOK": {"NAME": f"BK-{i % 4}"}, "COMMENT": "",
            "LEGS": [{"LEG_ID": n, "CASH_FLOWS": [{"FLOW": k, "AMOUNT": k * 1.5} for k in range(2)]} for n in range(2)]}


def book(n: int, dates=DATES) -> list[dict]:
    return [deal(i, dates[i % len(dates)]) for i in range(n)]


_STORE = None


def setUpModule():
    """The BI-store stamp a build leaves goes to a temporary folder, never the repository's data/."""
    global _STORE
    _STORE = tempfile.TemporaryDirectory()
    os.environ["DRISHTI_BI_STORE"] = _STORE.name


def tearDownModule():
    os.environ.pop("DRISHTI_BI_STORE", None)
    _STORE.cleanup()


def args(**kw):
    a = build.build_parser().parse_args([])
    a.quiet = True
    for k, v in kw.items():
        setattr(a, k, v)
    return a


def quiet(*_):
    pass


class StreamTest(unittest.TestCase):

    def test_pretty_array_read_in_tiny_windows(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "book.json"
            docs = book(40)
            f.write_text(json.dumps(docs, indent=2), encoding="utf-8")
            got = [d for d, _ in jsonstream.Reader(f, chunk=37)]
            self.assertEqual(got, docs)                     # nested arrays intact, every element once
            self.assertEqual(len(got[0]["LEGS"][1]["CASH_FLOWS"]), 2)

    def test_jsonl_gzip_bad_lines_and_non_objects(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "book.jsonl.gz"
            lines = [json.dumps(d) for d in book(5)] + ["{not json", "[1, 2]", ""]
            with gzip.open(f, "wt", encoding="utf-8") as fh:
                fh.write("\n".join(lines) + "\n")
            got = list(jsonstream.Reader(f))
            self.assertEqual(sum(isinstance(d, dict) for d, _ in got), 5)
            bad = [d for d, _ in got if isinstance(d, jsonstream.Bad)]
            self.assertEqual([b.where for b in bad], ["book.jsonl.gz:6", "book.jsonl.gz:7"])
            self.assertIn("not JSON", bad[0].reason)
            self.assertEqual(bad[1].reason, "not a JSON object")

    def test_array_with_non_object_truncated_and_single_object(self):
        with tempfile.TemporaryDirectory() as tmp:
            t = Path(tmp)
            (t / "a.json").write_text('[{"a": 1}, 42, {"a": 2}', encoding="utf-8")
            got = list(jsonstream.Reader(t / "a.json", chunk=4))
            self.assertEqual([d for d, _ in got if isinstance(d, dict)], [{"a": 1}, {"a": 2}])
            reasons = [d.reason for d, _ in got if isinstance(d, jsonstream.Bad)]
            self.assertEqual(reasons[0], "not a JSON object")
            self.assertIn("not closed", reasons[1])
            (t / "one.json").write_text(json.dumps(deal(1, DATES[0]), indent=2), encoding="utf-8")
            self.assertEqual([d for d, _ in jsonstream.Reader(t / "one.json")], [deal(1, DATES[0])])
            self.assertEqual([p.name for p in jsonstream.input_files([t])], ["a.json", "one.json"])


class BuildTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.lake = self.root / "lake"
        self.src = self.root / "book.json"
        self.src.write_text(json.dumps(book(300), indent=1), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def base(self, **kw):
        d = dict(src=[str(self.src)], lake=str(self.lake), domain="trading", kind="deal", key="TRADE_ID", date="TRADE_DATE",
                 partition_by=["PRODUCT_TYPE"], promote=["NOTIONAL,BOOK.NAME"], engine="deltalake")
        d.update(kw)
        return args(**d)

    def table(self):
        from deltalake import DeltaTable
        return DeltaTable(str(self.lake / "trading" / "deal"))

    def test_dry_run_counts_partitions_and_rejects(self):
        docs = book(30) + [{"TRADE_ID": 9, "TRADE_DATE": "soon"}, {"TRADE_DATE": DATES[0]}, {"TRADE_ID": 7}]
        self.src.write_text(json.dumps(docs), encoding="utf-8")
        code, s = build.run(self.base(dry_run=True), quiet)
        self.assertEqual(code, build.EXIT_PARTIAL)
        self.assertEqual((s["documents"], s["accepted"], s["rejected"]), (33, 30, 3))
        self.assertEqual(s["rows"], {"2026-10-01": 10, "2026-10-02": 10, "2026-10-05": 10})
        self.assertEqual(len(s["partitions"]), 9)
        self.assertEqual(set(s["rejects"]["byReason"]), {"no business date (field 'TRADE_DATE' is not a date)",
                                                         "no id (field 'TRADE_ID' is missing or empty)",
                                                         "no business date (field 'TRADE_DATE' is missing or empty)"})
        self.assertFalse(self.lake.exists())               # a dry run writes nothing

    def test_write_partitions_promoted_columns_and_hive_folders(self):
        code, s = build.run(self.base(), quiet)
        self.assertEqual(code, build.EXIT_OK, s.get("error"))
        self.assertEqual(s["engine"], self.base().engine)
        t = self.table()
        self.assertEqual(t.metadata().partition_columns, ["business_date", "PRODUCT_TYPE"])
        rows = t.to_pyarrow_table().to_pylist()
        self.assertEqual(len(rows), 300)
        r = next(x for x in rows if x["id"] == "100004")
        self.assertEqual((r["PRODUCT_TYPE"], r["NOTIONAL"], r["BOOK__NAME"]), ("Future", 5000.0, "BK-0"))
        self.assertEqual(json.loads(r["doc"])["LEGS"][1]["CASH_FLOWS"][1]["AMOUNT"], 1.5)
        self.assertTrue((self.lake / "trading" / "deal" / "business_date=2026-10-01" / "PRODUCT_TYPE=Swap").is_dir())
        last = json.loads((self.lake / "_drishti-lake" / "last-run.json").read_text())
        self.assertEqual(last["status"], "ok")
        log = (self.lake / "_drishti-lake" / "runs.log").read_text().strip().splitlines()
        self.assertEqual(len(log), 1)
        self.assertIn("lake-build ok exit=0", log[0])
        self.assertFalse(any((self.lake / "_drishti-lake" / "staging").glob("*")))

    def test_rerun_replaces_only_that_day_and_append_adds(self):
        self.assertEqual(build.run(self.base(), quiet)[0], 0)
        day2 = [dict(deal(i, "2026-10-02"), NOTIONAL=1.0) for i in range(7)]
        self.src.write_text(json.dumps(day2), encoding="utf-8")
        self.assertEqual(build.run(self.base(), quiet)[0], 0)
        by_day = {}
        for r in self.table().to_pyarrow_table().to_pylist():
            by_day.setdefault(str(r["business_date"]), []).append(r)
        self.assertEqual({d: len(v) for d, v in by_day.items()}, {"2026-10-01": 100, "2026-10-02": 7, "2026-10-05": 100})
        self.assertTrue(all(r["NOTIONAL"] == 1.0 for r in by_day["2026-10-02"]))
        self.assertEqual(build.run(self.base(mode="append"), quiet)[0], 0)
        self.assertEqual(len(self.table().to_pyarrow_table()), 214)

    def test_overwrite_partitions_keeps_the_days_other_partitions(self):
        self.assertEqual(build.run(self.base(), quiet)[0], 0)
        swaps = [dict(deal(i, "2026-10-01"), PRODUCT_TYPE="Swap") for i in range(3)]
        self.src.write_text(json.dumps(swaps), encoding="utf-8")
        self.assertEqual(build.run(self.base(mode="overwrite-partitions"), quiet)[0], 0)
        rows = [r for r in self.table().to_pyarrow_table().to_pylist() if str(r["business_date"]) == "2026-10-01"]
        counts = {}
        for r in rows:
            counts[r["PRODUCT_TYPE"]] = counts.get(r["PRODUCT_TYPE"], 0) + 1
        self.assertEqual(counts["Swap"], 3)
        self.assertGreater(counts["Future"], 0)

    def test_as_of_and_batches(self):
        code, s = build.run(self.base(date=None, as_of="2026-09-30", partition_by=None, batch_rows=40), quiet)
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(s["rows"], {"2026-09-30": 300})
        self.assertGreaterEqual(s["commit"]["commits"], 8)

    def test_lock_held_exits_3_and_partitioning_mismatch_fails(self):
        with build.LakeLock(self.lake):
            code, s = build.run(self.base(), quiet)
        self.assertEqual(code, build.EXIT_LOCKED)
        self.assertIn("locked by", s["error"])
        self.assertEqual(build.run(self.base(), quiet)[0], 0)
        code, s = build.run(self.base(partition_by=["DESK"]), quiet)
        self.assertEqual(code, build.EXIT_FAILED)
        self.assertIn("partitioned by business_date, PRODUCT_TYPE", s["error"])

    def test_usage_failures(self):
        self.assertEqual(build.run(self.base(date=None), quiet)[0], build.EXIT_FAILED)
        self.assertEqual(build.run(self.base(partition_by=["id"]), quiet)[0], build.EXIT_FAILED)
        code, s = build.run(self.base(src=[str(self.root / "missing.json")]), quiet)
        self.assertEqual(code, build.EXIT_FAILED)
        self.assertIn("no such file", s["error"])

    def test_pack_supplies_domain_key_date_and_promoted_columns(self):
        pack = self.root / "pack"
        pack.mkdir()
        (pack / "pack.yaml").write_text(
            "pack: synth-book\nconnectors:\n  book-store:\n    plugin: delta\n    kinds: [deal]\n    settings:\n      root: x\n      domain: book\n"
            "      layout:\n        deal:\n          columns: [NOTIONAL]\ningest:\n  deal: {key: TRADE_ID, date: TRADE_DATE}\n", encoding="utf-8")
        a = args(src=[str(self.src)], lake=str(self.lake), pack=str(pack), engine="deltalake")
        code, s = build.run(a, quiet)
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual((s["pack"], s["domain"], s["kind"], s["promote"]), ("synth-book", "book", "deal", ["NOTIONAL"]))
        self.assertTrue((self.lake / "book" / "deal" / "_delta_log").is_dir())

    def test_signal_posts_each_date(self):
        got = []

        class H(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                got.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"]))), self.headers.get("Authorization")))
                self.send_response(201)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"verified": "verified"}')

            def log_message(self, *a):
                pass
        srv = http.server.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            tok = self.root / "tok"
            tok.write_text("drk_secret\n")
            code, s = build.run(self.base(signal=f"http://127.0.0.1:{srv.server_port}", pack_name="synth-book", token_file=str(tok)), quiet)
        finally:
            srv.shutdown()
        self.assertEqual(code, 0, s)
        self.assertEqual(sorted(b["businessDate"] for _, b, _ in got), DATES)
        self.assertTrue(all(p == "/api/v1/packs/synth-book/loads" and t == "Bearer drk_secret" for p, _, t in got))
        self.assertNotIn("drk_secret", json.dumps(s))
        stamp = Path(os.environ["DRISHTI_BI_STORE"]) / "lake-rebuilt.json"
        stamp.unlink(missing_ok=True)
        self.assertNotIn("stamp", s)                            # the server was told: no stamp
        code, s = build.run(self.base(signal="http://127.0.0.1:9", pack_name="synth-book"), quiet)
        self.assertEqual(code, build.EXIT_PARTIAL)              # written, but the server was not told
        self.assertEqual(s["stamp"], str(stamp))               # ... so its next start clears the BI columns read from the lake
        self.assertEqual(json.loads(stamp.read_text())["dates"], DATES)

    def test_without_a_signal_the_stamp_is_left_unless_asked_not_to(self):
        stamp = Path(os.environ["DRISHTI_BI_STORE"]) / "lake-rebuilt.json"
        stamp.unlink(missing_ok=True)
        code, s = build.run(self.base(no_stamp=True), quiet)
        self.assertEqual(code, 0, s.get("error"))
        self.assertFalse(stamp.exists())
        code, s = build.run(self.base(), quiet)
        self.assertEqual(code, 0, s.get("error"))
        self.assertTrue(json.loads(stamp.read_text())["table"].endswith("deal"))


@unittest.skipUnless(__import__("writers").java_command(None, None), "Drishti's writer: build the server jar or set DRISHTI_LAKE_CLASSPATH")
class DrishtiEngineTest(BuildTest):
    """The same runs through Drishti's Java writer; deltalake reads what it wrote (the two engines agree on the table)."""

    def base(self, **kw):
        return super().base(**{"engine": "drishti", **kw})

    def test_write_partitions_promoted_columns_and_hive_folders(self):
        super().test_write_partitions_promoted_columns_and_hive_folders()
        self.assertEqual(self.table().version(), 0)                    # one commit for the whole table

    def test_as_of_and_batches(self):
        code, s = build.run(self.base(date=None, as_of="2026-09-30", partition_by=None, batch_rows=40), quiet)
        self.assertEqual(code, 0, s.get("error"))
        self.assertEqual(s["commit"]["commits"], 1)
        self.assertEqual(len(self.table().to_pyarrow_table()), 300)


class CommandLineTest(unittest.TestCase):

    def test_drishti_py_lake_build_scan_and_status(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import contextlib
        import io
        import drishti
        with tempfile.TemporaryDirectory() as tmp:
            src, lake = Path(tmp) / "b.jsonl", Path(tmp) / "lake"
            src.write_text("\n".join(json.dumps(d) for d in book(12)) + "\n{}\n", encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = drishti.main(["lake", "build", "--from", str(src), "--kind", "deal", "--key", "TRADE_ID", "--date", "TRADE_DATE",
                                     "--domain", "trading", "--lake", str(lake), "--engine", "deltalake", "--partition-by", "DESK"])
            self.assertEqual(code, 2, out.getvalue())                          # written, one reject
            self.assertIn("rejected 1: no id (field 'TRADE_ID' is missing or empty)", out.getvalue())
            self.assertIn("lake-build partial exit=2", out.getvalue())
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(drishti.main(["lake", "status", "--lake", str(lake)]), 0)
            self.assertIn("lock: free", out.getvalue())
            self.assertIn("lake-build partial", out.getvalue())
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(drishti.main(["lake", "scan", str(src)]), 0)
            self.assertIn("suggested --key TRADE_ID   --date TRADE_DATE", out.getvalue())


class ScanTest(unittest.TestCase):

    def test_suggestions(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "b.json"
            f.write_text(json.dumps(book(120)), encoding="utf-8")
            r = scan.scan([f])
            self.assertEqual(r["documents"], 120)
            self.assertEqual(r["suggest"]["key"][0], "TRADE_ID")
            self.assertEqual(r["suggest"]["date"][0], "TRADE_DATE")
            self.assertIn("PRODUCT_TYPE", r["suggest"]["partitionBy"])
            legs = next(x for x in r["fields"] if x["path"] == "LEGS")
            self.assertEqual(legs["types"], {"array": 120})


if __name__ == "__main__":
    unittest.main()
