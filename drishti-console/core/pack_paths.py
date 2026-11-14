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

"""Where a pack's folder is: the one rule, for the console and the tools (the Java twin is ``com.ash.drishti.api.PackFolders``
in drishti-api, which the server, the loaders and the CLIs use).

A packs root (``config/packs``, an installed-packs folder, ``examples/packs``) holds packs directly (``<root>/<pack>/pack.yaml``) or one
level down in **domain folders** (``<root>/<domain>/<pack>/pack.yaml``, docs/architecture/DOMAIN_MODULES.md section 8): a domain folder
is a folder of the root with no ``pack.yaml`` of its own. The shipped packs live in their domain's folder (``modules/banking/dmod/packs/trading``),
and a pack's ``domain:`` names that folder (``tools/test_pack_layout.py``). Nothing else joins a pack's name to a packs folder by hand.

Tools import it with the console folder on ``sys.path``: ``from core.pack_paths import pack_dir``.
"""

from __future__ import annotations

from pathlib import Path

#: The repository's packs folder (``config/packs``), the default root.
REPO_PACKS = Path(__file__).resolve().parents[2] / "config" / "packs"


def roots(root: Path | str | None = None) -> list[Path]:
    """The folders packs are in under ``root``: the root itself, then each domain folder in name order."""
    r = Path(root) if root is not None else REPO_PACKS
    out = [r]
    if r.is_dir():
        out += sorted(d for d in r.iterdir() if d.is_dir() and not (d / "pack.yaml").is_file())
    return out


def _holders(root: Path | str | None = None) -> dict[str, list[Path]]:
    found: dict[str, list[Path]] = {}
    for d in roots(root):
        if d.is_dir():
            for p in sorted(d.iterdir()):
                if (p / "pack.yaml").is_file():
                    found.setdefault(p.name, []).append(p)
    return found


def _twice(name: str, at: list[Path]) -> str:
    return (f"pack '{name}' is in two folders: {at[0]} and {at[1]}; neither is read: a pack's name is unique across a packs folder "
            "and its domain folders (keep one, rename or remove the other)")


def duplicates(root: Path | str | None = None) -> dict[str, str]:
    """The pack names two folders of ``root`` hold, each with the problem naming both folders (such a name is never resolved to one)."""
    return {n: _twice(n, at) for n, at in sorted(_holders(root).items()) if len(at) > 1}


def pack_dirs(root: Path | str | None = None) -> dict[str, Path]:
    """Every pack folder under ``root`` by pack name, in name order; a name two folders hold is in neither (``duplicates`` says so)."""
    return {n: at[0] for n, at in sorted(_holders(root).items()) if len(at) == 1}


def pack_dir(name: str, root: Path | str | None = None) -> Path:
    """A pack's folder under ``root`` (default ``config/packs``); ``<root>/<name>`` (not there) when no folder holds it. Raises
    ``ValueError`` naming both folders when two folders of the root hold it."""
    at = _holders(root).get(name, [])
    if len(at) > 1:
        raise ValueError(_twice(name, at))
    for d in roots(root):
        p = d / name
        if (p / "pack.yaml").is_file():
            return p
    return (Path(root) if root is not None else REPO_PACKS) / name


def manifests(root: Path | str | None = None) -> list[Path]:
    """Every ``pack.yaml`` under ``root``, in pack-name order (what ``config/packs/*/pack.yaml`` was)."""
    return [p / "pack.yaml" for p in pack_dirs(root).values()]
