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

"""The settings files' format, read the one way for the console and the Python tools (tools/settingsfile.py loads this file): the same
rules as the Java ``SettingsFile`` (kernel/drishti-settingsfile). One absolute path, or several joined by commas (a later file wins);
never searched for, never relative to the working folder. One ``key=value`` per line; blank lines and lines starting with ``#`` or ``;``
are comments; a section whose name has a dot (``[lake.demo]``) is a namespace that prefixes its keys (``root=`` is ``lake.demo.root``),
a section without a dot (``[roots]``) is a label whose keys are full names. Standard library only."""
from __future__ import annotations

from pathlib import Path

PROPERTY = "drishti.settings.file"


class SettingsError(ValueError):
    """A settings file is relative, missing, unreadable or malformed; the message says which and where."""


def check(path) -> Path:
    """The file as an absolute path that exists; ``SettingsError`` otherwise."""
    p = Path(str(path))
    if not p.is_absolute():
        raise SettingsError(f"{PROPERTY} must be a full absolute path, got \"{path}\" (it is never searched for and never relative to the working folder)")
    if not p.is_file():
        raise SettingsError(f"{PROPERTY} names {p}, which is not a file")
    return p


def files(named) -> list[Path]:
    """The files named by a value of the option (one absolute path, or several joined by commas), each checked."""
    return [check(part.strip()) for part in str(named).split(",") if part.strip()]


def read(path) -> dict[str, str]:
    """One file's entries in file order (a later duplicate key wins)."""
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


def pop_option(argv: list[str]) -> tuple[list[str], str | None]:
    """``argv`` without ``--settings <path>`` / ``--settings=<path>``, and the value (None when absent; not yet checked)."""
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
    return rest, path
