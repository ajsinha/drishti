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

"""``drishti.py launchers``: the IDE launchers generated from one table, ``tools/launch/launchers.yaml`` (LAUNCHER_REVIEW.md 4.2).

* ``launchers`` writes ``run-configs/*.run.xml`` (IntelliJ and PyCharm: one folder, one project at the repository root), the VS Code
  workspace ``drishti.code-workspace`` and the tables of ``docs/guides/IDE_GUIDE.md`` section 4; the files are committed, never made only locally.
* ``launchers --check`` changes nothing and fails (exit 1) naming each committed file that differs from the table, a launcher file the
  table does not name, or a stale IDE_GUIDE section 4.
* ``launchers --machine`` writes this machine's JVM arguments files, ``<settings folder>/jvm-<profile>.args``, which every server launcher
  names (``@…/jvm-demo.args``): the one function ``drishti.py start server`` runs (``serverstart.jvm_flags``), with this machine's data
  folder (from ``dev.ini`` and the profile), so the temp folder, heap dumps and GC log follow a moved data folder. ``setup`` runs it.

A launcher carries what to run and its profile only (``config/profiles/<profile>.ini``); the development secrets are the one exception,
taken from ``serverstart.DEV`` (kept as they are, LAUNCHER_REVIEW.md D6)."""
from __future__ import annotations

import os
import shlex
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import procs  # noqa: E402
import serverstart  # noqa: E402
import settingsfile  # noqa: E402
import javacmd  # noqa: E402

TABLE = HERE / "launchers.yaml"
GUIDE = "docs/guides/IDE_GUIDE.md"   # under the install folder (home / GUIDE)
BEGIN, END = "<!-- generated: launchers begin (python tools/drishti.py launchers) -->", "<!-- generated: launchers end -->"
FOLDERS = {"intellij": "run-configs", "pycharm": "run-configs"}   # under the install folder: the one folder of the launcher standard (docs/plans/LAUNCHER_STANDARD.md)
WORKSPACE = "drishti.code-workspace"                                     # VS Code: a task or launch of every launcher's name
DEV_INI = "$USER_HOME$/drishti-envs/dev.ini"
ENVS = "$USER_HOME$/drishti-envs"
JAR = "$PROJECT_DIR$/lib/drishti-server-exec.jar"
SERVER_ENV = (("MALLOC_ARENA_MAX", "2"), ("DRISHTI_TOKEN_SECRET", serverstart.DEV["DRISHTI_TOKEN_SECRET"]),
              ("DRISHTI_SESSION_SECRET", serverstart.DEV["DRISHTI_SESSION_SECRET"]))
CONSOLE_ENV = (("PYTHONUNBUFFERED", "1"), ("DRISHTI_TOKEN_SECRET", serverstart.DEV["DRISHTI_TOKEN_SECRET"]),
               ("DRISHTI_SESSION_SECRET", serverstart.DEV["DRISHTI_SESSION_SECRET"]))
TOOL_ENV = (("PYTHONUNBUFFERED", "1"),)


class LauncherError(Exception):
    pass


def table(home: Path) -> dict:
    """The table, each row checked: a known kind, a name, a profile that is a file of config/profiles/."""
    import yaml
    doc = yaml.safe_load((home / "tools" / "launch" / "launchers.yaml").read_text(encoding="utf-8")) or {}
    for ide in FOLDERS:
        for row in doc.get(ide) or []:
            if row.get("kind") not in ("maven", "server", "console", "tool"):
                raise LauncherError(f"launchers.yaml: {row.get('name')}: kind {row.get('kind')!r} (maven, server, console or tool)")
            if row.get("profile") and not (home / "config" / "profiles" / f"{row['profile']}.ini").is_file():
                raise LauncherError(f"launchers.yaml: {row['name']}: no profile config/profiles/{row['profile']}.ini")
            if row["kind"] == "server" and not row.get("profile"):
                raise LauncherError(f"launchers.yaml: {row['name']}: a server launcher names its profile")
    return doc


def header(home: Path, lines: list[str]) -> str:
    """The repository's notice as an XML comment, then what the launcher is, and that it is generated."""
    notice = (home / "tools" / "launch" / "launchers.py").read_text(encoding="utf-8").splitlines()[:13]
    body = [x[2:] if x.startswith("# ") else x.lstrip("#") for x in notice]
    return "<!--\n" + "\n".join(("  " + x) if x else "" for x in body) + "\n\n" + "\n".join("  " + x for x in lines) + "\n-->\n"


def esc(v: str) -> str:
    return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def q(v: str) -> str:
    """One argument, quoted when it holds a macro path (a user folder with a space, C:\\Users\\First Last, keeps it whole)."""
    return f'"{v}"' if "$" in v or " " in v else v


def envs(pairs) -> str:
    return "    <envs>\n" + "".join(f'      <env name="{esc(k)}" value="{esc(v)}" />\n' for k, v in pairs) + "    </envs>\n"


def server_vm(profile: str) -> str:
    """A server launcher's VM options: this machine's arguments file (the JVM flags), the install and the settings files."""
    return " ".join(q(x) for x in (f"@{ENVS}/jvm-{profile}.args", "-Ddrishti.home=$PROJECT_DIR$",
                                     f"-Ddrishti.settings.file={DEV_INI},$PROJECT_DIR$/config/profiles/{profile}.ini"))


def python_params(row: dict) -> str:
    parts = [f"--settings {q(DEV_INI)}"] + ([f"--profile {row['profile']}"] if row.get("profile") else [])
    if row["kind"] == "console":
        parts += list(row.get("args") or [])
    else:
        parts += [" ".join(q(w) if w.startswith("$") else w for w in row["command"].split())]
    return " ".join(parts)


def render(home: Path, ide: str, row: dict) -> str:
    """One launcher's .run.xml."""
    name, kind = row["name"], row["kind"]
    why = [f"{name}: {row['what']}.", f"Use it when: {row['when']}."]
    if kind == "server":
        why += [f"The profile config/profiles/{row['profile']}.ini holds its settings; ~/drishti-envs/jvm-{row['profile']}.args its JVM options",
                "(python tools/drishti.py setup writes it, from the function drishti.py start server runs).",
                "The server jar Drishti build all installed (lib/drishti-server-exec.jar): the jar the modules are stamped for; nothing is built before launch."]
    elif kind in ("console", "tool") and row.get("profile"):
        why += [f"The profile config/profiles/{row['profile']}.ini (after your dev.ini) holds its settings."]
    if row.get("template"):
        why += ["A template: copy it (Ctrl+D) and name your own file in Parameters; the copy lives in the git-ignored .idea/."]
    why += ["Generated from tools/launch/launchers.yaml by python tools/drishti.py launchers: never edit this file, edit the table."]
    out = [header(home, why), '<component name="ProjectRunConfigurationManager">\n']
    if kind == "maven":
        goals = "".join(f'              <option value="{esc(g)}" />\n' for g in row["goals"].split())
        out.append(f'  <configuration default="false" name="{esc(name)}" type="MavenRunConfiguration" factoryName="Maven" folderName="Drishti">\n'
                   "    <MavenSettings>\n      <option name=\"myGeneralSettings\" />\n      <option name=\"myRunnerSettings\" />\n"
                   "      <option name=\"myRunnerParameters\">\n        <MavenRunnerParameters>\n          <option name=\"profiles\">\n"
                   "            <set />\n          </option>\n          <option name=\"goals\">\n            <list>\n" + goals +
                   "            </list>\n          </option>\n          <option name=\"pomFileName\" />\n          <option name=\"profilesMap\">\n"
                   "            <map />\n          </option>\n          <option name=\"resolveToWorkspace\" value=\"false\" />\n"
                   "          <option name=\"workingDirPath\" value=\"$PROJECT_DIR$\" />\n        </MavenRunnerParameters>\n      </option>\n"
                   "    </MavenSettings>\n    <method v=\"2\" />\n  </configuration>\n")
    elif kind == "server":
        out.append(f'  <configuration default="false" name="{esc(name)}" type="JarApplication" folderName="Drishti">\n'
                   f'    <option name="JAR_PATH" value="{JAR}" />\n'
                   f'    <option name="VM_PARAMETERS" value="{esc(server_vm(row["profile"]))}" />\n'
                   '    <option name="PROGRAM_PARAMETERS" value="" />\n'
                   '    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />\n'
                   '    <option name="ALTERNATIVE_JRE_PATH" />\n' + envs(SERVER_ENV) + '    <method v="2" />\n  </configuration>\n')
    else:
        script = "$PROJECT_DIR$/drishti-console/run_drishti_web.py" if kind == "console" else "$PROJECT_DIR$/tools/drishti.py"
        out.append(f'  <configuration default="false" name="{esc(name)}" type="PythonConfigurationType" factoryName="Python" folderName="Drishti">\n'
                   '    <module name="drishti" />\n    <option name="ENV_FILES" value="" />\n'
                   '    <option name="INTERPRETER_OPTIONS" value="" />\n    <option name="PARENT_ENVS" value="true" />\n'
                   + envs(CONSOLE_ENV if kind == "console" else TOOL_ENV) +
                   '    <option name="SDK_HOME" value="" />\n    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />\n'
                   '    <option name="IS_MODULE_SDK" value="false" />\n    <option name="ADD_CONTENT_ROOTS" value="true" />\n'
                   '    <option name="ADD_SOURCE_ROOTS" value="true" />\n'
                   f'    <option name="SCRIPT_NAME" value="{script}" />\n'
                   f'    <option name="PARAMETERS" value="{esc(python_params(row))}" />\n'
                   '    <option name="SHOW_COMMAND_LINE" value="false" />\n    <option name="EMULATE_TERMINAL" value="false" />\n'
                   '    <option name="MODULE_MODE" value="false" />\n    <option name="REDIRECT_INPUT" value="false" />\n'
                   '    <option name="INPUT_FILE" value="" />\n    <method v="2" />\n  </configuration>\n')
    out.append("</component>\n")
    return "".join(out)


def workspace(doc: dict) -> str:
    """The VS Code workspace: a task for every launcher (Maven through mvnw, the Python ones through the interpreter, a server through
    drishti.py start server) and a debugpy launch of the same name for every Python one; JDK 21 (LAUNCHER_STANDARD.md section 4)."""
    import json
    tasks, launches = [], []
    for ide in FOLDERS:
        for r in doc.get(ide) or []:
            name, kind = r["name"], r["kind"]
            if kind == "maven":
                tasks.append({"label": name, "type": "shell", "command": " ".join(["./mvnw", *r["goals"].split()]),
                              "windows": {"command": " ".join(["mvnw.cmd", *r["goals"].split()])}, "options": {"cwd": "${workspaceFolder}"},
                              "group": "test" if name.endswith(": test") else "build", "problemMatcher": []})
                continue
            if kind == "server":
                args = ["tools/drishti.py", "start", "server", "--profile", r["profile"]]
            elif kind == "console":
                args = ["drishti-console/run_drishti_web.py", "--settings", "~/drishti-envs/dev.ini"] + (["--profile", r["profile"]] if r.get("profile") else []) + list(r.get("args") or [])
            else:
                args = ["tools/drishti.py", "--settings", "~/drishti-envs/dev.ini"] + (["--profile", r["profile"]] if r.get("profile") else []) + r["command"].split()
            tasks.append({"label": name, "type": "shell", "command": "python3 " + " ".join(args), "windows": {"command": "python " + " ".join(args)},
                          "options": {"cwd": "${workspaceFolder}"}, "problemMatcher": []})
            if kind != "server":
                launches.append({"name": name, "type": "debugpy", "request": "launch", "cwd": "${workspaceFolder}", "console": "integratedTerminal",
                                 "program": "${workspaceFolder}/" + args[0], "args": args[1:]})
    head = ("// Project Drishti. Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.\n"
            "//\n// Drishti in VS Code: File, Open Workspace from File. The tasks and launch configurations carry the SAME names as the JetBrains run\n"
            "// configurations in run-configs/ (the launcher standard: docs/plans/LAUNCHER_STANDARD.md). Generated from tools/launch/launchers.yaml by\n"
            "// python tools/drishti.py launchers: never edit this file, edit the table. JDK 21: change the Java path below.\n"
            "// Build and run in the IDE: README.md#build-and-run-in-the-ide.\n")
    body = {"folders": [{"name": "drishti", "path": "."}],
            "extensions": {"recommendations": ["ms-python.python", "ms-python.debugpy", "vscjava.vscode-java-pack", "redhat.vscode-yaml"]},
            "settings": {"python.defaultInterpreterPath": "python3", "java.configuration.runtimes": [{"name": "JavaSE-21", "path": "/usr/lib/jvm/java-21-openjdk-amd64", "default": True}],
                         "files.exclude": {"**/__pycache__": True}},
            "tasks": {"version": "2.0.0", "tasks": tasks}, "launch": {"version": "0.2.0", "configurations": launches}}
    return head + json.dumps(body, indent=2) + "\n"


def guide_section(doc: dict) -> str:
    """The tables of IDE_GUIDE section 4, from the table."""
    def rows(ide, kinds):
        return [r for r in doc.get(ide) or [] if r["kind"] in kinds]

    def runs(r):
        if r["kind"] == "maven":
            return f"Maven `{r['goals']}`"
        if r["kind"] == "server":
            return f"the jar, profile `{r['profile']}.ini`"
        p = f"profile `{r['profile']}.ini`" if r.get("profile") else "`dev.ini` only"
        if r["kind"] == "console":
            return "`run_drishti_web.py`, " + p + (", `" + " ".join(r["args"]) + "`" if r.get("args") else "")
        return f"`drishti.py {r['command'].split(' --from')[0]}`, " + p
    out = [BEGIN, "", "### 4.1 Servers (IntelliJ)", "", "| Name | What it runs | What it is | Use it when |", "|---|---|---|---|"]
    out += [f"| **{r['name']}** | {runs(r)} | {r['what']} | {r['when']} |" for r in rows("intellij", ("server",))]
    out += ["", "### 4.2 Consoles (PyCharm)", "", "| Name | What it runs | What it is | Use it when |", "|---|---|---|---|"]
    out += [f"| **{r['name']}** | {runs(r)} | {r['what']} | {r['when']} |" for r in rows("pycharm", ("console",))]
    out += ["", "### 4.3 Build (IntelliJ) and tools (PyCharm)", "", "| IDE | Name | What it runs | What it is | Use it when |", "|---|---|---|---|---|"]
    out += [f"| IntelliJ | **{r['name']}** | {runs(r)} | {r['what']} | {r['when']} |" for r in rows("intellij", ("maven",))]
    out += [f"| PyCharm | **{r['name']}** | {runs(r)} | {r['what']} | {r['when']} |" for r in rows("pycharm", ("tool",))]
    out += ["", END]
    return "\n".join(out)


#: the deployment targets' JVM arguments files (committed, generated, checked): the systemd unit and the server images run java @<file>
DEPLOY = {"deploy/jvm/server-service.args": ("the systemd unit (deploy/systemd/drishti-server.service): /opt/drishti/lib/server.args", "4g"),
          "deploy/jvm/server-container.args": ("the server images (deploy/docker/server.Dockerfile, ecs/server.Dockerfile): /opt/drishti/lib/server.args", "75%")}


def deploy_args(heap: str, why: str) -> str:
    """A deployment target's arguments file: serverstart.jvm_flags for the install /opt/drishti, ZGC (the GC choice is this one line)."""
    from pathlib import PurePosixPath
    return serverstart.argfile_text(serverstart.jvm_flags(PurePosixPath("/opt/drishti/data"), heap, gc="zgc"), f"The JVM options of {why}") \
        .replace(" (setup runs it); never edit it: run that command again.", "; generated from serverstart.jvm_flags and committed: never edit it.") \
        .replace("Written by python tools/drishti.py launchers --machine", "Written by python tools/drishti.py launchers")


def expected(home: Path) -> dict:
    """{repository-relative path: the text it must hold}: every launcher file, the deployment arguments files and IDE_GUIDE.md with its
    section 4 regenerated."""
    doc = table(home)
    out = {p: deploy_args(heap, why) for p, (why, heap) in DEPLOY.items()}
    for ide, folder in FOLDERS.items():
        for row in doc.get(ide) or []:
            out[f"{folder}/{row['name'].replace(':', '')}.run.xml"] = render(home, ide, row)
    out[WORKSPACE] = workspace(doc)
    text = (home / GUIDE).read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        raise LauncherError(f"{GUIDE}: the markers of the generated section 4 are missing ({BEGIN} ... {END})")
    i, j = text.index(BEGIN), text.index(END) + len(END)
    out[str(GUIDE)] = text[:i] + guide_section(doc) + text[j:]
    return out


def check(home: Path) -> list[str]:
    """What differs from the table, each in words; [] when the committed files are what the table generates."""
    want = expected(home)
    problems = [f"{p}: differs from tools/launch/launchers.yaml (python tools/drishti.py launchers rewrites it)"
                for p, text in want.items() if not (home / p).is_file() or (home / p).read_text(encoding="utf-8") != text]
    for folder in sorted(set(FOLDERS.values())):
        for f in sorted((home / folder).glob("*.run.xml")):
            if f"{folder}/{f.name}" not in want:
                problems.append(f"{folder}/{f.name}: a launcher the table does not name (add a row to tools/launch/launchers.yaml, or delete it)")
    return problems


def write(home: Path) -> list[str]:
    """Writes every generated file that differs and deletes launcher files the table does not name; the paths changed."""
    want = expected(home)
    changed = []
    for p, text in want.items():
        f = home / p
        if not f.is_file() or f.read_text(encoding="utf-8") != text:
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(text, encoding="utf-8", newline="\n")
            changed.append(p)
    for folder in sorted(set(FOLDERS.values())):
        for f in sorted((home / folder).glob("*.run.xml")):
            if f"{folder}/{f.name}" not in want:
                f.unlink()
                changed.append(f"{folder}/{f.name} (removed)")
    return changed


def profiles_of_servers(home: Path) -> list[str]:
    return sorted({r["profile"] for r in table(home).get("intellij") or [] if r["kind"] == "server"})


def machine(home: Path, envs_dir: Path, dev_ini: Path | None) -> list[Path]:
    """This machine's arguments files, one per server profile: serverstart.jvm_flags with the data folder ``dev.ini`` and the profile
    give (the same lookup as drishti.py start server); the folders they name are made. The files written."""
    written = []
    envs_dir.mkdir(parents=True, exist_ok=True)
    for profile in profiles_of_servers(home):
        named = settingsfile.with_profiles(str(dev_ini) if dev_ini and dev_ini.is_file() else None, home, [profile])
        keys = settingsfile.read_all(named)
        roots = javacmd.launch_roots(home, None, {k: v for k, v in os.environ.items() if k != "DRISHTI_DATA_DIR"}, keys)
        data = roots["data"][0]
        heap = (keys.get(serverstart.HEAP_KEY) or "").strip() or "2g"
        flags = serverstart.jvm_flags(data, heap, "server", "server", home)
        for d in (data / "tmp" / "server", data / "dumps" / "server", data / "logs", data / "run"):
            d.mkdir(parents=True, exist_ok=True)
        f = envs_dir / f"jvm-{profile}.args"
        f.write_text(serverstart.argfile_text(flags, f"The JVM options of the IDE's server launchers on the {profile} profile, on this machine "
                                                     f"(data folder {data.as_posix()})."), encoding="utf-8", newline="\n")
        written.append(f)
    return written


def argv(xml: Path, home: Path, user_home: Path) -> tuple[list[str], dict]:
    """The command line a committed launcher runs, as the IDE builds it: (argv, the environment it adds). A JarApplication becomes
    ``java <VM options> -jar <jar> <program args>`` (``java`` is the first word: the caller's JDK); a Python configuration becomes
    ``python <script> <parameters>``. Macros: $PROJECT_DIR$ is the repository, $USER_HOME$."""
    import xml.etree.ElementTree as ET
    conf = ET.parse(xml).getroot().find("configuration")
    o = {e.get("name"): e.get("value") for e in conf.findall("option")}
    env = {e.get("name"): e.get("value") for e in conf.iter("env")}
    project = home                                                 # the one project: the repository root (every launcher folder is run-configs/)

    def expand(s: str) -> str:
        return (s or "").replace("$PROJECT_DIR$", project.as_posix()).replace("$USER_HOME$", user_home.as_posix())
    if conf.get("type") == "JarApplication":
        # the IDE's own java is the first word (a description of the line, never run from here)
        return ["java"] + [*shlex.split(expand(o.get("VM_PARAMETERS"))), "-jar", expand(o["JAR_PATH"]), *shlex.split(expand(o.get("PROGRAM_PARAMETERS")))], env
    if conf.get("type") == "PythonConfigurationType":
        return ["python", expand(o["SCRIPT_NAME"]), *shlex.split(expand(o.get("PARAMETERS")))], env
    raise LauncherError(f"{xml.name}: a {conf.get('type')} has no command line of its own")


def main(a) -> int:
    home = javacmd.launch_roots(None, None, None, javacmd.settings()[1])["home"][0]
    try:
        if a.machine:
            envs_dir = Path(a.envs_dir).expanduser().resolve() if a.envs_dir else Path.home() / "drishti-envs"
            for f in machine(home, envs_dir, envs_dir / "dev.ini"):
                procs.say(f"written {f}")
            return 0
        if a.check:
            problems = check(home)
            for p in problems:
                print(f"launchers: {p}", file=sys.stderr)
            if not problems:
                procs.say("launchers: every launcher and IDE_GUIDE section 4 is what tools/launch/launchers.yaml generates")
            return 1 if problems else 0
        for p in write(home):
            procs.say(f"written {p}")
        return 0
    except LauncherError as e:
        print(f"drishti: {e}", file=sys.stderr)
        return 2
