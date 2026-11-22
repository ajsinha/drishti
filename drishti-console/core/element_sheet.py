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

"""The rewriting of the console's sheets for a shadow root, shared by the renderer's sheet (``core/render_dist.py``, ``tools/render_sheet.py``).

The sheets' ``:root``, ``html`` and ``body`` become the host element, a width-based ``@media`` rule becomes a container rule, the icon font's
``@font-face`` is left out and of Bootstrap Icons only the glyphs a caller names are kept; the fixed tokens are written into the rules that use them.
"""
from __future__ import annotations

import re
from pathlib import Path

from core.elements_pin import static_path

WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
ICON_SHEET = "vendor/bootstrap-icons/bootstrap-icons.css"
ICON_NAME = re.compile(r"(?<![\w-])bi-([a-z0-9]+(?:-[a-z0-9]+)*)")
BASE_ICON_CLASSES = {"bi"}          # the font's base rule is always kept
_LICENCE = re.compile(r"\A/\*.*?\*/\s*", re.S)


def shadow_css(text: str) -> str:
    """A console sheet for a shadow root: the page-level selectors (:root, html, body) become the host element, and the
    icon font's @font-face goes (the element adds it with FontFace)."""
    text = re.sub(r"@font-face\s*\{[^}]*\}", "", text)
    text = re.sub(r":root\[data-theme=\"([^\"]+)\"\]", r':host([data-theme="\1"])', text)
    text = re.sub(r":root:not\(\[data-theme\]\)", ":host(:not([data-theme]))", text)
    text = re.sub(r"html\[data-theme\]", ":host([data-theme])", text)
    text = re.sub(r"(?<![\w-]):root\b", ":host", text)
    text = re.sub(r"(?m)^(\s*)html,\s*body\b", r"\1:host", text)
    text = re.sub(r"(?m)^(\s*)body\.(terminal|embed)\b", r"\1:host", text)
    text = re.sub(r"(?m)^(\s*)body\b", r"\1:host", text)
    return text


CONTAINER = "drishti"
_WIDTH_MEDIA = re.compile(r"@media\s+((?:\(\s*(?:max|min)-width\s*:\s*[^)]+\)\s*(?:and\s+)?)+)\{")


def container_queries(text: str) -> str:
    """Width-based ``@media`` rules become ``@container drishti`` rules, so an embedded view answers to the width of its
    element, not of the host page's viewport. Non-width media (colour scheme, print, hover, motion, contrast) stay as they
    are. The host element is the container, and a container cannot style itself, so a ``:host`` selector inside such a
    rule moves to ``.view``; viewport units (``vw``) inside become container units (``cqw``)."""
    out, pos = [], 0
    for m in _WIDTH_MEDIA.finditer(text):
        if m.start() < pos:
            continue
        depth, i = 1, m.end()
        while depth:
            depth += (text[i] == "{") - (text[i] == "}")
            i += 1
        body = re.sub(r"(?<![\w-]):host\b(?!\()", ".view", text[m.end():i - 1])
        body = re.sub(r"(\d)vw\b", r"\1cqw", body)
        out += [text[pos:m.start()], f"@container {CONTAINER} {m.group(1).strip()} {{", body, "}"]
        pos = i
    out.append(text[pos:])
    return "".join(out)


def _compact(text: str) -> str:
    """Drops the licence block, comments and blank lines of a sheet; rules are untouched."""
    text = _LICENCE.sub("", text, count=1) if "PROPRIETARY" in text[:600] else text
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(line.rstrip() for line in text.splitlines() if line.strip()) + "\n"


def public_names(paths) -> str:
    """The sheets a generated sheet is built from, by their public names (``css/drishti_tokens.css``, as the embed routes name them, not the
    file's ``drishti_`` name): a published sheet's bytes then do not depend on how the console names its files."""
    return ", ".join(re.sub(r"(^|/)drishti_", r"\1", p) for p in paths)


def licence_header() -> str:
    first = (STATIC / "css/drishti_theme.css").read_text(encoding="utf-8")     # the console's own licence header
    return _LICENCE.match(first).group().rstrip() + "\n"


def glyph_rules() -> dict[str, str]:
    """The icon sheet's per-glyph rules by glyph name, and its base rule under the key ``""``."""
    text = (STATIC / ICON_SHEET).read_text(encoding="utf-8")
    rules = {m.group(1): m.group(0) for m in re.finditer(r"^\.bi-([a-z0-9-]+)::before \{[^}]*\}", text, re.M)}
    base = re.search(r"^\.bi::before,.*?\}", text, re.M | re.S)
    rules[""] = base.group(0)
    return rules


# The scale and fixed-colour token families of drishti_tokens.css (the theme colours are not among them: scripts read those). In a
# generated sheet each one that has a single value in every theme is written into the rules that use it, and its declaration goes
# when no rule still uses it: the sheet stays as small as it was before the tokens, and draws the same.
PRUNABLE = re.compile(r"--d-(?:fs|sp|radius|z|bp|density|elev|swatch|frtb|heat|band|scrim|cap|market|trading|on-trading|world|statement|calc|print|on-color|paper|on-band|form)(?:-[\w-]*)?")
_DECL = re.compile(r"(--d-[\w-]+)\s*:\s*([^;{}]*);[ \t]*")
_REF = re.compile(r"var\((--d-[\w-]+)\)")


def fixed_tokens() -> dict[str, str]:
    """The tokens of PRUNABLE's families declared once in drishti_tokens.css with a plain value (no var()), by name."""
    text = re.sub(r"/\*.*?\*/", "", static_path("css/drishti_tokens.css").read_text(encoding="utf-8"), flags=re.S)
    found: dict[str, list[str]] = {}
    for m in _DECL.finditer(text):
        found.setdefault(m.group(1), []).append(" ".join(m.group(2).split()))
    return {k: v[0] for k, v in found.items() if len(v) == 1 and PRUNABLE.fullmatch(k) and "var(" not in v[0]}


def inline_tokens(body: str, fixed: dict[str, str]) -> str:
    """Writes each fixed token's value where a rule uses it (declarations of tokens are left as they are)."""
    return "\n".join(line if _DECL.match(line.strip()) else _REF.sub(lambda m: fixed.get(m.group(1), m.group(0)), line) for line in body.split("\n"))


def prune_tokens(body: str, also: str = "") -> str:
    """Drops the declarations of PRUNABLE tokens that no rule of the sheet (or of ``also``, a sheet laid over it) uses, following
    tokens that use tokens."""
    values = {m.group(1): m.group(2) for m in _DECL.finditer(body)}
    used = set(_REF.findall(_DECL.sub("", body) + also))
    todo = list(used)
    while todo:
        for r in _REF.findall(values.get(todo.pop(), "")):
            if r not in used:
                used.add(r)
                todo.append(r)
    body = _DECL.sub(lambda m: m.group(0) if m.group(1) in used or not PRUNABLE.fullmatch(m.group(1)) else "", body)
    return "\n".join(line.rstrip() for line in body.splitlines() if line.strip()) + "\n"
