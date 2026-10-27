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

"""Tests of the reproducible pipeline: the committed glossary, Sutra tests and guide tables equal what the tools generate, normalising a
small recorded cache is deterministic, and --no-imf leaves out every IMF record and reference. No network."""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import about as A  # noqa: E402
import build_docs as BD  # noqa: E402
import doc_snippets as DS  # noqa: E402
import normalise as N  # noqa: E402
import pack_tests as PT  # noqa: E402
import realdata as RD  # noqa: E402

FX = HERE / "fixtures"


def mini_cache(tmp: Path) -> Path:
    """A raw cache of the recorded fixtures laid out as fetch.py would."""
    for src, dst in (("wb_country.json", "wb_country.json"), ("wb_gdp.json", "wb/NY.GDP.MKTP.CD.json"), ("imf_ngdp_rpch.json", "imf/NGDP_RPCH.json"),
                     ("undp_hdi.csv", "undp_hdi.csv"), ("factbook_in.json", "factbook/in.json")):
        (tmp / dst).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(FX / src, tmp / dst)
    (tmp / "fetch.json").write_text(json.dumps({"wb/NY.GDP.MKTP.CD.json": "2026-10-10", "imf/NGDP_RPCH.json": "2026-10-10"}), encoding="utf-8")
    return tmp


def test_normalise_is_deterministic_and_no_imf_leaves_the_imf_out(tmp_path):
    cache, cfg = mini_cache(tmp_path / "cache"), N.load_config()
    N.run(cache, cfg, tmp_path / "a")
    N.run(cache, cfg, tmp_path / "b")
    N.run(cache, cfg, tmp_path / "c", no_imf=True)
    for f in (tmp_path / "a").iterdir():
        assert f.read_bytes() == (tmp_path / "b" / f.name).read_bytes(), f.name
    assert {f.name for f in (tmp_path / "a").iterdir()} == {"meta.json", "countries.jsonl", "groups.jsonl", "series.jsonl", "forecast.jsonl",
                                                            "factbook.jsonl"}                    # JSON Lines records; the manifest
    assert N.read("forecast", tmp_path / "a")["f_gdp_growth"]["IND"]
    assert N.read("forecast", tmp_path / "c") == {}
    assert (tmp_path / "c" / "forecast.jsonl").read_text() == ""
    assert N.read("series", tmp_path / "c")["gdp"]["IND"]                                       # World Bank series stay
    assert json.loads((tmp_path / "c" / "meta.json").read_text())["vintage"]["imf"] == " edition"        # nothing read from the IMF


def test_generated_glossary_is_what_is_committed():
    assert A.check() == []
    for pack in A.PACKS:
        assert yaml.safe_load(A.render(pack))["vocabulary"]


def test_no_imf_glossary_cites_no_imf_data():
    eco = yaml.safe_load(A.render("world", no_imf=True))
    assert "economic-forecast" not in eco["kinds"] and "debt" not in eco["vocabulary"] and "net_lending" not in eco["vocabulary"]
    assert "debtToGdp" not in eco["kinds"]["economy"]["glossary"]
    text = A.render("world", no_imf=True).replace("reserve position in the IMF", "")      # a definition of the reserves, not IMF data
    assert "IMF" not in text and "Monetary Fund" not in text
    assert "jurisdiction" in eco["kinds"] and "social-indicator" in eco["kinds"]             # the politics half stays whole


def test_sutra_tests_are_what_is_committed():
    for pack in RD.GENERATORS:
        assert PT.check(pack, RD.pack_docs(pack)) == [], pack


def test_guide_tables_are_what_is_committed():
    assert DS.check(BD.Data())
    assert "| `gdp` | GDP, current prices | USD bn | World Bank WDI `NY.GDP.MKTP.CD` |" in DS.render(BD.Data())["indicators-economics"]


def test_no_imf_documents_carry_no_imf():
    d = BD.Data(no_imf=True)
    eco = BD.economics(d)
    assert not eco["economic-forecast"] and eco["fiscal-position"]["FISC-USA"].keys().isdisjoint({"debt", "balance", "debtPath", "balancePath"})
    assert "debtToGdp" not in eco["economy"]["ECON-IND"] and "outlook" not in eco["economy"]["ECON-IND"]
    blob = json.dumps(eco, ensure_ascii=False).replace("reserve position in the IMF", "")
    assert "IMF" not in blob and "Monetary Fund" not in blob and "imf" not in blob.lower().replace("imfree", "")
    assert BD.economics(BD.Data(no_imf=False))["economic-forecast"]                         # the default build still has them
    assert json.dumps(BD.politics(d)) == json.dumps(BD.politics(BD.Data(no_imf=False)))     # world-economy is the same either way
