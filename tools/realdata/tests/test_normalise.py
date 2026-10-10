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

"""Tests of the normaliser against small recorded fixtures (responses trimmed from the real APIs); no network."""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import normalise as N  # noqa: E402

FX = HERE / "fixtures"


def jload(name):
    return json.loads((FX / name).read_text(encoding="utf-8"))


def test_wb_countries_split_countries_and_groups():
    countries, groups = N.wb_countries(jload("wb_country.json"))
    assert set(countries) == {"IND", "USA"}
    assert countries["IND"]["region"] == "SAS" and countries["IND"]["income"] == "LMC"
    assert countries["IND"]["capital"] == "New Delhi" and countries["IND"]["iso2"] == "IN"
    assert groups["SAS"]["type"] == "region" and groups["HIC"]["type"] == "income" and groups["WLD"]["type"] == "world"


def test_wb_series_drops_nulls_scales_and_keeps_only_wanted_codes():
    s, updated = N.wb_series(jload("wb_gdp.json"), 1e-9, {"IND", "SAS"})
    assert updated == "2026-10-08"
    assert set(s) == {"IND", "SAS"}
    assert "2025" not in s["IND"]                                  # a null observation is dropped
    assert s["IND"]["2024"] == 3760.81                              # USD scaled to billions, six significant digits
    assert list(s["IND"]) == sorted(s["IND"])


def test_imf_series_keeps_the_year_window():
    s = N.imf_series(jload("imf_ngdp_rpch.json"), "NGDP_RPCH", {"IND"}, 2015, 2031)
    assert s["IND"]["2015"] == 8 and s["IND"]["2031"] == 6.5 and "1990" not in s["IND"]
    assert "WEOWORLD" not in s


def test_undp_hdi_reads_the_year_columns():
    out = N.undp_hdi((FX / "undp_hdi.csv").read_text(encoding="utf-8"), {"IND", "USA"}, 2015, 2023)
    assert out["IND"]["2023"] == 0.685 and out["USA"]["2015"] == 0.928
    assert "ZZZ" not in out


def test_clean_removes_html_and_joins_lines():
    assert N.clean("Aam Aadmi Party or AAP<br>All India Trinamool Congress or AITC") == "Aam Aadmi Party or AAP; All India Trinamool Congress or AITC"
    assert N.clean("<strong>note:</strong> in 2023&nbsp;both") == "note: in 2023 both"
    assert N.clean(None) == ""


def test_parse_seats_and_dates():
    rows = N.parse_seats("Bharatiya Janata Party (BJP) (240); Indian National Congress (INC) (99); Other (138)")
    assert rows[0] == {"party": "Bharatiya Janata Party (BJP)", "seats": 240} and rows[-1]["seats"] == 138
    assert N.parse_date("18 July 2022") == "2022-07-18" and N.parse_date("4/19/2024 to 6/1/2024") == "2024-04-19"
    assert N.parse_date("July 2027") == ""


def test_factbook_profile_of_india():
    p = N.factbook_profile(jload("factbook_in.json"))
    assert p["capital"] == "New Delhi" and p["governmentType"] == "federal parliamentary republic"
    assert p["chiefOfState"].startswith("President Droupadi MURMU") and p["headOfGovernment"].startswith("Prime Minister")
    assert p["currency"] == "INR" and p["areaKm2"] == 3287263.0
    low = p["lowerChamber"]
    assert low["name"] == "House of the People (Lok Sabha)" and low["parties"][0]["seats"] == 240 and low["lastElectionIso"] == "2024-04-19"
    assert p["parties"][0] == "Aam Aadmi Party or AAP"


def test_factbook_matches_world_bank_countries_by_internet_code():
    countries, _ = N.wb_countries(jload("wb_country.json"))
    p = N.factbook_profile(jload("factbook_in.json"))
    out, unmatched = N.factbook_to_iso3({"in": p, "zz": {"shortName": "Nowhere", "internetCode": ".zz"}}, countries)
    assert list(out) == ["IND"] and out["IND"]["gec"] == "IN" and unmatched == ["zz"]


def test_sig_is_compact_and_stable():
    assert N.sig(1234567.891) == 1234570 and N.sig(0.040829123456) == 0.0408291 and N.sig(0) == 0.0
