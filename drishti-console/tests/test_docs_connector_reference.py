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

"""The connector reference (docs/reference/CONNECTORS.md) is generated from the plugin READMEs (tools/docs/connector_reference.py): it is
current, it names every plugin of the root pom, and Help lists it."""
from __future__ import annotations

import importlib.util
import re

import pytest
import yaml

from conftest import CONSOLE

ROOT = CONSOLE.parent


def _tool():
    spec = importlib.util.spec_from_file_location("drishti_connector_reference", ROOT / "tools" / "docs" / "connector_reference.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.mark.static
def test_the_connector_reference_is_current():
    tool = _tool()
    assert tool.PAGE.is_file() and tool.PAGE.read_text(encoding="utf-8") == tool.render(tool.plugins()), \
        "stale: run python3 tools/docs/connector_reference.py"


@pytest.mark.static
def test_it_names_every_plugin_module_and_help_lists_it():
    modules = re.findall(r"<module>plugins/(drishti-plugin-[a-z0-9-]+)</module>", (ROOT / "pom.xml").read_text(encoding="utf-8"))
    page = _tool().PAGE.read_text(encoding="utf-8")
    assert modules and all(f"../../plugins/{m}/README.md" in page for m in modules)
    help_yaml = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text(encoding="utf-8"))
    docs = [g.get("doc") for s in help_yaml["sections"] for grp in s["groups"] for g in grp["guides"]]
    assert "reference/CONNECTORS.md" in docs
