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
- No console file reads a distribution, kernel, lens or module manifest file (step S2): the distribution compiler (``core/distribution.py``)
  is gone and nothing names ``config/distributions``, ``config/lenses`` or a ``module.yaml`` outside the module parts' own cache.
- The version the pages show is the server's: no template shows the console's own build (``ASSET_V``) as text.
"""
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

CONSOLE = Path(__file__).resolve().parents[1]

#: console files that still import the resolver, each with the step that deletes the import
REMAINING = {"core/resolution.py": "S5"}
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


def test_no_console_file_reads_a_distribution_lens_or_manifest_file():
    assert not (CONSOLE / "core" / "distribution.py").exists(), "the distribution compile is the server's (GET /api/v1/console/bootstrap)"
    found = []
    for p in _sources():
        text = p.read_text(encoding="utf-8")
        for word in ("config/distributions", "../config/lenses", "distributions_dir", "lens.dir", "module_manifests_dir", "kernel.yaml"):
            if word in text:
                found.append(f"{p.relative_to(CONSOLE).as_posix()}: {word}")
    assert not found, f"the console reads the server's distribution, lens or manifest files again: {found}"


def test_the_console_keeps_no_engine_catalogue_or_under_construction_file():
    for f in ("config/engines.yaml", "plugins/banking/engines.yaml"):
        assert not (CONSOLE / f).exists(), f"{f}: the engine catalogue comes in the server's installation answer (step S3)"
    for f in ("core/engines.py", "core/under_construction.py"):
        text = (CONSOLE / f).read_text(encoding="utf-8")
        assert "yaml" not in text and "read_text" not in text, f"{f} reads a file: the server's answer holds it (step S3)"


def test_the_console_keeps_no_copy_of_the_server_s_limits_host_rule_or_secret_rule():
    """Step S6: the limits come in the installation answer's ``limits``; the host-address rule is the server's (its member list) and the
    tools' (tools/hostaddr.py); which settings are secrets is the server's rule (``/admin/settings/secret-keys``)."""
    assert not (CONSOLE / "core" / "hostrule.py").exists(), "the host-address rule is the server's HostAddress (and tools/hostaddr.py)"
    text = (CONSOLE / "core" / "controls.py").read_text(encoding="utf-8")
    assert not re.search(r"MAX_\w+\s*=\s*\d", text), "core/controls.py holds a number of its own: the bounds are the server's (limits.controls)"
    assert "LIMITS" not in (CONSOLE / "core" / "forms.py").read_text(encoding="utf-8"), "FORM's largest row count is the server's (limits.form)"
    for f in ("core/settings_page.py", "core/config_editor.py"):
        assert "def is_secret" not in (CONSOLE / f).read_text(encoding="utf-8"), f"{f}: the secret rule is the server's"
    found = [p.relative_to(CONSOLE).as_posix() for p in _sources() if "hostrule" in p.read_text(encoding="utf-8")]
    assert not found, f"the console checks host addresses with a copy of the server's rule again: {found}"


def test_the_console_has_no_ai_page_route_or_button():
    """AI is not in this release (owner, 2026-11-16): no AI page, menu item, route or button; the server's layer is off (one switch)."""
    for f in ("routes/ai_routes.py", "web/templates/admin/ai.html", "web/static/js/drishti_bi-ai.js", "web/static/js/drishti_bi-design-copilot.js",
              "web/static/js/drishti_about-ask.js", "web/static/js/drishti_admin-ai-test.js", "web/static/css/drishti_bi-ai.css"):
        assert not (CONSOLE / f).exists(), f"{f}: AI is not in this release's console"
    found = []
    for p in [*_sources(), *(CONSOLE / "web" / "templates").rglob("*.html"), *(CONSOLE / "config").glob("*.yaml")]:
        text = p.read_text(encoding="utf-8")
        for word in ("/api/ai/", "/admin/ai", "/ai/status", "/ai/chat", "data-about-ask", "data-narrative", "ai_chat"):
            if word in text:
                found.append(f"{p.relative_to(CONSOLE).as_posix()}: {word}")
    assert not found, f"an AI page, route or button is back in the console: {found}"
