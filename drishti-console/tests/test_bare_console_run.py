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

"""i7g: ``run_drishti_web.py`` run bare (PyCharm's "Run current file") is the launcher Drishti: run console: the default settings file
with the demo profile and the development secrets of the one source (tools/launch/devsecrets.py), each said in one line; any other run
is left as it is; a Python without the console's libraries stops at once with the setup line."""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest

CONSOLE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("run_drishti_web_under_test", CONSOLE / "run_drishti_web.py")
web = importlib.util.module_from_spec(spec)
spec.loader.exec_module(web)


def test_a_bare_run_takes_the_default_settings_file_the_demo_profile_and_the_one_dev_secrets(tmp_path):
    (tmp_path / "drishti-envs").mkdir()
    ini = tmp_path / "drishti-envs" / "dev.ini"
    ini.write_text(f"drishti.home={CONSOLE.parent.as_posix()}\n", encoding="utf-8")
    env = {}
    argv, said = web.bare_defaults([], env, home=tmp_path)
    assert argv == ["--settings", str(ini), "--profile", "demo"] and str(ini) in said[0]
    dev = web.devsecrets()
    sys.path.insert(0, str(CONSOLE.parent / "tools" / "launch"))
    import serverstart
    assert dev.DEV is not None and serverstart.DEV == dev.DEV, "one source for the server and the console"
    assert env["DRISHTI_TOKEN_SECRET"] == dev.DEV["DRISHTI_TOKEN_SECRET"] and env["DRISHTI_SESSION_SECRET"] == dev.DEV["DRISHTI_SESSION_SECRET"]
    assert "DEVELOPMENT secrets in use (NOT FOR PRODUCTION)" in said[-1]


def test_secrets_set_by_the_user_are_kept_and_no_settings_file_means_the_demo_profile_only(tmp_path):
    env = {"DRISHTI_TOKEN_SECRET": "mine", "DRISHTI_SESSION_SECRET": "mine too"}
    argv, said = web.bare_defaults([], env, home=tmp_path)
    assert argv == ["--profile", "demo"] and "no settings file" in said[0] and len(said) == 1
    assert env == {"DRISHTI_TOKEN_SECRET": "mine", "DRISHTI_SESSION_SECRET": "mine too"}


@pytest.mark.parametrize("argv,env", [(["--server.port=17999"], {}), ([], {"DRISHTI_BACKEND_URL": "http://h:1"}),
                                      ([], {"DRISHTI_BACKEND_URLS": "http://h:1"})])
def test_any_other_run_is_left_as_it_is(tmp_path, argv, env):
    before = dict(env)
    assert web.bare_defaults(list(argv), env, home=tmp_path) == (argv, []) and env == before


def test_a_python_without_the_libraries_stops_at_once_with_the_setup_line():
    assert web.missing_libraries(lambda m: None if m == "drishti_lake" else object()) == ["drishti-lake"]
    r = subprocess.run([sys.executable, "-S", "-B", str(CONSOLE / "run_drishti_web.py")], capture_output=True, text=True, timeout=60)
    assert r.returncode == 1 and r.stderr.startswith("[FAIL]") and "lacks" in r.stderr and "drishti.py setup" in r.stderr and "Traceback" not in r.stderr


def test_a_bare_run_never_follows_another_checkouts_settings_file(tmp_path):
    """FIRST_RUN_ROBUSTNESS.md rows 11 and 65: ~/drishti-envs/dev.ini of another clone is said and not used (none of its roots)."""
    (tmp_path / "drishti-envs").mkdir()
    (tmp_path / "drishti-envs" / "dev.ini").write_text("drishti.home=/an/old/clone\n", encoding="utf-8")
    argv, said = web.bare_defaults([], {"DRISHTI_TOKEN_SECRET": "x", "DRISHTI_SESSION_SECRET": "y"}, home=tmp_path)
    assert argv == ["--profile", "demo"] and said[0].startswith("[WARN]") and "another checkout" in said[0]
