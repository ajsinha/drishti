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

``--settings /absolute/path/drishti.ini`` on a tool's command line (``-Ddrishti.settings.file=`` for a JVM). The path is never discovered:
no environment search, no home folder, no working folder; a relative path is refused and a missing file is an error. Format: one ``key=value``
per line, the key a full property name (``drishti.data.dir``); blank lines and lines starting with ``#`` or ``;`` are comments; a ``[section]``
line is accepted and ignored (it does not prefix the keys). An explicit argument or ``-D`` beats the file, the file beats the defaults.
Standard library only."""
from __future__ import annotations

from pathlib import Path

PROPERTY = "drishti.settings.file"
FLAG = "-D" + PROPERTY + "="


class SettingsError(ValueError):
    """The settings file is relative, missing, unreadable or malformed; the message says which and where."""


def check(path) -> Path:
    """The file as an absolute path that exists; ``SettingsError`` otherwise."""
    p = Path(str(path))
    if not p.is_absolute():
        raise SettingsError(f"{PROPERTY} must be a full absolute path, got \"{path}\" (it is never searched for and never relative to the working folder)")
    if not p.is_file():
        raise SettingsError(f"{PROPERTY} names {p}, which is not a file")
    return p


def read(path) -> dict[str, str]:
    """The entries in file order (a later duplicate key wins)."""
    p = check(path)
    out: dict[str, str] = {}
    for n, raw in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", ";")) or (line.startswith("[") and line.endswith("]")):
            continue
        key, eq, value = line.partition("=")
        if not eq or not key.strip():
            raise SettingsError(f"{p}:{n}: expected key=value, got \"{line}\"")
        out[key.strip()] = value.strip()
    return out


def resolve(path, explicit: dict[str, str] | None = None) -> dict[str, str]:
    """The file's entries minus those the caller set explicitly (``explicit`` wins), as the Java side does with -D."""
    given = explicit or {}
    return {k: v for k, v in read(path).items() if k not in given}


def java_flag(path) -> str:
    """``-Ddrishti.settings.file=<the absolute path>``, for any java the tool launches."""
    return FLAG + str(check(path))


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
        check(path)
    return rest, path
