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

"""Where the ``drishti_lake`` package (Drishti Lake's Python path: the business-date rule, the Python loader, the archive guards, the
Java tools' launcher) comes from, for every tool that imports it.

Installed in the Python environment (``pip install -r drishti-console/requirements.txt``: the pinned wheel in ``lib/wheels``), it is simply
imported and this module does nothing. When it is not installed, the newest ``lib/wheels/drishti_lake-*.whl`` is put on the path (a wheel
is a zip of pure Python: Linux, macOS and Windows alike; no executable), or for development ``LAKE_PYTHON``, the product's ``python`` folder.
The path is also handed to the child processes (``PYTHONPATH``). Importing this module is the one line a tool adds before its first
``from drishti_lake import ...``."""
from __future__ import annotations

import os
import pathlib
import sys

HOME = pathlib.Path(os.environ.get("DRISHTI_HOME") or pathlib.Path(__file__).resolve().parents[2])
WHEELS = HOME / "lib" / "wheels"


def find() -> str | None:
    """The path to add for ``drishti_lake`` (``LAKE_PYTHON``, else the newest wheel in ``lib/wheels``), or None."""
    dev = os.environ.get("LAKE_PYTHON")
    if dev and (pathlib.Path(dev) / "drishti_lake").is_dir():
        return dev
    wheels = sorted(WHEELS.glob("drishti_lake-*.whl"), key=lambda p: (p.stat().st_mtime, p.name)) if WHEELS.is_dir() else []
    return str(wheels[-1]) if wheels else None


def ensure() -> None:
    """Makes ``import drishti_lake`` work, or raises ImportError saying where the wheel goes."""
    try:
        import drishti_lake  # noqa: F401
        return
    except ImportError:
        pass
    path = find()
    if path is None:
        raise ImportError("drishti_lake is not installed: pip install -r drishti-console/requirements.txt (the drishti-lake wheel is looked for in "
                          f"{WHEELS}; for development set LAKE_PYTHON to the product's python folder)")
    sys.path.insert(0, path)
    os.environ["PYTHONPATH"] = path + (os.pathsep + os.environ["PYTHONPATH"] if os.environ.get("PYTHONPATH") else "")


ensure()
