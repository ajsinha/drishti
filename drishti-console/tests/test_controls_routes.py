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

"""Controls on the console's side (BI_DESIGN.md 8.1, RACHANA_REFERENCE.md "Controls"): the ``ctl.<id>`` values of the address reach the server for
the page, for the panel fragments, for the JSON view and for the live subscription; the page carries the controls for controls.js; the share
link keeps them. The server decides what a value means: nothing here evaluates a control."""
import asyncio
import json
import re

from core import controls

LEGS = {"id": "legs", "kind": "multi-select", "label": "Legs", "placement": "header", "panel": "legs", "options": [{"value": "1", "label": "1"}, {"value": "2", "label": "2"}],
        "value": "1,2", "values": ["1", "2"], "required": False, "remember": True, "reads": ["legs"]}
WHEN = {"id": "when", "kind": "date-range", "label": "Paid between", "placement": "strip", "options": [{"value": "2026-10-10..2027-01-07", "label": "Next 90 days"}],
        "value": "", "required": False, "remember": False, "reads": ["legs", "strip"]}


def with_controls(backend):
    """The fixture view, with two controls and a recorder of the calls the console makes."""
    calls = []
    original = backend.view

    async def view(kind, id_, ident, controls=None, panels=None):
        calls.append({"controls": controls, "panels": panels})
        vm = await original(kind, id_, ident)
        vm["controls"] = [dict(LEGS), dict(WHEN)]
        vm["selects"] = [{"panel": "legs", "by": "legs", "filter": []}]
        if controls and "ctl.legs" in controls:
            vm["controls"][0]["value"] = controls["ctl.legs"]
        if panels is not None:
            vm["panels"] = [p for p in vm["panels"] if p["id"] in panels]
        return vm

    backend.view = view
    return calls


def test_the_request_parameters_are_read_with_limits():
    class Q(dict):
        def getlist(self, k):
            return [self[k]]

    got = controls.from_query(Q({"ctl.leg": "L2", "ctl.when": "2026-10-01..2026-12-31", "q": "x", "ctl.": "bad", "ctl." + "x" * 60: "long"}))
    assert got == {"ctl.leg": "L2", "ctl.when": "2026-10-01..2026-12-31"}
    assert controls.from_query(Q({"ctl.a": "v" * 5000}))["ctl.a"] == "v" * controls.MAX_VALUE
    assert controls.to_query({"ctl.leg": "L2", "ctl.when": "2026-10-01..2026-12-31"}) == "ctl.leg=L2&ctl.when=2026-10-01..2026-12-31"


def test_a_subscription_carries_the_controls_and_splits_back():
    assert controls.split_subscription("view:trade/IRS-1") == ("view:trade/IRS-1", {})
    assert controls.split_subscription("view:trade/IRS-1?ctl.legs=1%2C2&ctl.when=2026-10-01..2026-12-31") == (
        "view:trade/IRS-1", {"ctl.legs": "1,2", "ctl.when": "2026-10-01..2026-12-31"})
    assert controls.split_subscription("view:trade/a?b/c?ctl.q=x")[0] == "view:trade/a?b/c"


def test_the_page_passes_the_values_on_and_carries_the_controls(client, backend):
    calls = with_controls(backend)
    r = client.get("/v/trade/IRS-48213?ctl.legs=2&ctl.when=2026-10-01..2026-12-31")
    assert r.status_code == 200
    assert calls[0]["controls"] == {"ctl.legs": "2", "ctl.when": "2026-10-01..2026-12-31"}
    assert 'data-controls="' in r.text and "&#34;id&#34;: &#34;legs&#34;" in r.text.replace("&quot;", "&#34;") or "legs" in r.text
    data = re.search(r'data-controls="([^"]*)"', r.text).group(1)
    parsed = json.loads(data.replace("&#34;", '"').replace("&quot;", '"').replace("&amp;", "&"))
    assert [c["id"] for c in parsed] == ["legs", "when"] and parsed[0]["value"] == "2"
    assert '/static/js/controls.js' in r.text and "controls.css" in r.text
    assert "data-selects=" in r.text
    # the link a person copies keeps what they see
    share = re.search(r'data-share="([^"]*)"', r.text).group(1)
    assert "ctl.legs=2" in share and "ctl.when=2026-10-01..2026-12-31" in share


def test_a_page_without_controls_is_as_before(client, backend):
    r = client.get("/v/trade/IRS-48213")
    assert r.status_code == 200 and "data-controls" not in r.text and "/static/js/controls.js" not in r.text


def test_the_panel_fragment_builds_only_the_named_panels(client, backend):
    calls = with_controls(backend)
    r = client.get("/v/trade/IRS-48213/panels?panels=cashflows,legs&ctl.legs=1")
    assert r.status_code == 200
    body = r.json()
    assert calls[-1] == {"controls": {"ctl.legs": "1"}, "panels": ["cashflows", "legs"]}
    assert {p["id"] for p in body["panels"]} <= {"cashflows", "legs"} and body["panels"]
    assert all(p["html"].lstrip().startswith("<section") for p in body["panels"])
    assert body["controls"][0]["id"] == "legs" and body["controls"][0]["value"] == "1"
    assert body["strip"].startswith("<dl")
    client.get("/v/trade/IRS-48213/panels?panels=-&ctl.legs=1")
    assert calls[-1]["panels"] == []                        # "-" is no panel at all (only the strip and the controls move)
    client.get("/v/trade/IRS-48213/panels?ctl.legs=1")
    assert calls[-1]["panels"] is None                      # no list: every panel


def test_a_missing_entity_is_a_json_problem_for_the_fragment(client, backend):
    r = client.get("/v/trade/NOPE-1/panels?panels=a")
    assert r.status_code == 404 and r.json()["code"] == "DRS-1001"


def test_the_json_view_and_the_stream_relay_take_the_values(client, backend):
    calls = with_controls(backend)
    client.get("/api/view/trade/IRS-48213?ctl.legs=1")
    assert calls[-1]["controls"] == {"ctl.legs": "1"}
    seen = {}

    async def fake_stream(kind, id_, ident=None, opened=None, controls=None):
        seen["controls"] = controls
        yield "view", json.dumps({"provenance": {"generation": 1}})

    backend.stream = fake_stream
    with client.stream("GET", "/api/stream/trade/IRS-48213?ctl.legs=2") as r:
        "".join(r.iter_text())
    assert seen["controls"] == {"ctl.legs": "2"}


def test_a_live_channel_subscription_builds_the_stream_with_the_controls(client, backend):
    from core.channel import ChannelSession

    seen = {}

    async def fake_stream(kind, id_, ident=None, opened=None, controls=None):
        seen.update(kind=kind, id=id_, controls=controls)
        yield "view", json.dumps({"provenance": {"generation": 1}})

    backend.stream = fake_stream

    async def go():
        s = ChannelSession(backend, None, lambda event, data: data, limit=4, app="", registry={})
        await s._pump("view:trade/IRS-48213?ctl.legs=1%2C2&ctl.when=2026-10-01..2026-12-31")
        first = dict(seen)
        await s._pump("view:trade/IRS-48213")
        return first

    first = asyncio.run(go())
    assert first["controls"] == {"ctl.legs": "1,2", "ctl.when": "2026-10-01..2026-12-31"}
    assert seen["kind"] == "trade" and seen["id"] == "IRS-48213"
    assert seen["controls"] is None                          # the second call carried none (the last one wins)
