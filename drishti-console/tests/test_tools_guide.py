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

"""docs/guides/TOOLS.md is the one guide to every tool: it fails when a drishti.py command or a tools/ script is not mentioned in it, so a
new command or script cannot ship without its line in the guide."""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"
GUIDE = (ROOT / "docs" / "guides" / "TOOLS.md").read_text(encoding="utf-8")


def _drishti():
    sys.path.insert(0, str(TOOLS))
    spec = importlib.util.spec_from_file_location("drishti_cli_for_tools_guide", TOOLS / "drishti.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod                   # drishti.py registers its groups through sys.modules[__name__]
    spec.loader.exec_module(mod)
    return mod


def _subs(p: argparse.ArgumentParser) -> dict:
    for a in p._actions:
        if isinstance(a, argparse._SubParsersAction):
            return a.choices
    return {}


def commands() -> list[str]:
    """`group command` for every command of drishti.py, and `drishti.py group` for a group without commands."""
    out = []
    for group, gp in _subs(_drishti().build_parser()).items():
        cmds = _subs(gp)
        out += [f"{group} {c}" for c in cmds] if cmds else [f"drishti.py {group}"]
    return out


def scripts() -> list[str]:
    """The tools a person runs (or a command loads): tools/*.py|*.sh and the scripts of tools' subfolders, tests and packages aside."""
    found = list(TOOLS.glob("*.py")) + list(TOOLS.glob("*.sh")) + list(TOOLS.glob("lake/*.py")) + list(TOOLS.glob("packgen/*.py")) \
        + list(TOOLS.glob("packgen/*/make*.py")) + list(TOOLS.glob("windows/*.ps1")) + list(TOOLS.glob("bench/*.sh")) \
        + [TOOLS / p for p in ("samplegen/bulk_trades.py", "docs/screenshots.py", "realdata/realdata.py", "packreg/packreg.py",
                                "rachana/md_to_yaml.py", "maps/build_regions.py")]
    return sorted(str(f.relative_to(ROOT)) for f in found if f.is_file() and not f.name.startswith("test_") and f.name != "__init__.py")


def test_every_drishti_command_is_in_the_guide():
    missing = [c for c in commands() if c not in GUIDE]
    assert not missing, f"docs/guides/TOOLS.md does not mention: {missing}"


def test_every_tools_script_is_in_the_guide():
    missing = [s for s in scripts() if s not in GUIDE and Path(s).name not in GUIDE]
    assert not missing, f"docs/guides/TOOLS.md does not mention: {missing}"


def test_the_guide_is_in_the_docs_index_and_the_help_centre():
    assert "guides/TOOLS.md" in (ROOT / "docs" / "README.md").read_text(encoding="utf-8")
    assert "doc: guides/TOOLS.md" in (ROOT / "drishti-console" / "config" / "help.yaml").read_text(encoding="utf-8")
