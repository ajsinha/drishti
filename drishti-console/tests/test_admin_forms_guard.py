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

"""The Admin screens' forms are Drishti Elements (docs/architecture/ELEMENTS.md section 3.1 and 12.3, the survey's "Packs, lake files…" row):
no Admin template writes a raw ``<input>``, ``<select>`` or ``<textarea>``; its fields are drishti-inputs' ``Form`` from a schema the server
builds (core/admin_forms.py), or drishti-grid editing for a table's rows. A hidden input (a token, a value the page carries) is not a field
and is allowed. A table's row picks are drishti-grid's row selection (data-select on a console table), never a checkbox per row. ALLOWED
(the templates still being moved, with their raw controls) is empty and CAP is 0: a raw control in any Admin template fails."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ADMIN = Path(__file__).resolve().parents[1] / "web" / "templates" / "admin"
COMMENT = re.compile(r"\{#.*?#\}|<!--.*?-->", re.S)
RAW = re.compile(r"<(input|select|textarea)\b([^>]*)>", re.I)
HIDDEN = re.compile(r"""\btype\s*=\s*["']?hidden\b""", re.I)

# Every Admin template is moved (2.9.11.e): the list is empty and stays so; a raw control anywhere fails. CAP: the raw controls allowed in all.
ALLOWED: dict[str, int] = {}
CAP = 0


def raw_controls(text: str) -> int:
    """How many raw form controls a template writes (hidden inputs are not fields)."""
    text = COMMENT.sub("", text)
    return sum(1 for m in RAW.finditer(text) if not (m.group(1).lower() == "input" and HIDDEN.search(m.group(2))))


@pytest.fixture(scope="module")
def counts() -> dict[str, int]:
    return {p.relative_to(ADMIN).as_posix(): raw_controls(p.read_text(encoding="utf-8")) for p in sorted(ADMIN.rglob("*.html"))}


def test_no_admin_template_writes_a_raw_form_control(counts):
    bad = [f"{name}: {n} raw <input>/<select>/<textarea> (use drishti-inputs' Form from a server schema, core/admin_forms.py)"
           for name, n in counts.items() if n and name not in ALLOWED]
    assert not bad, "\n".join(bad)


def test_the_allow_list_only_shrinks(counts):
    stale = [f"{name}: {'gone' if name not in counts else 'clean now'} (take it off the list)" for name in ALLOWED if not counts.get(name)]
    grown = [f"{name}: {counts[name]} raw controls, the list allows {n}" for name, n in ALLOWED.items() if counts.get(name, 0) > n]
    assert not stale and not grown, "\n".join(stale + grown)


def test_no_raw_control_at_all(counts):
    assert not ALLOWED and CAP == 0
    assert sum(counts.values()) <= CAP, {k: n for k, n in counts.items() if n}


def test_the_guard_finds_what_it_looks_for():
    assert raw_controls('<label>Kind <select name="kind"></select></label><input name="x"><textarea></textarea>') == 3
    assert raw_controls('<input type="hidden" name="csrf" value="1">{# <input name="y"> #}<!-- <select> -->') == 0
    assert raw_controls('<div data-di-form="{}"></div>') == 0
