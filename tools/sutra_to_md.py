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

"""Converts plain-YAML Sutras (*.vN.yaml) into Markdown Sutras (*.vN.sutra.md): a readable document with the
layout in one ```sutra block. The prose is generated from the Sutra (description, match, strip, panels,
keys) as a starting point; edit it freely, the engine reads only the block.

  tools/sutra_to_md.py FILE.yaml...        write FILE.sutra.md next to each, remove the .yaml
  tools/sutra_to_md.py --keep FILE.yaml... keep the .yaml
"""
import sys
from pathlib import Path

import yaml

HEADER = Path(__file__).resolve().parent.parent / "docs" / "RACHANA_REFERENCE.md"
KINDS = {"kv": "field list", "table": "table", "tabs": "tabbed tables", "line": "line chart", "area": "area chart",
         "hbar": "bar chart", "ladder": "ladder", "links": "linked entities", "status": "status list",
         "provenance": "how the view was built", "markdown": "notes", "gauge": "gauge"}


def strip_header(text):
    lines = text.splitlines()
    i = 0
    while i < len(lines) and (lines[i].startswith("#") or not lines[i].strip()):
        i += 1
    return "\n".join(lines[i:]).rstrip() + "\n"


def cell(v):
    return str(v).replace("|", "\\|") if v is not None else ""


def prose(s):
    m = s.get("match", {}) or {}
    title = s.get("title", {}) or {}
    name = title.get("pill") or s["sutra"].replace("-", " ").capitalize()
    out = [f"# {name} (`{s['sutra']}` v{s['version']})", ""]
    if s.get("description"):
        out += [s["description"], ""]
    where = f" where `{m['where']}`" if m.get("where") else ""
    out += [f"**Applies to:** entities of kind `{m.get('kind')}`{where}, priority {m.get('priority', 0)}.", ""]
    strip = s.get("strip") or []
    if strip:
        out += ["**Strip:** " + ", ".join(x.get("label", "?") for x in strip) + ".", ""]
    return out


def panels(s):
    rows = ["## Panels", "", "| Panel | Code | Key | Shows | Area |", "|---|---|---|---|---|"]
    for p in s.get("panels") or []:
        rows.append(f"| {cell(p.get('title', p.get('id')))} | {cell(p.get('code'))} | {cell(p.get('key'))} "
                    f"| {KINDS.get(p.get('kind'), p.get('kind'))} | {cell(p.get('area', 'main'))} |")
    keys = s.get("keys") or {}
    if keys:
        rows += ["", "**Function keys:** " + ", ".join(f"{k} → `{v}`" for k, v in keys.items()) + "."]
    return rows + [""]


def convert(path, keep=False):
    text = path.read_text()
    s = yaml.safe_load(text)
    header = "\n".join(HEADER.read_text().splitlines()[:15])
    body = prose(s) + ["```sutra", strip_header(text).rstrip(), "```", ""] + panels(s)
    target = path.with_name(path.name[: -len(".yaml")] + ".sutra.md")
    target.write_text(header + "\n" + "\n".join(body))
    if not keep:
        path.unlink()
    return target


if __name__ == "__main__":
    keep = "--keep" in sys.argv
    for a in [a for a in sys.argv[1:] if a != "--keep"]:
        print(convert(Path(a), keep))
