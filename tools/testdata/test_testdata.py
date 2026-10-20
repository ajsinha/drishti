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

"""Guards on the committed test data (data/testdata): small files, a manifest that matches them, 5,000 production-shaped trades per
source system with unique ids and real amendments, UPPER_SNAKE attributes everywhere, the six look-alike counterparties with their
netting sets, and NO real data: every counterparty is a fictitious CP- id, and when the user's real extracts are on this machine
(~/Downloads/endur.json, ComSwap.json) none of their trade ids or counterparty names appears anywhere in the test data. Stdlib and
PyYAML only.

    python3 -m unittest -q tools/testdata/test_testdata.py
"""
from __future__ import annotations

import gzip
import json
import re
import sys
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TD = ROOT / "data" / "testdata"
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "trades"))
import schema as TS  # noqa: E402

UPPER = re.compile(r"^[A-Z][A-Z0-9_]*$")
REAL = [Path.home() / "Downloads" / "endur.json", Path.home() / "Downloads" / "ComSwap.json"]
LOOKALIKES = ("CP-VAULTGUARD", "CP-HARBOURLINE-LDN", "CP-HOUSE", "CP-METROEX", "CP-PRAIRIEGAS", "CP-NORTHPLAINS")


def manifest() -> dict:
    import yaml
    return yaml.safe_load((TD / "testdata.yaml").read_text(encoding="utf-8"))


def lines(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                yield json.loads(line)


def rebuild(folder: str, key: str, dates: list[str], upto: int | None = None) -> list[dict]:
    """The dataset's documents on day ``upto`` (default the last): the snapshot, then each day's changes by key."""
    state: dict[str, dict] = {}
    for i, day in enumerate(dates[: (len(dates) - 1 if upto is None else upto) + 1]):
        for f in sorted((TD / folder).glob(f"{'snapshot' if i == 0 else 'changes'}-{day}*.jsonl.gz")):
            for d in lines(f):
                state[str(d[key])] = d
    return list(state.values())


def names(v):
    if isinstance(v, dict):
        for k, x in v.items():
            yield k
            yield from names(x)
    elif isinstance(v, list):
        for x in v:
            yield from names(x)


@unittest.skipUnless((TD / "testdata.yaml").is_file(), "no data/testdata")
class TestData(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = manifest()
        cls.dates = cls.m["business-dates"]

    def test_files_are_small_and_the_whole_within_budget(self):
        files = list(TD.rglob("*.jsonl.gz"))
        big = [f"{f.relative_to(TD)} {f.stat().st_size / 1e6:.1f} MB" for f in files if f.stat().st_size > 10 * 1024 * 1024]
        self.assertEqual(big, [], "a file above 10 MB: lower --part-mb")
        total = sum(f.stat().st_size for f in files)
        self.assertLess(total, 150e6, f"{total / 1e6:.1f} MB committed")   # sixty days, about 115 MB; GitHub refuses files over 100 MB, none is over 10
        self.assertEqual(sorted(p.name for p in TD.iterdir() if p.is_file()), ["README.md", "testdata.yaml"])

    def test_the_manifest_matches_the_files_and_the_packs(self):
        import yaml
        self.assertEqual(len(self.dates), 60)
        packs = {}
        for ds in self.m["datasets"]:
            spec = packs.setdefault(ds["pack"], yaml.safe_load((ROOT / "config" / "packs" / ds["pack"] / "pack.yaml").read_text(encoding="utf-8")))
            conn = spec["connectors"][ds["connector"]]
            self.assertEqual(conn["plugin"], "delta", ds)
            self.assertIn(ds["kind"], conn["kinds"], ds)
            if "samples" in ds:
                self.assertTrue((ROOT / ds["samples"]).is_dir(), ds)
                continue
            if "snapshot" in ds:
                self.assertTrue((TD / ds["folder"] / ds["snapshot"]).is_file(), ds)
                continue
            folder = TD / ds["folder"]
            self.assertTrue(list(folder.glob(f"snapshot-{self.dates[0]}*.jsonl.gz")), ds["folder"])
            for day in self.dates[1:]:
                self.assertTrue(list(folder.glob(f"changes-{day}*.jsonl.gz")), f"{ds['folder']} {day}")
        self.assertEqual({ds["pack"] for ds in self.m["datasets"]},
                         {"core", "market-data", "trading", "counterparty-risk", "market-risk", "world-economy", "news-and-markets"})

    def test_three_source_systems_of_5000_production_shaped_trades_amended_over_the_days(self):
        ids: list[str] = []
        for system in ("endur", "calypso", "murex"):
            last = rebuild(f"trades/{system}", "TRADE_ID", self.dates)
            first = {str(t["TRADE_ID"]): t for t in rebuild(f"trades/{system}", "TRADE_ID", self.dates, 0)}
            self.assertEqual(len(last), 5000, system)
            self.assertEqual({t["SOURCE_SYSTEM"] for t in last}, {system.upper()})
            problems = [p for t in last for p in TS.problems(t)]
            self.assertEqual(problems[:5], [], system)
            amended = [t for t in last if str(t["TRADE_ID"]) in first and t["TRADE_VERSION"] != first[str(t["TRADE_ID"])]["TRADE_VERSION"]]
            self.assertGreater(len(amended), 500, f"{system}: amendments across the days")
            self.assertGreater(len(last) - len(first), 0, f"{system}: trades booked after the first day")
            ids += [str(t["TRADE_ID"]) for t in last]
            if system == "endur":
                self.assertEqual(set(Counter(t["PRODUCT_TYPE"] for t in last)), {"ComSwap", "ComFut", "Cash", "CallNot", "Commodity"})
        self.assertEqual(len(ids), len(set(ids)), "trade ids unique across the systems")

    def test_a_days_change_follows_the_price_and_is_not_one_figure_every_day(self):
        """QA1-10: CHANGE_1D is the day's move of the walked price (equity PRICE, index LEVEL, FX MID), not the last day's figure on all."""
        for folder, key, price in (("market-data/equity", "TICKER", "PRICE"), ("market-data/equity-index", "INDEX_ID", "LEVEL"),
                                   ("market-data/fx-spot", "PAIR", "MID")):
            days = [{str(d[key]): d for d in rebuild(folder, key, self.dates, i)} for i in range(5)]
            some = sorted(days[0])[0]
            changes = {days[i][some]["CHANGE_1D"] for i in range(5)}
            self.assertGreater(len(changes), 3, f"{folder} {some}: {changes}")
            for i in range(1, 5):
                today, before = days[i][some], days[i - 1][some]
                self.assertAlmostEqual(today["CHANGE_1D"], today[price] / before[price] - 1, places=4, msg=f"{folder} {some} day {i}")

    def test_every_attribute_is_upper_snake(self):
        for ds in self.m["datasets"]:
            if "folder" not in ds:
                continue
            f = TD / ds["folder"] / ds["snapshot"] if "snapshot" in ds else sorted((TD / ds["folder"]).glob(f"snapshot-{self.dates[0]}*.jsonl.gz"))[0]
            for n, d in enumerate(lines(f)):
                bad = sorted({k for k in names(d) if not UPPER.match(k)})
                self.assertEqual(bad, [], f"{ds['folder']}: {bad[:5]}")
                if n > 200:
                    break

    def test_the_lookalike_counterparties_with_netting_sets_of_endur_trades(self):
        cps = {d["COUNTERPARTY_ID"]: d for d in rebuild("core/counterparty", "COUNTERPARTY_ID", self.dates)}
        sets = {d["NETTING_SET_ID"]: d for d in rebuild("counterparty-risk/netting-set", "NETTING_SET_ID", self.dates)}
        endur = {str(t["TRADE_ID"]): t for t in rebuild("trades/endur", "TRADE_ID", self.dates)}
        for cp in LOOKALIKES:
            d = cps[cp]
            self.assertTrue(d["UEN"].startswith("UEN") and d["COUNTRY_OF_RISK"] and d["LEGAL_ENTITY"].startswith("LE-"), d)
            mine = [sets[s["ID"]] for s in d["NETTING_SETS"]]
            held = [t for s in mine for t in s["TRADE_IDS"] if t in endur]
            self.assertTrue(held, f"{cp}: a netting set holding ENDUR trades")
            self.assertTrue(all(endur[t]["COUNTERPARTY"] == cp for t in held))
        uens = [d["UEN"] for d in cps.values()]
        self.assertEqual(len(uens), len(set(uens)), "UEN unique")

    def test_the_captured_market_snapshot_covers_every_company_and_topic(self):
        snaps = {ds["kind"]: ds for ds in self.m["datasets"] if "snapshot" in ds}
        if not snaps:
            self.skipTest("no captured market snapshot (tools/testdata/capture_market.py)")
        self.assertEqual(set(snaps), {"quote", "news"})
        sys.path.insert(0, str(ROOT / "tools" / "testdata"))
        import capture_market as CM
        want = CM.targets(ROOT / "config" / "packs" / "news-and-markets")
        for kind, ds in snaps.items():
            self.assertEqual((ds["pack"], ds["connector"]), ("news-and-markets", "news-store"))
            docs = {d[ds["key"]]: d for d in lines(TD / ds["folder"] / ds["snapshot"])}
            self.assertEqual(len(docs), ds["documents"])
            self.assertTrue(re.fullmatch(r"snapshot-\d{4}-\d{2}-\d{2}\.jsonl\.gz", ds["snapshot"]), ds["snapshot"])
            self.assertLess(ds["bytes"], 3 * 1024 * 1024, "keep the snapshot small")
            gone = [i for i in want[kind] if i not in docs]
            self.assertLessEqual(len(gone), len(want[kind]) // 10, f"{kind}: not captured {gone}")
            for d in docs.values():
                self.assertTrue(d["SNAPSHOT"].startswith("Snapshot captured ") and d["SNAPSHOT_CAPTURED_AT"].endswith("Z"), d[ds["key"]])
                self.assertIn("SNAPSHOT", d["PROVENANCE"])
        quotes = [d for d in lines(TD / snaps["quote"]["folder"] / snaps["quote"]["snapshot"])]
        self.assertTrue(all(len(q["HISTORY"]) >= 200 for q in quotes), "about a year of daily bars per company")

    def test_no_real_data_in_the_test_data(self):
        cps = {d["COUNTERPARTY_ID"] for d in rebuild("core/counterparty", "COUNTERPARTY_ID", self.dates)}
        self.assertTrue(all(c.startswith("CP-") for c in cps), sorted(c for c in cps if not c.startswith("CP-")))
        for system in ("endur", "calypso", "murex"):
            for t in rebuild(f"trades/{system}", "TRADE_ID", self.dates):
                self.assertTrue(str(t["COUNTERPARTY"]).startswith("CP-"), t["TRADE_ID"])
        real = [p for p in REAL if p.is_file()]
        if not real:
            self.skipTest("no real extract on this machine: the structural checks above stand alone")
        ids, parties = set(), set()
        for p in real:
            rows = json.loads(p.read_text(encoding="utf-8"))
            for t in rows if isinstance(rows, list) else [rows]:
                ids.add(str(t.get("TRADE_ID")))
                for f in ("COUNTERPARTY", "ORIGINAL_COUNTERPARTY"):
                    if t.get(f):
                        parties.add(str(t[f]))
        ours = {str(t["TRADE_ID"]) for s in ("endur", "calypso", "murex") for f in (TD / "trades" / s).glob("*.jsonl.gz") for t in lines(f)}
        self.assertEqual(sorted(ids & ours), [], "a real trade id is in data/testdata")
        needles = [json.dumps(p).encode() for p in parties]
        for f in TD.rglob("*.jsonl.gz"):
            body = gzip.decompress(f.read_bytes())
            hits = [n for n in needles if n in body]
            self.assertEqual(hits, [], f"{f.relative_to(TD)} names a real counterparty")


if __name__ == "__main__":
    unittest.main()
