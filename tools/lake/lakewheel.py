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

The wheel is the pinned one: ``lib/lake-wheel.pin`` names the Lake ref (a release tag, or the release commit until it is tagged), the exact version and the SHA-256 of the wheel built from it (how to
build it is written there). Installed in the Python environment (``pip install -r drishti-console/requirements.txt``) it is simply imported. When
it is not installed, ``lib/wheels/drishti_lake-<pinned version>-py3-none-any.whl`` is put on the path after its SHA-256 is checked (a wheel is a zip of
pure Python: Linux, macOS and Windows alike; no executable); any other wheel in the folder is never used. For development ``LAKE_PYTHON``, the
product's ``python`` folder, is used as it is. The path is also handed to the child processes (``PYTHONPATH``). Importing this module is the one
line a tool adds before its first ``from drishti_lake import ...``; ``python tools/lake/lakewheel.py`` prints what is pinned and what is found."""
from __future__ import annotations

import hashlib
import os
import pathlib
import sys

HOME = pathlib.Path(os.environ.get("DRISHTI_HOME") or pathlib.Path(__file__).resolve().parents[2])
WHEELS = HOME / "lib" / "wheels"
PIN = HOME / "lib" / "lake-wheel.pin"


def pin(path: pathlib.Path | None = None) -> dict[str, str]:
    """The pin's ``ref``, ``version`` and ``sha256``; ImportError naming the file when it is missing or incomplete."""
    path = path or PIN
    try:
        rows = [ln.split("=", 1) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.lstrip().startswith("#") and "=" in ln]
    except OSError as e:
        raise ImportError(f"the Lake wheel pin {path} cannot be read: {e}") from e
    found = {k.strip(): v.strip() for k, v in rows}
    missing = [k for k in ("ref", "version", "sha256") if not found.get(k)]
    if missing:
        raise ImportError(f"the Lake wheel pin {path} lacks {', '.join(missing)}")
    return found


def wheel_name(version: str) -> str:
    return f"drishti_lake-{version}-py3-none-any.whl"


def digest(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find() -> str | None:
    """The path to add for ``drishti_lake`` (``LAKE_PYTHON``, else the pinned wheel in ``lib/wheels``), or None when the pinned wheel is not there.
    A wheel of the pinned version whose SHA-256 differs from the pin is refused by name (ImportError)."""
    dev = os.environ.get("LAKE_PYTHON")
    if dev and (pathlib.Path(dev) / "drishti_lake").is_dir():
        return dev
    p = pin()
    wheel = WHEELS / wheel_name(p["version"])
    if not wheel.is_file():
        return None
    if digest(wheel) != p["sha256"]:
        raise ImportError(f"{wheel} is not the pinned wheel: its SHA-256 is {digest(wheel)}, {PIN} pins {p['sha256']} (Lake {p['ref']}); build it again from that ref")
    return str(wheel)


def ensure() -> None:
    """Makes ``import drishti_lake`` work, or raises ImportError saying which wheel goes where."""
    try:
        import drishti_lake  # noqa: F401
        return
    except ImportError:
        pass
    path = find()
    if path is None:
        p = pin()
        raise ImportError(f"drishti_lake is not installed and the pinned wheel {WHEELS / wheel_name(p['version'])} is not there: build it from Lake {p['ref']} "
                          f"(the commands are in {PIN}), then pip install -r drishti-console/requirements.txt; for development set LAKE_PYTHON to the product's python folder")
    sys.path.insert(0, path)
    os.environ["PYTHONPATH"] = path + (os.pathsep + os.environ["PYTHONPATH"] if os.environ.get("PYTHONPATH") else "")


def _main() -> int:
    p = pin()
    wheel = WHEELS / wheel_name(p["version"])
    state = "missing" if not wheel.is_file() else ("ok" if digest(wheel) == p["sha256"] else "WRONG SHA-256")
    print(f"pinned: Lake {p['ref']}, drishti-lake {p['version']}, sha256 {p['sha256']}\nwheel:  {wheel} ({state})")
    return 0 if state == "ok" else 1


if __name__ == "__main__":
    sys.exit(_main())
else:
    ensure()
