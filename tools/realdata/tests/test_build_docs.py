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

"""Tests of the document builders against the committed dataset (tools/realdata/dataset): no network, no invented values."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import build_docs as BD  # noqa: E402

D = BD.Data()
ECO = BD.economics(D)
POL = BD.politics(D)


def test_every_document_carries_its_provenance():
    for docs in list(ECO.values()) + list(POL.values()):
        for doc in docs.values():
            p = doc["provenance"]
            assert p["source"] and p["dataset"] and p["licence"] and p["attribution"] and p["retrievedAt"], doc
            assert doc["_meta"]["licence"] == p["licence"] and doc["_meta"]["retrievedAt"] == p["retrievedAt"]


def test_economy_values_are_the_dataset_values():
    e = ECO["economy"]["ECON-IND"]
    assert e["growth"] == D.val("gdp_growth", "IND") and e["gdpUsdBn"] == D.val("gdp", "IND")
    assert e["regionGroup"] == "ECG-SAS" and e["incomeGroup"] == "ECG-LMC"
    assert e["growthPath"][0]["year"] == "2015" and ECO["economy"]["ECON-USA"]["name"] == "United States"


def test_links_point_at_documents_that_exist():
    kinds = {k: set(v) for k, v in ECO.items()}
    groups = {f"ECG-{g}" for g in D.G}                    # the country groups are computed from these keys (since 2.9.2)
    for e in ECO["economy"].values():
        for f in ("regionGroup", "incomeGroup"):
            assert e[f] is None or e[f] in groups, (e["economyId"], f)
        for f, k in (("labour", "labour-market"), ("fiscal", "fiscal-position"), ("outlook", "economic-forecast")):
            assert e[f] is None or e[f] in kinds[k], (e["economyId"], f)
        assert all(i in kinds["macro-indicator"] for i in e["indicators"])


def test_no_country_group_is_stored():
    assert "country-group" not in ECO                     # computed from the economies when asked (GROUPINGS_ANALYTICS_GUIDE.md)


def test_politics_documents_are_published_facts():
    j = POL["jurisdiction"]["JUR-IND"]
    assert j["capital"] == "New Delhi" and j["currency"] == "INR" and j["legislature"][0]["seats"] == 240
    assert POL["office-holder"]["OFFC-USA-COS"]["holder"].startswith("President")
    assert POL["election"]["ELEC-IND-LOW"]["date"] == "2024-04-19"
    assert POL["region"]["REGN-SAS"]["countries"] >= 6


def test_percentages_stay_as_published():
    s = POL["social-indicator"]["SOCI-IND-URBAN"]
    assert s["latest"] == D.val("urban", "IND") and 0 < s["latest"] <= 100
