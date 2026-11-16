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

"""The Python launch commands (tools/launch: drishti.py setup, build jar, start, stop, status) and the one Java, home and jar lookup
(tools/javacmd.py). Windows behaviour is checked with os.name mocked: no PowerShell, no .exe of our own."""
from __future__ import annotations

import os
import subprocess
import sys
import zipfile
from pathlib import Path
from unittest import mock

import pytest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for p in (str(TOOLS), str(TOOLS / "launch"), str(TOOLS / "lake")):
    if p not in sys.path:
        sys.path.insert(0, p)
import javacmd  # noqa: E402
import procs  # noqa: E402

DRISHTI = [sys.executable, str(TOOLS / "drishti.py")]


def fake_jdk(folder: Path, version: int) -> Path:
    """A JDK folder whose bin/java says it is ``version`` (POSIX shell script)."""
    b = folder / "bin"
    b.mkdir(parents=True)
    j = b / "java"
    j.write_text(f"#!/bin/sh\necho 'java.specification.version = {version}' >&2\n", encoding="utf-8")
    j.chmod(0o755)
    return folder


@pytest.fixture(autouse=True)
def _fresh_versions():
    javacmd._VERSIONS.clear()
    yield
    javacmd._VERSIONS.clear()


def env_of(**kw) -> dict:
    base = {k: v for k, v in os.environ.items() if k not in ("DRISHTI_JAVA_HOME", "JAVA_HOME", "DRISHTI_HOME", "DRISHTI_DATA_DIR",
                                                               "DRISHTI_MODULES_HOME", "DRISHTI_TMP_DIR")}
    base.update({k: str(v) for k, v in kw.items()})
    return base


# ---- the one Java lookup ----------------------------------------------------------------------------------------------------------------
@pytest.mark.skipif(os.name == "nt", reason="the fake JDKs are shell scripts")
def test_the_one_order_java_home_option_then_drishti_java_home_then_java_home_then_path(tmp_path):
    a, b, c, d = (fake_jdk(tmp_path / n, 21) for n in "abcd")
    env = env_of(DRISHTI_JAVA_HOME=b, JAVA_HOME=c, PATH=str(d / "bin"))
    assert javacmd.find_java(None, a, env) == str(a / "bin" / "java")
    assert javacmd.find_java(None, None, env) == str(b / "bin" / "java")
    env.pop("DRISHTI_JAVA_HOME")
    assert javacmd.find_java(None, None, env) == str(c / "bin" / "java")
    env.pop("JAVA_HOME")
    assert javacmd.find_java(None, None, env) == str(d / "bin" / "java")
    assert javacmd.locate_java(None, None, env)[1] == "java on the PATH"
    assert javacmd.find_java(str(a / "bin" / "java"), None, env_of(DRISHTI_JAVA_HOME=b)) == str(a / "bin" / "java")   # --java first


@pytest.mark.skipif(os.name == "nt", reason="the fake JDKs are shell scripts")
def test_an_old_java_is_refused_in_words_and_a_named_empty_home_is_never_skipped(tmp_path):
    old = fake_jdk(tmp_path / "jdk17", 17)
    with pytest.raises(javacmd.JavaError, match=r"Java 21 or newer.*DRISHTI_JAVA_HOME=.*is Java 17"):
        javacmd.find_java(None, None, env_of(DRISHTI_JAVA_HOME=old))
    good = fake_jdk(tmp_path / "jdk21", 21)
    with pytest.raises(javacmd.JavaError, match="DRISHTI_JAVA_HOME is .*empty.*JDK 21 folder"):
        (tmp_path / "empty").mkdir()
        javacmd.find_java(None, None, env_of(DRISHTI_JAVA_HOME=tmp_path / "empty", JAVA_HOME=good))
    assert javacmd.find_java(None, None, env_of(PATH=str(tmp_path / "nothing")), required=False) is None
    with pytest.raises(javacmd.JavaError, match="no Java found"):
        javacmd.find_java(None, None, env_of(PATH=str(tmp_path / "nothing")))
    d = javacmd.describe_java(None, None, env_of(DRISHTI_JAVA_HOME=old))
    assert d["version"] == 17 and not d["ok"] and "older than 21" in d["problem"]


@pytest.mark.skipif(os.name == "nt", reason="the fake JDKs are shell scripts")
def test_lake_demo_runs_the_java_of_drishti_java_home(tmp_path, monkeypatch):
    """`lake demo --engine java` starts the parallel loader through javaload.command: DRISHTI_JAVA_HOME wins over JAVA_HOME."""
    import javaload
    mine, other = fake_jdk(tmp_path / "mine", 21), fake_jdk(tmp_path / "other", 21)
    jar = tmp_path / "drishti-server-9-exec.jar"
    with zipfile.ZipFile(jar, "w") as z:
        z.writestr(javaload.LOADER_CLASS, b"")
    monkeypatch.setenv("DRISHTI_JAVA_HOME", str(mine))
    monkeypatch.setenv("JAVA_HOME", str(other))
    monkeypatch.delenv("DRISHTI_LAKE_CLASSPATH", raising=False)
    java, args = javaload.command(str(jar), None)
    assert java == str(mine / "bin" / "java") and args[-1] == str(jar)


def test_every_java_finder_is_the_one_in_javacmd():
    """No second Java lookup: the tools ask javacmd.find_java (no JAVA_HOME read and no /usr/lib/jvm guess of their own)."""
    for rel in ("tools/drishti.py", "tools/sutragen.py", "tools/lake/writers.py", "tools/launch/serverstart.py", "tools/launch/clusterctl.py"):
        text = (ROOT / rel).read_text(encoding="utf-8")
        assert "/usr/lib/jvm" not in text and 'environ.get("JAVA_HOME")' not in text, rel


# ---- the one home and the one jar place --------------------------------------------------------------------------------------------------
def test_the_home_is_the_tools_own_folder_and_another_is_refused(tmp_path):
    assert javacmd.find_home(None, env_of(), {}) == (ROOT.resolve(), "the folder holding tools/drishti.py")
    assert javacmd.find_home(None, env_of(DRISHTI_HOME=ROOT), {})[1] == "DRISHTI_HOME"
    for named in ({"DRISHTI_HOME": tmp_path}, {}):
        with pytest.raises(javacmd.HomeError, match="one release's tools never drive another"):
            javacmd.find_home(None if named else tmp_path, env_of(**named), {})
    with pytest.raises(javacmd.HomeError, match="drishti.home in the settings file"):
        javacmd.find_home(None, env_of(), {"drishti.home": str(tmp_path)})


def test_a_relative_home_is_refused_as_relative_before_any_comparison():
    """Explicit paths: a relative home is refused for being relative (never resolved under the working folder first)."""
    for named in ({"DRISHTI_HOME": "drishti"}, {}):
        with pytest.raises(javacmd.HomeError, match="a relative path: name the install folder as an absolute path"):
            javacmd.find_home(None if named else "drishti", env_of(**named), {})
    with pytest.raises(javacmd.HomeError, match="relative path"):
        javacmd.find_home(None, env_of(), {"drishti.home": "drishti"})


def test_the_roots_and_where_each_came_from(tmp_path):
    r = javacmd.launch_roots(None, None, env_of(), {})
    assert r["data"] == (ROOT.resolve() / "data", "<home>/data") and r["modules"][0] == ROOT.resolve() / "modules"
    r = javacmd.launch_roots(None, None, env_of(DRISHTI_DATA_DIR=tmp_path / "env"), {"drishti.data.dir": str(tmp_path / "ini")})
    assert r["data"] == ((tmp_path / "ini").resolve(), "drishti.data.dir in the settings file")
    r = javacmd.launch_roots(None, tmp_path / "opt", env_of(DRISHTI_DATA_DIR=tmp_path / "env"), {})
    assert r["data"] == ((tmp_path / "opt").resolve(), "--data-dir")
    assert javacmd.launch_roots(None, None, env_of(DRISHTI_DATA_DIR=tmp_path / "env"), {})["data"][1] == "DRISHTI_DATA_DIR"


def test_the_server_jar_is_found_in_home_lib_only(tmp_path):
    home = tmp_path / "home"
    (home / "kernel" / "drishti-server" / "target").mkdir(parents=True)
    (home / "kernel" / "drishti-server" / "target" / "drishti-server-1-exec.jar").write_bytes(b"x")   # Maven's output: never looked at
    with pytest.raises(javacmd.JarError, match=r"no server jar in .*lib: run  python tools/drishti.py build jar"):
        javacmd.server_jar(None, home)
    (home / "lib").mkdir()
    (home / "lib" / "drishti-server-2-exec.jar").write_bytes(b"x")
    assert javacmd.server_jar(None, home) == home / "lib" / "drishti-server-2-exec.jar"
    (home / "lib" / "ext").mkdir()
    (home / "lib" / "ext" / "drishti-server-0-exec.jar").write_bytes(b"x")       # lib/ext is the site's: never looked at for the server
    assert javacmd.server_jar(None, home) == home / "lib" / "drishti-server-2-exec.jar"
    (home / "lib" / "drishti-server-1-exec.jar").write_bytes(b"x")             # an upgrade's stale old jar: refused, never guessed
    with pytest.raises(javacmd.JarError, match="holds 2 server jars.*remove all but the version to run"):
        javacmd.server_jar(None, home)
    with pytest.raises(javacmd.JarError, match="no such file"):
        javacmd.server_jar(tmp_path / "missing.jar")


# ---- processes: pid files, never by name, Windows ---------------------------------------------------------------------------------------
def test_the_env_file_rules(tmp_path):
    f = tmp_path / "x.env"
    f.write_text("# c\n\nexport A=1\nB=\"two words\"\nC='3'\n9X=bad\nnot a pair\n", encoding="utf-8")
    assert procs.read_env_file(f) == {"A": "1", "B": "two words", "C": "3"}


def test_a_stale_pid_file_is_removed_and_nothing_is_killed(tmp_path):
    f = tmp_path / "run" / "server.pid"
    procs.write_pid(f, 999999, port=1, url="u")
    assert procs.read_pid(f) == (999999, {"port": "1", "url": "u"})
    with mock.patch.object(procs, "terminate") as kill:
        procs.stop_pid_file(f, "server", procs.SERVER_MARK, 1)
    kill.assert_not_called()
    assert not f.exists()


def test_a_process_that_is_not_drishtis_is_never_stopped(tmp_path):
    """Our own pid with a server's pid file: alive, but its command line has no -jar, so it is not ours."""
    f = tmp_path / "server.pid"
    procs.write_pid(f, os.getpid())
    with mock.patch.object(procs, "terminate") as kill:
        procs.stop_pid_file(f, "server", procs.SERVER_MARK, 1)
    kill.assert_not_called()


def test_windows_alive_terminate_and_spawn(tmp_path):
    out = '"java.exe","4242","Console","1","123,456 K"\n'
    with mock.patch.object(procs, "windows", lambda: True), mock.patch.object(procs.subprocess, "run",
                                                                      return_value=subprocess.CompletedProcess([], 0, out, "")) as run:
        assert procs.alive(4242, procs.SERVER_MARK) and not procs.alive(4242, procs.CONSOLE_MARK)
        assert run.call_args[0][0][:3] == ["tasklist", "/FI", "PID eq 4242"]
        procs.terminate(4242)
        assert run.call_args[0][0] == ["taskkill", "/PID", "4242"]
        procs.terminate(4242, force=True)
        assert run.call_args[0][0] == ["taskkill", "/F", "/PID", "4242"]
    with mock.patch.object(procs, "windows", lambda: True), mock.patch.object(procs.subprocess, "run",
                                                                      return_value=subprocess.CompletedProcess([], 0, "INFO: No tasks", "")):
        assert not procs.alive(4242, procs.SERVER_MARK)
    with mock.patch.object(procs, "windows", lambda: True), mock.patch.object(procs.subprocess, "Popen") as popen:
        procs.spawn(["a-server", "--port=1"], tmp_path, {}, log=tmp_path / "logs" / "s.out")
        kw = popen.call_args.kwargs
        assert kw["creationflags"] == procs.CREATE_NEW_PROCESS_GROUP | procs.CREATE_NO_WINDOW and "start_new_session" not in kw
        assert procs.venv_python(tmp_path) == tmp_path / "drishti-console" / ".venv" / "Scripts/python.exe"
    with mock.patch.object(procs.subprocess, "Popen") as popen:
        if os.name != "nt":
            procs.spawn(["a-server"], tmp_path, {}, log=tmp_path / "s.out")
            assert popen.call_args.kwargs["start_new_session"] is True


def test_windows_builds_with_mvnw_cmd_through_cmd(tmp_path):
    import envsetup
    (tmp_path / "mvnw.cmd").write_text("", encoding="utf-8")
    with mock.patch.object(procs, "windows", lambda: True):
        assert envsetup.maven_command(tmp_path) == ["cmd", "/c", str(tmp_path / "mvnw.cmd")]
    with mock.patch.object(procs, "windows", lambda: True), mock.patch.dict(os.environ, {"USERPROFILE": str(tmp_path / "me")}):
        assert envsetup.envs_dir() == tmp_path / "me" / "drishti-envs"


# ---- the commands ----------------------------------------------------------------------------------------------------------------------
def run(*args, env=None) -> subprocess.CompletedProcess:
    return subprocess.run([*DRISHTI, *args], capture_output=True, text=True, timeout=120, env=env or env_of(), cwd=str(ROOT))


def test_wrong_options_exit_2():
    assert run("start", "server", "--node", "a", "--advertise", "127.0.0.1").returncode == 2
    assert run("start", "server", "--seed", "10.0.0.1:18480").returncode == 2
    assert run("stop", "server", "--node", "a", "--all-nodes").returncode == 2
    r = run("status", env=env_of(DRISHTI_HOME="/somewhere/else"))
    assert r.returncode == 2 and "another release's install" in r.stderr


def test_status_shows_the_roots_and_a_stale_pid(tmp_path):
    procs.write_pid(tmp_path / "run" / "console-risk.pid", 999999, port=17999, distribution="risk")
    r = run("status", "--data-dir", str(tmp_path))
    assert r.returncode == 1 and "stale pid" in r.stdout and "console-risk" in r.stdout
    assert "home " in r.stdout and "the folder holding tools/drishti.py" in r.stdout and "java " in r.stdout


def test_setup_check_changes_nothing_and_setup_is_idempotent(tmp_path):
    data, envs = tmp_path / "data", tmp_path / "envs"
    r = run("setup", "--check", "--data-dir", str(data), "--envs-dir", str(envs), "--skip-venv")
    assert r.returncode == 1 and not data.exists() and not envs.exists(), r.stdout
    r = run("setup", "--data-dir", str(data), "--envs-dir", str(envs), "--skip-venv")
    assert all((data / d).is_dir() for d in ("run", "logs", "tmp", "modules", "delta")), r.stdout + r.stderr
    assert not (data / "lib").exists(), "every jar is under the home (lib, lib/ext), never the data folder"
    ini = (envs / "dev.ini").read_text(encoding="utf-8")
    assert f"drishti.home={ROOT.resolve().as_posix()}" in ini and f"drishti.data.dir={data.resolve().as_posix()}" in ini
    assert "/path/to/drishti" not in ini
    (envs / "dev.ini").write_text("# mine\n", encoding="utf-8")
    r = run("setup", "--data-dir", str(data), "--envs-dir", str(envs), "--skip-venv")
    assert (envs / "dev.ini").read_text(encoding="utf-8") == "# mine\n" and "kept as it is" in r.stdout
    assert "Environment variables seen" in r.stdout and "Next steps" in r.stdout


def test_build_jar_is_clean_package_never_install():
    r = run("build", "jar", "--dry-run")
    assert r.returncode == 0, r.stderr
    assert "clean package -DskipTests -pl :drishti-app -am" in r.stdout and " install" not in r.stdout


def test_the_shell_twins_are_gone():
    """No dual machinery: bin/ keeps only the containers' and systemd's POSIX stop hook (no Python in the images)."""
    tracked = subprocess.run(["git", "ls-files", "bin"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    assert sorted(tracked) == ["bin/README.md", "bin/drishti-node.sh"]   # what git holds: a leftover __pycache__ is not a shell twin
    assert not (ROOT / "tools" / "windows").exists() and not (ROOT / "tools" / "rolling-upgrade.sh").exists()


def test_nothing_stops_a_process_by_name():
    for p in (TOOLS / "launch").glob("*.py"):
        text = p.read_text(encoding="utf-8")
        for bad in ("pkill", "killall", "taskkill /IM", '"/IM"', "pgrep"):
            assert bad not in text, (p.name, bad)


def test_a_cluster_files_jvm_flags_and_server_args_reach_the_server_options():
    """stack.yaml's jvm: and args: start with a dash: clusterctl hands them as --java-opt=FLAG and --server-arg=ARG, which parse."""
    import argparse
    import launchcli
    p = argparse.ArgumentParser()
    launchcli.add_options(p, "start", "server")
    a = p.parse_args(["--node", "b", "--java-opt=-XX:G1PeriodicGCInterval=60000", "--server-arg=--drishti.security.registered-users-only=false"])
    assert a.java_opt == ["-XX:G1PeriodicGCInterval=60000"] and a.server_arg == ["--drishti.security.registered-users-only=false"]
    text = (TOOLS / "launch" / "clusterctl.py").read_text(encoding="utf-8")
    assert 'f"--java-opt={flag}"' in text and 'f"--server-arg={arg}"' in text
