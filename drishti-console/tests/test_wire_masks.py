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
"""Masks across panels (RACHANA_REFERENCE.md 15, the decided behaviour): a panel that follows a value masked for the reader shows nothing, and
says so in words ("Hidden for you"), never an empty panel or the generic "no data"."""
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

TEMPLATES = Path(__file__).resolve().parents[1] / "web" / "templates"


def no_data(panel: dict) -> str:
    env = Environment(loader=FileSystemLoader(str(TEMPLATES)), autoescape=True)
    return str(env.get_template("_macros/panels.html").module.no_data(panel))


def test_a_panel_that_follows_a_masked_value_says_hidden_for_you():
    html = no_data({"id": "counterparty", "kind": "kv", "denied": "hidden for you", "empty": True})
    assert "data-denied" in html and "Hidden for you" in html
    assert "masked for you" in html
    assert "No data available" not in html and "roles do not allow you to open" not in html


def test_a_value_hidden_at_its_source_and_a_kind_not_open_say_their_own_words():
    assert "hidden from your role where it is kept" in no_data({"id": "x", "kind": "kv", "denied": "not available to your role"})
    assert "roles do not allow you to open" in no_data({"id": "x", "kind": "kv", "denied": "no access to credit-limit"})
