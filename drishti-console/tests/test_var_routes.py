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

"""The VaR explorer's command words (routes/var_routes.py): VAR <word> <rest> opens /var on the scope it means; VAR <id of the var kind>
still goes to the server's command (the market-risk pack's var record); the page and its JSON need no server to answer a bad path."""
from routes.var_routes import var_command


def test_command_words_open_the_explorer_on_their_scope():
    assert var_command("VAR BANK".split(), "asha") == "/var?scope=BANK"
    assert var_command("VAR DESK DESK-RATES".split(), "asha") == "/var?scope=DESK%3ADESK-RATES"
    assert var_command("var desk rates".split(), "asha") == "/var?scope=DESK%3Arates"
    assert var_command("VAR LE LE-NY".split(), "asha") == "/var?scope=LE%3ALE-NY"
    assert var_command("VAR POS POS-BOOK-RATES-1~CMS".split(), "asha") == "/var?scope=POS%3APOS-BOOK-RATES-1~CMS"
    assert var_command("VAR TRD MX-1,MX-2".split(), "asha") == "/var?scope=TRD%3AMX-1%2CMX-2"
    assert var_command("VAR PORT PF-USD-RATES".split(), "asha") == "/var?scope=PORT%3APF-USD-RATES"
    assert var_command("VAR SCOPE unwind".split(), "asha") == "/var?scope=BASKET%3Aasha/unwind"
    assert var_command("VAR SCOPE ola/unwind".split(), "asha") == "/var?scope=BASKET%3Aola/unwind"
    assert var_command("VAR SCOPE PF-EUR-ALL".split(), "asha") == "/var?scope=PORT%3APF-EUR-ALL"
    assert var_command("VAR SCOPE DESK:DESK-FX - TRD:MX-1".split(), "asha") == "/var?scope=DESK%3ADESK-FX%20-%20TRD%3AMX-1"
    assert var_command("VAR CAPTURE CAP-20260930-3".split(), "asha") == "/var?capture=CAP-20260930-3"
    assert var_command(["VAR"], "asha") == "/var"


def test_other_commands_are_left_to_the_server():
    assert var_command("VAR VAR-RATES".split(), "asha") is None          # the var kind's record
    assert var_command("VAR DESK".split(), "asha") is None               # nothing named
    assert var_command("TRD MX-20000001".split(), "asha") is None


def test_an_unknown_engine_path_is_refused_by_the_console(client):
    r = client.get("/api/var/nothing-here")
    assert r.status_code == 404 and r.json()["code"] == "DRS-1001"
    r = client.post("/api/var/reload", json={})
    assert r.status_code == 404
