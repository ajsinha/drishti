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

"""The pictures of the real-data guide (docs/guides/PUBLIC_DATA.md): real countries in the world-economy pack, written to
docs/guides/img/realdata/. They need a server and console with that pack (DRISHTI_PACKS=world-economy):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide realdata --base http://127.0.0.1:17987
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shotlib import Ctx, register  # noqa: E402

shot = register("realdata")


def view(c: Ctx, kind: str, id_: str, name: str, panel: str | None = None):
    c.page.goto(f"{c.base}/v/{kind}/{id_}")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.save(name)


@shot("economy-india.jpg")
def economy_india(c: Ctx):
    view(c, "economy", "ECON-IND", "economy-india.jpg")


@shot("economy-usa.jpg")
def economy_usa(c: Ctx):
    view(c, "economy", "ECON-USA", "economy-usa.jpg")


@shot("economy-nigeria.jpg")
def economy_nigeria(c: Ctx):
    view(c, "economy", "ECON-NGA", "economy-nigeria.jpg")


@shot("forecast-japan.jpg")
def forecast_japan(c: Ctx):
    view(c, "economic-forecast", "FCST-JPN-WEO", "forecast-japan.jpg")


@shot("group-south-asia.jpg")
def group_south_asia(c: Ctx):
    view(c, "country-group", "ECG-SAS", "group-south-asia.jpg")


@shot("jurisdiction-india.jpg")
def jurisdiction_india(c: Ctx):
    view(c, "jurisdiction", "JUR-IND", "jurisdiction-india.jpg")


@shot("jurisdiction-brazil.jpg")
def jurisdiction_brazil(c: Ctx):
    view(c, "jurisdiction", "JUR-BRA", "jurisdiction-brazil.jpg")


@shot("election-brazil.jpg")
def election_brazil(c: Ctx):
    view(c, "election", "ELEC-BRA-LOW", "election-brazil.jpg")


@shot("indicator-nigeria-rule-of-law.jpg")
def indicator_nigeria(c: Ctx):
    view(c, "social-indicator", "SOCI-NGA-WGI-LAW", "indicator-nigeria-rule-of-law.jpg")


@shot("form-economics.jpg")
def form_economics(c: Ctx):
    """FORM world-economy economy: real growth above 6% among economies larger than USD 100 bn."""
    c.page.goto(f"{c.base}/form/world-economy?kind=economy")
    c.page.locator('[name="op.GROWTH"]').wait_for(timeout=30000)
    c.page.locator('[name="op.GROWTH"]').select_option(">")
    c.page.locator('[name="v.GROWTH"]').fill("6")
    c.page.locator('[name="op.GDP_USD_BN"]').select_option(">")
    c.page.locator('[name="v.GDP_USD_BN"]').fill("100")
    c.page.locator("button.qf-go").click()
    c.page.locator("table tbody tr").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1000)
    print("  rows:", c.page.locator("table tbody tr").count(), "|", c.page.locator("table tbody").first.inner_text()[:300].replace("\n", " "))
    c.save("form-economics.jpg")
