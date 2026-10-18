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

"""capture_market.py against a stub server (never Yahoo or Google): what it asks for, politeness (2 s between calls, backoff after a 429),
the snapshot stamp, the files it writes and the manifest entries `lake demo` reads. Stdlib and PyYAML only.

    python3 -m unittest -q tools/testdata/test_capture_market.py
"""
from __future__ import annotations

import gzip
import json
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import capture_market as CM  # noqa: E402

COMPANIES = [{"COMPANY_ID": "CO-AAA", "QUOTE_ID": "QUOTE-AAA", "NEWS_ID": "NEWS-Aaa", "NEWS_ALL_ID": "NEWS-Aaa~all"},
             {"COMPANY_ID": "CO-BBB", "QUOTE_ID": "QUOTE-BBB", "NEWS_ID": "NEWS-Bbb", "NEWS_ALL_ID": "NEWS-Bbb~all"}]
TOPICS = [{"TOPIC_ID": "TOPIC-X", "NEWS_ID": "NEWS-X@de-DE", "NEWS_ALL_ID": "NEWS-X@de-DE~all"}]


class Stub(BaseHTTPRequestHandler):
    """The server's raw endpoint: QUOTE-BBB answers 429 once, NEWS-Bbb~all is unknown (404), everything else a document."""
    calls: list[str] = []
    throttled: set[str] = set()

    def log_message(self, *args):
        pass

    def do_GET(self):  # noqa: N802
        url = urllib.parse.urlparse(self.path)
        kind = url.path.split("/")[4]
        id_ = urllib.parse.parse_qs(url.query)["id"][0]
        Stub.calls.append(f"{kind} {id_}")
        if id_ == "QUOTE-BBB" and id_ not in Stub.throttled:
            Stub.throttled.add(id_)
            return self.reply(502, {"code": "DRS-1003", "message": "yfinance failed: 429 Too Many Requests"})
        if id_ == "NEWS-Bbb~all":
            return self.reply(404, {"code": "DRS-1001", "message": "not found"})
        if kind == "quote":
            data = {"QUOTE_ID": id_, "PRICE": 101.5, "AS_OF": "2026-10-16 16:00 America/New_York", "HISTORY": [{"DATE": "2026-10-16", "CLOSE": 101.5}],
                    "PROVENANCE": {"FEED": "Yahoo Finance public chart endpoint (unofficial)"}}
        else:
            data = {"NEWS_ID": id_, "COUNT": 1, "FETCHED_AT": "2026-10-17T10:00:00Z", "ITEMS": [{"TITLE": "A headline", "SOURCE": "Reuters"}],
                    "PROVENANCE": {"FEED": "Google News RSS"}}
        self.reply(200, {"ref": {"kind": kind, "id": id_}, "provenance": {}, "data": data})

    def reply(self, code, body):
        b = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)


class CaptureMarketTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="capture-market-test-"))
        pack = self.tmp / "packs" / CM.PACK
        (pack / "samples").mkdir(parents=True)
        (pack / "samples" / "listed-company.jsonl").write_text("".join(json.dumps(d) + "\n" for d in COMPANIES), encoding="utf-8")
        (pack / "samples" / "news-topic.jsonl").write_text("".join(json.dumps(d) + "\n" for d in TOPICS), encoding="utf-8")
        (pack / "pack.yaml").write_text("pack: news-and-markets\nconsole:\n  examples:\n    - ['NEWS NEWS-Extra', 'any name']\n"
                                        "    - ['QUOTE QUOTE-AAA', 'again: listed once']\n", encoding="utf-8")
        self.td = self.tmp / "testdata"
        self.td.mkdir()
        (self.td / "testdata.yaml").write_text("# the header\n# stays\n\nbusiness-dates: ['2026-09-30']\nas-of: '2026-09-30'\ndatasets:\n"
                                               "- {samples: config/packs/news-and-markets/samples, pack: news-and-markets, kind: news-topic, connector: news-store}\n"
                                               "- {folder: news-and-markets/quote, pack: news-and-markets, kind: quote, connector: news-store, key: QUOTE_ID, snapshot: old}\n",
                                               encoding="utf-8")
        old = self.td / CM.PACK / "quote"
        old.mkdir(parents=True)
        (old / "snapshot-2026-01-01.jsonl.gz").write_bytes(gzip.compress(b'{"QUOTE_ID":"QUOTE-OLD"}\n'))
        Stub.calls, Stub.throttled = [], set()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def args(self, *extra):
        return CM.build_parser().parse_args(["--server", self.url, "--testdata", str(self.td), "--packs", str(self.tmp / "packs"), "-q", *extra])

    def test_targets_are_the_companies_topics_and_examples_once_each(self):
        t = CM.targets(self.tmp / "packs" / CM.PACK)
        self.assertEqual(t["quote"], ["QUOTE-AAA", "QUOTE-BBB"])
        self.assertEqual(t["news"], ["NEWS-Aaa", "NEWS-Aaa~all", "NEWS-Bbb", "NEWS-Bbb~all", "NEWS-X@de-DE", "NEWS-X@de-DE~all", "NEWS-Extra"])

    def test_a_capture_politely_writes_stamped_sorted_snapshots_and_the_manifest(self):
        slept: list[float] = []
        out: list[str] = []
        code = CM.run(self.args("--delay", "0.5"), say=out.append, sleep=slept.append)
        self.assertEqual(code, 2, "one id could not be captured (404): the rest is written, exit 2")
        self.assertIn("news NEWS-Bbb~all", " ".join(out))
        # politeness: never under 2 s between calls (--delay 0.5 is raised to 2), and the 429 waits 5 s before trying again
        gaps = [s for s in slept if s != 5]
        self.assertTrue(gaps and all(1.5 < s <= 2.0 for s in gaps), slept)
        self.assertIn(5, slept)
        self.assertEqual(Stub.calls.count("quote QUOTE-BBB"), 2)
        self.assertEqual(Stub.calls.count("news NEWS-Bbb~all"), 1, "a 404 is not retried")
        # files: the old snapshot replaced, one document a line sorted by id, every document stamped
        quote_files = sorted((self.td / CM.PACK / "quote").glob("snapshot-*.jsonl.gz"))
        self.assertEqual(len(quote_files), 1)
        self.assertNotIn("2026-01-01", quote_files[0].name)
        quotes = [json.loads(x) for x in gzip.decompress(quote_files[0].read_bytes()).decode().splitlines()]
        self.assertEqual([q["QUOTE_ID"] for q in quotes], ["QUOTE-AAA", "QUOTE-BBB"])
        q = quotes[0]
        self.assertTrue(q["SNAPSHOT"].startswith("Snapshot captured ") and q["SNAPSHOT"].endswith("UTC from Yahoo Finance"), q["SNAPSHOT"])
        self.assertTrue(q["SNAPSHOT_CAPTURED_AT"].endswith("Z"))
        self.assertIn("(snapshot captured ", q["AS_OF"])
        self.assertTrue(q["AS_OF"].startswith("2026-10-16 16:00"))
        self.assertIn("not live", q["PROVENANCE"]["SNAPSHOT"])
        self.assertEqual(q["PROVENANCE"]["FEED"], "Yahoo Finance public chart endpoint (unofficial)")
        news = [json.loads(x) for x in gzip.decompress(next((self.td / CM.PACK / "news").glob("snapshot-*.jsonl.gz")).read_bytes()).decode().splitlines()]
        self.assertEqual([n["NEWS_ID"] for n in news], sorted(["NEWS-Aaa", "NEWS-Aaa~all", "NEWS-Bbb", "NEWS-X@de-DE", "NEWS-X@de-DE~all", "NEWS-Extra"]))
        self.assertTrue(all("(snapshot captured " in n["FETCHED_AT"] and "Google News" in n["SNAPSHOT"] for n in news))
        # the manifest: the header kept, the sample dataset kept, the old quote entry replaced by the two captured datasets
        import yaml
        text = (self.td / "testdata.yaml").read_text(encoding="utf-8")
        self.assertTrue(text.startswith("# the header\n# stays\n"))
        ds = yaml.safe_load(text)["datasets"]
        self.assertEqual([d["kind"] for d in ds], ["news-topic", "quote", "news"])
        quote = ds[1]
        self.assertEqual((quote["folder"], quote["connector"], quote["key"], quote["documents"]), ("news-and-markets/quote", "news-store", "QUOTE_ID", 2))
        self.assertEqual(quote["snapshot"], quote_files[0].name)
        self.assertTrue(quote["captured"].endswith("Z") and "Yahoo Finance" in quote["source"])

    def test_a_dry_run_fetches_nothing(self):
        out: list[str] = []
        self.assertEqual(CM.run(self.args("--dry-run"), say=out.append, sleep=lambda s: None), 0)
        self.assertEqual(Stub.calls, [])
        self.assertIn("2 quote, 7 news", out[0])

    def test_stamping_twice_marks_once(self):
        from datetime import datetime, timezone
        at = datetime(2026, 10, 17, 14, 5, tzinfo=timezone.utc)
        d = CM.stamp("quote", CM.stamp("quote", {"QUOTE_ID": "QUOTE-A", "AS_OF": "2026-10-16 16:00"}, at), at)
        self.assertEqual(d["AS_OF"], "2026-10-16 16:00 (snapshot captured 2026-10-17)")
        self.assertEqual(d["SNAPSHOT"], "Snapshot captured 2026-10-17 14:05 UTC from Yahoo Finance")

    def test_placeholders_of_a_pack_template_resolve_to_their_defaults(self):
        self.assertEqual(CM.resolve("${DRISHTI_NEWS_URL:https://news.google.com/rss/search}"), "https://news.google.com/rss/search")
        self.assertEqual(CM.resolve("${drishti.branding.product:Drishti} news connector (sample)"), "Drishti news connector (sample)")
        self.assertEqual(CM.resolve(10), 10)


if __name__ == "__main__":
    unittest.main()
