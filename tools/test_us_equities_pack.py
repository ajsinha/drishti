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

"""The us-equities pack's generator: make.py --check passes with no network and no cache, the annual values are read from XBRL facts
as documented, and the pack commits no prices, no index weights and no GICS."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GEN = ROOT / "tools" / "packgen" / "us_equities"
PACK = ROOT / "config" / "packs" / "us-equities"
sys.path.insert(0, str(ROOT / "tools" / "publicdata"))
from us_equities import F  # noqa: E402  (the generator's fetch.py by path: tools/realdata has a fetch module too)
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import publicsnap as SNAP  # noqa: E402  (the pack's data: data/testdata/us-equities/<kind>/)


def test_make_check_passes_without_a_cache(tmp_path):
    r = subprocess.run([sys.executable, str(GEN / "make.py"), "--check", "--cache", str(tmp_path / "none")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "matches its generator" in r.stdout


def test_annual_values_take_a_year_long_10k_period_the_latest_filing_and_the_first_tag():
    facts = {"us-gaap": {
        "Revenues": {"units": {"USD": [
            {"start": "2023-01-01", "end": "2023-12-31", "val": 100, "form": "10-K", "filed": "2024-02-01", "accn": "a"},
            {"start": "2023-01-01", "end": "2023-12-31", "val": 101, "form": "10-K", "filed": "2025-02-01", "accn": "b"},   # restated: wins
            {"start": "2023-10-01", "end": "2023-12-31", "val": 30, "form": "10-K", "filed": "2024-02-01", "accn": "a"},    # a quarter: no
            {"start": "2024-01-01", "end": "2024-12-31", "val": 120, "form": "10-Q", "filed": "2025-02-01", "accn": "q"}]}},  # not a 10-K
        "SalesRevenueNet": {"units": {"USD": [
            {"start": "2022-01-01", "end": "2022-12-31", "val": 90, "form": "10-K", "filed": "2023-02-01", "accn": "c"},
            {"start": "2023-01-01", "end": "2023-12-31", "val": 999, "form": "10-K", "filed": "2024-02-01", "accn": "d"}]}}}}
    rows = F._annual(facts, ["Revenues", "SalesRevenueNet"], "USD", False)
    assert [(r["year"], r["value"], r["tag"]) for r in rows] == [(2022, 90, "SalesRevenueNet"), (2023, 101, "Revenues")]


def test_the_pack_commits_no_prices_weights_or_gics():
    snap = SNAP.TESTDATA / "us-equities"
    assert {p.name for p in snap.iterdir()} == {"us-company", "index-membership", "filing-feed", "filing", "financial-fact"}
    names = {p.name for p in (PACK / "samples").iterdir()}
    assert not any(n.startswith("price-history") for n in names)
    catalog = json.loads((PACK / "samples" / "catalog.json").read_text(encoding="utf-8"))
    assert {e["kind"] for e in catalog} <= {"us-company", "index-membership", "sic-sector", "filing-feed", "filing", "financial-fact"}
    cos = SNAP.kind_docs("us-equities", "us-company")
    assert len(cos) > 400
    assert not any(k in d for d in cos for k in ("PRICE", "CLOSE", "WEIGHT", "GICS", "MARKET_CAP"))
    assert all("WEIGHT" not in json.dumps(d) for d in SNAP.kind_docs("us-equities", "index-membership"))
    size = sum(p.stat().st_size for p in snap.rglob("*.jsonl.gz"))         # compressed
    assert size < 4_000_000, size
    assert sum(p.stat().st_size for p in (PACK / "samples").iterdir()) < 1_000_000   # reference documents only


def test_a_foreign_banks_annual_report_is_a_20f_or_40f_in_its_own_currency():
    facts = {"ifrs-full": {"Assets": {"units": {"CAD": [
        {"end": "2024-10-31", "val": 2_000, "form": "40-F", "filed": "2024-12-04", "accn": "a"},
        {"end": "2025-10-31", "val": 2_100, "form": "40-F", "filed": "2025-12-03", "accn": "b"},
        {"end": "2025-07-31", "val": 2_050, "form": "6-K", "filed": "2025-08-27", "accn": "q"}]}}}}     # a quarter's 6-K: no
    rows = F._annual(facts, ["Assets", "ifrs-full:Assets"], "USD", True)
    assert [(r["year"], r["value"], r["unit"], r["form"]) for r in rows] == [(2024, 2_000, "CAD", "40-F"), (2025, 2_100, "CAD", "40-F")]
    assert F._unit({"GBP/shares": [], "USD/shares": []}, "USD/shares") == "USD/shares"       # USD when the filer gives it
    assert F._unit({"GBP/shares": []}, "USD/shares") == "GBP/shares"


def test_the_bank_lists_are_in_the_pack_with_their_currencies():
    idx = {d["INDEX"]: d for d in SNAP.kind_docs("us-equities", "index-membership")}
    assert {"BANKS-US", "BANKS-CA", "BANKS-UK"} <= set(idx)
    assert {"RY", "TD", "BNS", "BMO", "CM"} <= {r["TICKER"] for r in idx["BANKS-CA"]["ROWS"]}
    assert {"HSBC", "BCS", "LYG", "NWG"} <= {r["TICKER"] for r in idx["BANKS-UK"]["ROWS"]}
    assert {"JPM", "BAC", "C", "WFC", "GS", "MS"} <= {r["TICKER"] for r in idx["BANKS-US"]["ROWS"]}
    cos = {d["TICKER"]: d for d in SNAP.kind_docs("us-equities", "us-company")}
    assert cos["RY"]["CURRENCY"] == "CAD" and cos["RY"]["ECONOMY"] == "ECON-CAN" and cos["RY"]["ASSETS"]
    assert cos["JPM"]["CURRENCY"] == "USD"
