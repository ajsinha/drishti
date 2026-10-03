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

"""Example entities come from the installed packs (UX-05): Studio's first preview and the landing page's commands name
only entities of packs that are switched on, never a sample id of a pack that may be absent."""
import re


def _land(client, url, **params):
    r = client.get(url, params=params, follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"].startswith("/build/d/")
    return client.get(r.headers["location"]).text


def test_studio_names_no_entity_of_a_pack_that_may_be_absent(client, with_packs, monkeypatch):
    monkeypatch.setattr(client.app.state.examples, "default", "")   # no default example
    for packs in (("banking-core", "market-data", "trading"), ("no-such-pack",)):
        with_packs(*packs)
        page = _land(client, "/studio")
        assert "IRS-48213" not in page and "CP-NORTHBRIDGE" not in page and "irs-vanilla" not in page
        source = page.split("data-yaml-src")[1].split("</textarea>")[0]
        assert "sutra: my-layout" in source and "productType" not in source              # the new-Sutra template names no pack's fields


def test_studio_keeps_an_entity_asked_for(client, with_packs):
    with_packs("trading")
    page = _land(client, "/studio", kind="curve", id="USD-SOFR")
    assert "curve USD-SOFR" in page and "stored entity" in page


def test_landing_commands_come_from_the_installed_packs(client, with_packs):
    with_packs("trading")
    page = client.get("/").text
    assert "IRS-48213" not in page and "FXS-20931" not in page and "CFT-77120" not in page
    assert "TRD MX-20000001 &lt;GO&gt;" in page and "data-commands=" in page
    assert "IRS-48213" not in client.get("/static/js/landing.js").text


def test_landing_without_packs_names_no_entity(client, with_packs):
    with_packs("no-such-pack")
    page = client.get("/").text
    assert "IRS-48213" not in page and "TRD MX-" not in page and 'data-commands="' in page
