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

"""`drishti.py lake demo` on a small test-data set (40 trades a system, seven business days): seven business days per table, trades partitioned by
PRODUCT_TYPE in their source system's table, `--with-real` adding a made-up "real" extract's trades, counterparty, agreement and
netting set to the lake and never to the test data, and a connector file that pointed elsewhere moved to the lake with its earlier text
kept. Needs deltalake, pyarrow and PyYAML (uv run --with deltalake --with pyarrow --with pyyaml python -m unittest tools/lake/test_demo.py).
"""
from __future__ import annotations

import contextlib
import http.server
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

try:
    import deltalake  # noqa: F401
    import yaml  # noqa: F401
    HAVE = True
except ImportError:
    HAVE = False

FAKE = [{"SOURCE_SYSTEM": "ENDUR", "TRADE_ID": 99000001, "PRODUCT_TYPE": "Cash", "PRODUCT_SUB_TYPE": "CASH", "COUNTERPARTY": "MADE UP VAULTS",
         "COUNTERPARTY_UEN": "", "COUNTERPARTY_COUNTRY_OF_RISK": None, "BMO_LEGAL_ENTITY": "LE-TEST", "PORTFOLIO": "TEST-TO", "CURRENCY": "USD",
         "MATURITY_DATE": "2027-01-01", "LEGS": [{"LEG_ID": 1, "NOTIONAL": 0, "CASH_FLOWS": []}], "EVENTS": []}]


class FakeServer:
    """A server that answers its health and records the data-load signals."""

    def __init__(self):
        got = self.got = []

        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}')

            def do_POST(self):
                got.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                self.send_response(201)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, *a):
                pass
        self.srv = http.server.HTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.srv.server_port}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def close(self):
        self.srv.shutdown()
        self.srv.server_close()


@unittest.skipUnless(HAVE, "needs deltalake and PyYAML")
class LakeDemo(unittest.TestCase):
    def test_seven_days_per_table_real_extras_only_in_the_lake_and_connector_files_moved(self):
        import demo
        from deltalake import DeltaTable
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            td, lake, conn, data = tmp / "testdata", tmp / "lake", tmp / "connectors", tmp / "data"
            subprocess.run([sys.executable, str(ROOT / "tools" / "testdata" / "make_testdata.py"), "--out", str(td), "--per-system", "40", "--days", "7"],
                           check=True, capture_output=True)
            real = tmp / "real.json"
            real.write_text(json.dumps(FAKE))
            conn.mkdir()
            (conn / "trade-endur.yaml").write_text("plugin: delta\nkinds: [trade]\nsettings:\n  root: /nowhere/else\n  domain: trading\n"
                                                   "  table:\n    trade: trade_endur\n")
            server = FakeServer()
            old = os.environ.get("DRISHTI_DATA_DIR")
            os.environ["DRISHTI_DATA_DIR"] = str(data)
            try:
                a = demo.build_parser().parse_args(["--testdata", str(td), "--lake", str(lake), "--connectors-dir", str(conn), "--with-real", str(real),
                                                    "--only", "trades/,core/counterparty,counterparty-risk/netting-set,counterparty-risk/agreement",
                                                    "--as-of", "2026-10-30", "-q", "--server", server.url])
                with contextlib.redirect_stdout(io.StringIO()) as out:
                    self.assertEqual(demo.run(a), 0)
                # no server: the stamp the server reads at its next start, in its BI store
                b = demo.build_parser().parse_args(["--testdata", str(td), "--lake", str(lake), "--connectors-dir", str(conn), "--only", "core/desk",
                                                    "--as-of", "2026-10-30", "-q", "--server", "http://127.0.0.1:9"])
                with contextlib.redirect_stdout(io.StringIO()) as out2:
                    self.assertEqual(demo.run(b), 0)
            finally:
                server.close()
                os.environ.pop("DRISHTI_DATA_DIR") if old is None else os.environ.__setitem__("DRISHTI_DATA_DIR", old)
            # the seven days moved to end on --as-of: the seven USNY business days up to 2026-10-30
            dates = ["2026-10-22", "2026-10-23", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30"]
            for table in ("trading/trade_endur", "trading/trade_calypso", "trading/trade_murex", "reference/counterparty", "credit/netting-set"):
                t = DeltaTable(str(lake / table)).to_pyarrow_table()
                self.assertEqual(sorted({str(d) for d in t.column("business_date").to_pylist()}), dates, table)
            endur = DeltaTable(str(lake / "trading" / "trade_endur"))
            self.assertEqual(endur.metadata().partition_columns, ["business_date", "PRODUCT_TYPE"])
            rows = endur.to_pyarrow_table().to_pylist()
            last = [r for r in rows if str(r["business_date"]) == dates[-1]]
            self.assertEqual(len(last), 41)                                   # 40 test trades and the made-up real one
            fake = json.loads(next(r["doc"] for r in last if r["id"] == "99000001"))
            self.assertEqual(fake["LEGAL_ENTITY"], "LE-TEST")                 # BMO_LEGAL_ENTITY read as LEGAL_ENTITY
            stamps = {json.loads(r["doc"])["SOURCE_UPDATE_TIME"][:10] for r in last if r["id"] != "99000001"}
            self.assertTrue(stamps and stamps <= set(dates) | {""}, stamps)   # the documents' dates of the day moved with the days
            cps = {r["id"] for r in DeltaTable(str(lake / "reference" / "counterparty")).to_pyarrow_table().to_pylist()}
            self.assertIn("MADE UP VAULTS", cps)
            ns = [json.loads(r["doc"]) for r in DeltaTable(str(lake / "credit" / "netting-set")).to_pyarrow_table().to_pylist()
                  if r["id"] == "NS-MADE-UP-VAULTS-LE-TEST"]
            self.assertTrue(ns and ns[0]["TRADE_IDS"] == ["99000001"], ns[:1])
            self.assertFalse(any(b"MADE UP VAULTS" in __import__("gzip").decompress(f.read_bytes()) for f in td.rglob("*.jsonl.gz")))
            self.assertIn(f"root: {json.dumps(str(lake.resolve()))}", (conn / "trade-endur.yaml").read_text())
            self.assertEqual(len(list((data / "connectors" / "history").glob("trade-endur.*.yaml"))), 1)
            self.assertTrue((conn / "trade-calypso.yaml").is_file())          # a lake elsewhere: the missing files are written
            self.assertFalse((data / "build" / "lake-demo").exists())        # staging removed
            self.assertTrue((data / "feeds" / "fixing" / "SOFR-HISTORY.csv").is_file())
            self.assertIn("Demo lake ready", out.getvalue())
            # the server told: each table written, each of the seven dates
            told = {(path.split("/")[4], body["kind"], body["businessDate"]) for path, body in server.got}
            self.assertIn(("trading", "trade", "2026-10-30"), told)
            self.assertIn(("core", "counterparty", "2026-10-22"), told)
            self.assertEqual(len(server.got), 7 * 7, sorted(told))              # 3 trade tables, counterparty (and group), netting set, agreement
            self.assertIn("data-load signals", out.getvalue())
            stamp = data / "bi-store" / "lake-rebuilt.json"
            self.assertEqual(json.loads(stamp.read_text())["tables"], ["reference/desk"])
            self.assertIn("no server at http://127.0.0.1:9", out2.getvalue())



class SeriesMoved(unittest.TestCase):
    """QA1-10: a document's embedded market series moves with the business dates, by the same number of business days."""

    def lake(self, old, new):
        import demo
        d = object.__new__(demo.DemoLake)
        d.manifest = type("M", (), {"dates": old})()
        d.dates = new
        d.shift = {o: n for o, n in zip(old, new) if o != n}
        return d

    def test_series_points_move_with_the_days_and_points_before_them_keep_their_distance(self):
        old = ["2026-09-28", "2026-09-29", "2026-09-30"]
        new = ["2026-10-16", "2026-10-19", "2026-10-20"]
        d = self.lake(old, new)
        doc = {"TICKER": "EQ-X", "AS_OF": "2026-09-30", "BUILT_AT": "2026-09-30T18:02:41Z", "PRICE": 21.84,
               "HISTORY": [{"DATE": "2026-09-25", "CLOSE": 21.0}, {"DATE": "2026-09-28", "CLOSE": 21.5}, {"DATE": "2026-09-30", "CLOSE": 21.84}],
               "OHLC": [{"DATE": "2026-09-29", "OPEN": 1, "CLOSE": 2}], "COUPONS": [{"DATE": "2027-03-15", "AMOUNT": 2.5}],
               "HOLIDAYS": [{"DATE": "2026-12-25", "NAME": "Christmas"}]}
        out = json.loads(d.redate(json.dumps(doc, separators=(",", ":"))))
        self.assertEqual(out["AS_OF"], "2026-10-20")
        self.assertEqual(out["BUILT_AT"], "2026-10-20T18:02:41Z", "a curve is built on its own day (QA2-201)")
        # 09-25 is the business day before the data's first (09-28): it lands on the business day before the lake's first (10-16)
        self.assertEqual([p["DATE"] for p in out["HISTORY"]], ["2026-10-15", "2026-10-16", "2026-10-20"])
        self.assertEqual(out["OHLC"][0]["DATE"], "2026-10-19")
        self.assertEqual(out["COUPONS"][0]["DATE"], "2027-03-15", "a coupon schedule is real dates")
        self.assertEqual(out["HOLIDAYS"][0]["DATE"], "2026-12-25")

    def test_nothing_moves_when_the_dates_are_the_data_own(self):
        d = self.lake(["2026-09-30"], ["2026-09-30"])
        line = '{"HISTORY":[{"DATE":"2026-09-29","CLOSE":1}]}'
        self.assertEqual(d.redate(line), line)


class DataFolder(unittest.TestCase):
    """QA2-21: the demo's feeds (and staging) go to the server's data folder, never the repository's when another is named."""

    def folder(self, args, env=None):
        import demo
        old = os.environ.pop("DRISHTI_DATA_DIR", None)
        if env:
            os.environ["DRISHTI_DATA_DIR"] = env
        try:
            a = demo.build_parser().parse_args(args)
            return demo.demo_data_dir(a, Path(a.lake).resolve())
        finally:
            os.environ.pop("DRISHTI_DATA_DIR", None)
            if old is not None:
                os.environ["DRISHTI_DATA_DIR"] = old

    def test_the_data_folder_follows_the_server_not_the_repository(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp).resolve()
            self.assertEqual(self.folder(["--lake", str(tmp / "srv" / "delta")]), tmp / "srv")
            self.assertEqual(self.folder(["--lake", str(tmp / "x"), "--data-dir", str(tmp / "d")]), tmp / "d")
            self.assertEqual(self.folder(["--lake", str(tmp / "srv" / "delta")], env=str(tmp / "e")), tmp / "e")
            self.assertEqual(self.folder(["--lake", str(tmp / "elsewhere")]), (ROOT / "data").resolve())

    def test_make_data_takes_the_data_folder_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            code = ("import sys; sys.path.insert(0, 'tools/packgen/banking'); import make_data as m; "
                    "print(m.data_dir()); print(m.write_feeds(m.data_dir() / 'feeds'))")
            env = {k: v for k, v in os.environ.items() if k != "DRISHTI_DATA_DIR"}
            r = subprocess.run([sys.executable, "-c", code, "--data-dir", tmp], cwd=ROOT, env=env, capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.split("\n")[0], tmp)
            self.assertTrue((Path(tmp) / "feeds" / "fixing" / "SOFR-HISTORY.csv").is_file())

class TellServer(unittest.TestCase):
    """QA3A-07: a signal the server did not answer in time is not "refused", and the token hint is only for 401/403; --signal-once tells
    each table once (QA3A-05)."""

    def lake(self, url, tmp, once=False):
        import argparse
        import demo
        d = object.__new__(demo.DemoLake)
        d.a = argparse.Namespace(no_signal=False, server=url, token_file=None, signal_once=once, quiet=True)
        d.written = [("trading", "trade", "trading/trade_murex"), ("core", "counterparty", "reference/counterparty")]
        d.dates = ["2026-10-19", "2026-10-20"]
        d.run_id = "t"
        d.data = Path(tmp)
        d.lake = Path(tmp) / "delta"
        return d

    def server(self, answer):
        got = []

        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()

            def do_POST(self):
                got.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                answer(self, len(got))

            def log_message(self, *a):
                pass
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        return srv, got

    def test_signal_once_tells_each_table_on_its_newest_date(self):
        def ok(h, n):
            h.send_response(201)
            h.end_headers()
        srv, got = self.server(ok)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                note = self.lake(f"http://127.0.0.1:{srv.server_port}", tmp, once=True).tell_server()
        finally:
            srv.shutdown()
            srv.server_close()
        self.assertEqual(sorted((b["kind"], b["businessDate"]) for b in got), [("counterparty", "2026-10-20"), ("trade", "2026-10-20")])
        self.assertIn("told: 2 data-load signals", note)
        self.assertIn("each table once", note)

    def test_a_signal_that_times_out_is_not_called_refused(self):
        import demo

        def slow_then_401(h, n):
            if n == 1:
                time.sleep(1.5)                                   # longer than the signal's time-out below
            h.send_response(401 if n == 2 else 201)
            h.end_headers()
            if n == 2:
                h.wfile.write(b"DRS-5010 missing bearer token")
        srv, got = self.server(slow_then_401)
        old = demo.SIGNAL_TIMEOUT
        demo.SIGNAL_TIMEOUT = 0.5
        try:
            with tempfile.TemporaryDirectory() as tmp:
                note = self.lake(f"http://127.0.0.1:{srv.server_port}", tmp).tell_server()
        finally:
            demo.SIGNAL_TIMEOUT = old
            srv.shutdown()
            srv.server_close()
        self.assertIn("took 2 of 4 data-load signals", note)
        self.assertIn("refused 1 (401", note)
        self.assertIn("loads:write", note)                    # a 401: the token hint
        self.assertIn("did not answer 1 within 0.5 s", note)
        self.assertNotIn("refused 2", note)

    def test_no_token_hint_when_nothing_was_refused(self):
        import demo

        def slow(h, n):
            time.sleep(1.0)
            h.send_response(201)
            h.end_headers()
        srv, got = self.server(slow)
        old = demo.SIGNAL_TIMEOUT
        demo.SIGNAL_TIMEOUT = 0.3
        try:
            with tempfile.TemporaryDirectory() as tmp:
                d = self.lake(f"http://127.0.0.1:{srv.server_port}", tmp, once=True)
                note = d.tell_server()
        finally:
            demo.SIGNAL_TIMEOUT = old
            srv.shutdown()
            srv.server_close()
        self.assertIn("did not answer 2", note)
        self.assertNotIn("refused", note)
        self.assertNotIn("loads:write", note)


if __name__ == "__main__":
    unittest.main()
