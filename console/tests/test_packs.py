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

"""Domain packs in the console: examples, workspace starters, help guides and About follow the enabled packs."""


def test_examples_starters_and_guides_come_from_packs(client, backend):
    home = client.get("/t").text
    assert "TRD IRS-48213" in home and "SHP SHP-10042" in home and "Logistics · ocean freight" in home
    starters = client.get("/w").text
    assert "Credit desk" in starters and "Shipment tracker" in starters
    assert client.get("/help/getting-started").status_code == 200
    assert client.get("/help/logistics-pack").status_code == 200
    assert "Domain packs" in client.get("/help").text and client.get("/help/packs").status_code == 200
    assert "Finance · capital markets" in client.get("/about").text


def test_without_packs_the_console_stays_neutral(client, backend):
    saved = backend.enabled_packs
    try:
        backend.enabled_packs = []
        client.app.state.packs._cache = {}
        home = client.get("/t").text
        assert "No domain pack is enabled" in home and "TRD IRS-48213" not in home
        assert "Credit desk" not in client.get("/w").text
        assert client.get("/help/getting-started").status_code == 404
        assert client.get("/help/context/terminal", follow_redirects=False).headers["location"] == "/help/using-the-terminal"
    finally:
        backend.enabled_packs = saved
        client.app.state.packs._cache = {}


def test_pack_switcher_chooses_active_packs(client, backend):
    saved = backend.enabled_packs
    try:
        client.app.state.packs._cache = {}
        home = client.get("/t").text
        assert "data-packs" in home and 'value="logistics" checked' in home
        assert client.post("/api/packs", json={"active": ["logistics"]}).json()["active"] == ["logistics"]
        home = client.get("/t").text
        assert "SHP SHP-10042" in home and "TRD IRS-48213" not in home
        assert client.post("/api/packs", json={"active": []}).status_code == 403
    finally:
        backend.enabled_packs = saved
        client.app.state.packs._cache = {}
