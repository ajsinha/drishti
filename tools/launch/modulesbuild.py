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

"""``drishti.py build modules`` and ``build all``: the modules beside the server jar (docs/architecture/KERNEL_AND_MODULES.md section 3.3).

The server jar holds no module. Every shipped module is a package (``<id>-<version>.dmod``) in the modules folder (``<home>/modules``, the
one place every tool and the server look), and ``first-party.yaml`` beside them names which are the product's own. This file builds them:
Maven (``packaging/dist``) packs the modules with Java through the one Python packer (tools/modulepack.py) after building their jars; the
packer packs the others here; each package goes in through a ``.part`` file and one replace, an older file of the same module is removed,
and ``first-party.yaml`` is written last. A package built for a Java module is stamped for ONE kernel build: the exec jar the server will run
(the one just built by ``build all``; the one in ``<home>/lib`` by ``build modules``).

The same file holds the freshness rule (R2): a package whose module folder changed since it was built, or that was stamped for another
kernel jar, is *stale*; ``start server``, ``build jar`` and the test sessions say so in words and name the command that mends it."""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import procs
from procs import LaunchError, say
import javacmd  # noqa: E402 - tools/ is on the path through procs
import modulepack  # noqa: E402

#: where Maven's packaging/dist leaves the packages and their first-party list
DIST = ("packaging", "dist", "target", "dmod")
PACKAGE = ".dmod"


def module_folders(modules: Path) -> list[Path]:
    """The module source folders under a modules folder (a folder with module.yaml at its top), sorted."""
    if not modules.is_dir():
        return []
    return sorted(d for d in modules.iterdir() if d.is_dir() and (d / modulepack.MANIFEST).is_file())


def packages(modules: Path) -> list[Path]:
    """The ``.dmod`` files of a modules folder, sorted."""
    return sorted(modules.glob("*" + PACKAGE)) if modules.is_dir() else []


def has_java(folder: Path) -> bool:
    """Whether the module has a ``java:`` block (it is built by Maven; the others are packed straight from their folders)."""
    return any(line.startswith("java:") for line in (folder / modulepack.MANIFEST).read_text(encoding="utf-8").splitlines())


def _package_of(folder: Path, modules: Path) -> Path | None:
    m = modulepack.read_manifest(folder)
    p = modules / f"{m['id']}-{m['version']}{PACKAGE}"
    return p if p.is_file() else None


def _kernel_build_in(dmod: Path) -> str | None:
    import zipfile
    with zipfile.ZipFile(dmod) as z:
        for line in z.read(modulepack.MANIFEST).decode("utf-8").splitlines():
            if line.startswith("kernel-build:"):
                return line.split(":", 1)[1].strip().strip("'\"")
    return None


def fresh_report(modules: Path, jar: Path | None = None) -> list[str]:
    """What is wrong with the packages of a modules folder, in words, each with the command that mends it; empty when every module folder
    has a current package. Only folders that are there are judged: an install holds packages and no sources, and is never stale."""
    out = []
    for folder in module_folders(modules):
        m = modulepack.read_manifest(folder)
        ident = f"{m['id']}-{m['version']}{PACKAGE}"
        dmod = _package_of(folder, modules)
        fix = f"python tools/drishti.py build modules {m['id']}"
        if dmod is None:
            out.append(f"modules/{folder.name} has no package ({ident}): {fix}")
            continue
        try:
            changed = modulepack.stale(folder, dmod)
        except (modulepack.PackError, OSError, KeyError) as e:
            out.append(f"modules/{folder.name}: {ident} does not compare ({e}): {fix}")
            continue
        if changed:
            more = f" and {len(changed) - 1} more" if len(changed) > 1 else ""
            out.append(f"modules/{folder.name} changed since {ident} was built ({changed[0]}{more}): {fix}")
            continue
        built = _kernel_build_in(dmod)
        if built and jar is not None:
            try:
                now = modulepack.kernel_build_of_jar(jar)
            except modulepack.PackError:
                continue
            if now != built:
                out.append(f"{ident} was built for the kernel {built} and the server jar is {now}: {fix}")
    return out


def session_refusal(root: Path) -> str | None:
    """What a test session says before it starts, or None: in a built checkout (<root>/modules holds a package) a module whose folder
    changed since its package was built (R2: a test would pass on old content). A module with no package yet is not refused here
    (the launcher says it); nothing is refused where no package was ever built."""
    modules = Path(root) / "modules"
    if not packages(modules):
        return None
    lines = [line for line in fresh_report(modules, None) if " has no package " not in line]
    return ("the modules are not current:\n  " + "\n  ".join(lines)) if lines else None


def missing_report(modules: Path) -> list[str]:
    """The same for a folder with no module sources: nothing is judged; for one with sources and no package at all, one line."""
    if module_folders(modules) and not packages(modules):
        return [f"{modules} holds module sources and no .dmod: run  python tools/drishti.py build modules"]
    return []


def _maven_env(a, env: dict, roots: dict) -> dict:
    java = javacmd.find_java(None, a.java_home, env)
    env = dict(env)
    env["JAVA_HOME"] = javacmd.java_home_of(java)
    tmp = roots["data"][0] / "tmp" / "maven"
    tmp.mkdir(parents=True, exist_ok=True)
    if "-Djava.io.tmpdir=" not in env.get("MAVEN_OPTS", ""):
        env["MAVEN_OPTS"] = f"-Djava.io.tmpdir={tmp} " + env.get("MAVEN_OPTS", "")
    return env


def _put(dmod: Path, modules: Path) -> None:
    """One package into the modules folder: through a .part file and one replace; an older file of the same module is removed."""
    stem = dmod.name.rsplit("-", 1)[0]
    for old in modules.glob(f"{stem}-*{PACKAGE}"):
        if old.name != dmod.name and old.name[len(stem) + 1:][:1].isdigit():      # banking-1.0.0, never banking-risk-1.0.0
            old.unlink()
    part = modules / (dmod.name + ".part")
    shutil.copy2(dmod, part)
    os.replace(part, modules / dmod.name)


def build_modules(a, env: dict | None = None, with_jar: bool = False) -> int:
    """``drishti.py build modules [id ...]``: Maven builds the jars and packs the modules with Java (stamped for the exec jar in <home>/lib,
    or, with ``with_jar`` (build all), for the jar that same run builds); the packer packs the rest; every package goes into <home>/modules
    and first-party.yaml is written. Named ids only: those packages (every module with Java still builds, Maven builds them as one)."""
    env = dict(os.environ if env is None else env)
    roots = javacmd.launch_roots(None, None, env, javacmd.settings()[1])
    home, modules = roots["home"][0], roots["modules"][0]
    sources = {modulepack.read_manifest(f)["id"]: f for f in module_folders(modules)}
    if not sources:
        raise LaunchError(f"{modules} holds no module sources (no folder with a module.yaml): nothing to build; an install is given its .dmod files")
    ids = list(a.ids or [])
    unknown = [i for i in ids if i not in sources]
    if unknown:
        raise LaunchError(f"no module {', '.join(unknown)} under {modules} (modules: {', '.join(sorted(sources))})", 2)
    chosen = ids or sorted(sources)
    java_ids = [i for i in chosen if has_java(sources[i])]
    jar = None
    if not with_jar:
        jar = roots["jar"][0]
        if java_ids and jar is None and not a.dry_run:
            raise LaunchError(f"no server jar in <home>/lib to stamp {', '.join(java_ids)} for: run  python tools/drishti.py build all  (or build jar first)")
    kernel_jar = None
    built_dir = home.joinpath(*DIST)
    no_maven = getattr(a, "no_maven", False)
    if no_maven:
        if not jar:
            raise LaunchError("--no-maven: no server jar in <home>/lib to take the packages' stamp from: python tools/drishti.py build all")
        kernel_jar = jar
    elif java_ids or with_jar:
        cmd = [*envsetup_maven(home), "-q", *(["-o"] if a.offline else []), "clean", "package", "-DskipTests"]
        if with_jar:
            cmd += ["-pl", ":drishti-app,:drishti-modules", "-am"]
        else:
            cmd += ["-pl", ":drishti-modules", "-am", "-Dspring-boot.repackage.skip=true", f"-Ddrishti.kernel.jar={jar or '<home>/lib/drishti-server-VERSION-exec.jar'}"]
        say(f"building the modules with Java {javacmd.java_version(javacmd.find_java(None, a.java_home, env))}: {' '.join(cmd[-8:])}")
        if a.dry_run:
            say("(dry run: nothing built) the other modules are packed from their folders: " + ", ".join(i for i in chosen if i not in java_ids))
            return 0
        if subprocess.run(cmd, cwd=str(home), env=_maven_env(a, env, roots)).returncode:
            say("the build failed: see Maven's messages above")
            return 1
        if with_jar:
            from envsetup import copy_exec_jar
            jar = copy_exec_jar(a, home, say_it=True)
            if jar is None:
                return 0 if a.no_copy else 1
        kernel_jar = jar
    elif a.dry_run:
        say("no module with Java is named: the packer packs " + ", ".join(chosen))
        return 0
    else:
        kernel_jar = jar
    build = modulepack.kernel_build_of_jar(kernel_jar) if kernel_jar else None
    modules.mkdir(parents=True, exist_ok=True)
    done = []
    for i in chosen:
        folder = sources[i]
        m = modulepack.read_manifest(folder)
        name = f"{m['id']}-{m['version']}{PACKAGE}"
        if has_java(folder):
            dmod = built_dir / name
            if not dmod.is_file():
                raise LaunchError(f"Maven finished but {built_dir} holds no {name}: packaging/dist does not pack {i}")
        else:
            dmod, notes = modulepack.pack(folder, (), built_dir, build, lib_dir=None)
            for n in notes:
                say("note: " + n)
        _put(dmod, modules)
        done.append(name)
    modulepack.first_party(modules)
    wrong = [w for w in fresh_report(modules, kernel_jar) if "was built for the kernel" in w]
    if wrong:
        raise LaunchError("the packages are not stamped for the server jar:\n  " + "\n  ".join(wrong))
    say(f"{len(done)} package(s) in {modules}: {', '.join(done)}; first-party.yaml written")
    return 0


def envsetup_maven(home: Path) -> list:
    import envsetup
    return envsetup.maven_command(home)
