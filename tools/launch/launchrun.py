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

"""``drishti.py launchers --list`` and ``launchers --run "<name>"``: the committed IDE launchers (``run-configs/*.run.xml`` and
``drishti-console/run-configs/*.run.xml``) read as they are, the one source (no second table), so the IDE and the command line never drift apart.

* ``--list`` prints every launcher by group with the exact command it runs and its working folder.
* ``--run NAME`` (the whole name or a prefix that names one launcher) runs that command in that folder with the launcher's environment
  variables, streams its output and returns its exit code.

The command, as the IDE builds it: Maven (MavenRunConfiguration) is ``mvnw`` (``mvnw.cmd`` on Windows) + profiles + goals; Python
(PythonConfigurationType) is the one Python (DRISHTI_PYTHON, else ``drishti.python`` of the settings files, else the running Python) + script +
parameters; JAR Application is ``java`` (the Java of the usual order: drishti.java.home, DRISHTI_JAVA_HOME, JAVA_HOME, the PATH) + VM options
(an ``@file`` too) + ``-jar`` + program arguments; Application is ``java`` + VM options + main class + program arguments. ``$PROJECT_DIR$`` is
the project the file belongs to (the install folder; ``drishti-console`` for the PyCharm launchers), ``$USER_HOME$`` the user's folder."""
from __future__ import annotations

import os
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import procs  # noqa: E402

FOLDERS = ("run-configs", "drishti-console/run-configs")
SUFFIX = ".run.xml"


class Launcher:
    """One committed launcher: its group, name, command words, working folder and the environment variables it adds."""

    def __init__(self, file: Path, group: str, name: str, kind: str, words: list, cwd: Path, env: dict, java: str | None):
        self.file, self.group, self.name, self.kind = file, group, name, kind
        self.words, self.cwd, self.env, self.java = words, cwd, env, java   # java: the alternative JRE folder a JAR/Application launcher names

    def argv(self) -> list:
        return list(self.words)

    def line(self, win: bool | None = None) -> str:
        win = procs.windows() if win is None else win
        return " ".join(procs._q(w, win) for w in self.words)


def _project(home: Path, file: Path) -> Path:
    return home / "drishti-console" if file.resolve().parent == (home / FOLDERS[1]).resolve() else home


def parse(file: Path, home: Path, user_home: Path, win: bool | None = None, keys: dict | None = None, env: dict | None = None) -> Launcher:
    """The launcher a ``*.run.xml`` file holds. Raises ValueError for a configuration of a kind that has no command line of its own."""
    win = procs.windows() if win is None else win
    conf = ET.parse(file).getroot().find("configuration")
    if conf is None:
        raise ValueError(f"{file.name}: no <configuration>")
    project = _project(home, file)

    def expand(s: str | None) -> str:
        return (s or "").replace("$PROJECT_DIR$", project.as_posix()).replace("$USER_HOME$", user_home.as_posix())

    def opts(node) -> dict:
        return {e.get("name"): e.get("value") for e in node.findall("option") if e.get("value") is not None}
    o = opts(conf)
    kind = conf.get("type") or ""
    name, group = conf.get("name") or file.name, conf.get("folderName") or ""
    envs = {e.get("name"): expand(e.get("value")) for e in conf.iter("env")}
    java = expand(o.get("ALTERNATIVE_JRE_PATH")) or None
    if kind == "MavenRunConfiguration":
        params = conf.find(".//MavenRunnerParameters")
        if params is None:
            raise ValueError(f"{file.name}: no MavenRunnerParameters")
        goals = [e.get("value") for e in params.findall("./option[@name='goals']/list/option")]
        profiles = [e.get("value") for e in params.findall("./option[@name='profiles']/set/option")]
        pom = expand(opts(params).get("pomFileName"))
        cwd = Path(os.path.normpath(expand(opts(params).get("workingDirPath")) or project.as_posix()))
        words = [str(project / ("mvnw.cmd" if win else "mvnw"))] + (["-f", pom] if pom else []) + (["-P", ",".join(profiles)] if profiles else []) + goals
        return Launcher(file, group, name, kind, words, cwd, envs, None)
    cwd = Path(os.path.normpath(expand(o.get("WORKING_DIRECTORY")) or project.as_posix()))
    if kind == "PythonConfigurationType":
        py = procs.tools_python(env, keys)[0]
        if o.get("MODULE_MODE") == "true":
            target = ["-m", expand(o.get("SCRIPT_NAME"))]
        else:
            target = [os.path.normpath(expand(o.get("SCRIPT_NAME")))]
        words = [py] + shlex.split(expand(o.get("INTERPRETER_OPTIONS"))) + target + shlex.split(expand(o.get("PARAMETERS")))
        return Launcher(file, group, name, kind, words, cwd, envs, None)
    if kind in ("JarApplication", "Application"):
        vm = shlex.split(expand(o.get("VM_PARAMETERS")))
        prog = shlex.split(expand(o.get("PROGRAM_PARAMETERS")))
        target = ["-jar", expand(o["JAR_PATH"])] if kind == "JarApplication" else [o.get("MAIN_CLASS_NAME") or ""]
        return Launcher(file, group, name, kind, ["java"] + vm + target + prog, cwd, envs, java)
    raise ValueError(f"{file.name}: a {kind} has no command line of its own")


def load(home: Path, user_home: Path | None = None, win: bool | None = None, keys: dict | None = None, env: dict | None = None) -> list:
    """Every committed launcher of both folders, ordered by group then name. A file that cannot be read is an error, never skipped."""
    user_home = Path.home() if user_home is None else user_home
    out = []
    for folder in FOLDERS:
        for f in sorted((home / folder).glob("*" + SUFFIX)):
            out.append(parse(f, home, user_home, win, keys, env))
    return sorted(out, key=lambda l: (l.group, l.name))


def _fix() -> str:
    return procs.command("launchers", "--list")


def listing(items: list, win: bool | None = None) -> str:
    lines, group = [], None
    for l in items:
        if l.group != group:
            group = l.group
            lines += ["", group]
        lines += [f"  {l.name}", f"      {l.line(win)}", f"      in {l.cwd}" + (f"   env {', '.join(sorted(l.env))}" if l.env else "")]
    return "\n".join(lines).lstrip("\n")


def find(items: list, wanted: str) -> Launcher:
    """The launcher called ``wanted``: the whole name (any case) or a prefix that names exactly one; ``Drishti: `` may be left off."""
    w = " ".join((wanted or "").split()).lower()
    if not w:
        raise procs.LaunchError(procs.fail('launcher ""', "no name given", _fix()), 1)
    for pre in ("", "drishti: "):
        exact = [l for l in items if l.name.lower() == pre + w]
        if len(exact) == 1:
            return exact[0]
        hits = [l for l in items if l.name.lower().startswith(pre + w)]
        if len(hits) == 1:
            return hits[0]
        if len(hits) > 1:
            raise procs.LaunchError(procs.fail(f'launcher "{wanted}"', "it names " + str(len(hits)) + " launchers (" + "; ".join(l.name for l in hits) + "): give more of the name", _fix()), 1)
    raise procs.LaunchError(procs.fail(f'launcher "{wanted}"', "no launcher has that name", _fix()), 1)


def run(l: Launcher, java_finder=None) -> int:
    """Runs the launcher's command in its folder with the environment it names (on top of this process's); the child's exit code."""
    words = l.argv()
    if words[0] == "java":
        import javacmd
        words[0] = (java_finder or javacmd.find_java)(None, l.java)
    env = {**os.environ, **l.env}
    procs.say(f"[{l.group}] {l.name}\n  {' '.join(procs._q(w, procs.windows()) for w in words)}\n  in {l.cwd}")
    if not l.cwd.is_dir():
        raise procs.LaunchError(procs.fail(f'launcher "{l.name}"', f"its working folder {l.cwd} does not exist", _fix()), 1)
    try:
        return subprocess.run(words, cwd=str(l.cwd), env=env).returncode
    except OSError as e:
        raise procs.LaunchError(procs.fail(f'launcher "{l.name}"', f"{words[0]} could not be started ({e})", _fix()), 1)


def main(a, home: Path) -> int:
    try:
        items = load(home)
        if a.run is not None:
            return run(find(items, a.run))
        print(listing(items))
        return 0
    except procs.LaunchError as e:
        print(str(e), file=sys.stderr, flush=True)
        return e.code
    except (ValueError, ET.ParseError) as e:
        print(procs.fail("launchers", str(e), _fix()), file=sys.stderr, flush=True)
        return 1
