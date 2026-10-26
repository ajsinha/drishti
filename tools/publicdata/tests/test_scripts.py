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

"""The script audit of tools/publicdata (the product owner's request of 2026-10-26: every external data source has a clearly
documented script): each script has a docstring with worked examples, its --help and its subcommands' --help show them, the
shell and PowerShell wrappers exist and run that script, `status` works with no network at all, and the External data help page
documents every script, wrapper and source."""
from __future__ import annotations

import importlib
import re
import socket
import sys
import urllib.request
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path.insert(0, str(TOOLS))
import pdbase as B  # noqa: E402

ROOT = B.ROOT
GUIDE = ROOT / "docs" / "guides" / "PUBLIC_DATA.md"
SCRIPTS = sorted(B.WRAPPERS)                              # world_economy.py -> world-economy, and so on


@pytest.fixture(autouse=True)
def data_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    return tmp_path / "data"


def module(script: str):
    return importlib.import_module(script[:-3])


def test_every_script_is_listed():
    found = sorted(p.name for p in TOOLS.glob("*.py") if p.name != "pdbase.py")
    assert found == SCRIPTS, "a new script needs its pack in pdbase.WRAPPERS, its two wrappers and a section of PUBLIC_DATA.md"


@pytest.mark.parametrize("script", SCRIPTS)
def test_docstring_has_worked_examples(script):
    doc = module(script).__doc__ or ""
    ex = [line for line in doc.splitlines() if line.startswith(f"    python3 tools/publicdata/{script} ")]
    assert any(" status" in line for line in ex) and any("refresh --dry-run" in line for line in ex), script
    assert len(ex) >= 4, f"{script}: at least four worked examples"
    assert "docs/guides/PUBLIC_DATA.md" in " ".join(doc.split()), script


@pytest.mark.parametrize("script", SCRIPTS)
@pytest.mark.parametrize("command", ["refresh", "status"])
def test_subcommand_help_shows_the_examples_and_the_wrappers(script, command, capsys):
    with pytest.raises(SystemExit) as e:
        module(script).main([command, "--help"])
    assert e.value.code == 0
    out = capsys.readouterr().out
    pack = B.WRAPPERS[script]
    assert "examples:" in out and f"python3 tools/publicdata/{script} refresh --dry-run" in out
    assert f"tools/publicdata/{pack}.sh" in out and f"publicdata-{pack}.ps1" in out


@pytest.mark.parametrize("script", SCRIPTS)
def test_wrappers_exist_and_run_the_script(script):
    pack = B.WRAPPERS[script]
    sh = TOOLS / f"{pack}.sh"
    ps1 = ROOT / "tools" / "windows" / f"publicdata-{pack}.ps1"
    for w in (sh, ps1):
        assert w.is_file(), w
        text = w.read_text()
        assert "Copyright (c)" in text, f"{w.name}: the licence header"
        assert "PUBLIC_DATA.md" in text, f"{w.name}: names the guide"
    assert re.search(rf'tools/publicdata/{re.escape(script)} "\$@"', sh.read_text()), sh
    assert f"tools\\publicdata\\{script}" in ps1.read_text() and "@args" in ps1.read_text(), ps1
    assert ".EXAMPLE" in ps1.read_text() and f"{pack}.sh status" in sh.read_text()


@pytest.mark.parametrize("script", SCRIPTS)
def test_status_works_offline(script, monkeypatch, capsys):
    def no_network(*a, **k):
        raise AssertionError("status must not reach the network")
    monkeypatch.setattr(urllib.request, "urlopen", no_network)
    monkeypatch.setattr(socket, "create_connection", no_network)
    assert module(script).main(["status"]) == 0
    out = capsys.readouterr().out
    assert "SOURCE" in out and "LICENCE" in out and "USE" in out
    for s in module(script).SOURCES:
        assert s.name in out


def test_the_external_data_page_documents_every_script_and_source():
    text = GUIDE.read_text()
    assert re.search(r"^# External data", text, re.M), "the page's title"
    for script, pack in B.WRAPPERS.items():
        assert f"tools/publicdata/{script}" in text and f"publicdata-{pack}.ps1" in text and f"{pack}.sh" in text, script
        for s in module(script).SOURCES:
            assert f"`{s.name}`" in text, f"{script}: source {s.name} is not on the page"
    for internal in ("IMF", "Yahoo"):
        assert re.search(rf"{internal}[^\n]*\*\*internal only\*\*", text), internal
