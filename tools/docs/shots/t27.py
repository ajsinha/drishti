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

"""Pictures of tutorial 27, groupings and analytics computed (docs/tutorials/27-groupings-and-analytics.md), written to
docs/tutorials/img/t27/. They need a server and console with the public-data packs on the demo lake
(DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif) and sign-in off:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t27

The pictures, in the tutorial's order: an ownership group computed from its entities, every group, a country, two sectors, a company's
computed margin and growth, a World Bank region, a factor set's correlations (Pearson over every return, then Ledoit-Wolf)."""
from __future__ import annotations

import os

from _tut import OUT, go
from shotlib import ROOT, Ctx, register

shot = register("t27", OUT / "t27")
# the lake `drishti.py lake demo` built (DRISHTI_SHOTS_DELTA_ROOT names another), read with the demo connector off
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false", "DRISHTI_DEMO_ENABLED": "false",
              "DRISHTI_DELTA_ROOT": os.environ.get("DRISHTI_SHOTS_DELTA_ROOT", str(ROOT / "data" / "delta")),
              "DRISHTI_PACKS": "core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif"}


def view(c: Ctx, kind: str, ident: str, name: str, wait: str = "[data-panel]"):
    go(c, f"/v/{kind}/{ident}", wait=wait, settle=2500)
    c.save(name)


@shot("01-leig-group.jpg")
def group(c: Ctx):
    view(c, "lei-group", "8I5DZWZKVSZI1NUHU748", "01-leig-group.jpg")


@shot("02-leig-all.jpg")
def every_group(c: Ctx):
    view(c, "lei-group", "ALL", "02-leig-all.jpg")


@shot("03-leic-country.jpg")
def country(c: Ctx):
    view(c, "lei-country", "GB", "03-leic-country.jpg")


@shot("04-sic-sector.jpg")
def sector(c: Ctx):
    view(c, "sic-sector", "3674", "04-sic-sector.jpg")


@shot("05-sic-currency.jpg")
def sector_currency(c: Ctx):
    view(c, "sic-sector", "6022", "05-sic-currency.jpg")


@shot("06-useq-ratios.jpg")
def company(c: Ctx):
    view(c, "us-company", "AAPL", "06-useq-ratios.jpg")


@shot("07-ecg-region.jpg")
def region(c: Ctx):
    view(c, "country-group", "ECG-SAS", "07-ecg-region.jpg")


@shot("08-corr-pearson.jpg")
def corr(c: Ctx):
    view(c, "correlation-matrix", "CORR-EU-EQ", "08-corr-pearson.jpg")


@shot("10-equity-beta.jpg")
def equity_beta(c: Ctx):
    go(c, "/v/equity/EQ-NVTK", settle=2000)
    c.page.wait_for_timeout(3000)                                  # the Beta panel fills when the engine answers
    c.save("10-equity-beta.jpg")


@shot("09-corr-ledoit-wolf.jpg")
def corr_lw(c: Ctx):
    go(c, "/v/correlation-matrix/CORR-EU-EQ?ctl.estimator=ledoit-wolf", settle=2000)   # the control set, as a click leaves it in the address
    c.until("document.body.innerText.indexOf('ledoit-wolf, 29 daily returns') >= 0", 60)   # the panels asked again and answered
    c.page.wait_for_timeout(800)
    c.save("09-corr-ledoit-wolf.jpg")
