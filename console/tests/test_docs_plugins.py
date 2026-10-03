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

"""The plugin lists in the docs are the plugin modules of the root pom (DOC-13, DOC-14)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONNECTOR_NAME = {"feeds": "feed"}  # the plugin name used in `plugin:` where it differs from the module's suffix


def _text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def _modules() -> list[str]:
    return re.findall(r"<module>plugins/drishti-plugin-([a-z0-9]+)</module>", _text("pom.xml"))


def test_the_root_pom_lists_every_plugin_directory():
    on_disk = sorted(p.name.removeprefix("drishti-plugin-") for p in (ROOT / "plugins").glob("drishti-plugin-*"))
    assert sorted(_modules()) == on_disk


def test_developer_guide_table_and_readme_list_every_plugin_module():
    guide, readme = _text("docs/guides/DEVELOPER_GUIDE.md"), _text("README.md")
    for name in _modules():
        assert f"| `drishti-plugin-{name}` |" in guide, f"DEVELOPER_GUIDE section 1.2 lacks drishti-plugin-{name}"
    tree = next(line for line in readme.splitlines() if "plugins/drishti-plugin-*/" in line)
    for name in _modules():
        assert re.search(rf"\b{name}\b", tree), f"README's module tree lacks {name}"
    assert "all %s plugins" % {15: "fifteen"}.get(len(_modules()), "?") in guide, "DEVELOPER_GUIDE's plugin count is stale"


def test_configuration_lists_every_plugin_a_connector_can_name():
    line = next(l for l in _text("docs/admin/CONFIGURATION.md").splitlines() if l.startswith("| `connectors.<name>.plugin`"))
    for name in _modules():
        if name == "demo":
            continue  # runs only as the single instance `drishti.sources.plugins.demo`
        want = CONNECTOR_NAME.get(name, name)
        assert f"`{want}`" in line, f"CONFIGURATION's connectors.<name>.plugin list lacks {want}"
