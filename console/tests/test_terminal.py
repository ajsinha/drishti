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

"""The terminal: command dispatch, entity views for the four mockups, errors and the JSON proxy."""
import re


def text(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html))


def test_irs_view_renders_the_mockup(client):
    r = client.get("/v/trade/IRS-48213")
    assert r.status_code == 200
    h, t = r.text, text(r.text)
    assert "Trade · Interest rate swap" in t and "IRS-48213" in t and "Northbridge Capital LLP" in t
    for v in ("50,000,000", "Pay fixed", "3.8500%", "−412,580", "+22,310"):
        assert v in t, v
    assert 'class="mono t-neg emph"' in h and 'data-path="$.mtm"' in h
    for panel in ("legs", "cashflows", "leg2", "built", "curve", "refs", "dv01"):
        assert f'id="p-{panel}"' in h, panel
    assert 'role="tab"' in h and "Leg 1 · Pay fixed 3.8500%" in t
    assert "−9,764,027.78" in t and "Total" in t
    assert 'data-chart="' in h and 'data-kind="line"' in h
    assert 'href="/v/netting-set/NS-NORTH-01"' in h and "EE 4.1m" in t
    assert "Sutra irs-vanilla v3 + inference" in t and "aero-risk, gen 1742" in t
    assert 'data-fkey="F9"' in h and "Raw JSON" in t and 'data-fkey="F7"' in h
    assert 'value="TRD IRS-48213 &lt;GO&gt;"' in h


def test_other_reference_views(client):
    fx = text(client.get("/v/trade/FXS-20931").text)
    assert "EUR 25,000,000.00" in fx and "+55.8" in fx and "Confirmed" in fx
    fut = client.get("/v/trade/CFT-77120").text
    assert 'class="hl"' in fut and "2026-09-30 (intraday)" in fut and "+409,500" in fut
    ns = client.get("/v/netting-set/NS-NORTH-01").text
    assert "10 more trades" in ns and 'data-kind="area"' in ns and 'href="/v/trade/IRS-47102"' in ns
    inferred = client.get("/v/trade/IRS-47102").text
    assert "inference only" in inferred and "inferred" in inferred


def test_go_dispatches_commands(client):
    r = client.get("/go", params={"q": "TRD IRS-48213 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/v/trade/IRS-48213"
    bad = client.get("/go", params={"q": "nonsense"}, follow_redirects=False)
    assert bad.headers["location"].startswith("/t?error=")


def test_missing_entity_is_a_friendly_page(client):
    r = client.get("/v/trade/NOPE-1")
    assert r.status_code == 404 and "DRS-1001" in r.text


def test_home_lists_examples(client):
    r = client.get("/t")
    assert r.status_code == 200 and "NSET NS-NORTH-01" in r.text and 'role="combobox"' in r.text


def test_json_proxy_for_the_browser(client, backend):
    s = client.get("/api/suggest", params={"q": "TRD IRS-4"}).json()
    assert s[0]["complete"].startswith("TRD IRS-4")
    assert client.get("/api/raw/trade/IRS-48213").json()["provenance"]["source"] == "aero-risk"
    assert client.get("/api/view/trade/NOPE-1").status_code == 404
