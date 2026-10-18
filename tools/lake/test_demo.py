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

"""`drishti.py lake demo` on a small test-data set (40 trades a system): seven business days per table, trades partitioned by
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
            subprocess.run([sys.executable, str(ROOT / "tools" / "testdata" / "make_testdata.py"), "--out", str(td), "--per-system", "40"],
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


if __name__ == "__main__":
    unittest.main()
