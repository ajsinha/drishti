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

"""The optional settings file, Python side: the same file and rules as the Java ``SettingsFile`` (Drishti Tools, `tools-settings`).

``--settings /absolute/path/drishti.ini[,/absolute/path/secrets.ini]`` on a tool's command line (``-Ddrishti.settings.file=`` for a JVM).
The paths are never discovered: no environment search, no home folder, no working folder; a relative path is refused and a missing file is
an error. Format: one ``key=value`` per line; blank lines and lines starting with ``#`` or ``;`` are comments; a section whose name has a
dot (``[lake.demo]``) is a namespace that prefixes its keys (``root=`` is ``lake.demo.root``), a section without a dot (``[roots]``) is a
label whose keys are full names. A later file beats an earlier one; an explicit argument or ``-D`` beats every file; the files beat the
defaults. Standard library only."""
from __future__ import annotations

import importlib.util
from pathlib import Path

# the format itself, one reader for the console and the tools: drishti-console/core/settings_ini.py (standard library only)
_SPEC = importlib.util.spec_from_file_location("drishti_settings_ini", Path(__file__).resolve().parents[1] / "drishti-console" / "core" / "settings_ini.py")
_INI = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_INI)

PROPERTY = _INI.PROPERTY
FLAG = "-D" + PROPERTY + "="
SettingsError = _INI.SettingsError
check, files, read, read_all = _INI.check, _INI.files, _INI.read, _INI.read_all
profile_path, pop_profile, with_profiles, home_value = _INI.profile_path, _INI.pop_profile, _INI.with_profiles, _INI.home_value
server_url = _INI.server_url


def resolve(named, explicit: dict[str, str] | None = None) -> dict[str, str]:
    """The files' entries minus those the caller set explicitly (``explicit`` wins), as the Java side does with -D."""
    given = explicit or {}
    return {k: v for k, v in read_all(named).items() if k not in given}


def java_flag(named) -> str:
    """``-Ddrishti.settings.file=<the absolute paths>``, for any java the tool launches."""
    return FLAG + ",".join(str(f) for f in files(named))


#: the launch roots a Python tool reads from its environment (DRISHTI_HOME, DRISHTI_DATA_DIR, DRISHTI_MODULES_HOME, DRISHTI_LIB_PATH)
ROOTS = ("drishti.home", "drishti.data.dir", "drishti.modules.home", "drishti.lib.path")
#: every DRISHTI_* variable that names a folder (setup reports one naming a folder outside this checkout, and never uses it)
ROOT_VARS = ("DRISHTI_HOME", "DRISHTI_DATA_DIR", "DRISHTI_MODULES_HOME", "DRISHTI_LIB_PATH", "DRISHTI_TMP_DIR")
#: the environment values :func:`apply_roots` replaced with the file's (the file wins): {variable: the old value}
REPLACED: dict[str, str] = {}


def env_name(key: str) -> str:
    """The environment variable of a key, as the Java side names it (``drishti.data.dir`` is ``DRISHTI_DATA_DIR``)."""
    return key.upper().replace(".", "_").replace("-", "_")


def inside(path, root, flavour=None) -> bool:
    """Whether ``path`` is ``root`` or under it, compared as text in the platform's own way (``flavour``: ``ntpath`` or ``posixpath``,
    default this machine's ``os.path``): case and separators as Windows treats them on Windows. Nothing is resolved on the disk."""
    import os
    f = flavour or os.path

    def norm(x) -> str:
        return f.normcase(f.normpath(f.expanduser(str(x)))).rstrip("\\/") or f.sep
    p, r = norm(path), norm(root)
    return p == r or p.startswith(r + f.sep)


def foreign_vars(environ, home, flavour=None) -> list[tuple[str, str]]:
    """The ``DRISHTI_*`` folder variables (:data:`ROOT_VARS`) whose value names a folder outside ``home`` (this checkout): (name, value),
    in order. An old ``setx`` from an earlier copy: setup reports each as a WARNING and never uses it."""
    out = []
    for n in ROOT_VARS:
        v = (environ.get(n) or "").strip()
        if v and not inside(v, home, flavour):
            out.append((n, v))
    return out


def dated_folder(edir: Path, now=None) -> Path:
    """A new folder ``replaced-YYYY-MM-DD-HHMM`` in the settings folder (``-2``, ``-3`` ... when it exists; made atomically, so two runs
    in the same second never share one): where setup and ``launchers --machine`` move a file they would otherwise overwrite."""
    import datetime
    stamp = (now or datetime.datetime.now()).strftime("%Y-%m-%d-%H%M")
    n = 1
    while True:
        folder = edir / (f"replaced-{stamp}" if n == 1 else f"replaced-{stamp}-{n}")
        try:
            folder.mkdir(parents=True)
            return folder
        except FileExistsError:
            n += 1


def move_to_dated(files, edir: Path, folder: Path | None = None) -> Path:
    """Moves ``files`` into ``folder`` (a new :func:`dated_folder` of ``edir`` when None) and returns it; nothing is overwritten or
    deleted. A file another program holds open (Windows) raises :class:`MovedError` naming it, the files before it already moved."""
    import shutil
    folder = folder or dated_folder(edir)
    for f in files:
        try:
            shutil.move(str(f), str(folder / Path(f).name))
        except OSError as e:
            raise MovedError(f"{f} could not be moved to {folder} ({e.strerror or e}): close the program that holds it open and run the "
                             f"command again") from None
    return folder


class MovedError(OSError):
    """A settings-folder file could not be moved aside (open in another program): said in one line naming the file."""


def apply_roots(entries: dict[str, str], environ=None) -> list[str]:
    """Puts the launch roots the files name into the environment the Python tools read, the Java order: the settings file beats the
    environment (as ``LaunchRoots`` does: -D, then the file, then the environment), so a stale ``DRISHTI_DATA_DIR`` from an old ``setx``
    never overrides a correct dev.ini. A value replaced is kept in :data:`REPLACED`; the variables set, in order."""
    import os
    env = os.environ if environ is None else environ
    out = []
    home = (entries.get("drishti.home") or "").strip() or (env.get("DRISHTI_HOME") or "").strip() or str(Path(__file__).resolve().parents[1])
    for key in ROOTS:
        value = home_value((entries.get(key) or "").strip(), home)
        if not value:
            continue
        old = (env.get(env_name(key)) or "").strip()
        if old and old != value:
            REPLACED[env_name(key)] = old
        if old != value:
            env[env_name(key)] = value
            out.append(env_name(key))
    return out


def pop_option(argv: list[str]) -> tuple[list[str], str | None]:
    """``argv`` without ``--settings <path>`` / ``--settings=<path>`` and the path (None when absent); the paths are validated."""
    rest, path = _INI.pop_option(argv)
    if path is not None:
        files(path)
    return rest, path


def other_home(entries: dict[str, str], checkout: Path | None = None) -> str | None:
    """A WARNING when the settings file names a drishti.home other than this checkout (an old settings file from an earlier copy): the
    tools then read and write that other folder's data and modules. None when they agree or when the file names no drishti.home."""
    import os
    named = (entries.get("drishti.home") or "").strip()
    if not named:
        return None
    here = (checkout or Path(__file__).resolve().parents[1])
    a, b = Path(named).expanduser(), here
    try:
        if a.exists() and os.path.samefile(a, b):
            return None
    except OSError:
        pass
    norm = lambda p: os.path.normcase(os.path.normpath(str(p.expanduser().absolute()))).rstrip("\\/")
    if norm(a) == norm(b):
        return None
    return (f"WARNING: your settings file names drishti.home={named}, but these tools are in {here}: their data and modules come from"
            f" {named}. If that is not what you want, run python tools/drishti.py setup: it moves the old settings files to a dated folder in"
            f" drishti-envs and writes fresh ones for this checkout.")

