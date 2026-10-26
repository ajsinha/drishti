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

"""Exposure commands (routes/exposure_routes.py): EXP with a spaced scope opens the computed kind exposure-result on the scope it means;
an id or a link of the retired stored exposure profile (EXP EXP-ALDERSHOT-FRA, /v/exposure-profile/EXP-ALDERSHOT-FRA) opens the same
netting set's computed profile (docs/contracts/EXP.md section 14)."""
from routes.exposure_routes import exp_command, retired_link


def test_spaced_scopes_become_the_scope_grammar():
    assert exp_command("EXP NSET NS-ALDERSHOT-FRA".split()) == "/v/exposure-result/NSET:NS-ALDERSHOT-FRA"
    assert exp_command("exp cpty CP-ALDERSHOT".split()) == "/v/exposure-result/CPTY:CP-ALDERSHOT"
    assert exp_command("EXP GROUP GRP-ALDERSHOT".split()) == "/v/exposure-result/GROUP:GRP-ALDERSHOT"
    assert exp_command("EXP LE LE-NY".split()) == "/v/exposure-result/LE:LE-NY"
    assert exp_command("EXP TRD CLY-1, CLY-2".split()) == "/v/exposure-result/TRD:CLY-1%2CCLY-2"


def test_an_id_of_the_retired_stored_profile_opens_its_netting_set():
    assert exp_command("EXP EXP-ALDERSHOT-FRA".split()) == "/v/exposure-result/NSET:NS-ALDERSHOT-FRA?from=EXP-ALDERSHOT-FRA"
    assert retired_link("exposure-profile", "EXP-ALDERSHOT-FRA") == "/v/exposure-result/NSET:NS-ALDERSHOT-FRA?from=EXP-ALDERSHOT-FRA"
    assert retired_link("exposure-profile", "X") == "/v/exposure-result/BANK?from=X"
    assert retired_link("netting-set", "EXP-ALDERSHOT-FRA") is None


def test_the_old_link_redirects(client):
    r = client.get("/v/exposure-profile/EXP-ALDERSHOT-FRA", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/v/exposure-result/NSET:NS-ALDERSHOT-FRA?from=EXP-ALDERSHOT-FRA"


def test_the_plain_command_is_left_to_the_server():
    assert exp_command("EXP NSET:NS-ALDERSHOT-FRA".split()) is None
    assert exp_command("EXP CPTY:CP-X - NSET:NS-X-FRA".split()) is None
    assert exp_command(["EXP"]) is None
    assert exp_command("NSET NS-ALDERSHOT-FRA".split()) is None
