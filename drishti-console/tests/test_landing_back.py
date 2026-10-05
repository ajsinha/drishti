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

"""The logo always leads to the landing page; a signed-in visitor gets a way back to the page they left."""
from routes.home_routes import back_label


def test_the_logo_carries_the_page_when_signed_in_and_the_landing_page_offers_the_way_back(client):
    view = client.get("/v/trade/IRS-48213").text
    assert 'class="tbar-brand" href="/?from=/v/trade/IRS-48213"' in view
    landing = client.get("/?from=/v/trade/IRS-48213").text
    assert 'data-landing-back' in landing and 'href="/v/trade/IRS-48213"' in landing and "Back to IRS-48213" in landing



def test_an_unsafe_or_pointless_from_offers_no_way_back(client):
    for bad in ("https://evil.example/x", "//evil.example", "/\\\\evil", "/", "/login"):
        assert "data-landing-back" not in client.get("/", params={"from": bad}).text, bad


def test_labels_name_the_page():
    assert back_label("/v/trade/MX-20000001?asOf=2026-10-01") == "MX-20000001"
    assert back_label("/build/d/abc") == "Build"
    assert back_label("/t") == "the terminal"
    assert back_label("/something-else") == "where you were"


def test_every_header_logo_carries_the_page(client):
    assert 'class="tbar-brand" href="/?from=/help"' in client.get("/help").text      # Help, signed in: the terminal header
    assert 'class="brand" href="/"' in client.get("/").text, "the landing page's own logo needs no way back to itself"
