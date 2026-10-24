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

"""The common trade schema holds for every source system: ENDUR, CALYPSO and MUREX trades carry exactly the 47 top-level attributes, the
same nesting and upper-snake names at every level; ids are unique across the systems; ENDUR has its five product types; amendments over
business days change versions and values; the pack's samples are the same shape.

    python3 -m unittest tools/packgen/trades/test_trades.py
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import refdata as R  # noqa: E402,F401  (paths)
import amend as A  # noqa: E402
import make_trades as M  # noqa: E402
import schema as S  # noqa: E402
from endur import PRODUCTS, Endur  # noqa: E402

ROOT = HERE.parents[2]


class CommonSchemaTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.books = {s: M.book(s, 160, "2026-09-24") for s in S.SYSTEMS}

    def test_every_system_writes_exactly_the_common_attributes(self):
        for system, trades in self.books.items():
            for t in trades:
                self.assertEqual(list(t), S.TOP, f"{system} {t['TRADE_ID']}")
                self.assertEqual(S.problems(t), [], system)
                self.assertEqual(t["SOURCE_SYSTEM"], system)
                self.assertNotIn("BMO_LEGAL_ENTITY", json.dumps(t))
        self.assertEqual(len(S.TOP), 47)

    def test_ids_are_unique_across_systems(self):
        ids = [str(t["TRADE_ID"]) for trades in self.books.values() for t in trades]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(isinstance(t["TRADE_ID"], str) for b in self.books.values() for t in b))     # ids are text, ENDUR digits too
        self.assertTrue(all(t["TRADE_ID"].startswith("CLY-") for t in self.books["CALYPSO"]))
        self.assertTrue(all(t["TRADE_ID"].startswith("MX-") for t in self.books["MUREX"]))

    def test_endur_has_its_five_product_types_with_blanks_and_text_numbers(self):
        samples = Endur().samples(2)
        self.assertEqual(sorted({t["PRODUCT_TYPE"] for t in samples}), sorted(PRODUCTS))
        flat = json.dumps(samples)
        self.assertIn('""', flat)                                                    # blanks spelt as empty text
        self.assertTrue(any(isinstance(t["TRANSACTION_ID"], str) and t["TRANSACTION_ID"].isdigit() for t in samples))
        swaps = [t for t in self.books["ENDUR"] if t["PRODUCT_TYPE"] == "ComSwap"]
        self.assertTrue(swaps and all(t["LEGS"][0]["NOTIONAL"] > 0 for t in swaps))   # realistic, not the extract's zeros

    def test_calypso_and_murex_cover_the_product_range_with_flows(self):
        types = {t["PRODUCT_TYPE"] for s in ("CALYPSO", "MUREX") for t in M.templates(s, "2026-09-30")}
        self.assertGreaterEqual(len(types), 120)
        for s in ("CALYPSO", "MUREX"):
            self.assertTrue(all(t["LEGS"] and all(leg["CASH_FLOWS"] for leg in t["LEGS"]) for t in self.books[s]), s)
            self.assertTrue(any(r for t in self.books[s] for leg in t["LEGS"] for f in leg["CASH_FLOWS"] for r in f["RESETS"]), s)
            self.assertTrue(any(e["AMOUNT"] != "" and e["AMOUNT"] < 0 for t in self.books[s] for e in t["EVENTS"]), s)

    def test_amendments_over_days_change_versions_and_values(self):
        days = M.business_days(R.AS_OF, 7)
        changed = 0
        for t in self.books["MUREX"] + self.books["ENDUR"]:
            h = A.history(t, days)
            self.assertEqual(len(h), 7)
            if h[-1] is not t:
                changed += 1
                self.assertEqual(h[-1]["TRADE_ACTION"], "AMEND")
                self.assertGreater(int(h[-1]["TRADE_VERSION"]), int(t["TRADE_VERSION"] or 1))
                self.assertEqual(S.problems(h[-1]), [])
        self.assertGreater(changed, 20)

    def test_the_packs_trade_samples_have_the_common_schema(self):
        samples = ROOT / "config" / "packs" / "trading" / "samples"
        systems = set()
        docs = [(f.name, json.loads(f.read_text(encoding="utf-8"))) for f in (samples / "trade").glob("*.json")]        # one file per deal (before 2.3)
        docs += [(f"trade.jsonl:{n}", json.loads(line)) for f in samples.glob("trade*.jsonl")                          # samples/<kind>.jsonl (2.3)
                 for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1) if line.strip()]
        for name, d in docs:
            d.pop("_meta", None)
            self.assertEqual(S.problems(d), [], name)
            systems.add(d["SOURCE_SYSTEM"])
        self.assertEqual(systems, set(S.SYSTEMS))

    def test_every_product_and_sub_type_of_the_generators_has_exactly_one_sutra(self):
        """Each (source system, product type) the generators book is matched by one Sutra file of the pack, and each Endur sub type
        shows its own panel (when:) inside its product's one Sutra: no inferred trade, no catch-all, no two Sutras for one."""
        import re
        sutras = ROOT / "config" / "packs" / "trading" / "sutras"
        matches: dict[tuple[str, str], list[str]] = {}
        whens: dict[str, set[str]] = {}
        for f in sutras.rglob("*.sutra.yaml"):
            text = f.read_text(encoding="utf-8")
            if not re.search(r"^match:.*kind: trade,", text, re.M):     # a trade page, not a screen that reads trades (book-desk)
                continue
            m = re.search(r"\$\.SOURCE_SYSTEM == '([A-Z]+)' && \$\.PRODUCT_TYPE == '([^']+)'", text)
            self.assertIsNotNone(m, f"{f.name} matches trades without naming a system and a product type")
            matches.setdefault((m.group(1), m.group(2)), []).append(f.name)
            whens[m.group(2)] = set(re.findall(r"when: \"\$\.PRODUCT_SUB_TYPE == '([^']+)'\"", text))
        for system, trades in self.books.items():
            for t in trades + (Endur().samples(2) if system == "ENDUR" else []):
                key = (t["SOURCE_SYSTEM"], t["PRODUCT_TYPE"])
                self.assertEqual(len(matches.get(key, [])), 1, f"{key}: {matches.get(key)}")
                if system == "ENDUR":
                    self.assertIn(t["PRODUCT_SUB_TYPE"], whens[t["PRODUCT_TYPE"]], f"{key} {t['PRODUCT_SUB_TYPE']} has no panel of its own")
        from endur import SUB_TYPES
        for product, subs in SUB_TYPES.items():
            self.assertEqual(set(subs), whens[product], product)
        self.assertEqual(len(matches), 130)                    # 125 products of the taxonomy and Endur's five, one Sutra each

    def test_the_three_trade_connectors_each_carry_their_own_layout(self):
        """A layout shared as a YAML anchor reached only the first connector (the others read no columns, so drop-downs and pivots
        saw one system): the manifest spells it out for each."""
        import yaml
        text = (ROOT / "config" / "packs" / "trading" / "pack.yaml").read_text(encoding="utf-8")
        self.assertNotRegex(text, r"[&*]id\d")
        conns = yaml.safe_load(text)["connectors"]
        for name in ("trade-endur", "trade-calypso", "trade-murex"):
            self.assertIn("SOURCE_SYSTEM", conns[name]["settings"]["layout"]["trade"]["columns"], name)


if __name__ == "__main__":
    unittest.main()
