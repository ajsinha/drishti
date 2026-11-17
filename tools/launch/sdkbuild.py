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

"""``drishti.py build sdk``: the Module SDK folder of an install, ``<home>/sdk`` (docs/architecture/KERNEL_AND_MODULES.md section 8).

A third party builds a module from this folder alone, with no kernel source::

    <home>/sdk/repo/                     a Maven file repository: the kernel API (drishti-kernel-api and what it needs), the product's
                                         parent pom, drishti-module-parent and the archetype
    <home>/sdk/settings.xml              a Maven settings file naming that repository by its absolute path (``mvn -s``)
    <home>/sdk/drishti-module-parent/    the parent pom a module project names as its parent (relativePath), beside its repository

Maven builds the jars (``package``, never ``install``: nothing goes into ~/.m2); this file copies the jar and the flattened pom (the one
version, ``${revision}``, resolved) of each artifact into the repository's layout. The repository is rewritten whole each time."""
from __future__ import annotations

import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import procs
from procs import LaunchError, say

#: the artifacts of the SDK: (module folder under the repository root, artifactId, packaging); the pom of the product's parent comes with them
ARTIFACTS = (("", "drishti-parent", "pom"),
             ("kernel/drishti-settingsfile", "drishti-settingsfile", "jar"),
             ("kernel/drishti-api", "drishti-api", "jar"),
             ("kernel/drishti-common", "drishti-common", "jar"),
             ("kernel/drishti-kernel-api", "drishti-kernel-api", "jar"),
             ("drishti-sdk/drishti-module-parent", "drishti-module-parent", "pom"),
             ("drishti-sdk/drishti-module-archetype", "drishti-module-archetype", "jar"))
GROUP = "com.ash.drishti"
FLATTENED = "drishti-flattened-pom.xml"


def maven_projects() -> str:
    """The ``-pl`` list that builds every SDK artifact (and what they need)."""
    return ",".join(":" + a for _, a, _ in ARTIFACTS if a != "drishti-parent")


def _pom_text(folder: Path, version: str) -> str:
    """The pom to publish: the flattened one where the build made it, else the project's own with ``${revision}`` resolved."""
    flat = folder / "target" / FLATTENED
    text = (flat if flat.is_file() else folder / "pom.xml").read_text(encoding="utf-8")
    return text.replace("${revision}", version)


def _version(root: Path) -> str:
    m = re.search(r"-Drevision=(\S+)", (root / ".mvn" / "maven.config").read_text(encoding="utf-8"))
    if not m:
        raise LaunchError(f"{root}/.mvn/maven.config names no -Drevision: the SDK has no version")
    return m.group(1)


def assemble(root: Path, sdk: Path) -> list[str]:
    """Writes ``sdk`` (repo and parent folder) from the artifacts Maven built under ``root``; the files written, repository-relative."""
    version = _version(root)
    repo = sdk / "repo"
    if sdk.exists():
        shutil.rmtree(sdk)
    written = []
    for rel, artifact, packaging in ARTIFACTS:
        folder = root / rel if rel else root
        out = repo.joinpath(*GROUP.split("."), artifact, version)
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{artifact}-{version}.pom").write_text(_pom_text(folder, version), encoding="utf-8")
        written.append(f"{artifact}-{version}.pom")
        if packaging == "jar":
            jar = folder / "target" / f"{artifact}-{version}.jar"
            if not jar.is_file():
                raise LaunchError(f"Maven finished but {jar} is not there")
            shutil.copy2(jar, out / jar.name)
            written.append(jar.name)
    parent = sdk / "drishti-module-parent"
    parent.mkdir(parents=True)
    # the folder's copy keeps ${revision} (a project that names it by relativePath says ${revision} too); the property gives it the SDK's version
    raw = (root / "drishti-sdk" / "drishti-module-parent" / "pom.xml").read_text(encoding="utf-8")
    (parent / "pom.xml").write_text(raw.replace("<properties>", f"<properties>\n    <revision>{version}</revision>", 1), encoding="utf-8")
    (sdk / "settings.xml").write_text(_settings(repo), encoding="utf-8")
    written.append("settings.xml")
    return written


def _settings(repo: Path) -> str:
    """A Maven settings file naming the repository by its absolute path: ``mvn -s <home>/sdk/settings.xml ...`` finds drishti-module-parent
    (a project's parent is looked up before its own repositories are read) and the kernel API, in a project that names no path."""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!-- Written by `python tools/drishti.py build sdk`: the Module SDK's file repository, by absolute path. Use it as  mvn -s <this file> verify -->
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <profiles>
    <profile>
      <id>drishti-sdk</id>
      <activation><activeByDefault>true</activeByDefault></activation>
      <repositories>
        <repository><id>drishti-sdk</id><url>{repo.resolve().as_uri()}</url><snapshots><enabled>false</enabled></snapshots></repository>
      </repositories>
    </profile>
  </profiles>
</settings>
"""


def build_sdk(a, env: dict | None = None) -> int:
    """``drishti.py build sdk``: Maven builds the SDK's jars, then ``<home>/sdk`` is written (see the module's docstring)."""
    import os
    import envsetup
    import javacmd
    env = dict(os.environ if env is None else env)
    roots = javacmd.launch_roots(None, None, env, javacmd.settings()[1])
    home = roots["home"][0]
    sdk = home / "sdk"
    cmd = [*envsetup.maven_command(home), "-q", *(["-o"] if a.offline else []), "clean", "package", "-DskipTests", "-pl", maven_projects(), "-am"]
    say(f"building the SDK: {' '.join(cmd[-6:])}")
    if a.dry_run:
        say(f"(dry run: nothing built) then {sdk} is written")
        return 0
    java = javacmd.find_java(None, a.java_home, env)
    env["JAVA_HOME"] = javacmd.java_home_of(java)
    if subprocess.run(cmd, cwd=str(home), env=env).returncode:
        say("the build failed: see Maven's messages above")
        return 1
    files = assemble(home, sdk)
    say(f"{len(files)} file(s) in {sdk / 'repo'}; the parent pom in {sdk / 'drishti-module-parent'}")
    return 0
