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

"""The function-key bar names a panel in full on hover and focus (QA 2026-10-01 UX-17)."""


def test_a_function_key_carries_the_whole_panel_title(client):
    html = client.get("/v/trade/IRS-48213").text
    assert 'data-fkey="F3" data-action="panel" aria-label="F3 Cashflows · Leg 1" title="Cashflows · Leg 1"' in html
    assert '<span class="fk-t">Cashflows · Leg 1</span>' in html          # the engine's two-word label is not what is shown


def test_a_key_that_is_not_a_panel_keeps_its_label(client):
    html = client.get("/v/trade/IRS-48213").text
    assert 'aria-label="F9 Raw JSON" title="Raw JSON"' in html
