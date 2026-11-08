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

"""An engine switched off hides its help pages (config/engines.yaml help:): only pages about that engine alone, never a guide many
subjects share. The OpenBLAS switch once listed the whole Operations guide, so switching it off hid deployment, capacity and the runbooks."""

from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

CONSOLE = Path(__file__).resolve().parents[1]
HELP = [yaml.safe_load(f.read_text(encoding="utf-8"))                 # the core's Help and each module's (plugins/<module>/help.yaml)
        for f in [CONSOLE / "config" / "help.yaml", *sorted((CONSOLE / "plugins").glob("*/help.yaml"))]]


def _guides():
    out = {}

    def walk(node):
        if isinstance(node, dict):
            if "slug" in node and ("doc" in node or "title" in node):
                out[node["slug"]] = node
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
    walk(HELP)
    return out


def test_an_engine_hides_only_its_own_pages():
    guides = _guides()
    from core import engines as catalogue
    engines = catalogue.entries()                      # the core's and every module's (plugins/<module>/engines.yaml)
    shared = {"operations", "configuration", "user-guide", "api-guide", "cli-guide", "troubleshooting", "getting-started"}
    for e in engines:
        for slug in e.get("help") or []:
            assert slug not in shared, f"engine {e['id']} hides the shared guide {slug} when it is off"
            g = guides.get(slug)
            assert g is not None, f"engine {e['id']}: help page {slug} is not in help.yaml"
