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
    <home>/sdk/repo/com/ash/{rachana,lake,tools}/   the pinned Drishti products (drishti-rachana, drishti-lake, drishti-tools): every jar and pom of
                                         the version the product's pom pins, copied from the local Maven repository (the owner installs a
                                         released product there from its tag) - one rule for all three, never a build of them here
    <home>/sdk/settings.xml              a Maven settings file naming that repository by its absolute path (``mvn -s``)
    <home>/sdk/drishti-module-parent/    the parent pom a module project names as its parent (relativePath), beside its repository
    <home>/sdk/kernel-libraries.txt      the libraries the kernel ships (META-INF/drishti/kernel-libraries.txt of the server jar in <home>/lib); the
                                         parent pom manages every one of them at the kernel's version, scope ``provided``
    <home>/sdk/typings/                  the Drishti Elements and renderer ``.d.ts`` files (the newest vendored release)
    <home>/sdk/schemas/                  the JSON schemas the product ships (``contract.schema.json``)

Maven builds the jars (``package``, never ``install``: nothing goes into ~/.m2); this file copies the jar and the flattened pom (the one
version, ``${revision}``, resolved) of each artifact into the repository's layout. The repository is rewritten whole each time."""
from __future__ import annotations

import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import procs
from procs import LaunchError, say

#: the artifacts of the SDK: (module folder under the repository root, artifactId, packaging); the pom of the product's parent comes with them
ARTIFACTS = (("", "drishti-parent", "pom"),
             ("kernel/drishti-api", "drishti-api", "jar"),
             ("kernel/drishti-common", "drishti-common", "jar"),
             ("kernel/drishti-kernel-api", "drishti-kernel-api", "jar"),
             ("testing/drishti-testkit", "drishti-testkit", "jar"),
             ("drishti-sdk/drishti-module-parent", "drishti-module-parent", "pom"),
             ("drishti-sdk/drishti-module-archetype", "drishti-module-archetype", "jar"))
GROUP = "com.ash.drishti"
FLATTENED = "drishti-flattened-pom.xml"
#: where the generated list of the kernel's libraries is in the server jar, and where it goes in the SDK
KERNEL_LIBRARIES = "META-INF/drishti/kernel-libraries.txt"
#: the marker in drishti-module-parent's dependencyManagement where the kernel's libraries are written
MANAGED_MARKER = "<!-- drishti-kernel-libraries -->"
#: the typings and schemas of the repository that go into the SDK: (source folder or file, SDK folder)
TYPINGS_ELEMENTS = "drishti-console/web/static/vendor/drishti-elements"
TYPINGS_RENDER = "drishti-console/web/static/render"
#: the pinned products carried in repo/: (the property of the root pom that pins the version, the Maven group path; every artifact of the group)
PINNED_PRODUCTS = (("drishti.rachana.version", "com.ash.rachana"),
                   ("drishti.lake.version", "com.ash.lake"),
                   ("drishti.tools.version", "com.ash.tools"))
#: the files of a version folder that are not part of the repository (Maven's bookkeeping)
_SKIP = re.compile(r"(_remote\.repositories|maven-metadata.*\.xml.*|.*\.lastUpdated|resolver-status\.properties|.*-(sources|javadoc|tests)\.jar.*)$")
SCHEMAS = (("tools/contracts.schema.json", "contract.schema.json"),)


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


def local_maven_repository(env: dict | None = None) -> Path:
    """The local Maven repository the pinned products are taken from: ``DRISHTI_LOCAL_MAVEN_REPO``, else the ``-Dmaven.repo.local`` in
    ``MAVEN_ARGS``, else ``~/.m2/repository``."""
    import os
    env = os.environ if env is None else env
    if env.get("DRISHTI_LOCAL_MAVEN_REPO"):
        return Path(env["DRISHTI_LOCAL_MAVEN_REPO"]).expanduser()
    m = re.search(r"-Dmaven\.repo\.local=(\S+)", env.get("MAVEN_ARGS", ""))
    return Path(m.group(1)).expanduser() if m else Path.home() / ".m2" / "repository"


def pinned_versions(root: Path) -> dict[str, str]:
    """The group path to the version the root pom pins, for each of the pinned products."""
    text = (root / "pom.xml").read_text(encoding="utf-8")
    out = {}
    for prop, group in PINNED_PRODUCTS:
        m = re.search(rf"<{re.escape(prop)}>([^<]+)</{re.escape(prop)}>", text)
        if not m:
            raise LaunchError(f"{root}/pom.xml does not pin {prop}: the SDK cannot name the version of {group}")
        out[group] = m.group(1).strip()
    return out


def copy_pinned_products(root: Path, repo: Path, local: Path) -> list[str]:
    """Copies every artifact (jar and pom) of the three pinned products, at the pinned version, from the local Maven repository into the SDK's
    ``repo/`` in the same layout; the files written, repository-relative. A product not installed there is refused by name."""
    written = []
    for group, version in pinned_versions(root).items():
        base = local.joinpath(*group.split("."))
        folders = sorted(d / version for d in base.iterdir() if (d / version).is_dir()) if base.is_dir() else []
        if not folders:
            raise LaunchError(f"{group} {version} is not in the local Maven repository {local}: install the product's release there from its tag "
                              f"(or set DRISHTI_LOCAL_MAVEN_REPO) and build the SDK again")
        for folder in folders:
            for src in sorted(p for p in folder.iterdir() if p.is_file() and not _SKIP.match(p.name)):
                rel = src.relative_to(local)
                (repo / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, repo / rel)
                written.append(rel.as_posix())
    return written


def kernel_libraries(jar: Path | None) -> str | None:
    """The text of ``META-INF/drishti/kernel-libraries.txt`` of the server jar, or None when there is no jar (or it has no list)."""
    if jar is None or not jar.is_file():
        return None
    with zipfile.ZipFile(jar) as z:
        try:
            return z.read(KERNEL_LIBRARIES).decode("utf-8")
        except KeyError:
            return None


def managed_rows(text: str, already: str) -> str:
    """``dependencyManagement`` rows (scope ``provided``) for every ``group:artifact:version`` line of the kernel's list; a library the pom
    already manages is left to its own row."""
    rows = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        coords = line.split()[0].split(":")
        if len(coords) != 3:
            raise LaunchError(f"{KERNEL_LIBRARIES}: not group:artifact:version: {line}")
        group, artifact, version = coords
        if f"<artifactId>{artifact}</artifactId>" in already and f"<groupId>{group}</groupId>" in already:
            continue
        rows.append(f"      <dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version>"
                    f"<scope>provided</scope></dependency>")
    return "\n".join(sorted(set(rows)))


def manage(pom: str, text: str | None) -> str:
    """The parent pom with the kernel's libraries managed at the kernel's versions (provided): the module compiles against the very copy
    the kernel runs with. Without the list the marker stays a comment."""
    if text is None or MANAGED_MARKER not in pom:
        return pom
    rows = managed_rows(text, pom)
    return pom.replace(MANAGED_MARKER, MANAGED_MARKER + "\n      <!-- generated by `drishti.py build sdk` from kernel-libraries.txt: do not edit -->\n" + rows, 1)


def _newest(folder: Path) -> Path | None:
    versions = [d for d in folder.iterdir() if d.is_dir()] if folder.is_dir() else []
    return max(versions, key=lambda d: tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", d.name)), default=None)


def copy_typings_and_schemas(root: Path, sdk: Path) -> list[str]:
    """The Elements ``.d.ts`` files (every library of the newest vendored release) and the renderer's into ``typings/``, the product's JSON
    schemas into ``schemas/``; the files written, SDK-relative."""
    written = []
    elements = _newest(root / TYPINGS_ELEMENTS)
    for src in sorted(elements.rglob("*.d.ts")) if elements else []:
        rel = Path("typings") / "elements" / src.name
        (sdk / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, sdk / rel)
        written.append(rel.as_posix())
    render = _newest(root / TYPINGS_RENDER)
    for src in sorted(render.glob("*.d.ts")) if render else []:
        rel = Path("typings") / src.name
        (sdk / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, sdk / rel)
        written.append(rel.as_posix())
    for source, name in SCHEMAS:
        if (root / source).is_file():
            (sdk / "schemas").mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / source, sdk / "schemas" / name)
            written.append(f"schemas/{name}")
    return written


def assemble(root: Path, sdk: Path, server_jar: Path | None = None, lenient: bool = False, local_repo: Path | None = None) -> list[str]:
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
            if not jar.is_file() and lenient:
                continue                       # no Maven run here: the jars the gate's build left are all there is (the archetype's is not among them)
            if not jar.is_file():
                raise LaunchError(f"Maven finished but {jar} is not there")
            shutil.copy2(jar, out / jar.name)
            written.append(jar.name)
    written.extend(copy_pinned_products(root, repo, local_repo if local_repo is not None else local_maven_repository()))
    parent = sdk / "drishti-module-parent"
    parent.mkdir(parents=True)
    # the folder's copy keeps ${revision} (a project that names it by relativePath says ${revision} too); the property gives it the SDK's version
    raw = (root / "drishti-sdk" / "drishti-module-parent" / "pom.xml").read_text(encoding="utf-8")
    libs = kernel_libraries(server_jar)
    pom = manage(raw.replace("<properties>", f"<properties>\n    <revision>{version}</revision>", 1), libs)
    (parent / "pom.xml").write_text(pom, encoding="utf-8")
    if libs is not None:
        (sdk / "kernel-libraries.txt").write_text(libs, encoding="utf-8")
        written.append("kernel-libraries.txt")
        # the repository's own copy of the parent (a project that names it by version only finds this one) is managed the same way
        rp = repo.joinpath(*GROUP.split("."), "drishti-module-parent", version, f"drishti-module-parent-{version}.pom")
        rp.write_text(manage(rp.read_text(encoding="utf-8"), libs), encoding="utf-8")
    written.extend(copy_typings_and_schemas(root, sdk))
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
    sdk = Path(a.out).expanduser().resolve() if getattr(a, "out", None) else home / "sdk"
    cmd = [*envsetup.maven_command(home), "-q", *(["-o"] if a.offline else []), "clean", "package", "-DskipTests", "-pl", maven_projects(), "-am"]
    say(f"building the SDK: {' '.join(cmd[-6:])}")
    no_maven = getattr(a, "no_maven", False)
    if a.dry_run:
        say(f"(dry run: nothing built) then {sdk} is written")
        return 0
    if not no_maven:
        java = javacmd.find_java(None, a.java_home, env)
        env["JAVA_HOME"] = javacmd.java_home_of(java)
        if subprocess.run(cmd, cwd=str(home), env=env).returncode:
            say("the build failed: see Maven's messages above")
            return 1
    try:
        jar = javacmd.server_jar(None, home)
    except javacmd.JarError:
        jar = None
        say(f"no server jar in {home / 'lib'}: the SDK has no kernel-libraries.txt (python tools/drishti.py build jar, then build sdk again)")
    files = assemble(home, sdk, jar, lenient=no_maven)
    say(f"{len(files)} file(s) in {sdk / 'repo'}; the parent pom in {sdk / 'drishti-module-parent'}")
    return 0
