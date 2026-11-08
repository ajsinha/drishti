#!/usr/bin/env python3
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
"""``drishti.py elements pack``: one JavaScript file and one style sheet with exactly the libraries an application names and the ones
they require, for a page that wants two files and no loader (docs/guides/EMBED_GUIDE.md, Pick libraries).

* the JavaScript: a header naming what is inside, the vendored engines the libraries need (ECharts for drishti-chart; ``--no-engines``
  when the page brings its own), then each library's bundle, deepest first. Loaded as a classic script it registers every library on
  ``Drishti.elements`` and defines the web components; ``Drishti.elements.base`` is set to ``<the file's folder>/elements/`` so what a
  library loads later (CodeMirror, the map shapes) is read from beside the file;
* the style sheet: the design tokens (every theme, or with ``--theme`` one theme on :root) and each library's component sheet;
* beside them, only when a packed library reads them at run time: ``vendor/codemirror/`` (drishti-inputs' code editor) and, with
  ``--maps``, ``vendor/echarts/maps/`` (drishti-chart's maps); and ``drishti-elements-pack.json``: what is inside, with SRI hashes.

A library licensed with a domain module (drishti-risk: banking) is refused unless ``--include-domain <module>`` is given."""
from __future__ import annotations

import json
import re
from pathlib import Path

import dist

JS_NAME, CSS_NAME, RECORD = "drishti-elements.js", "drishti-elements.css", "drishti-elements-pack.json"

LICENCE_BLOCK = re.compile(r"^/\*\n( \*[^\n]*\n)+? \*/\n")


def body(text: str) -> str:
    """A sheet or bundle without its own licence header (the packed file carries it once, at the top)."""
    return LICENCE_BLOCK.sub("", text, count=1).strip("\n") + "\n"


PRELUDE = ("(function (root) {\n"
           "  'use strict';\n"
           "  var D = root.Drishti = root.Drishti || {}, E = D.elements = D.elements || {};\n"
           "  var cs = typeof document !== 'undefined' && document.currentScript;\n"
           "  /* the packed file's folder stands for the static root: elements/ beside it, vendor/ beside it */\n"
           "  if (!E.base && cs && cs.src) { E.base = cs.src.replace(/[^/]*(\\?.*)?$/, '') + 'elements/'; }\n"
           "})(typeof window !== 'undefined' ? window : globalThis);\n")


def pack(libs: list[str], out: Path, theme: str | None = None, include_domains: set[str] = frozenset(), engines: bool = True,
         maps: bool = False, train: dist.Train | None = None) -> dict:
    """Writes the packed JavaScript and style sheet (and what they read at run time) into ``out``; returns the record of what is inside."""
    train = train or dist.Train()
    names = train.resolve(libs, set(include_domains))
    table = dist.themes()
    chosen = dist.theme_alias(theme, table) if theme else None
    inside = ", ".join(f"{n} {train.libs[n]['version']}" for n in names)
    words = (f"Drishti Elements {train.version} (Drishti {dist.product_version()}), packed by drishti.py elements pack: {inside}"
             + (f"; domains: {', '.join(sorted(include_domains))}" if include_domains else "") + ".")
    js = [dist.HEADER_CSS, f"/* {words}\n   Engines inside: " + ("none (the page brings its own)" if not engines else "{engines}") + ". */\n", PRELUDE]
    engine_names, css_parts, extra = [], [], {}
    for n in names:
        spec = train.libs[n]
        if engines:
            for s in spec.get("scripts") or []:
                if s not in engine_names:
                    engine_names.append(s)
                    js.append(f"/* ---- {s} (vendored, its licence: {Path(s).parent.as_posix()}/LICENSE) ---- */\n")
                    js.append((dist.STATIC / s).read_text(encoding="utf-8").rstrip("\n") + "\n;\n")
        for a in spec.get("assets") or []:
            if a.startswith("vendor/echarts/maps") and not maps:
                continue
            extra.update(dist.folder_files(a.rstrip("/")))          # a vendored folder carries its own LICENSE
    js[1] = js[1].replace("{engines}", ", ".join(engine_names) or "none needed")
    for n in names:
        js.append(f"/* ---- {n} {train.libs[n]['version']} ---- */\n")
        js.append(body((dist.ELEMENTS / n / dist.bundle_name(n)).read_text(encoding="utf-8")))
    tokens = dist.theme_css(chosen, table) if chosen else (dist.STATIC / dist.TOKENS).read_text(encoding="utf-8")
    css_parts.append(f"/* {words}\n   Theme: " + (f"{chosen} only, on :root" if chosen else "every theme (data-theme on <html>; the default without one)")
                     + ". Override a token after this sheet: :root { --d-accent: #0a6; } */\n")
    css_parts.append("/* ---- the design tokens ---- */\n" + body(tokens))
    seen = {dist.TOKENS}
    for n in names:
        for c in train.libs[n].get("css") or []:
            if c not in seen:
                seen.add(c)
                css_parts.append(f"/* ---- {c} ({n}) ---- */\n" + body((dist.STATIC / c).read_text(encoding="utf-8")))
    files = {JS_NAME: "".join(js).encode("utf-8"), CSS_NAME: (dist.HEADER_CSS + "".join(css_parts)).encode("utf-8")}
    files.update(extra)
    out.mkdir(parents=True, exist_ok=True)
    dist.write_tree(files, out)
    record = {"elements": train.version, "drishti": dist.product_version(), "libraries": {n: train.libs[n]["version"] for n in names},
              "requested": libs, "theme": chosen or "all", "domains": sorted(include_domains), "engines": engine_names,
              "files": dist.integrity(files)}
    (out / RECORD).write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record
