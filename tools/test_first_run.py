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

"""The first-run matrix (docs/plans/FIRST_RUN_ROBUSTNESS.md, section B), the rows a unit test proves: each test names its rows. Scratch
folders only; no port of a running stack (18480, 17480, 18791, 18792) is used; nothing is started."""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path
from unittest import mock

import pytest

TOOLS = Path(__file__).resolve().parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import envsetup  # noqa: E402
import javacmd  # noqa: E402
import procs  # noqa: E402
import pylibs  # noqa: E402
import serverstart  # noqa: E402

ROOT = TOOLS.parent


def snapshot(*folders: Path) -> dict:
    """path -> sha256 of every file under ``folders`` (the write-set oracle, B1)."""
    out = {}
    for folder in folders:
        for f in sorted(folder.rglob("*")) if folder.exists() else []:
            if f.is_file():
                out[str(f)] = hashlib.sha256(f.read_bytes()).hexdigest()
    return out


def cli(*argv, env=None, cwd=None):
    return subprocess.run([sys.executable, str(TOOLS / "drishti.py"), *map(str, argv)], capture_output=True, text=True, cwd=cwd or ROOT,
                          env=env if env is not None else dict(os.environ))


def home_env(tmp: Path, **extra) -> dict:
    env = {k: v for k, v in os.environ.items() if not k.startswith("DRISHTI_")}
    env.update(HOME=str(tmp / "home"), USERPROFILE=str(tmp / "home"), **extra)
    (tmp / "home").mkdir(exist_ok=True)
    return env


# ---- E2: a pasteable command -------------------------------------------------------------------------------------------------------

def test_e2_a_command_is_pasteable_on_windows_and_linux_rows_44():
    with mock.patch.object(procs, "ROOT", Path("C:/Users/First Last/IdeaProjects/drishti-platform (1)/drishti")):
        win = procs.command("setup", "--wheels", "<folder>", win=True, cwd=Path("C:/elsewhere"))
    assert win.startswith(f"py -{sys.version_info[0]}.{sys.version_info[1]} ") and " python " not in win
    assert '"C:' in win and 'First Last' in win and '"<folder>"' in win, "a path with a space and <> are quoted"
    here = procs.command("start", "all", "--demo", win=True, cwd=procs.ROOT)
    assert "tools\\drishti.py start all --demo" in here
    lin = procs.command("setup", win=False, cwd=Path("/"))
    assert lin.split()[0] == procs._q(sys.executable, False) and lin.endswith("tools/drishti.py setup")
    assert procs.fail("x", "y.", "z") == "[FAIL] x: y. Fix: z"


def test_e1_an_unexpected_error_is_one_line_and_a_log_never_a_traceback(tmp_path):
    with mock.patch("drishti.build_parser", side_effect=RuntimeError("boom")) if False else mock.patch.object(javacmd, "set_settings", side_effect=RuntimeError("boom")):
        import io
        import contextlib
        import drishti
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = drishti.main(["status"])
    text = "\n".join(l for l in err.getvalue().splitlines() if not l.startswith("[WARN]"))
    assert code == 1 and text.startswith("[FAIL] RuntimeError: boom.") and "Traceback" not in text and "drishti-error-" in text


# ---- R5: a foreign environment root is ignored by every command (rows 12) -----------------------------------------------------------

def test_row12_a_stale_setx_is_ignored_said_and_nothing_is_made_there(tmp_path):
    old = Path("/drishti-first-run-decoy/old-clone")         # outside this checkout and outside the temp folder; never made
    env = home_env(tmp_path, DRISHTI_DATA_DIR=str(old / "data"), DRISHTI_MODULES_HOME=str(old / "modules"))
    r = cli("status", env=env)
    assert "[WARN] DRISHTI_DATA_DIR=" in r.stderr and "[WARN] DRISHTI_MODULES_HOME=" in r.stderr and "ignored" in r.stderr
    assert "Traceback" not in r.stderr and not old.exists()


# ---- E1: Java, jar, modules and port failures are [FAIL] ... Fix: (i7g gate F3) --------------------------------------------------------

def test_a_java_home_naming_nothing_is_one_fail_line_with_a_fix(tmp_path):
    env = home_env(tmp_path, JAVA_HOME=str(tmp_path / "no-jdk"))
    r = cli("sutra", "lint", tmp_path / "x.yaml", env=env)
    fails = [l for l in r.stderr.splitlines() if l.startswith("[FAIL]")]
    assert r.returncode == 2 and len(fails) == 1 and "Traceback" not in r.stderr and "drishti: " not in r.stderr, r.stderr
    assert fails[0].startswith("[FAIL] Java: JAVA_HOME is ") and ". Fix: install Java 21" in fails[0] and "drishti.java.home in " in fails[0]


def test_no_java_no_jar_and_a_busy_port_say_fix_with_the_command(tmp_path, monkeypatch):
    with pytest.raises(javacmd.JavaError, match=r"^Java: no Java found .*\. Fix: install Java 21 .*JAVA_HOME"):
        javacmd.find_java(None, None, {"PATH": str(tmp_path / "nothing")})
    with pytest.raises(javacmd.JarError, match=r"^the server jar: no server jar in .*\. Fix: .*drishti\.py build jar$"):
        javacmd.server_jar(None, tmp_path)
    import drishti
    assert drishti._fail_line(javacmd.JarError("the server jar: none. Fix: x")) == "[FAIL] the server jar: none. Fix: x"
    import consolestart
    monkeypatch.setattr(procs, "port_in_use", lambda port: True)
    monkeypatch.setattr(consolestart, "prepared_python", lambda *a, **k: sys.executable)
    (tmp_path / "data" / "run").mkdir(parents=True)
    a = argparse.Namespace(env_file=None, data_dir=str(tmp_path / "data"), port=17998, name=None, distribution=None, demo=False, home=None)
    with pytest.raises(procs.LaunchError, match=r"^\[FAIL\] port 17998: it is in use by another process\. Fix: .*drishti\.py stop console"):
        consolestart.start(a, {k: v for k, v in os.environ.items() if not k.startswith("DRISHTI_")})


# ---- G2, R3: start makes nothing before setup; --demo needs the lake; both ports first (rows 04, 06, 49) ------------------------------

def test_row04_start_before_setup_is_one_fail_with_the_setup_line_and_makes_nothing(tmp_path):
    data = tmp_path / "fresh-data"
    with pytest.raises(procs.LaunchError, match=r"^\[FAIL\] this checkout is not set up: .* Fix: .*drishti\.py.* setup"):
        serverstart.require_set_up(data, "--data-dir")
    assert not data.exists()


def test_row06_demo_without_the_demo_lake_is_one_fail_with_the_lake_line(tmp_path):
    with pytest.raises(procs.LaunchError, match=r"the demo lake is not built.*Fix: .*lake demo --engine java"):
        serverstart.demo_lake_ready(tmp_path, {}, tmp_path / "delta" / "demo")
    (tmp_path / "delta" / "demo" / "trading" / "trade" / "_delta_log").mkdir(parents=True)
    serverstart.demo_lake_ready(tmp_path, {}, tmp_path / "delta" / "demo")


def test_row49_start_all_checks_the_console_port_before_the_server(tmp_path, monkeypatch, capsys):
    import launchcli
    import consolestart
    started = []
    monkeypatch.setattr(consolestart, "prepared_python", lambda ns: sys.executable)
    monkeypatch.setattr(procs, "port_in_use", lambda port: True)
    monkeypatch.setattr(serverstart, "start", lambda *a, **k: started.append("server") or 0)
    p = argparse.ArgumentParser()
    launchcli.add_options(p, "start", "all")
    a = p.parse_args(["--console-port", "17999"])
    a.what = "all"
    assert launchcli.cmd_start(a, []) == 3 and started == []
    assert "[FAIL] port 17999" in capsys.readouterr().err


# ---- S8, S9: setup is idempotent and ends with its verdict (row 02) -------------------------------------------------------------------

def test_row02_setup_a_second_time_changes_no_byte_and_runs_no_pip(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(envsetup, "default_wheels", lambda: tmp_path / "none")
    monkeypatch.setattr(envsetup, "pack_places", lambda: [])
    installed = []
    monkeypatch.setattr(pylibs, "verify", lambda *a, **k: [])
    monkeypatch.setattr(pylibs, "install", lambda *a, **k: installed.append(a))
    a = argparse.Namespace(part=None, system=False, check=False, home=None, data_dir=str(tmp_path / "data"), envs_dir=str(tmp_path / "envs"),
                           java_home=None, python=None, skip_venv=False, reinstall=False, index=False, wheels=None)
    envsetup.setup(a, {"DRISHTI_HOME": str(ROOT)})
    first = snapshot(tmp_path / "envs", tmp_path / "data")
    capsys.readouterr()
    envsetup.setup(argparse.Namespace(**vars(a)), {"DRISHTI_HOME": str(ROOT)})
    out = capsys.readouterr().out
    assert snapshot(tmp_path / "envs", tmp_path / "data") == first and installed == []
    assert not list((tmp_path / "envs").glob("replaced-*")) and "[made]" not in out and "[move]" not in out
    assert out.rstrip().splitlines()[-1] in ("Drishti is ready.",) or out.rstrip().splitlines()[-1].startswith("Drishti is NOT ready:")


def test_row03_setup_check_changes_no_byte(tmp_path, monkeypatch):
    monkeypatch.setattr(pylibs, "verify", lambda *a, **k: ["alpha is not installed"])
    a = argparse.Namespace(part=None, system=False, check=True, home=None, data_dir=str(tmp_path / "data"), envs_dir=str(tmp_path / "envs"),
                           java_home=None, python=None, skip_venv=False, reinstall=False, index=False, wheels=None)
    assert envsetup.setup(a, {"DRISHTI_HOME": str(ROOT)}) == 1
    assert not (tmp_path / "envs").exists() and not (tmp_path / "data").exists()


# ---- S5: pip is never bent by the company's configuration (row 69) ------------------------------------------------------------------

def test_row69_pip_from_the_pack_is_isolated_and_offline(tmp_path):
    calls = []

    def run(cmd, **kw):
        calls.append(cmd)
        if cmd[1] == "-c":
            import json
            return subprocess.CompletedProcess(cmd, 0, json.dumps({n: None for n in cmd[3:]}) + "\n", "")
        return subprocess.CompletedProcess(cmd, 0, "", "")
    home = tmp_path / "h"
    (home / "drishti-console").mkdir(parents=True)
    (home / "drishti-console" / "requirements.txt").write_text("alpha>=1\n", encoding="utf-8")
    (home / "lib").mkdir()
    (home / "lib" / "lake-wheel.pin").write_text("version=0.9.1\n", encoding="utf-8")
    pack = tmp_path / "pack"
    pack.mkdir()
    (pack / "alpha-1.2-py3-none-any.whl").write_bytes(b"w")
    pylibs.install(sys.executable, home, windows=False, run=run, wheels=pack)
    pips = [c for c in calls if c[1:3] == ["-m", "pip"] and "install" in c]
    assert pips and all("--isolated" in c and "--no-index" in c and "--no-input" in c for c in pips)


# ---- P4: a named Python that is not there is a [FAIL], never a fall-through (row 13) ---------------------------------------------------

def test_row13_a_gone_drishti_python_is_a_fail_not_another_python(tmp_path):
    import consolestart
    with pytest.raises(procs.LaunchError, match=r"^\[FAIL\] the console's Python: .*gone.* is not there"):
        consolestart.console_python({"DRISHTI_PYTHON": str(tmp_path / "gone")}, {}, ROOT, verify=lambda *a: [])


# ---- G5: Drishti's own calls never go through a proxy (row 56) ------------------------------------------------------------------------

def test_row56_drishtis_own_calls_ignore_the_proxy():
    import urllib.request
    handlers = procs.no_proxy_opener().handlers          # an empty ProxyHandler replaces the default one, which reads the environment
    assert not any(isinstance(h, urllib.request.ProxyHandler) for h in handlers)
    with mock.patch.dict(os.environ, {"HTTP_PROXY": "http://127.0.0.1:9", "http_proxy": "http://127.0.0.1:9"}):
        assert any(isinstance(h, urllib.request.ProxyHandler) for h in urllib.request.build_opener().handlers), "the default follows it"
        assert not any(isinstance(h, urllib.request.ProxyHandler) for h in procs.no_proxy_opener().handlers), "Drishti's own does not"
    for rel in ("core/backend.py", "core/server_facts.py", "core/cluster.py", "core/backend_watch.py", "core/servers.py"):
        text = (ROOT / "drishti-console" / rel).read_text(encoding="utf-8")
        assert text.count("httpx.AsyncClient(") == text.count("trust_env=False"), rel
