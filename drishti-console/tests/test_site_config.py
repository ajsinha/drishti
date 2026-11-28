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
"""The console's own site file (docs/admin/PRODUCTION_STANDARD.md, "Site configuration"): with a site folder named it is
<site>/console/application.local.yaml, read AND written there, never in the release; with none it is the console's config folder."""
from __future__ import annotations

from pathlib import Path

import yaml

from core import config_editor
from core.config import load_settings, local_file

CONSOLE = Path(__file__).resolve().parent.parent


def test_no_site_folder_is_the_developer_layout(monkeypatch):
    monkeypatch.delenv("DRISHTI_SITE_DIR", raising=False)
    assert local_file(CONSOLE / "config") == CONSOLE / "config" / "application.local.yaml"


def test_a_site_folder_holds_the_consoles_file(monkeypatch, tmp_path):
    monkeypatch.setenv("DRISHTI_SITE_DIR", str(tmp_path / "etc-drishti"))
    assert local_file(CONSOLE / "config") == tmp_path / "etc-drishti" / "console" / "application.local.yaml"


def test_an_approved_key_is_written_to_the_site_and_read_back(monkeypatch, tmp_path):
    site = tmp_path / "etc-drishti"
    monkeypatch.setenv("DRISHTI_SITE_DIR", str(site))
    target = local_file(CONSOLE / "config")
    config_editor.write_console_key(target, "ui.placeholder", "TRD ...", "approved")
    assert target.is_file() and site in target.parents
    assert yaml.safe_load(target.read_text())["ui"]["placeholder"] == "TRD ..."
    assert load_settings(CONSOLE / "config").get("ui.placeholder") == "TRD ..."          # restart keeps it
