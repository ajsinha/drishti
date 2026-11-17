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

"""The product version, read from its one home: ``.mvn/maven.config`` (``-Drevision=<version>``), the file Maven reads for the
``${revision}`` in every pom. Everything else (the console's cache-busting asset version, the tools, the image tags, the release
check) reads it through here. The UI shows the SERVER's version; this one is the console build's own, diagnostic only.
The tag form of a version: ``2.9.11.i5`` is tagged ``v2.9.11.i_5``. Standard library only."""
from __future__ import annotations

import re
from pathlib import Path

CONFIG = Path(".mvn") / "maven.config"
_REVISION = re.compile(r"-Drevision=(\S+)")
_INCREMENT = re.compile(r"\.i(\d+)$")


def root(start: Path | None = None) -> Path | None:
    """The repository (or install) root: the nearest of the three folders above this file that holds the version file; None when there
    is none. Never further up: the files that ask sit at most two folders below the root (``drishti-console/core/``, ``tools/``), and a
    console copied into a folder inside another checkout (the detached console, THIN_CONSOLE_DESIGN.md S0) has no version of its own,
    so it must not read that checkout's."""
    here = (start or Path(__file__)).resolve()
    for folder in here.parents[:3]:
        if (folder / CONFIG).is_file():
            return folder
    return None


def current(start: Path | None = None, default: str = "dev") -> str:
    """The version in the version file, or ``default`` when this build carries no such file (a copy of the console alone)."""
    base = root(start)
    if base is None:
        return default
    found = _REVISION.search((base / CONFIG).read_text(encoding="utf-8"))
    return found.group(1) if found else default


def tag_of(version: str) -> str:
    """The release tag of a version: ``v`` plus the version, the increment letter's number set off by an underscore (2.9.11.i5 -> v2.9.11.i_5)."""
    return "v" + _INCREMENT.sub(lambda m: ".i_" + m.group(1), version)
