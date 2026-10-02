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


def _value(page: str, attr: str) -> str:
    return re.search(r'<input[^>]*' + attr + r'[^>]*>', page).group(0).split('value="')[1].split('"')[0]


def _source(page: str) -> str:
    return page.split('id="sutraSrc"')[1].split("</textarea>")[0]


def test_studio_opens_on_an_entity_of_an_installed_pack(client, with_packs):
    with_packs("banking-core", "market-data", "trading")
    page = client.get("/studio").text
    assert "IRS-48213" not in page and "irs-vanilla" not in _source(page)
    assert (_value(page, "data-kind"), _value(page, "data-id")) == ("counterparty", "CP-NORTHBRIDGE")   # banking-core's first example


def test_studio_without_any_example_opens_empty_with_a_hint(client, with_packs):
    with_packs("no-such-pack")
    page = client.get("/studio").text
    assert "IRS-48213" not in page and _value(page, "data-id") == ""
    assert "data-studio-empty" in page and "Type a kind and an id" in page
    assert "productType" not in _source(page)                         # the new-Sutra template names no pack's fields


def test_studio_keeps_an_entity_asked_for(client, with_packs):
    with_packs("trading")
    page = client.get("/studio", params={"kind": "trade", "id": "MX-20000005"}).text
    assert _value(page, "data-id") == "MX-20000005" and "data-studio-empty" not in page


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
