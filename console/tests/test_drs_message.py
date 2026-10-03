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

# Drishti - Copyright (c) 2025-2030 Ashutosh Sinha (ajsinha@gmail.com). All rights reserved. Proprietary and confidential.
"""UX-10: a DRS code appears once in a message, whatever the server wrote in the detail."""
import pytest

from core.backend import BackendError, drs_message
from conftest import CONSOLE


@pytest.mark.parametrize("code,detail,want", [
    ("DRS-2101", "alert expression is bad", "DRS-2101: alert expression is bad"),
    ("DRS-2101", "DRS-2101 alert expression: DRS-2101 bad", "DRS-2101: alert expression: bad"),
    ("DRS-2101", "DRS-2101: DRS-2101 x", "DRS-2101: x"),
    ("DRS-4004", "", "DRS-4004"),
    ("", "plain", "plain"),
])
def test_the_code_comes_once(code, detail, want):
    assert drs_message(code, detail) == want


def test_a_backend_error_and_the_missing_page_say_the_code_once(client, backend, monkeypatch):
    assert str(BackendError(404, "DRS-2101", "DRS-2101 nothing here")).count("DRS-2101") == 1
    async def view(kind, id_, user, *a, **k):
        raise BackendError(404, "DRS-2101", "DRS-2101 no such trade")
    monkeypatch.setattr(backend, "view", view)
    page = client.get("/v/trade/NOPE-1").text
    assert page.count("DRS-2101") == 1


def test_every_page_script_builds_problem_text_with_the_shared_helper():
    js = "".join(p.read_text() for p in (CONSOLE / "web" / "static" / "js").glob("*.js"))
    assert "window.drsMessage" in js
    assert "(res.b.code || 'Error') + ': '" not in js and "(res.body.code || 'Error') + ': '" not in js
