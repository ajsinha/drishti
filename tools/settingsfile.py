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

"""The optional settings file, Python side: the same file and rules as the Java ``SettingsFile`` (kernel/drishti-settingsfile).

``--settings /absolute/path/drishti.ini[,/absolute/path/secrets.ini]`` on a tool's command line (``-Ddrishti.settings.file=`` for a JVM).
The paths are never discovered: no environment search, no home folder, no working folder; a relative path is refused and a missing file is
an error. Format: one ``key=value`` per line; blank lines and lines starting with ``#`` or ``;`` are comments; a section whose name has a
dot (``[lake.demo]``) is a namespace that prefixes its keys (``root=`` is ``lake.demo.root``), a section without a dot (``[roots]``) is a
label whose keys are full names. A later file beats an earlier one; an explicit argument or ``-D`` beats every file; the files beat the
defaults. Standard library only."""
from __future__ import annotations

from pathlib import Path

PROPERTY = "drishti.settings.file"
FLAG = "-D" + PROPERTY + "="


class SettingsError(ValueError):
    """The settings file is relative, missing, unreadable or malformed; the message says which and where."""


def files(named) -> list[Path]:
    """The files named by a value of the option (one absolute path, or several joined by commas), each checked."""
    return [check(part.strip()) for part in str(named).split(",") if part.strip()]


def check(path) -> Path:
    """The file as an absolute path that exists; ``SettingsError`` otherwise."""
    p = Path(str(path))
    if not p.is_absolute():
        raise SettingsError(f"{PROPERTY} must be a full absolute path, got \"{path}\" (it is never searched for and never relative to the working folder)")
    if not p.is_file():
        raise SettingsError(f"{PROPERTY} names {p}, which is not a file")
    return p


def read(path) -> dict[str, str]:
    """One file's entries in file order (a later duplicate key wins); sections as in the module text."""
    p = check(path)
    out: dict[str, str] = {}
    prefix = ""
    for n, raw in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            prefix = section + "." if "." in section else ""     # [lake.demo] is a namespace; [roots] a label
            continue
        key, eq, value = line.partition("=")
        if not eq or not key.strip():
            raise SettingsError(f"{p}:{n}: expected key=value, got \"{line}\"")
        out[prefix + key.strip()] = value.strip()
    return out


def read_all(named) -> dict[str, str]:
    """The entries of every file named (comma-separated), a later file beating an earlier one."""
    out: dict[str, str] = {}
    for f in files(named):
        out.update(read(f))
    return out


def resolve(named, explicit: dict[str, str] | None = None) -> dict[str, str]:
    """The files' entries minus those the caller set explicitly (``explicit`` wins), as the Java side does with -D."""
    given = explicit or {}
    return {k: v for k, v in read_all(named).items() if k not in given}


def java_flag(named) -> str:
    """``-Ddrishti.settings.file=<the absolute paths>``, for any java the tool launches."""
    return FLAG + ",".join(str(f) for f in files(named))


#: the launch roots a Python tool reads from its environment (DRISHTI_HOME, DRISHTI_DATA_DIR, DRISHTI_MODULES_HOME)
ROOTS = ("drishti.home", "drishti.data.dir", "drishti.modules.home")


def env_name(key: str) -> str:
    """The environment variable of a key, as the Java side names it (``drishti.data.dir`` is ``DRISHTI_DATA_DIR``)."""
    return key.upper().replace(".", "_").replace("-", "_")


def apply_roots(entries: dict[str, str], environ=None) -> list[str]:
    """Puts the launch roots the files name into the environment the Python tools read, unless already set there (an explicit environment
    variable beats the file, as a -D does on the Java side); the variables set, in order."""
    import os
    env = os.environ if environ is None else environ
    out = []
    for key in ROOTS:
        value = (entries.get(key) or "").strip()
        if value and not (env.get(env_name(key)) or "").strip():
            env[env_name(key)] = value
            out.append(env_name(key))
    return out


def pop_option(argv: list[str]) -> tuple[list[str], str | None]:
    """``argv`` without ``--settings <path>`` / ``--settings=<path>`` and the path (None when absent); the path is validated."""
    rest: list[str] = []
    path = None
    it = iter(argv)
    for a in it:
        if a == "--settings":
            path = next(it, None)
            if path is None:
                raise SettingsError("--settings needs the absolute path of the settings file")
        elif a.startswith("--settings="):
            path = a.split("=", 1)[1]
        else:
            rest.append(a)
    if path is not None:
        files(path)
    return rest, path
