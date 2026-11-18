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

"""``drishti.py build jar|modules|all``: one Maven command each, and the freshness rule (R2) of the modules beside the server jar
(docs/architecture/KERNEL_AND_MODULES.md section 3.3).

The server jar holds no module. Every shipped module is a package (``<id>-<version>.dmod``) in the modules folder (``<home>/modules``, the
one place every tool and the server look), and ``first-party.yaml`` beside them names which are the product's own. Maven builds and installs
all of it, with no Python: drishti-app writes the exec jar and installs it into ``<home>/lib``; packaging/dist packs every module with the
one packer (``module pack`` of the server classes, Java), runs the conformance checks, and installs the packages into ``<home>/modules``
(each through a ``.part`` file and one replace, an older version of the same module removed, ``first-party.yaml`` written last). The
commands here only choose the Maven projects and properties:

    build jar       mvn clean package -DskipTests -pl :drishti-app -am
    build all       mvn clean package -DskipTests -pl :drishti-app,:drishti-modules -am      (the packages stamped for this very jar)
    build modules   mvn clean package -DskipTests -pl :drishti-modules -am -Pmodules-only    (stamped for the jar already in <home>/lib)

``--no-copy`` is ``-Ddrishti.install.skip=true`` (everything stays in target/); module ids are ``-Ddrishti.modules.ids=a,b``.

The freshness rule (R2): a package whose module folder changed since it was built, or that was stamped for another kernel jar, is *stale*;
``start server``, ``build jar`` and the test sessions say so in words and name the command that mends it. The packer answers it
(``module fresh`` of the server jar); this file only asks."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

import procs  # noqa: F401 - puts tools/ on the path
from procs import LaunchError, say
import javacmd  # noqa: E402

PACKAGE = ".dmod"
MANIFEST = "module.yaml"


def module_folders(modules: Path) -> list[Path]:
    """The module source folders under a modules folder (a folder with module.yaml at its top), sorted."""
    if not modules.is_dir():
        return []
    return sorted(d for d in modules.iterdir() if d.is_dir() and (d / MANIFEST).is_file())


def packages(modules: Path) -> list[Path]:
    """The ``.dmod`` files of a modules folder, sorted."""
    return sorted(modules.glob("*" + PACKAGE)) if modules.is_dir() else []


def module_ids(modules: Path) -> list[str]:
    """The ids of the module folders under a modules folder (``id:`` of each module.yaml), sorted."""
    import yaml
    out = []
    for f in module_folders(modules):
        m = yaml.safe_load((f / MANIFEST).read_text(encoding="utf-8")) or {}
        if m.get("id"):
            out.append(str(m["id"]))
    return sorted(out)


# ------------------------------------------------------------------------------------------------------------------ the Java packer
def module_cli(args: list, jar: Path | None = None, env: dict | None = None) -> subprocess.CompletedProcess:
    """``java -jar <server jar> module <args>`` (the one packer and verifier, ModuleCli), output captured; ``jar`` defaults to the one in
    <home>/lib. LaunchError in words when there is no Java or no jar."""
    env = dict(os.environ if env is None else env)
    try:
        java = javacmd.find_java(None, None, env)
        jar = Path(jar) if jar else javacmd.server_jar()
    except (javacmd.JavaError, javacmd.JarError, javacmd.HomeError) as e:
        raise LaunchError(str(e)) from None
    with javacmd.cli_tmp(env) as tmp:
        cmd = javacmd.java_cmd(java, ["-jar", str(jar), "module", *[str(x) for x in args]], tmp=tmp)
        return subprocess.run(cmd, capture_output=True, text=True, env=env)


def pack(folder: Path, out: Path, jars=(), kernel_build: str | None = None, jar: Path | None = None) -> Path:
    """Packs a module folder with the Java packer (``module pack``) into ``out``; returns the .dmod. LaunchError with the packer's words."""
    args = ["pack", Path(folder).resolve(), "--out", Path(out).resolve()]
    for j in jars:
        args += ["--jar", Path(j).resolve()]
    if kernel_build:
        args += ["--kernel-build", kernel_build]
    r = module_cli(args, jar)
    if r.returncode:
        raise LaunchError((r.stderr or r.stdout).strip() or f"module pack {folder} failed ({r.returncode})")
    said = [x for x in r.stdout.splitlines() if x.startswith("packed ")]
    return Path(said[-1][len("packed "):].split(" (")[0])


def fresh_report(modules: Path, jar: Path | None = None, runner: Path | None = None) -> list[str]:
    """What is wrong with the packages of a modules folder, in words, each with the command that mends it; empty when every module folder
    has a current package (``module fresh``; with ``jar``, a package stamped for another kernel build is named too). Only folders that are
    there are judged: an install holds packages and no sources, is never stale and needs no Java here. ``runner`` is the server jar that
    answers (default ``jar``, else the one in <home>/lib)."""
    if not module_folders(modules):
        return []
    args = ["fresh", Path(modules).resolve(), *(["--kernel-jar", Path(jar).resolve()] if jar and Path(jar).is_file() else [])]
    r = module_cli(args, runner or (jar if jar and Path(jar).is_file() else None))
    if r.returncode not in (0, 1) or (r.returncode == 1 and not r.stdout.strip()):
        raise LaunchError(f"module fresh {modules} failed: {(r.stderr or r.stdout).strip()}")
    return [x for x in r.stdout.splitlines() if x.strip()]


def session_refusal(root: Path) -> str | None:
    """What a test session says before it starts, or None: in a built checkout (<root>/modules holds a package and <root>/lib the server
    jar) a module whose folder changed since its package was built (R2: a test would pass on old content). A module with no package yet is
    not refused here (the launcher says it); nothing is refused where no package was ever built or no server jar is there to ask."""
    modules = Path(root) / "modules"
    if not packages(modules):
        return None
    jars = sorted((Path(root) / "lib").glob(javacmd.JAR_GLOB))
    if len(jars) != 1:
        return None
    lines = [line for line in fresh_report(modules, None, jars[0]) if " has no package " not in line]
    return ("the modules are not current:\n  " + "\n  ".join(lines)) if lines else None


def missing_report(modules: Path) -> list[str]:
    """The same for a folder with no module sources: nothing is judged; for one with sources and no package at all, one line."""
    if module_folders(modules) and not packages(modules):
        return [f"{modules} holds module sources and no .dmod: run  python tools/drishti.py build modules"]
    return []


# ------------------------------------------------------------------------------------------------------------------ build
def maven_line(a, what: str, roots: dict) -> list:
    """The one Maven command of ``build jar|modules|all`` (never install): projects, and where the jar and the packages go."""
    home, modules = roots["home"][0], roots["modules"][0]
    import envsetup
    cmd = [*envsetup.maven_command(home), "-q", *(["-o"] if a.offline else []), "clean", "package", "-DskipTests"]
    cmd += ["-pl", {"jar": ":drishti-app", "all": ":drishti-app,:drishti-modules", "modules": ":drishti-modules"}[what], "-am"]
    if what == "modules":
        cmd.append("-Pmodules-only")                        # stamped for the jar in <home>/lib; no new jar made or installed
    if getattr(a, "no_copy", False):
        cmd.append("-Ddrishti.install.skip=true")
    else:
        cmd.append(f"-Ddrishti.home.lib={javacmd.lib_dir(home)}")
        if what != "jar":
            cmd.append(f"-Ddrishti.home.modules={modules}")
    if getattr(a, "ids", None):
        cmd.append("-Ddrishti.modules.ids=" + ",".join(a.ids))
    return cmd


def holding_jar(home: Path) -> None:
    """LaunchError in one line when a server of this install runs (from an IDE, a terminal or drishti.py start): the build replaces the jar
    and the packages it runs, which Windows keeps locked (and on Linux the server would run on files the build just replaced)."""
    import procs
    servers = [p for p in procs.drishti_processes(home) if p["kind"] == "server"]
    if servers:
        names = ", ".join(f"pid {p['pid']} on port {p['port']}" for p in servers)
        raise LaunchError(f"stop the server first: {names} runs the jar and the modules this build replaces "
                          "(drishti.py stop server, or the IDE's stop button); then build again")


def build(a, what: str, env: dict | None = None) -> int:
    """``drishti.py build jar|modules|all``: the Maven command (Java from the one lookup, Maven's temp files in the data folder), then what
    the modules folder holds, in words."""
    env = dict(os.environ if env is None else env)
    roots = javacmd.launch_roots(None, None, env, javacmd.settings()[1])
    home, modules = roots["home"][0], roots["modules"][0]
    ids = list(getattr(a, "ids", None) or [])
    if ids:
        known = module_ids(modules)
        unknown = [i for i in ids if i not in known]
        if unknown:
            raise LaunchError(f"no module {', '.join(unknown)} under {modules} (modules: {', '.join(known)})", 2)
    if what == "modules" and roots["jar"][0] is None and not a.dry_run:
        raise LaunchError("no server jar in <home>/lib to stamp the modules for: run  python tools/drishti.py build all  (or build jar first)")
    java = javacmd.find_java(None, a.java_home, env)
    env["JAVA_HOME"] = javacmd.java_home_of(java)
    tmp = roots["data"][0] / "tmp" / "maven"                 # Maven's own temp files in the data folder (the install is read only)
    cmd = maven_line(a, what, roots)
    say(f"building with Java {javacmd.java_version(java)} ({java}): {' '.join(cmd[1 if cmd[0] != 'cmd' else 3:])}")
    if a.dry_run:
        say("(dry run: nothing built)")
        return 0
    if not getattr(a, "no_copy", False):
        holding_jar(home)
    tmp.mkdir(parents=True, exist_ok=True)
    if "-Djava.io.tmpdir=" not in env.get("MAVEN_OPTS", ""):
        env["MAVEN_OPTS"] = f"-Djava.io.tmpdir={tmp} " + env.get("MAVEN_OPTS", "")
    if subprocess.run(cmd, cwd=str(home), env=env).returncode:
        say("the build failed: see Maven's messages above")
        return 1
    if getattr(a, "no_copy", False):
        say("built; --no-copy: the jar stays in kernel/drishti-server/target and the packages in packaging/dist/target/dmod")
        return 0
    jar = javacmd.server_jar(None, home)
    if what == "jar":
        say(f"built {jar.name}; it is in {jar.parent}")
        problems = missing_report(modules) + fresh_report(modules, jar)
        if problems:
            say("the modules are not beside this jar (a module is a package in the modules folder, never inside the jar):")
            for p in problems:
                say("  " + p)
            say("python tools/drishti.py build modules   builds them for this jar (build all does the jar and the modules)")
        elif module_folders(modules):
            say(f"the modules in {modules} are current for this jar")
        return 0
    wrong = [w for w in fresh_report(modules, jar) if "was built for the kernel" in w]
    if wrong:
        raise LaunchError("the packages are not stamped for the server jar:\n  " + "\n  ".join(wrong))
    held = packages(modules)
    say(f"{jar.name} in {jar.parent}; {len(held)} package(s) in {modules}: {', '.join(p.name for p in held)}; first-party.yaml written")
    return 0
