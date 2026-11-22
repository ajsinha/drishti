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

"""The one way Drishti's tools and tests start a JVM: ``java_cmd(java, [args...])`` puts ``-Djava.io.tmpdir=<a Drishti folder>`` first.

Since JDK 17 ``Files.createTempFile`` reads ``java.io.tmpdir`` once, at JVM start, so only the command line moves it. DuckDB (60 MB),
RocksDB and CatBoost unpack their native code that way; without the flag every JVM killed without a clean exit leaves its copy in the
system /tmp, which has a per-user quota (a full one crashes Chromium and the browser tests).

The folder: ``tmp`` when given (a test stack passes a folder under its own tmp_path), else the server's own temp base, as
``TempFiles.configuredBase`` computes it: ``DRISHTI_TMP_DIR``, else ``<DRISHTI_DATA_DIR or <Drishti folder>/data>/tmp/server``. The server removes
stale native copies from that base at start. The folder is made if absent (Java warns and the libraries fail on a missing one).
A ``-Djava.io.tmpdir=`` already in the arguments wins. Standard library only: the console tests load it through ``java_launch.py``.

The ``sutra`` command line (``cli_tmp``) is the exception: it writes nothing under the data folder, so its JVM gets ``DRISHTI_TMP_DIR``
when set, else a fresh folder in the system temp folder, removed when the command ends.

tests/test_java_launch_guard.py (drishti-console) fails when a file under drishti-console/tests or tools starts java without it."""
from __future__ import annotations

import contextlib
import os
import re
import shutil
import tempfile
from pathlib import Path

FLAG = "-Djava.io.tmpdir="

# The optional settings file (tools/settingsfile.py, --settings): when a tool was given one, every JVM it starts gets
# -Ddrishti.settings.file=<the same absolute path>, unless the arguments already carry one.
SETTINGS_FLAG = "-Ddrishti.settings.file="
_SETTINGS: list = []
#: the keys the settings files set (tools/settingsfile.py ``read_all``): a launch root they name is not added as a -D, which would beat them
_SETTINGS_KEYS: dict = {}


def set_settings(path, keys=None) -> None:
    """Remembers the settings files (``path``: one absolute path or several joined by commas, already checked) and the keys they set, for
    every ``java_cmd`` of this process; None clears them."""
    _SETTINGS[:] = [str(path)] if path else []
    _SETTINGS_KEYS.clear()
    if path and keys:
        _SETTINGS_KEYS.update(keys)


def settings() -> tuple:
    """(the settings files given to this process, as one comma-joined string or "", the keys they set): what ``drishti.py --settings``
    named, for a launcher that passes them on (``drishti.py start``)."""
    return (_SETTINGS[0] if _SETTINGS else ""), dict(_SETTINGS_KEYS)


def server_tmp(cwd=None, env=None) -> Path:
    """The server's temp base for a server started in ``cwd`` with ``env``: DRISHTI_TMP_DIR, else <data dir>/tmp/server."""
    env = os.environ if env is None else env
    base = Path(cwd) if cwd else REPO
    given = (env.get("DRISHTI_TMP_DIR") or "").strip()
    if given:
        p = Path(given)
    else:
        p = Path((env.get("DRISHTI_DATA_DIR") or "").strip() or _SETTINGS_KEYS.get("drishti.data.dir", "").strip() or "data") / "tmp" / "server"
    return (p if p.is_absolute() else base / p).resolve()


def tmp_flag(tmp=None, cwd=None, env=None) -> str:
    """``-Djava.io.tmpdir=<folder>``, the folder made."""
    folder = Path(tmp) if tmp else server_tmp(cwd, env)
    folder.mkdir(parents=True, exist_ok=True)
    return FLAG + str(folder)


# The JDK caches a temporary direct buffer per thread for every channel read into a heap buffer, as large as the read, unless capped:
# a server or loader with many threads then exhausts -XX:MaxDirectMemorySize (OPERATIONS_GUIDE.md, Direct memory). 256 KB.
CACHE_FLAG = "-Djdk.nio.maxCachedBufferSize="
CACHE_LIMIT = CACHE_FLAG + "262144"


# 2.9.7 (CLUSTER_GUIDE.md 5.4, D5): compact object headers (about 10% less heap) whenever the JVM is 25 or later. A product flag
# there (JEP 519); Java 21 refuses to start with it, and 24 is not supported, so it is added only when the version says 25+.
COMPACT = "-XX:+UseCompactObjectHeaders"
_VERSIONS: dict = {}


def java_version(java) -> int:
    """The feature version of a ``java`` binary (21, 25, ...), asked once per binary; 21 when it cannot say."""
    key = str(java)
    if key not in _VERSIONS:
        import subprocess
        try:
            out = subprocess.run([key, "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=30).stderr
            line = next((x for x in out.splitlines() if "java.specification.version" in x), "")
            _VERSIONS[key] = int(line.split("=")[1].strip().split(".")[0]) if line else 21
        except (OSError, ValueError, IndexError, subprocess.SubprocessError):
            _VERSIONS[key] = 21
    return _VERSIONS[key]


def version_flags(java, args=()) -> list:
    """The flags this JVM's version allows that Drishti wants: compact object headers on 25 and later (none on 21)."""
    return [COMPACT] if java_version(java) >= 25 and not any(str(a).startswith("-XX:+UseCompactObjectHeaders") or
                                                              str(a).startswith("-XX:-UseCompactObjectHeaders") for a in args) else []


#: the repository this file is in: its modules/ are the modules a server started from here serves
REPO = Path(__file__).resolve().parent.parent


# ---- finding Java: ONE order for every tool (drishti.py start, setup, build, lake demo, lake build, the loaders, sutra, doctor) ----------
#: the oldest Java Drishti runs on
MIN_JAVA = 21
#: the settings-file key naming the JDK the Python tools run Java from (the server in an IDE uses the project's JDK)
JAVA_HOME_KEY = "drishti.java.home"


class JavaError(RuntimeError):
    """No usable Java: said in words (which place was looked at, what it holds, and what to do)."""


def java_binary(home) -> Path:
    """``<home>/bin/java`` (``java.exe`` on Windows)."""
    return Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")


def locate_java(explicit=None, java_home=None, env=None) -> tuple:
    """(the java to run, where it came from), in the one order: ``--java FILE`` (a binary; a JDK folder is taken as its home), ``--java-home
    DIR``, ``drishti.java.home`` of the settings file, ``DRISHTI_JAVA_HOME``, ``JAVA_HOME``, then ``java`` on the PATH; (None, why) when there is none. A place that is named but holds
    no java is refused in words (never silently skipped for the next one)."""
    env = os.environ if env is None else env
    if explicit:
        e = Path(str(explicit))
        if e.is_dir():
            return _home_java(e, "--java"), f"--java {explicit}"
        if e.is_file() or shutil.which(str(explicit)):
            return str(explicit) if e.is_file() else shutil.which(str(explicit)), f"--java {explicit}"
        raise JavaError(f"--java {explicit}: no such file: give the java binary of a JDK {MIN_JAVA} (or --java-home <the JDK folder>)")
    from_file = (_SETTINGS_KEYS.get(JAVA_HOME_KEY) or "").strip()
    for name, home in (("--java-home", java_home), (f"{JAVA_HOME_KEY} (settings file)", from_file), ("DRISHTI_JAVA_HOME", (env.get("DRISHTI_JAVA_HOME") or "").strip()),
                       ("JAVA_HOME", (env.get("JAVA_HOME") or "").strip())):
        if home:
            return _home_java(Path(home), name), (f"{name}: {home}" if name.endswith("(settings file)") else f"{name}={home}")
    found = shutil.which("java", path=env.get("PATH"))
    if found:
        return found, "java on the PATH"
    return None, "none of --java, --java-home, drishti.java.home, DRISHTI_JAVA_HOME, JAVA_HOME names a JDK and no java is on the PATH"


def _home_java(home: Path, name: str) -> str:
    b = java_binary(home)
    if not b.is_file():
        raise JavaError(f"{name} is {home}, which has no {b.relative_to(home) if b.is_absolute() else b}: point it at a JDK {MIN_JAVA} folder "
                        f"(the folder that holds bin{os.sep}java)")
    return str(b)


def find_java(explicit=None, java_home=None, env=None, required: bool = True):
    """The java every Drishti tool runs (:func:`locate_java`'s order), checked to be Java 21 or newer; an older one is refused in words.
    None when there is none and ``required`` is false; :class:`JavaError` otherwise."""
    java, why = locate_java(explicit, java_home, env)
    if not java:
        if not required:
            return None
        raise JavaError(f"no Java found ({why}): install a JDK {MIN_JAVA} (Temurin) and set drishti.java.home in the settings file (or DRISHTI_JAVA_HOME, JAVA_HOME) to its "
                        "folder, or pass --java-home")
    v = java_version(java)
    if v < MIN_JAVA:
        raise JavaError(f"Drishti runs on Java {MIN_JAVA} or newer; the java from {why} ({java}) is Java {v}: set drishti.java.home in the settings file (or DRISHTI_JAVA_HOME) to a "
                        f"JDK {MIN_JAVA} folder (they come before JAVA_HOME and the PATH), or pass --java-home")
    return java


def describe_java(explicit=None, java_home=None, env=None) -> dict:
    """What :func:`find_java` would pick, for ``drishti.py setup`` and ``doctor``: {java, why, version, ok, problem}."""
    try:
        java, why = locate_java(explicit, java_home, env)
    except JavaError as e:
        return {"java": None, "why": "", "version": None, "ok": False, "problem": str(e)}
    if not java:
        return {"java": None, "why": why, "version": None, "ok": False, "problem": f"no Java found ({why})"}
    v = java_version(java)
    return {"java": java, "why": why, "version": v, "ok": v >= MIN_JAVA,
            "problem": "" if v >= MIN_JAVA else f"Java {v} is older than {MIN_JAVA}"}


class HomeError(RuntimeError):
    """The install folder named is not the one the running tools come from (one release's tools against another's install)."""


def find_home(explicit=None, env=None, keys=None) -> tuple:
    """(the install folder, where it came from), the ONE install root of the Python commands, as the server's ``drishti.home``: ``--home``,
    else ``DRISHTI_HOME``, else ``drishti.home`` in the settings file, else the folder tools/drishti.py lives in. The install is read only;
    what Drishti writes goes to the data folder. A home named anywhere that is not the folder these tools come from is refused in words:
    one release's tools never drive another release's install."""
    env = os.environ if env is None else env
    keys = _SETTINGS_KEYS if keys is None else keys
    for name, value in (("--home", explicit), ("DRISHTI_HOME", (env.get("DRISHTI_HOME") or "").strip()),
                        ("drishti.home in the settings file", (keys.get("drishti.home") or "").strip())):
        if value:
            if not Path(value).expanduser().is_absolute():   # explicit paths: never taken under the working folder
                raise HomeError(f"{name} is {value}, a relative path: name the install folder as an absolute path (or unset "
                                f"{name.split(' ')[0]}; the default is the folder tools/drishti.py lives in)")
            home = Path(value).expanduser().resolve()
            if home != REPO.resolve():
                raise HomeError(f"{name} is {home}, but these tools come from {REPO}: run {home / 'tools' / 'drishti.py'} for that install, "
                                f"or unset {name.split(' ')[0]} (one release's tools never drive another release's install)")
            return home, name
    return REPO.resolve(), "the folder holding tools/drishti.py"


#: Every jar is under the install: Drishti's OWN jar(s) in <home>/lib (shipped, replaced on an upgrade) and the SITE's jars (JDBC drivers,
#: connector plugins, company libraries) in <home>/lib/ext (drishti.lib.path, DRISHTI_LIB_PATH; kept on an upgrade).
LIB = "lib"
JAR_GLOB = "drishti-server-*-exec.jar"


class JarError(RuntimeError):
    """No server jar where the install keeps it: said in words, with the command that makes one."""


def lib_dir(home=None) -> Path:
    """<home>/lib: where every tool finds Drishti's own jars (the install root from :func:`find_home`)."""
    return (Path(home) if home else find_home()[0]) / LIB


def server_jar(explicit=None, home=None) -> Path:
    """The server's exec jar: ``explicit`` (a ``--jar`` for a one-off run), else the newest ``drishti-server-*-exec.jar`` in <home>/lib, the
    one place (``drishti.py build jar`` puts it there). :class:`JarError` in words when there is none, or more than one (a stale older
    version left by an upgrade: the server never guesses which to run)."""
    if explicit:
        p = Path(str(explicit)).expanduser()
        if not p.is_file():
            raise JarError(f"--jar {explicit}: no such file")
        return p.resolve()
    lib = lib_dir(home)
    jars = sorted(lib.glob(JAR_GLOB))
    if not jars:
        raise JarError(f"no server jar in {lib}: run  python tools/drishti.py build jar  (it builds the jar and copies it there)")
    if len(jars) > 1:
        raise JarError(f"{lib} holds {len(jars)} server jars ({', '.join(j.name for j in jars)}): an upgrade replaces the old one; remove "
                       "all but the version to run (your own jars belong in lib/ext and are never touched)")
    version = _version_of(home)
    if version and jars[0].name != f"drishti-server-{version}-exec.jar":
        raise JarError(f"lib/ holds {jars[0].name} but this checkout is {version}: run  python tools/drishti.py build jar  "
                       "(it replaces the old jar; your own jars in lib/ext are never touched)")
    return jars[0]


def _version_of(home=None) -> str:
    """The version in the one version file (.mvn/maven.config) of the install, or "" when it carries none (a bare lib folder)."""
    base = Path(home) if home else find_home()[0]
    try:
        found = re.search(r"-Drevision=(\S+)", (base / ".mvn" / "maven.config").read_text(encoding="utf-8"))
    except OSError:
        return ""
    return found.group(1) if found else ""


def _home_value(value: str, home) -> str:
    """``${drishti.home}`` in a root's value (a profile's drishti.data.dir=${drishti.home}/data/qa/data) is the install folder."""
    return str(value).replace("${drishti.home}", Path(home).as_posix())


def launch_roots(home=None, data_dir=None, env=None, keys=None) -> dict:
    """Every root a launcher uses, each {name: (path, where it came from)}: ``home`` (:func:`find_home`), ``data`` (``--data-dir``, else
    ``drishti.data.dir`` in the settings file, else ``DRISHTI_DATA_DIR``, else <home>/data), ``modules`` (``DRISHTI_MODULES_HOME``, else
    ``drishti.modules.home`` in the settings file, else <home>/modules), ``config`` (<home>/config) and ``jar`` (the newest built server jar
    under <home>, or None). Nothing is made."""
    env = os.environ if env is None else env
    keys = _SETTINGS_KEYS if keys is None else keys
    h, why = find_home(home, env, keys)
    out = {"home": (h, why)}
    for name, given, key, var, default in (("data", data_dir, "drishti.data.dir", "DRISHTI_DATA_DIR", "data"),
                                           ("modules", None, "drishti.modules.home", "DRISHTI_MODULES_HOME", "modules")):
        if given:
            out[name] = (Path(given).expanduser().resolve(), f"--{name}-dir" if name == "data" else f"--{name}")
        elif name == "data" and (keys.get(key) or "").strip():
            out[name] = (Path(_home_value(keys[key].strip(), h)).expanduser().resolve(), f"{key} in the settings file")
        elif (env.get(var) or "").strip():
            out[name] = (Path(env[var].strip()).expanduser().resolve(), var)
        elif (keys.get(key) or "").strip():
            out[name] = (Path(_home_value(keys[key].strip(), h)).expanduser().resolve(), f"{key} in the settings file")
        else:
            out[name] = (h / default, f"<home>/{default}")
    out["config"] = (h / "config", "<home>/config")
    jars = sorted((h / LIB).glob(JAR_GLOB))
    out["jar"] = (jars[0] if len(jars) == 1 else None, "<home>/lib/" + JAR_GLOB if len(jars) == 1 else
                  "none in <home>/lib yet: python tools/drishti.py build jar" if not jars else f"{len(jars)} server jars in <home>/lib: keep one")
    out["ext"] = (h / LIB / "ext", "<home>/lib/ext: your jars (drishti.lib.path)")
    return out


def java_home_of(java) -> str:
    """The JDK folder of a java binary (``<home>/bin/java``): what JAVA_HOME is set to for Maven."""
    return str(Path(java).resolve().parent.parent)


def _named(key: str, envs: tuple, args: list, env) -> bool:
    """Whether a setting is given: as a -D or --flag in the arguments, or as one of its environment variables."""
    env = os.environ if env is None else env
    return any(a.startswith(f"-D{key}=") or a.startswith(f"--{key}=") for a in args) or any((env.get(e) or "").strip() for e in envs) \
        or (key in _SETTINGS_KEYS and not any(a.startswith(SETTINGS_FLAG) for a in args))


def root_flags(args: list, cwd=None, env=None) -> list:
    """The launch roots of a Drishti JVM (a server or the command line, ``-jar`` a Drishti jar or a Drishti main class), as absolute paths, each only when not
    already given (docs/admin/CONFIGURATION.md, "Launch roots"): ``drishti.home``, the folder it runs in (``cwd``; this repository when
    none); ``drishti.data.dir``, ``<home>/data``; ``drishti.modules.home``, this repository's ``modules/``. The server never takes a root
    from its process's folder, so a launcher names them."""
    jar = "-jar" in args and any(Path(a).name.startswith("drishti") and a.endswith(".jar") for a in args)
    main = any(a.startswith("com.ash.drishti.") for a in args)        # a Drishti main class on a class path (LAKE_CLASSPATH)
    if not jar and not main:
        return []
    home = (Path(cwd) if cwd else REPO).resolve()
    out = []
    if not _named("drishti.home", ("DRISHTI_HOME",), args, env):
        out.append(f"-Ddrishti.home={home}")
    if not _named("drishti.data.dir", ("DRISHTI_DATA_DIR",), args, env):
        out.append(f"-Ddrishti.data.dir={home / 'data'}")
    if not _named("lake.data.dir", ("LAKE_DATA_DIR",), args, env):       # Drishti Lake's name of the same folder (lake files: ${lake.data.dir})
        out.append(f"-Dlake.data.dir={(env.get('DRISHTI_DATA_DIR') or '').strip() or home / 'data'}")
    if not _named("drishti.modules.home", ("DRISHTI_MODULES_HOME",), args, env):
        out.append(f"-Ddrishti.modules.home={REPO / 'modules'}")
    return out


def java_cmd(java, args, tmp=None, cwd=None, env=None) -> list:
    """``[java, -Djava.io.tmpdir=<folder>, -Djdk.nio.maxCachedBufferSize=262144, <launch roots>, *args]``; ``cwd`` and ``env`` are the
    ones the JVM will run with (they decide the default folder and the launch roots: :func:`root_flags`). A flag already in the
    arguments wins. On Java 25 and later ``-XX:+UseCompactObjectHeaders`` too (2.9.7)."""
    args = [str(a) for a in args]
    cache = [] if any(a.startswith(CACHE_FLAG) for a in args) else [CACHE_LIMIT]
    compact = version_flags(java, args)
    roots = root_flags(args, cwd, env)
    settings = [SETTINGS_FLAG + _SETTINGS[0]] if _SETTINGS and not any(a.startswith(SETTINGS_FLAG) for a in args) else []
    if any(a.startswith(FLAG) for a in args):
        return [str(java), *settings, *compact, *cache, *roots, *args]
    return [str(java), tmp_flag(tmp, cwd, env), *settings, *compact, *cache, *roots, *args]


@contextlib.contextmanager
def cli_tmp(env=None):
    """The JVM temp folder of one ``sutra`` command: ``DRISHTI_TMP_DIR`` when set, else a fresh folder in the system temp folder,
    removed afterwards. Never ``<data dir>/tmp/server``: the command line writes nothing under the site's data folder."""
    env = os.environ if env is None else env
    given = (env.get("DRISHTI_TMP_DIR") or "").strip()
    if given:
        Path(given).mkdir(parents=True, exist_ok=True)
        yield Path(given)
        return
    folder = Path(tempfile.mkdtemp(prefix="drishti-sutra-"))
    try:
        yield folder
    finally:
        shutil.rmtree(folder, ignore_errors=True)
