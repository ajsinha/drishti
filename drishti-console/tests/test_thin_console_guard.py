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

"""The thin console's guard (docs/architecture/THIN_CONSOLE_DESIGN.md 5.1): a step's deleted reader does not come back.

- No console file in ``core/``, ``routes/`` or ``plugins/`` imports the Python resolver (``core.resolution``) but the ones a later step
  deletes (``REMAINING``, each with its step); the list only shrinks.
- The console's pack store reads no pack folder (no ``pack.yaml``, no ``dir``), the packs answer is not asked for a folder, and the
  setting that chose packs when the server was away (``packs.enabled``) is gone.
- The version the pages show is the server's: no template shows the console's own build (``ASSET_V``) as text.
"""
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

CONSOLE = Path(__file__).resolve().parents[1]

#: console files that still import the resolver, each with the step that deletes the import (S2: distribution, lenses, modules)
REMAINING = {
    "core/distribution.py": "S2", "core/edition.py": "S2", "core/house.py": "S2", "core/menus.py": "S2", "core/module_parts.py": "S2",
    "core/resolution.py": "S5",
}
IMPORT = re.compile(r"^\s*(?:from core import resolution|from core\.resolution import|import core\.resolution|from core import resolution as)", re.M)


def _sources():
    for folder in ("core", "routes", "plugins"):
        yield from sorted((CONSOLE / folder).rglob("*.py"))


def test_no_console_file_imports_the_resolver_but_those_a_later_step_deletes():
    found = {p.relative_to(CONSOLE).as_posix() for p in _sources() if IMPORT.search(p.read_text(encoding="utf-8"))}
    new = sorted(found - set(REMAINING))
    assert not new, f"a console file reads the server's folders again (ask the server's answers instead): {new}"
    gone = sorted(set(REMAINING) - found)
    assert not gone, f"take these off REMAINING (their step is done): {gone}"


def test_the_pack_store_reads_no_folder_and_no_fallback_setting_is_left():
    text = (CONSOLE / "core" / "packs.py").read_text(encoding="utf-8")
    for word in ("pack.yaml", "yaml.safe_load", "resolution", '"dir"', "fallback", "packs.enabled"):
        assert word not in text, f"core/packs.py names {word!r}: the console asks the server for a person's packs"
    import yaml
    doc = yaml.safe_load((CONSOLE / "config" / "application.yaml").read_text(encoding="utf-8")) or {}
    assert "enabled" not in (doc.get("packs") or {}), "packs.enabled chose packs while the server was away: the console never guesses them"


def test_no_page_shows_the_console_s_own_build_as_the_version():
    shown = []
    for t in sorted((CONSOLE / "web" / "templates").rglob("*.html")) + sorted(CONSOLE.glob("plugins/*/templates/**/*.html")):
        for n, line in enumerate(t.read_text(encoding="utf-8").splitlines(), 1):
            for m in re.finditer(r"\{\{[^}]*ASSET_V[^}]*\}\}", line):
                cache_busting = line[:m.start()].endswith("v=") or re.search(r"v=['\"]\s*~\s*ASSET_V", m.group(0))
                if not cache_busting:
                    shown.append(f"{t.relative_to(CONSOLE)}:{n}")
    assert not shown, f"the console's own build shown as text (show the server's version, server_about().version): {shown}"
