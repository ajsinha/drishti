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

"""The content security policy of a page that holds a Perspective visual (docs/architecture/RUPAKA_POC.md section 12, docs/guides/REPORTS.md).

Perspective is WebAssembly and writes ``<style>`` elements into its shadow roots. Every BI page keeps the strict policy (``style-src 'self'``, no
WebAssembly); only a page that really holds a ``perspective-grid`` or ``perspective-pivot`` visual is given, in its own response and nowhere else:

* ``script-src 'self' 'wasm-unsafe-eval'``: the viewer compiles its WebAssembly on the main thread. Never ``'unsafe-eval'``; no inline script;
* ``style-src 'self'`` plus the SHA-256 of each ``<style>`` text the viewer and the datagrid write: the hashes below, not ``'unsafe-inline'``;
* the engine runs in a worker that is a script of this origin (``worker-src 'self'``), never a blob, and the worker's own file is served with the narrow
  worker policy of ``core/app.py`` (``WORKER_CSP``).

The hashes belong to one Perspective version (``PERSPECTIVE_VERSION``). A new version may write other styles: ``tests/test_bi_live_browser.py`` loads a
grid and a pivot under this exact policy and fails, naming the missing hashes, when the page logs a refused style; ``tools/perspective-style-hashes.py``
prints the list for the vendored version, so updating the library is: vendor it, run the tool, paste the list here."""
from __future__ import annotations

PERSPECTIVE_VERSION = "3.8.0"
PERSPECTIVE_BASE = f"/static/vendor/perspective/{PERSPECTIVE_VERSION}"
PERSPECTIVE_WORKERS = (f"{PERSPECTIVE_BASE}/perspective/cdn/perspective-server.worker.js",)
VISUAL_TYPES = ("perspective-grid", "perspective-pivot")

# the four <style> texts the viewer and the datagrid write for a grid and a pivot (settings panel closed) on the report page, measured on the real page by
# tools/perspective-style-hashes.py (the phase 0 page, which also loads the chart plugin, needs two more: core/app.py POC_EXTRA_HASHES)
STYLE_HASHES = ("sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", "sha256-5ZnN0JqHgS1XbqCOd7qSJmy89TJJwbmeYjC8fPeSuwc=",
                "sha256-TlH50xiDI4R2hMVrktT2rsnuNnNdfvL/RTinBMjapl0=", "sha256-rjC4ta+DkxJjU72hK9nIKZxg1UN00ALy4b/FYtZ7KHc=")


def policy(strict: str, hashes: tuple[str, ...] = STYLE_HASHES) -> str:
    """The strict policy with WebAssembly and the style hashes added: nothing else changes."""
    return (strict.replace("script-src 'self'", "script-src 'self' 'wasm-unsafe-eval'")
            .replace("style-src 'self'", "style-src 'self' " + " ".join(f"'{h}'" for h in hashes)))


def uses_perspective(model: dict | None) -> bool:
    """Whether a report (``/api/v1/bi/reports/<id>``) holds a Perspective visual on any page."""
    report = (model or {}).get("report") or {}
    return any(v.get("type") in VISUAL_TYPES for p in report.get("pages") or [] for v in p.get("visuals") or [])


def board_uses_perspective(model: dict | None) -> bool:
    """Whether a board (``/api/v1/bi/boards/<id>``) has a tile that is a Perspective visual."""
    return any((t or {}).get("type") in VISUAL_TYPES for t in ((model or {}).get("tiles") or {}).values())
