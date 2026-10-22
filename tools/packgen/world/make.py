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
"""The world-economy pack: the economics half (tools/packgen/economics/make.py: economies, indicators, IMF forecasts, labour
markets, fiscal positions, country groups, regional economies) and the politics-and-society half (tools/packgen/politics/make.py:
jurisdictions, parties, office holders, elections, regions, social and governance indicators) in one pack. A core counterparty's
`economy` field (its country of risk) opens the country's economy here.

    python3 tools/packgen/world/make.py            write config/packs/world-economy
    python3 tools/packgen/world/make.py --check    fail if the pack differs from what would be written
    python3 tools/realdata/realdata.py all         refresh the data, then write the pack
"""
from __future__ import annotations

import dataclasses
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _half(name: str):
    path = HERE.parent / name / "make.py"
    spec = importlib.util.spec_from_file_location(f"world_{name}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ECO = _half("economics")
POL = _half("politics")
PB, T, PR = ECO.PB, ECO.T, ECO.PR
sys.path.insert(0, str(HERE.parent / "common"))
import upper as U  # noqa: E402  (the pack writes UPPER_SNAKE names; the two halves build with their short ones)


def build() -> dict[str, dict[str, dict]]:
    return {kind: {i: U.record(d) for i, d in ds.items()} for kind, ds in {**ECO.build(), **POL.build()}.items()}


def _upper(e: PB.PackSpec) -> PB.PackSpec:
    """A half's spec in the names the documents carry: kinds, key columns and form overrides."""
    return dataclasses.replace(
        e, domains={d: [U.kind(k) for k in ks] for d, ks in e.domains.items()},
        columns={k: [U.name(f) for f in v] for k, v in e.columns.items()},
        form_overrides={k: {U.name(f): x for f, x in v.items()} for k, v in e.form_overrides.items()})


def spec() -> PB.PackSpec:
    e, p = _upper(ECO.spec()), _upper(POL.spec())
    return dataclasses.replace(
        e, name="world-economy", code="WRLD", title="World economy", generator="tools/packgen/world/make.py",
        description=e.description.rstrip(".") + "; and governments and societies: government type, office holders, elections, parties with seats, "
                    "and social and governance indicators (CIA World Factbook, World Bank, UNDP).",
        domains={**e.domains, **p.domains}, examples=e.examples + p.examples, columns={**e.columns, **p.columns},
        form_overrides={**e.form_overrides, **p.form_overrides}, roles={**e.roles, **p.roles},
        # the economics screens, written by hand from the Drishti UI kit (config/ui-kit/fragments) since Sutra Next phase 1 (SutraNextEquivalenceTest)
        hand_written={"country-group", "economic-forecast", "economy", "fiscal-position", "labour-market", "macro-indicator", "region-economy"},
        # the columns the shipped BI models read (config/bi/datasets/world-economics-economies, region-economics-regions): promoted, so a
        # report reads them as columns instead of opening every document
        layouts={"economy": ["NAME", "ISO3", "REGION_NAME", "INCOME_NAME", "GDP_USD_BN", "GDP_PER_CAPITA", "GROWTH", "INFLATION", "UNEMPLOYMENT", "POPULATION_M"],
                 "region-economy": ["ISO3", "COUNTRY_NAME", "REGION_CODE", "NAME", "POPULATION", "GDP_USD_BN", "GDP_PER_CAPITA", "YEAR", "SOURCE"]},
        overview=e.overview + "\n\n" + p.overview + "\n\nA counterparty of the core pack names its country of risk (`COUNTRY_OF_RISK`) and "
                 "links to that country's economy (`ECONOMY`), so `CPTY CP-NORTHBRIDGE` opens the United States economy in one click.")


if __name__ == "__main__":
    PR.run(PB, "world", spec(), build(), T.graph_fields())
