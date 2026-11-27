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

"""The first-run matrix, the bare console's rows (entry point B of docs/plans/FIRST_RUN_ROBUSTNESS.md section B2: 11, 39, 54, 56).
The functions of drishti-console/run_drishti_web.py are called directly: no console is started."""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent


@pytest.fixture(scope="module")
def web():
    sys.path.insert(0, str(ROOT / "drishti-console"))
    spec = importlib.util.spec_from_file_location("run_drishti_web_matrix", ROOT / "drishti-console" / "run_drishti_web.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_row11_a_bare_console_never_follows_another_checkouts_dev_ini(web, tmp_path):
    ini = tmp_path / "drishti-envs" / "dev.ini"
    ini.parent.mkdir()
    ini.write_text("[drishti]\ndrishti.home=/another/copy/drishti\n", encoding="utf-8")
    argv, said = web.bare_defaults([], {}, tmp_path)
    assert "--settings" not in argv and any("belongs to another checkout: not used" in s for s in said)
    ini.write_text(f"[drishti]\ndrishti.home={ROOT.as_posix()}\n", encoding="utf-8")
    argv, _ = web.bare_defaults([], {}, tmp_path)
    assert argv[:2] == ["--settings", str(ini)]


def test_row39_a_python_lacking_libraries_stops_at_once_with_the_owners_pip_command(web, monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setattr(web, "missing_libraries", lambda *a, **k: ["fastapi"])
    with pytest.raises(SystemExit) as e:
        web.check_libraries()
    text = str(e.value.code)
    assert text.startswith("[FAIL] the console's Python") and "Drishti installs nothing" in text and "Fix: cd " in text
    assert "-m pip install -r requirements.txt" in text and "drishti-console" in text


def test_row54_the_bare_console_and_start_demo_share_one_source_of_development_secrets(web):
    sys.path.insert(0, str(TOOLS / "launch"))
    import devsecrets
    env: dict = {}
    _, said = web.bare_defaults([], env, Path("/nonexistent-home"))
    assert {k: env[k] for k in devsecrets.CONSOLE} == {k: devsecrets.DEV[k] for k in devsecrets.CONSOLE}
    assert any("NOT FOR PRODUCTION" in s for s in said)
    env = {"DRISHTI_TOKEN_SECRET": "mine"}
    web.bare_defaults([], env, Path("/nonexistent-home"))
    assert env["DRISHTI_TOKEN_SECRET"] == "mine", "a secret already in the environment wins"


def test_row56_the_consoles_own_calls_ignore_the_proxy():
    for rel in ("core/backend.py", "core/server_facts.py", "core/cluster.py", "core/backend_watch.py", "core/servers.py"):
        text = (ROOT / "drishti-console" / rel).read_text(encoding="utf-8")
        assert text.count("httpx.AsyncClient(") == text.count("trust_env=False"), rel


@pytest.mark.xfail(reason="lane A: row 53 (console first, then server: back-off and join; needs E-light with a started console)", strict=False)
def test_row53_console_first_joins_the_server_when_it_comes():
    raise NotImplementedError("E row: not automated in this lane")
