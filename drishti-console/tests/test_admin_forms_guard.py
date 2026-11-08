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
and is allowed. ALLOWED lists the templates still being moved, each with the number of raw controls it still has; it only shrinks: an entry
whose file is gone or clean, or whose count went up, fails, and so does a new file with raw controls."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ADMIN = Path(__file__).resolve().parents[1] / "web" / "templates" / "admin"
COMMENT = re.compile(r"\{#.*?#\}|<!--.*?-->", re.S)
RAW = re.compile(r"<(input|select|textarea)\b([^>]*)>", re.I)
HIDDEN = re.compile(r"""\btype\s*=\s*["']?hidden\b""", re.I)

# Moving next (wip/e-admin-2 takes these off as it moves them): the template and its raw controls. Only shrinks; ends at {}.
ALLOWED: dict[str, int] = {
    "_lake_maintain.html": 2,                # a table's row picks (one per row, and the one a foreign table shows disabled) → drishti-grid's row selection
    "_connector_dialogs.html": 6,
    "_uninstall_dialog.html": 3,
    "access.html": 6,
    "artifacts.html": 6,
    "audit.html": 1,
    "caches.html": 2,
    "capacity.html": 6,
    "compute.html": 1,
    "configuration.html": 4,
    "connectors.html": 3,
    "edits.html": 1,
    "engines.html": 1,
    "health.html": 5,
    "inbox.html": 1,
    "lakes.html": 11,
    "net.html": 11,
    "pack_detail.html": 3,
    "packs.html": 3,
    "roles.html": 11,
    "users.html": 10,
}


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


def test_the_guard_finds_what_it_looks_for():
    assert raw_controls('<label>Kind <select name="kind"></select></label><input name="x"><textarea></textarea>') == 3
    assert raw_controls('<input type="hidden" name="csrf" value="1">{# <input name="y"> #}<!-- <select> -->') == 0
    assert raw_controls('<div data-di-form="{}"></div>') == 0
