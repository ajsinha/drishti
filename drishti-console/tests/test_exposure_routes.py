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

"""Exposure commands (routes/exposure_routes.py): EXP opens the exposure explorer (/exposure) on the scope it means, as typed or spaced;
an id or a link of the retired stored exposure profile (EXP EXP-ALDERSHOT-FRA, /v/exposure-profile/EXP-ALDERSHOT-FRA) opens the same
netting set there with a notice (docs/guides/EXPOSURE_GUIDE.md section 3)."""
from routes.exposure_routes import exp_command, retired_link


def test_every_exp_command_opens_the_explorer():
    assert exp_command(["EXP"]) == "/exposure?scope=BANK"
    assert exp_command("EXP BANK".split()) == "/exposure?scope=BANK"
    assert exp_command("EXP NSET:NS-ALDERSHOT-FRA".split()) == "/exposure?scope=NSET:NS-ALDERSHOT-FRA"
    assert exp_command("EXP NSET NS-ALDERSHOT-FRA".split()) == "/exposure?scope=NSET:NS-ALDERSHOT-FRA"
    assert exp_command("exp cpty CP-ALDERSHOT".split()) == "/exposure?scope=CPTY:CP-ALDERSHOT"
    assert exp_command("EXP GROUP GRP-ALDERSHOT".split()) == "/exposure?scope=GROUP:GRP-ALDERSHOT"
    assert exp_command("EXP LE LE-NY".split()) == "/exposure?scope=LE:LE-NY"
    assert exp_command("EXP TRD CLY-1, CLY-2".split()) == "/exposure?scope=TRD:CLY-1%2CCLY-2"
    assert exp_command("EXP NS-ALDERSHOT-NY".split()) == "/exposure?scope=NSET:NS-ALDERSHOT-NY"
    assert exp_command("EXP CPTY:CP-X - NSET:NS-X-FRA".split()) == "/exposure?scope=CPTY:CP-X%20-%20NSET:NS-X-FRA"


def test_an_id_of_the_retired_stored_profile_opens_its_netting_set():
    assert exp_command("EXP EXP-ALDERSHOT-FRA".split()) == "/exposure?scope=NSET:NS-ALDERSHOT-FRA&from=EXP-ALDERSHOT-FRA"
    assert retired_link("exposure-profile", "EXP-ALDERSHOT-FRA") == "/exposure?scope=NSET:NS-ALDERSHOT-FRA&from=EXP-ALDERSHOT-FRA"
    assert retired_link("exposure-profile", "X") == "/exposure?scope=BANK&from=X"
    assert retired_link("netting-set", "EXP-ALDERSHOT-FRA") is None


def test_the_old_link_redirects(client):
    r = client.get("/v/exposure-profile/EXP-ALDERSHOT-FRA", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/exposure?scope=NSET:NS-ALDERSHOT-FRA&from=EXP-ALDERSHOT-FRA"


def test_other_words_are_not_exposure_commands():
    assert exp_command("NSET NS-ALDERSHOT-FRA".split()) is None
    assert exp_command([]) is None
