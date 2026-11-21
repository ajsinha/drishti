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
"""The pinned Drishti Elements: an external component (github.com/ajsinha/drishti-elements), vendored as its release tree under
``web/static/vendor/drishti-elements/<version>/`` (docs/architecture/ELEMENTS_IN_DRISHTI.md). The pin has one home: the name of the
single folder there. Everything the console reads of Elements (the ten libraries, their sheets, the engines, the gallery, the two
tables) is read through this module, never from a path of its own.

``static_path(rel)`` maps a path under the console's static root as the pages name it (``elements/drishti-grid/drishti_grid.css``,
``css/drishti_tokens.css``, ``vendor/echarts/echarts.min.js``) to the file: Elements' own files from the vendored tree, the console's
from its static folder."""
from __future__ import annotations

import re
from pathlib import Path

STATIC = Path(__file__).resolve().parent.parent / "web" / "static"
VENDOR = STATIC / "vendor" / "drishti-elements"

#: the ten libraries Drishti Elements holds (drishti-insight and drishti-risk are Drishti's own)
LIBRARIES = ("drishti-core", "drishti-inputs", "drishti-grid", "drishti-chart", "drishti-data", "drishti-tree", "drishti-graph", "drishti-designer", "drishti-events", "drishti-views")
#: drishti-core's sheets, in css/ of the release tree
CORE_SHEETS = ("drishti_tokens.css", "drishti_resize.css", "drishti_chips.css", "drishti_states.css", "drishti_command-palette.css", "drishti_dock.css")
#: the engines that come inside the release tree's vendor/ folder
ENGINES = ("echarts", "echarts-gl", "codemirror", "apache-arrow")

_OURS = re.compile(r"^(?:elements/(?:%s)/|css/(?:%s)$|vendor/(?:%s)/|gallery/|data/)" % (
    "|".join(map(re.escape, LIBRARIES)), "|".join(map(re.escape, CORE_SHEETS)), "|".join(map(re.escape, ENGINES))))


def _version() -> str:
    folders = sorted(p.name for p in VENDOR.iterdir() if p.is_dir()) if VENDOR.is_dir() else []
    if len(folders) != 1:
        raise RuntimeError(f"exactly one Drishti Elements folder is expected under {VENDOR}, found {folders or 'none'}")
    return folders[0]


#: the pinned version: the folder's name
VERSION = _version()
#: the release tree on disk
ROOT = VENDOR / VERSION
#: its address under the console (the template global ELEMENTS_DIR; a page adds BASE in front)
URL = f"/static/vendor/drishti-elements/{VERSION}"


def is_elements(rel: str) -> bool:
    """Whether a static path (relative to the static root, no leading slash) names a file of Drishti Elements."""
    return bool(_OURS.match(rel.lstrip("/")))


def static_path(rel: str) -> Path:
    """The file of a static path as the pages name it: Elements' from the vendored tree, the console's from its static folder."""
    rel = rel.lstrip("/")
    return ROOT / rel if is_elements(rel) else STATIC / rel


def static_url(rel: str) -> str:
    """The address of a static path: Elements' inside the vendored tree, the console's under /static/."""
    rel = rel.lstrip("/")
    return f"{URL}/{rel}" if is_elements(rel) else f"/static/{rel}"
