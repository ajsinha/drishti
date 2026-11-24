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

"""The first-run matrix, start/stop/status rows (docs/plans/FIRST_RUN_ROBUSTNESS.md section B2: 04, 05, 06, 11, 12, 13, 49, 50, 55, 56,
67; G1 to G3). ``tools/drishti.py`` runs as a person runs it, in a scratch home whose name holds a space, under the three oracles
(tools/matrix_oracles.py). Every data folder is a scratch one (--data-dir); no port of the owner's stack is used, and no test here can
start a server: each row stops in the preflight, before Java is started."""
from __future__ import annotations

import os
import socket
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import javacmd  # noqa: E402
import matrix_oracles as mo  # noqa: E402
import procs  # noqa: E402

ROOT = mo.ROOT
PORT, CPORT = 18996, 17996                       # never the owner's (18480, 17480, 18791, 18792)


class Person:
    def __init__(self, tmp: Path, **extra):
        self.tmp, self.home = tmp, tmp / "John Smith"
        self.data = self.home / "drishti data"
        self.env = mo.oracle_env(tmp, home=self.home, **extra)

    def __call__(self, *argv, timeout=300):
        r = mo.drishti(*argv, env=self.env, timeout=timeout)
        r.text = r.stdout + r.stderr
        return r

    def set_up(self):
        r = self("setup", "--data-dir", str(self.data))
        assert self.data.joinpath("run").is_dir(), r.text[-2000:]
        return r

    def clean(self):
        return mo.net(self.tmp) + mo.calls(self.tmp)


@pytest.fixture()
def person(tmp_path):
    assert not ({PORT, CPORT} & set(mo.OWNER_PORTS))
    return Person(tmp_path)


# ---- row 04 / R3: start before setup makes nothing and starts nothing --------------------------------------------------------------

@pytest.mark.parametrize("what", [["start", "server", "--port", str(PORT)], ["start", "console", "--port", str(CPORT)],
                                  ["start", "all", "--server-port", str(PORT), "--console-port", str(CPORT)]], ids=["server", "console", "all"])
def test_row04_start_before_setup_is_one_fail_with_the_setup_line_and_makes_nothing(person, what):
    fresh = person.tmp / "fresh data"
    before = mo.snapshot(person.tmp, skip=("/oracle/",))
    r = person(*what, "--data-dir", str(fresh))
    assert r.returncode != 0 and "[FAIL]" in r.text and "setup" in r.text, r.text[-2000:]
    assert not fresh.exists() and mo.changed(before, mo.snapshot(person.tmp, skip=("/oracle/",))) == []
    assert not mo.output_problems(r.text) and not person.clean()


# ---- row 05: no server jar ---------------------------------------------------------------------------------------------------------------

def test_row05_no_server_jar_is_one_fail_with_the_build_line(tmp_path):
    with pytest.raises(Exception) as e:
        javacmd.server_jar(None, tmp_path)
    assert "build jar" in str(e.value)


# ---- row 06: --demo with no demo lake ------------------------------------------------------------------------------------------------

def test_row06_demo_without_the_demo_lake_stops_in_the_preflight_with_the_lake_line(person):
    person.set_up()
    r = person("start", "server", "--demo", "--port", str(PORT), "--data-dir", str(person.data), "--lake-root", str(person.tmp / "no lake"))
    assert r.returncode == 1 and "[FAIL] the demo lake is not built" in r.text and "lake demo --engine java" in r.text, r.text[-2000:]
    assert not list(person.data.joinpath("run").glob("*.pid")) and not mo.output_problems(r.text) and not person.clean()


# ---- rows 11, 12: another clone's dev.ini, setup not run; stale DRISHTI_* folder variables ------------------------------------------------

def test_row11_another_clones_dev_ini_is_warned_and_never_followed(person):
    old = person.tmp / "old clone"
    (old / "data" / "run").mkdir(parents=True)
    (old / "data" / "run" / "server.pid").write_text("1\nport=1\n", encoding="utf-8")
    ini = person.home / "drishti-envs" / "dev.ini"
    ini.parent.mkdir(parents=True)
    ini.write_text(f"[drishti]\ndrishti.home={old}\ndrishti.data.dir={old}/data\n", encoding="utf-8")
    before = mo.snapshot(old)
    for argv in (["status"], ["stop", "all"]):
        r = person(*argv)
        assert "Traceback" not in r.text and str(old / "data") not in r.stdout.split("process")[0].replace(f"{old}/data/run/server.pid", ""), r.text
    assert mo.snapshot(old) == before, "the old clone's pid file is neither read for a stop nor removed"


@pytest.mark.parametrize("argv", [["status"], ["stop", "all"]], ids=["status", "stop-all"])
def test_row12_stale_folder_variables_are_warned_in_every_command_and_nothing_is_made_there(tmp_path, argv):
    old = Path("/drishti-first-run-decoy/old clone")      # outside the temp folder (a scratch folder there is allowed); never made
    p = Person(tmp_path, DRISHTI_HOME=old, DRISHTI_DATA_DIR=old / "data", DRISHTI_MODULES_HOME=old / "modules", DRISHTI_TMP_DIR=old / "tmp")
    r = p(*argv)
    assert "DRISHTI_DATA_DIR" in r.stderr and "ignored" in r.stderr and not old.exists() and "Traceback" not in r.text


# ---- row 13: a DRISHTI_PYTHON that is gone --------------------------------------------------------------------------------------------

def test_row13_start_console_with_a_gone_drishti_python_is_one_fail(tmp_path):
    p = Person(tmp_path, DRISHTI_PYTHON=tmp_path / "gone" / "python")
    p.set_up()
    r = p("start", "console", "--port", str(CPORT), "--data-dir", str(p.data))
    assert r.returncode == 1 and "[FAIL] the console's Python" in r.text and "is not there" in r.text and not mo.output_problems(r.text)


# ---- row 49: a port held by another program: start all checks both first -----------------------------------------------------------------

def test_row49_a_held_console_port_stops_start_all_before_the_server(person):
    person.set_up()
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        s.listen(1)
        held = s.getsockname()[1]
        r = person("start", "all", "--server-port", str(PORT), "--console-port", str(held), "--data-dir", str(person.data))
    assert r.returncode == 3 and f"[FAIL] port {held}" in r.text and "Fix:" in r.text, r.text[-2000:]
    assert not list(person.data.joinpath("run").glob("*.pid")), "nothing was started"


# ---- rows 50, 55: a stale pid file (crash, or the pid now someone else's) -----------------------------------------------------------------

def test_row55_a_stale_pid_file_is_removed_and_nothing_is_stopped(person):
    person.set_up()
    import subprocess
    other = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])    # the pid now belongs to another program
    try:
        pid_file = person.data / "run" / "server.pid"
        pid_file.write_text(f"{other.pid}\nport={PORT}\n", encoding="utf-8")
        r = person("stop", "server", "--data-dir", str(person.data), "--timeout", "5")
        assert "Traceback" not in r.text and r.returncode == 0
        assert other.poll() is None, "another program is never stopped"
        assert not procs.alive(other.pid, procs.SERVER_MARK)
    finally:
        other.kill()
        other.wait()


def test_row50_status_works_on_an_unprepared_machine_and_names_no_owner_process(person):
    r = person("status", "--data-dir", str(person.tmp / "nothing"))
    assert r.returncode == 0 and "Traceback" not in r.text and not person.clean()


# ---- rows 56, 67: a dead proxy in the environment and no network: the commands work, nothing reaches another machine -------------------------

def test_rows56_67_a_dead_proxy_and_no_network_change_nothing(person):
    person.set_up()
    r = person("status", "--data-dir", str(person.data))
    assert r.returncode == 0 and "Traceback" not in r.text
    assert not mo.net(person.tmp) and not mo.calls(person.tmp), "no connect to another machine, no python/pip/git by name"
