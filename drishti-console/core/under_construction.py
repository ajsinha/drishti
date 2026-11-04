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

"""The under-construction registry in the console (docs/admin/UNDER_CONSTRUCTION.md; ``config/under-construction.yaml``, the file the server
reads): the menu items and help pages that depend on unbuilt work are left out (``hidden``) or shown with the "Under construction" chip
(``under-construction``). A figure on a page is the server's: it rewrites the Sutra, and the page draws its tone ``uc`` as the same chip
(``drishti_under-construction.css``). One file, one chip, no per-screen code."""
from __future__ import annotations

from pathlib import Path

import yaml
from markupsafe import Markup, escape

TEXT = "Under construction"
CHIP = Markup('<span class="drishti_uc-chip" title="Depends on work not built yet (Help → Under construction)">Under construction</span>')


class Registry:
    """The registry's menu items and help pages, by treatment."""

    def __init__(self, items: list[dict]):
        self.items = items
        self.menus: dict[str, str] = {}
        self.help: dict[str, str] = {}
        for it in items:
            t = str(it.get("treatment") or "")
            for m in it.get("menus") or []:
                self.menus[str(m)] = t
            for h in it.get("help") or []:
                self.help[str(h)] = t

    def menu_hidden(self, item_id: str) -> bool:
        return self.menus.get(item_id) == "hidden"

    def menu_label(self, item_id: str, label: str):
        """The label of a menu item, with the chip when the item is under construction."""
        return Markup(escape(label) + " " + CHIP) if self.menus.get(item_id) == "under-construction" else label

    def help_hidden(self, slug: str) -> bool:
        return self.help.get(slug) == "hidden"

    def help_marked(self, slug: str) -> bool:
        return self.help.get(slug) == "under-construction"


def load(settings, console_dir: Path) -> Registry:
    """``under_construction.file`` (default: the repository's ``config/under-construction.yaml``); none when the file is absent."""
    f = Path(str(settings.get("under_construction.file", "../config/under-construction.yaml") or "../config/under-construction.yaml"))
    path = f if f.is_absolute() else (console_dir / f).resolve()
    if not path.is_file():
        return Registry([])
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return Registry([i for i in data.get("items") or [] if isinstance(i, dict)])


def of(request_or_app) -> Registry:
    app = getattr(request_or_app, "app", request_or_app)
    reg = getattr(app.state, "under_construction", None)
    return reg if reg is not None else Registry([])
