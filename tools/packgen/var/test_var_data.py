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

"""The committed VaR demo inputs (data/testdata/market-risk/, tools/packgen/var/make_var_data.py) are what an upstream batch delivers
and nothing more (docs/guides/VAR_GUIDE.md):

- one P&L vector per live trade (every one: statuses NEW, VALIDATED, AMENDED, not matured), 509 finite values each, one batch run,
  unique trade ids; since 2.9.4 the Endur trades come as scenario PVs in their own currency instead (scenario-pv), with the supplied
  scenario FX of every currency they are in (fx-scenario): a trade in exactly one of the two forms, and a USD trade's P&L from its
  scenario PVs is whole USD, as its vector was;
- securities and listed trades carry a SECURITY_ID several trades share, so the positions the pack's rules derive (as the server does)
  include fungible ones netting buys and sells; OTC trades carry none (a position each);
- no position, no var or var-node table, and no figure the VaR engine computes on any input (portfolios are definitions);
- about 25 to 30 portfolios of the three types, each resolving on the date (nodes exist, rules parse, basket members exist), overlapping;
- the rule language and the scope's measures agree with the server's (the same hand-made answers as VarMathTest).

    python3 -m pytest tools/packgen/var/test_var_data.py -q          (pyyaml and pytest)
"""
import gzip
import json
import sys
import unittest
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import positions as P  # noqa: E402
import rules as R  # noqa: E402
import scenario_fx as SFX  # noqa: E402
import varmath as VM  # noqa: E402

TD = ROOT / "data" / "testdata" / "market-risk"
#: what only the VaR engine produces: never on an input (the guard of docs/contracts/README.md section 14)
ENGINE_FIGURES = {"VAR_99", "ES_975", "COMPONENT_VAR", "BOOK_VAR", "PCT_OF_BOOK_VAR", "PCT_OF_VAR", "HYPO_PNL", "EXCEPTIONS_250", "LIMIT_USE",
                  "TOP_CONTRIBUTORS"}


def docs(kind: str) -> list[dict]:
    out = []
    for f in sorted((TD / kind).glob("*.jsonl.gz")):
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            out += [json.loads(line) for line in fh if line.strip()]
    return out


def keys(d, prefix="") -> set[str]:
    out = set()
    if isinstance(d, dict):
        for k, v in d.items():
            out.add(k)
            out |= keys(v)
    elif isinstance(d, list):
        for x in d:
            out |= keys(x)
    return out


class VarDataTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.rules = P.Rules.of_pack(ROOT / "config" / "packs" / "market-risk" / "pack.yaml")
        cls.vector_rows = docs("pnl-vector")
        cls.pv_rows = docs("scenario-pv")
        cls.fx = {r["CURRENCY"]: r for r in docs("fx-scenario")}
        # every trade with the columns a position reads; a scenario-PV trade's PV is its USD value today, as the server takes it
        cls.rows = cls.vector_rows + [{**r, "PV": r["BASE_PV"] * cls.rate(r["CURRENCY"])} for r in cls.pv_rows]
        cls.vectors = {v["TRADE_ID"]: v for v in cls.rows}
        cls.nodes = {n["NODE_ID"]: n for n in docs("risk-hierarchy")}
        cls.portfolios = {p["PORTFOLIO_ID"]: p for p in docs("portfolio")}
        cls.positions: dict[str, list[dict]] = defaultdict(list)
        for v in sorted(cls.rows, key=lambda r: r["TRADE_ID"]):
            d = cls.rules.derive(v["TRADE_ID"], v["TT_BOOK"], v["PRODUCT_GROUP"], v["PRODUCT_TYPE"], v.get)
            cls.positions[d["POSITION_ID"]].append(v)

    @classmethod
    def rate(cls, ccy: str, j: int = -1) -> float:
        if ccy == "USD":
            return 1.0
        return cls.fx[ccy]["RATE"] if j < 0 else cls.fx[ccy]["RATES"][j]

    def test_every_live_trade_once_with_509_finite_values_from_one_run(self):
        self.assertEqual(len(self.vectors), len(self.rows), "a trade id twice (or in both forms)")
        self.assertGreater(len(self.rows), 9000)
        cob = self.rows[0]["BUSINESS_DATE"]
        for v in self.vector_rows:
            self.assertEqual(len(v["PNL"]), 509, v["TRADE_ID"])
            self.assertTrue(all(isinstance(x, (int, float)) for x in v["PNL"]), v["TRADE_ID"])
            self.assertIn(v["TRADE_STATUS"], P.LIVE, v["TRADE_ID"])
            self.assertGreaterEqual(str(v["MATURITY_DATE"] or "9999"), cob, v["TRADE_ID"])
            self.assertEqual((v["REPORTING_CCY"], v["STATUS"], v["SCENARIO_SET_ID"]), ("USD", "ok", "HS-509"))
            self.assertNotIn("POSITION_ID", v)                               # Drishti derives it
        self.assertEqual({v["BATCH_ID"] for v in self.rows}, {f"B-{cob}-01"})
        self.assertEqual({v["SCENARIO_SET_ID"] for v in self.rows}, {"HS-509"})

    def test_scenario_pvs_in_the_trades_currency_with_the_supplied_fx(self):
        self.assertGreater(len(self.pv_rows), 1000)
        self.assertEqual({r["SOURCE_SYSTEM"] for r in self.pv_rows}, set(SFX.PV_SYSTEMS))
        self.assertFalse({r["SOURCE_SYSTEM"] for r in self.vector_rows} & set(SFX.PV_SYSTEMS))
        self.assertGreater(sum(1 for r in self.pv_rows if r["CURRENCY"] != "USD"), 100, "scenario PVs in other currencies")
        cob = self.rows[0]["BUSINESS_DATE"]
        for c, f in self.fx.items():
            self.assertEqual(len(f["RATES"]), 509, c)
            self.assertTrue(f["RATE"] > 0 and all(x > 0 for x in f["RATES"]), c)
            self.assertEqual((f["SCENARIO_SET_ID"], f["BUSINESS_DATE"]), ("HS-509", cob))
        for r in self.pv_rows:
            t = r["TRADE_ID"]
            self.assertEqual(len(r["SCENARIO_PV"]), 509, t)
            self.assertTrue(all(isinstance(x, (int, float)) for x in r["SCENARIO_PV"]), t)       # no scenario failed to price
            self.assertEqual((r["VALUATION_DATE"], r["BASE_VALUATION_DATE"], r["STATUS"]), (cob, cob, "ok"), t)
            self.assertTrue(r["CURRENCY"] == "USD" or r["CURRENCY"] in self.fx, f"{t}: no scenario FX for {r['CURRENCY']}")
            self.assertNotIn("PNL", r)
            if r["CURRENCY"] == "USD":                                      # a USD trade's P&L is whole USD, as its vector was
                pnl = [x - r["BASE_PV"] for x in r["SCENARIO_PV"]]
                self.assertTrue(all(abs(x - round(x)) < 1e-6 for x in pnl), t)

    def test_a_usd_trade_sent_as_scenario_pvs_reproduces_its_vector(self):
        """The generator itself, on a sample of every book: the engine's P&L from a USD trade's files is its factor-model vector exactly,
        and another currency's adds the move of its rate on the trade's value."""
        import yaml
        sys.path.insert(0, str(HERE))
        import make_var_data as M
        td = ROOT / "data" / "testdata"
        demo = M.Demo(td, yaml.safe_load((td / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"], budget=3)
        b = demo.batch
        usd = [r for r in b.pv_rows if r["CURRENCY"] == "USD"]
        other = [r for r in b.pv_rows if r["CURRENCY"] != "USD"]
        self.assertTrue(usd and other)
        for r in usd:
            self.assertEqual(b.engine_vectors[r["TRADE_ID"]][:demo.n], [float(x) for x in b.trade_vectors[r["TRADE_ID"]][:demo.n]])
        r = other[0]
        e = SFX.engine_pnl(demo.fx, r["CURRENCY"], r["BASE_PV"], r["SCENARIO_PV"])
        self.assertEqual(e, b.engine_vectors[r["TRADE_ID"]][:demo.n])
        self.assertNotEqual([round(x) for x in e], b.trade_vectors[r["TRADE_ID"]][:demo.n])

    def test_securities_share_ids_and_the_rules_derive_fungible_positions(self):
        for v in self.rows:
            rule = self.rules.rule(v["PRODUCT_GROUP"], v["PRODUCT_TYPE"])
            keyed = rule is not None and "SECURITY_ID" in rule["key"]
            self.assertEqual(bool(v.get("SECURITY_ID")), keyed, v["TRADE_ID"])
        shared = Counter(v["SECURITY_ID"] for v in self.rows if v.get("SECURITY_ID"))
        self.assertGreater(sum(1 for n in shared.values() if n > 1), 50, "securities several trades hold")
        fungible = [p for p, ts in self.positions.items() if len(ts) > 1]
        both = [p for p, ts in self.positions.items() if {t["QUANTITY"] > 0 for t in ts} == {True, False}]
        self.assertGreater(len(fungible), 100)
        self.assertGreater(len(both), 20, "positions netting buys and sells")
        self.assertTrue(all(p.split("~", 1)[1] == ts[0]["TRADE_ID"] for p, ts in self.positions.items() if not ts[0].get("SECURITY_ID")))
        # a position's trades are of one book and one security; its vector is their sum and nets long and short
        pid = next(p for p in both if all("PNL" in t for t in self.positions[p]))     # a position of P&L vectors
        ts = self.positions[pid]
        self.assertEqual(len({t["TT_BOOK"] for t in ts}), 1)
        self.assertEqual(len({t["SECURITY_ID"] for t in ts}), 1)
        self.assertLess(VM.var(VM.add(t["PNL"] for t in ts)), sum(VM.var(t["PNL"]) for t in ts))

    def test_no_input_carries_a_figure_and_the_retired_tables_are_gone(self):
        for kind in ("pnl-vector", "scenario-pv", "fx-scenario", "portfolio", "risk-hierarchy", "scenario-set"):
            found = set().union(*(keys(d) for d in docs(kind))) & ENGINE_FIGURES
            self.assertEqual(found, set(), kind)
        for kind in ("position", "var-node", "var"):
            self.assertFalse((TD / kind).exists(), kind)

    def test_portfolios_of_every_type_resolve_and_overlap(self):
        self.assertTrue(25 <= len(self.portfolios) <= 30, len(self.portfolios))
        self.assertEqual({p["TYPE"] for p in self.portfolios.values()}, {"HIERARCHY", "RULE", "BASKET"})
        derived = {pid: self.position_doc(pid, ts) for pid, ts in self.positions.items()}
        members = {}
        for pid, pf in self.portfolios.items():
            d = pf["DEFINITION"]
            if pf["TYPE"] == "HIERARCHY":
                books = set(self.nodes[d["NODE"]]["BOOKS"])
                m = {t["TRADE_ID"] for t in self.rows if t["TT_BOOK"] in books}
            elif pf["TYPE"] == "RULE":
                rule = R.Rule(d["RULE"])
                m = {t["TRADE_ID"] for p, doc in derived.items() if rule.matches(doc) for t in self.positions[p]}
            else:
                m = set()
                for x in d["MEMBERS"]:
                    if "POSITION_ID" in x:
                        self.assertIn(x["POSITION_ID"], self.positions, pid)
                        m |= {t["TRADE_ID"] for t in self.positions[x["POSITION_ID"]]}
                    else:
                        self.assertIn(x["TRADE_ID"], self.vectors, pid)
                        m.add(x["TRADE_ID"])
            self.assertTrue(m, f"{pid} holds nothing on the date")
            self.assertGreater(pf["LIMIT_VAR"], 0, pid)
            members[pid] = m
        self.assertTrue(members["PF-RATES-DESK"] & members["PF-ASHA-UNWIND"], "the basket overlaps the desk on purpose")
        self.assertTrue(members["PF-USD-RATES"] & members["PF-RATES-DESK"])
        per_le = [p for p in self.portfolios if p.startswith("PF-LE-")]
        self.assertEqual(len(per_le), 4)
        self.assertEqual(set().union(*(members[p] for p in per_le)), set(self.vectors))       # the legal entities cover the bank

    @staticmethod
    def position_doc(pid: str, ts: list[dict]) -> dict:
        """The columns a rule reads of a derived position (the server's VarBook.POSITION_TEXTS and POSITION_NUMBERS)."""
        t0 = ts[0]
        q = sum(t["QUANTITY"] for t in ts)
        tags = sorted({x for t in ts for x in (t.get("TAGS") or "").split("|") if x})
        return {**{k: t0.get(k) for k in ("LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY",
                                           "STRATEGY", "SECURITY_ID", "INSTRUMENT")},
                "CURRENCY": t0.get("TRADE_CURRENCY") or t0.get("CURRENCY"),   # a P&L row's own currency (its CURRENCY is the values')
                "DIRECTION": "LONG" if q > 0 else "SHORT" if q < 0 else "FLAT", "QUANTITY": q, "TAGS": tags,
                "MARKET_VALUE": sum(t["PV"] for t in ts), "TRADE_COUNT": len(ts),
                "POSITION_RULE": VarDataTest.rules.derive(t0["TRADE_ID"], t0["TT_BOOK"], t0["PRODUCT_GROUP"], t0["PRODUCT_TYPE"], t0.get)["POSITION_RULE"]}

    def test_the_samples_are_the_servers_derived_positions(self):
        sample = [json.loads(x) for x in (ROOT / "config" / "packs" / "market-risk" / "samples" / "position.jsonl").read_text().splitlines() if x]
        self.assertEqual(len(sample), 2)
        for p in sample:
            ts = self.positions[p["POSITION_ID"]]
            self.assertEqual(p["TRADE_IDS"], [t["TRADE_ID"] for t in ts])
            self.assertAlmostEqual(p["QUANTITY"], sum(t["QUANTITY"] for t in ts), places=2)
            self.assertEqual(keys(p) & ENGINE_FIGURES, set())

    def test_the_rule_language(self):
        p = {"PRODUCT_GROUP": "Rates", "CURRENCY": "USD", "DIRECTION": "SHORT", "MARKET_VALUE": 2_500_000.0, "TAGS": ["hedge"]}
        self.assertTrue(R.Rule("PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')").matches(p))
        self.assertTrue(R.Rule("'hedge' in TAGS and not DIRECTION == 'LONG'").matches(p))
        self.assertTrue(R.Rule("MARKET_VALUE > 1000000").matches(p))
        self.assertFalse(R.Rule("COUNTERPARTY == 'CP-X'").matches(p))
        self.assertTrue(R.Rule("COUNTERPARTY != 'CP-X'").matches(p))

    def test_the_position_rules_are_the_servers(self):
        r = P.Rules({"live-statuses": ["NEW"], "rules": [{"name": "securities", "groups": ["Fixed income"], "key": ["SECURITY_ID", "ISIN"]},
                                                         {"name": "otc"}]})
        self.assertEqual(r.derive("T1", "B", "fixed income", "GOVT_BOND", {"ISIN": "US1"}.get)["POSITION_ID"], "POS-B~US1")
        self.assertEqual(r.derive("T2", "B", "Fixed income", "CORP_BOND", {}.get), {"POSITION_ID": "POS-B~T2", "INSTRUMENT_ID": "T2",
                                                                                 "SECURITY_ID": None, "POSITION_RULE": "otc"})
        self.assertEqual(r.derive("T3", "B", "Rates", "IRS", {}.get)["POSITION_RULE"], "otc")
        self.assertTrue(r.is_live(None) and r.is_live("new") and not r.is_live("VALIDATED"))

    def test_the_measures_follow_the_servers_rules(self):
        v = [0.0] * 100
        for i in range(100):
            v[(i * 37) % 100] = -(i + 1)
        self.assertEqual(VM.var(v, 0.99), 100)
        self.assertEqual(VM.var(v, 0.95), 96)
        self.assertEqual(VM.es(v, 0.95), (100 + 99 + 98 + 97 + 96) / 5)
        self.assertEqual(VM.rank_k(509, 0.99), 6)
        self.assertEqual(VM.rank_k(509, 0.975), 13)


class FormsPerSystemTest(unittest.TestCase):
    """The P&L-vector generation stays whole (product owner, 2026-10-27): with --all-vectors every system regenerates as P&L vectors
    exactly as in 2.9.3 (the bank 112,035,906 / 160,132,107 on the test data's date, every trade a vector, no scenario PV); the form is a
    choice per system (--pv-systems, --pnl-systems), today's split the default."""

    @classmethod
    def setUpClass(cls):
        import yaml
        import make_var_data as M
        cls.M = M
        dates = yaml.safe_load((ROOT / "data" / "testdata" / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
        cls.vectors = M.Demo(ROOT / "data" / "testdata", dates, pv_systems=M.pv_systems("ENDUR", all_vectors=True))
        cls.both = M.Demo(ROOT / "data" / "testdata", dates, pv_systems=M.pv_systems("ENDUR,CALYPSO"))

    def test_every_system_as_p_and_l_vectors_is_2_9_3(self):
        b = self.vectors.batch
        self.assertEqual(b.pv_rows, [])
        self.assertEqual(len(b.vector_rows), 9572)
        self.assertEqual(self.M.bank_figures(self.vectors),
                         "VaR 112,035,906 ES 160,132,107 (all as P&L vectors: VaR 112,035,906 ES 160,132,107)")
        self.assertIn("ENDUR", {r["SOURCE_SYSTEM"] for r in b.vector_rows})

    def test_the_form_is_a_choice_per_system(self):
        self.assertEqual(self.M.pv_systems("ENDUR"), ("ENDUR",))
        self.assertEqual(self.M.pv_systems("ENDUR,CALYPSO", "endur"), ("CALYPSO",))
        self.assertEqual(self.M.pv_systems("ENDUR", all_vectors=True), ())
        b = self.both.batch
        self.assertEqual({r["SOURCE_SYSTEM"] for r in b.pv_rows}, {"ENDUR", "CALYPSO"})
        self.assertEqual({r["SOURCE_SYSTEM"] for r in b.vector_rows}, {"MUREX"})
        self.assertEqual(len(b.pv_rows) + len(b.vector_rows), 9572)


if __name__ == "__main__":
    unittest.main()
