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


"""The one module layout (docs/architecture/KERNEL_AND_MODULES.md sections 3.2 and 8.10) on the Python side: the names a module folder's
top may hold, read from the ONE list the Java side reads too (kernel/drishti-api/.../resolution/module-layout.yaml, ModuleLayout.java).
Used by tools/test_module_layout.py and `drishti.py setup` (which reports leftovers of an older checkout, never deletes them)."""
from __future__ import annotations

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
#: the one list (a resource of the kernel's API jar)
LAYOUT = ROOT / "kernel" / "drishti-api" / "src" / "main" / "resources" / "com" / "ash" / "drishti" / "api" / "resolution" / "module-layout.yaml"


def lists(path: Path = LAYOUT) -> tuple[frozenset, frozenset]:
    """(content, build): the .dmod content names and the names that only build a module."""
    m = yaml.safe_load(path.read_text(encoding="utf-8"))
    return frozenset(m["content"]), frozenset(m["build"])


def leftovers(modules: Path, path: Path = LAYOUT) -> list[str]:
    """What under a modules folder is not part of the layout, in words: a folder without module.yaml (an old build output such as
    modules/dist) and, in a module, a name at its top the layout does not know (an old build folder such as modules/banking/banking-compute).
    Empty when there is no modules folder or no layout list (an install without the sources)."""
    if not modules.is_dir() or not path.is_file():
        return []
    content, build = lists(path)
    out = []
    for d in sorted(p for p in modules.iterdir() if p.is_dir()):
        if not (d / "module.yaml").is_file():
            out.append(f"modules/{d.name}: no module.yaml, not a module (an old build folder? delete it)")
            continue
        out += [f"modules/{d.name}/{p.name}: not part of the module layout, left out (an old build folder? delete it)"
                for p in sorted(d.iterdir()) if p.name not in content | build]
    return out
