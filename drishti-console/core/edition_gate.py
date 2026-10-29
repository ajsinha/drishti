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

"""Route gating by edition, lens and feature switch (docs/architecture/EDITIONS.md, sections 5.3, 5.6 and 5.9): a page whose word,
feature or Admin page the edition (or the person's lens) hides, or that a console ``features.*`` switch turns off, answers the console's
404 page (or a 404 problem under /api). Called by the auth gate (core/app.py) once the person's edition is known. Without an edition and
with every switch on (the defaults) nothing is refused: the full Drishti, as before."""
from __future__ import annotations

from core.edition import ADMIN_PAGES, FEATURE_PATHS, WORD_PATHS, Edition


#: the console's own switches (features.*, and studio.enabled, read nowhere before): a page of one switched off answers 404 with or
#: without an edition
CONSOLE_SWITCHES = frozenset({"build", "forms", "datamap", "workspaces", "monitors", "alerts", "lakes", "reports", "collaboration", "studio"})


def feature_on(request_or_app, edition: Edition, name: str) -> bool:
    """A feature as the person sees it: the lens's preset (a lens presets features for that person only), else the console settings
    (where the instance's edition is a layer under the site's configuration); a feature no setting names is on."""
    if edition.lens and edition.feature(name) is not None:
        return bool(edition.feature(name))
    app = getattr(request_or_app, "app", request_or_app)
    settings = app.state.settings
    keys = app.state.editions.mapping().get(name) or []
    for key in keys:
        v = settings.get(key)
        if v is not None and str(v).lower() in ("false", "0", "no", "off"):
            return False
    return True


def _under(path: str, prefixes: tuple) -> bool:
    return any(path == p.rstrip("/") or path.startswith(p if p.endswith("/") else p + "/") or path == p for p in prefixes)


def hidden(request, edition: Edition, path: str) -> str | None:
    """Why the page is hidden for this request (a short reason), or None when it is shown."""
    for word, prefixes in WORD_PATHS.items():
        if _under(path, prefixes) and not edition.word_on(word):
            return f"{word} is not part of {edition.title}"
    for feature, prefixes in FEATURE_PATHS.items():
        if not edition.active and feature not in CONSOLE_SWITCHES:
            continue                    # without an edition a product switch (calc, bi, pyconsole) answers as it always did, not 404
        if _under(path, prefixes) and not feature_on(request, edition, feature):
            return f"this page is switched off ({feature})"
    if path.startswith("/admin/"):
        page = path.split("/")[2] if len(path.split("/")) > 2 else ""
        page = {"api": path.split("/")[3] if len(path.split("/")) > 3 else ""}.get(page, page)
        if page in ADMIN_PAGES and not edition.admin_page_shown(page):
            return f"the Admin page {page} is not part of {edition.title}"
    return None
