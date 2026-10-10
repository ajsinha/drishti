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

"""Turns a drafted Sutra (from the auto-designer) plus the plan into the Sutra the pack ships: name, match, description, title id, the strip
of the most important numbers, schema labels and formats on every field, panel titles, link() on reference fields; and writes the
pack's About-this-page text (config/about.yaml) from the schema descriptions. The edit is line-oriented so the draft's layout survives."""
from __future__ import annotations

import json
import re

from .plan import FieldInfo, KindPlan, Plan, SutraPlan, humanize, norm

BIND = re.compile(r'(?P<pre>\{ label: )"(?P<label>[^"]*)"(?P<mid>, bind: )"(?P<bind>[^"]*)"(?P<post>.*)\}')
PATH_REF = re.compile(r"(?<![A-Za-z0-9_])([$@])\.([A-Za-z_][A-Za-z0-9_.]*)")


def q(s: str) -> str:
    return json.dumps(s, ensure_ascii=False)


def first_sentence(text: str, limit: int = 220) -> str:
    t = " ".join(text.split())
    m = re.match(r"(.+?[.!?])(\s|$)", t)
    s = m.group(1) if m else t
    return s if len(s) <= limit else s[:limit - 1].rstrip() + "…"


def label_with_unit(f: FieldInfo) -> str:
    if f.unit and f.unit.lower() not in f.label.lower() and f.unit not in ("%",):
        return f"{f.label} ({f.unit})"
    return f.label


def sutra_description(kp: KindPlan, sp: SutraPlan) -> str:
    base = first_sentence(kp.description) if kp.description else ""
    head = f"{kp.title} documents where {sp.label}." if sp.where else f"Default Sutra for every {kp.kind} document."
    if sp.variant is not None and sp.variant.description:
        head = f"{sp.label}: {first_sentence(sp.variant.description)}"
    text = f"{head} {base}".strip() if base and sp.where == "" else head
    return text + (f" Generated from {kp.source}." if kp.source else "")


def header(text: str, kp: KindPlan, sp: SutraPlan) -> str:
    """The draft's sutra name, description, match line and title id, set from the plan."""
    where = f'where: {json.dumps(sp.where, ensure_ascii=False)}, ' if sp.where else ""
    out = []
    for line in text.splitlines():
        if line.startswith("sutra:"):
            line = f"sutra: {sp.name}"
        elif line.startswith("description:"):
            line = f"description: {q(sutra_description(kp, sp))}"
        elif line.startswith("match:"):
            line = f"match: {{ kind: {kp.kind}, {where}priority: {sp.priority} }}"
        elif line.startswith("title:") and kp.key:
            line = re.sub(r'id: "[^"]*"', lambda _m: f'id: "$.{kp.key}"', line, count=1)
        out.append(line)
    return "\n".join(out) + "\n"


def strip_entries(kp: KindPlan, allowed: set | None = None) -> list[str]:
    out = []
    paths = [p for p in kp.strip if allowed is None or p.split(".")[0] in allowed]       # a variant's strip holds only its own fields
    for i, p in enumerate(paths):
        f = kp.index.get(p)
        if f is None:
            continue
        parts = [f"label: {q(label_with_unit(f))}", f'bind: "$.{p}"']
        if f.fmt and f.role in ("number", "date", "datetime"):
            parts.append(f"fmt: {q('date' if f.role in ('date', 'datetime') else f.fmt)}")
        if f.role == "status":
            parts.append('tone: "status"')
        elif f.fmt.startswith("signed"):
            parts.append('tone: "sign"')
        if i == (1 if kp.index.get(paths[0]) is not None and kp.index[paths[0]].role == "status" else 0) and f.role == "number":
            parts.append("emphasis: true")
        out.append("  - { " + ", ".join(parts) + " }")
    return out


def body(text: str, kp: KindPlan, allowed: set | None = None) -> tuple[str, set]:
    """Labels, formats, panel titles, strip and link() applied to the draft; also the set of field paths the Sutra shows."""
    links = {l.field: l for l in kp.links if not l.many}
    top = {norm(f.path): f for f in kp.fields if "." not in f.path and "[" not in f.path}
    hidden = {f.path for f in kp.fields if f.hidden}
    lines = text.splitlines()
    out, shown, i = [], set(), 0
    rows = ""
    while i < len(lines):
        line = lines[i]
        if line.startswith("strip:") and kp.strip:
            entries = strip_entries(kp, allowed)
            if entries:
                out.append(line)
                out += entries
                for p in kp.strip:
                    if allowed is None or p.split(".")[0] in allowed:
                        shown.add(p)
                i += 1
                while i < len(lines) and (lines[i].startswith(" ") or lines[i].startswith("-")):
                    i += 1
                continue
        m = re.match(r"\s+- id: (\S+)", line)
        if m:
            rows = ""
        mr = re.match(r'\s+rows: "\$\.([^"]+)"', line)
        if mr:
            p = mr.group(1)
            f = kp.index.get(p)
            rows = p + "[]" if f is not None and f.role == "table" else p
        mt = re.match(r'(\s+)title: "([^"]*)"(.*)$', line)
        if mt and out and re.match(r"\s+kind: ", out[-1]) is not None and len(out) >= 2:
            pid = re.match(r"\s+- id: (\S+)", out[-2])
            f = top.get(norm(pid.group(1))) if pid else None
            if f is not None and f.label != mt.group(2):
                line = f'{mt.group(1)}title: {q(f.label)}{mt.group(3)}'
        mb = BIND.search(line)
        if mb:
            bind = mb.group("bind")
            path = None
            if bind.startswith("$."):
                path = bind[2:]
            elif bind.startswith("@.") and rows:
                path = f"{rows}.{bind[2:]}"
            f = kp.index.get(path) if path else None
            if f is not None and f.path in hidden:
                i += 1
                continue
            if f is not None:
                label = q(label_with_unit(f))
                line = line.replace(f'label: "{mb.group("label")}"', f"label: {label}", 1)
                if f.firm and f.fmt:
                    line = re.sub(r'fmt: "[^"]*"', f'fmt: "{f.fmt}"', line, count=1) if 'fmt: "' in line else \
                        line.replace(f', bind: "{bind}"', f', bind: "{bind}", fmt: "{f.fmt}"', 1)
                if bind.startswith("$.") and path in links and f.role == "link":
                    line = line.replace(f'bind: "{bind}"', f'bind: "link({bind}, \'{links[path].to}\')"', 1)
        for rm in PATH_REF.finditer(line):
            p = rm.group(2) if rm.group(1) == "$" else (f"{rows}.{rm.group(2)}" if rows else None)
            if p:
                shown.add(p)
        out.append(line)
        i += 1
    return "\n".join(out) + "\n", shown


def finalize(text: str, kp: KindPlan, sp: SutraPlan) -> tuple[str, set]:
    allowed = None
    if sp.variant is not None and kp.node is not None:
        allowed = set(kp.node.base_props) | set(sp.variant.props)
    return body(header(text, kp, sp), kp, allowed)


# ----------------------------------------------------------------------------------------------- About this page

def esc_template(s: str) -> str:
    return s.replace("${", "$ {")


def about_yaml(plan: Plan, shown: dict | None = None) -> str:
    """config/about.yaml: a sentence per kind and a glossary entry per shown field, from the schemas' titles and descriptions."""
    import yaml
    doc: dict = {"about": 1, "kinds": {}}
    for kp in plan.kinds:
        key = f"${{$.{kp.key}}}" if kp.key else ""
        if kp.description:
            sentence = (f"{key}: " if key else "") + esc_template(" ".join(kp.description.split()))
            if kp.links:
                sentence += " It refers to " + ", ".join(f"{l.to} (by {l.field})" for l in kp.links[:4]) + "."
        else:
            sentence = f"TODO: one sentence about a {kp.kind}" + (f", e.g. {key}" if key else "") + "."
        paths = sorted((shown or {}).get(kp.kind) or [f.path for f in kp.fields if "[" not in f.path and f.role not in ("object", "table")],
                       key=lambda p: [f.path for f in kp.fields].index(p) if p in kp.index else 10_000)
        gl: dict = {}
        for p in paths:
            f = kp.index.get(p)
            if f is None:                                 # shown, but the schema does not describe it (a field only the samples have)
                gl[p.replace("[]", "")] = {"term": humanize(p.split(".")[-1].replace("[]", "")), "means": "TODO: what this field means."}
                continue
            if f.hidden:
                continue
            gp = p.replace("[]", "")
            entry: dict = {"term": f.label, "means": (" ".join(f.description.split())[:590]) if f.description else "TODO: what this field means."}
            if f.unit:
                entry["unit"] = f.unit
            if f.values:
                entry["values"] = {k: v[:590] for k, v in f.values.items()}
            gl[gp] = entry
        doc["kinds"][kp.kind] = {"title": kp.title, "about": sentence, "glossary": gl}
    return yaml.safe_dump(doc, sort_keys=False, width=120, allow_unicode=True)


def todo_count(plan: Plan) -> int:
    return sum(1 for k in plan.kinds for f in k.fields if not f.description and "[" not in f.path and f.role not in ("object", "table"))
