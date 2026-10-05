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

"""Workspaces: starters, embedded panes, saving, and the frame policy that allows same-origin panes only."""
import json
import re


def test_index_lists_starters_and_saved(client, backend):
    r = client.get("/w")
    assert r.status_code == 200 and "Credit desk" in r.text and "Cross-asset" in r.text


def test_starter_renders_panes_and_saving_round_trips(client, backend):
    r = client.get("/w/Credit desk", params={"template": "Credit desk"})
    assert r.status_code == 200 and 'data-ws' in r.text
    ws = json.loads(re.search(r'data-json="([^"]*)"', r.text).group(1).replace("&#34;", '"').replace("&quot;", '"'))
    assert ws["layout"] == "1+2" and ws["panes"][1]["follows"] == 0
    assert client.post("/w/api/Mine", json=ws).status_code == 200
    assert "Mine" in client.get("/w").text
    assert 'data-saved="true"' in client.get("/w/Mine").text
    assert client.post("/w/api/Bad", json={"layout": "2col", "panes": []}).status_code == 400
    assert client.post("/w/api/Mine/delete").json()["ok"]
    assert client.get("/w/Nope").status_code == 404


def _ws(page: str) -> dict:
    return json.loads(re.search(r'data-json="([^"]*)"', page).group(1).replace("&#34;", '"').replace("&quot;", '"'))


def test_without_starters_the_index_says_so_and_offers_a_blank_workspace(client, with_packs):
    """UX-06: with packs that bring no starters, /w listed nothing, said nothing and had no way to make a workspace."""
    with_packs("banking-core", "trading")
    page = client.get("/w").text
    assert "No starter workspaces" in page and "Open a starter below" not in page
    assert 'action="/w/new"' in page and 'name="name"' in page                  # a blank one, by name
    r = client.get("/w/new", params={"name": "QA desk"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/w/QA%20desk?new=1"
    blank = client.get("/w/QA desk", params={"new": 1})
    assert blank.status_code == 200 and 'data-saved="false"' in blank.text
    ws = _ws(blank.text)
    assert ws["layout"] == "2col" and len(ws["panes"]) == 2 and all(p["ref"] is None for p in ws["panes"])


def test_an_unknown_name_offers_to_create_it(client, with_packs):
    with_packs("trading")
    r = client.get("/w/QA")
    assert r.status_code == 404 and "There is no workspace called “QA”" in r.text
    assert 'href="/w/QA?new=1"' in r.text and "Create it" in r.text
    assert client.get("/w/new", params={"name": "  "}, follow_redirects=False).headers["location"] == "/w"


def test_embedded_views_have_no_chrome_and_frames_are_same_origin_only(client):
    r = client.get("/v/trade/IRS-48213", params={"embed": 1})
    assert "data-embed" in r.text and 'class="tbar"' not in r.text and 'class="fkeys"' not in r.text
    csp = r.headers["content-security-policy"]
    assert "frame-ancestors 'self'" in csp and "frame-src 'self'" in csp
    assert r.headers["x-frame-options"] == "SAMEORIGIN"
    assert 'class="tbar"' in client.get("/v/trade/IRS-48213").text


def test_resolve_for_pane_pickers(client):
    assert client.post("/api/resolve", json={"text": "TRD IRS-48213"}).json()["ref"]["id"] == "IRS-48213"
    assert client.post("/api/resolve", json={"text": "nonsense"}).status_code == 400
