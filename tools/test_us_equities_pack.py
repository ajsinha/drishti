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
    names = {p.name for p in (PACK / "samples").iterdir()}
    assert not any(n.startswith("price-history") for n in names)
    catalog = json.loads((PACK / "samples" / "catalog.json").read_text(encoding="utf-8"))
    assert {e["kind"] for e in catalog} == {"us-company", "index-membership", "sic-sector", "filing-feed", "filing", "financial-fact"}
    first = json.loads((PACK / "samples" / "us-company.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert not any(k in first for k in ("PRICE", "CLOSE", "WEIGHT", "GICS", "MARKET_CAP"))
    assert all("WEIGHT" not in line for line in (PACK / "samples" / "index-membership.jsonl").read_text(encoding="utf-8").splitlines())
    size = sum(p.stat().st_size for p in (PACK / "samples").iterdir())
    assert size < 18_000_000, size
