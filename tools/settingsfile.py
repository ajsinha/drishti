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
    home = (env.get("DRISHTI_HOME") or entries.get("drishti.home") or "").strip() or str(Path(__file__).resolve().parents[1])
    for key in ROOTS:
        value = home_value((entries.get(key) or "").strip(), home)
        if value and not (env.get(env_name(key)) or "").strip():
            env[env_name(key)] = value
            out.append(env_name(key))
    return out


def pop_option(argv: list[str]) -> tuple[list[str], str | None]:
    """``argv`` without ``--settings <path>`` / ``--settings=<path>`` and the path (None when absent); the paths are validated."""
    rest, path = _INI.pop_option(argv)
    if path is not None:
        files(path)
    return rest, path
