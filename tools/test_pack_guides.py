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

"""Every pack guide is complete (the product owner's request of 2026-10-26: data contract, data retrieval and details for all packs):
each pack with contracts has a guide listed in its help entry that ends with the generated Data contracts and Data retrieval sections,
naming every kind and every column of its contracts; every stored kind has an origin in data_retrieval.yaml (an external source or a
generator); every external source names a refresh script and a licence; the packs without a guide generator are current
(guide_data.py --check); every pack README points to its guide's two sections and to the External data page when it has external
sources."""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


GD = _load("drishti_guide_data", ROOT / "tools" / "packgen" / "common" / "guide_data.py")
PACKS = sorted(d for d in (ROOT / "config" / "packs").iterdir() if (d / "contracts").is_dir() and list((d / "contracts").glob("*.contract.yaml")))
SPEC = GD.spec()


def test_the_ten_packs_with_contracts_are_all_here():
    assert [p.name for p in PACKS] == sorted(SPEC["packs"]), "a pack with contracts needs its origins in data_retrieval.yaml"


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_the_guide_has_both_sections_with_every_kind_and_column(pack):
    guide = pack / "guides" / f"{pack.name}.md"
    assert guide.is_file(), f"{pack.name}: no guide"
    helps = yaml.safe_load((pack / "config" / "help.yaml").read_text()) or {}
    assert f"guides/{pack.name}.md" in [g.get("file") for g in helps.get("guides") or []], f"{pack.name}: the guide is not in its help.yaml"
    text = guide.read_text()
    assert "\n## Data contracts\n" in text and "\n## Data retrieval\n" in text, pack.name
    assert text.index("## Data contracts") < text.index("## Data retrieval")
    contracts = text[text.index("## Data contracts"):text.index("## Data retrieval")]
    retrieval = text[text.index("## Data retrieval"):]
    for c in GD.contracts(pack):
        assert f"### {c['mnemonic']}: " in contracts and f"(`{c['kind']}`)" in contracts, f"{pack.name}: {c['kind']}"
        for f in c.get("fields") or []:
            assert f"| `{f['name']}` |" in contracts, f"{pack.name}/{c['kind']}: column {f['name']}"
        assert f"| `{c['mnemonic']}` | `{c['kind']}` |" in retrieval, f"{pack.name}: {c['kind']} has no origin row"
    assert "DATA_GUIDE.md" in retrieval and "lake demo" in retrieval and "Offline" in retrieval


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_every_stored_kind_has_an_origin_and_every_source_a_script(pack):
    for c in GD.contracts(pack):
        if c.get("class") in ("computed", "drishti-owned"):
            continue
        o = GD._origin(SPEC, pack.name, c)
        assert o.get("external") or o.get("generator"), f"{pack.name}/{c['kind']}: no origin in data_retrieval.yaml"
        for i in (o.get("external") or []) + (o.get("official") or []):
            e = SPEC["external"][i]
            assert e["licence"] and e["url"].startswith("https://") and (ROOT / e["script"]).is_file(), i
        if o.get("generator"):
            assert o["generator"] in SPEC["generators"], o


def test_internal_only_sources_are_the_imf_and_yahoo_and_google_news():
    internal = sorted(i for i, e in SPEC["external"].items() if e.get("internal"))
    assert internal == ["google-news", "imf", "yahoo-prices", "yahoo-quote"]


def test_the_packs_without_a_guide_generator_are_current():
    assert GD.main(["--check"]) == 0


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_the_readme_points_to_the_guide_and_the_external_data_page(pack):
    text = (pack / "README.md").read_text()
    assert f"(guides/{pack.name}.md)" in text and "*Data contracts*" in text and "*Data retrieval*" in text, pack.name
    externals = {i for o in [(SPEC["packs"][pack.name].get("default") or {})] + list((SPEC["packs"][pack.name].get("kinds") or {}).values())
                 for i in (o.get("external") or []) + (o.get("official") or [])}
    if externals:
        assert "docs/guides/DATA_GUIDE.md" in text, pack.name
