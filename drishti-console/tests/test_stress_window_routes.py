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

"""The stress window selection (routes/var_routes.py; docs/guides/VAR_GUIDE.md section 18.9): SWIN opens /var/stress-window on the node
it names; the page is a shell; its JSON (the analysis, the proposals, the decision) goes to the engine as the user, nothing else."""
from plugins.banking.routes.var_routes import swin_command
import engine_fakes  # noqa: E402


def test_swin_opens_the_tool_on_the_node():
    assert swin_command(["SWIN"]) == "/var/stress-window?scope=BANK"
    assert swin_command("SWIN BANK <GO>".split()) == "/var/stress-window?scope=BANK"
    assert swin_command("swin desk desk-rates".split()) == "/var/stress-window?scope=DESK%3ADESK-RATES"
    assert swin_command("SWIN DESK:DESK-RATES".split()) == "/var/stress-window?scope=DESK%3ADESK-RATES"
    assert swin_command("SWIN LE LE-NY LEGAL".split()) == "/var/stress-window?scope=LE%3ALE-NY&hierarchy=LEGAL"
    assert swin_command("VAR BANK".split()) is None


def test_the_page_is_a_shell(client):
    r = client.get("/var/stress-window?scope=DESK:DESK-RATES&hierarchy=MGMT&length=250")
    assert r.status_code == 200
    assert 'data-swin' in r.text and 'data-scope="DESK:DESK-RATES"' in r.text and "drishti_stress-window.js" in r.text
    assert "Freeze and propose for sign-off" in r.text


def test_the_json_goes_to_the_engine_as_the_user(client, backend, monkeypatch):
    seen = []

    async def var(method, path, ident, body=None, raw=False, **params):
        seen.append((method, path, body, params))
        return {"ok": 1}
    engine_fakes.patch(monkeypatch, backend, var=var)
    assert client.get("/api/var/stress-window?scope=BANK&hierarchy=MGMT&length=250&asOf=2026-09-30").status_code == 200
    assert seen[-1] == ("GET", "/stress-window", None, {"scope": "BANK", "hierarchy": "MGMT", "length": "250", "confidence": "",
                                                        "asOf": "2026-09-30"})
    assert client.get("/api/var/stress-window/proposals?limit=5").status_code == 200
    assert seen[-1][1] == "/stress-window/proposals" and seen[-1][3]["limit"] == "5"
    assert client.get("/api/var/stress-window/proposals/SWP-000001").status_code == 200
    assert seen[-1][1] == "/stress-window/proposals/SWP-000001"
    assert client.post("/api/var/stress-window/proposals", json={"scope": "BANK", "first": "2007-11-30"}).status_code == 200
    assert seen[-1][:3] == ("POST", "/stress-window/proposals", {"scope": "BANK", "first": "2007-11-30"})
    assert client.post("/api/var/stress-window/proposals/SWP-000001/decision", json={"decision": "approve"}).status_code == 404   # decided in the one Approvals queue
