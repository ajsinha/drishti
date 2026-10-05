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

"""A Sutra that does not compile still has panels that do (QA UX-02). The server refuses a Sutra whole when one expression is
wrong; the workbench wants the other panels on the canvas and the mistake as a located problem. This reads the server's
refusal into problems (line, column, code, message), finds the panel each one is in, and cuts those panels out of the text so the
rest can be previewed. Nothing is saved; the design keeps the author's text."""
from __future__ import annotations

import re

import yaml

_AT = re.compile(r"studio\.sutra\.yaml:(\d+):(\d+) (DRS-\d+) ")


def parse_problems(detail: str) -> list[dict]:
    """The located problems inside a refusal such as ``1 problem(s): [file:6:5 DRS-2101 text, file:9:3 DRS-2011 text]``."""
    text = detail or ""
    hits = list(_AT.finditer(text))
    out = []
    for i, m in enumerate(hits):
        end = hits[i + 1].start() if i + 1 < len(hits) else len(text)
        body = text[m.end():end].strip()
        body = re.sub(r",\s*$", "", body)
        if i + 1 == len(hits):
            body = re.sub(r"\]\s*$", "", body)
        code = m.group(3)
        message = body.replace(code + ": ", "").replace(code + " ", "").replace(code, "").strip(" :")
        out.append({"line": int(m.group(1)), "column": int(m.group(2)), "code": code, "message": message})
    return out


def _panel_spans(text: str) -> list[tuple[str, int, int]]:
    """``[(id, first line, last line)]`` (1-based, inclusive) of the entries of the top-level ``panels`` list."""
    try:
        root = yaml.compose(text)
    except yaml.YAMLError:
        return []
    if not isinstance(root, yaml.MappingNode):
        return []
    for key, value in root.value:
        if getattr(key, "value", None) == "panels" and isinstance(value, yaml.SequenceNode):
            spans = []
            for item in value.value:
                # a block entry ends where the next token starts (exclusive); a flow one ends on the line of its closing brace
                end = item.end_mark.line if item.flow_style is False else item.end_mark.line + 1
                pid = ""
                if isinstance(item, yaml.MappingNode):
                    for k, v in item.value:
                        if getattr(k, "value", None) == "id":
                            pid = str(getattr(v, "value", ""))
                spans.append((pid, item.start_mark.line + 1, max(end, item.start_mark.line + 1)))
            return spans
    return []


def locate(text: str, problems: list[dict]) -> list[dict]:
    """Each problem with the ``panel`` (id) it is in and the ``option`` (the key on its line), when it is inside a panel."""
    spans = _panel_spans(text)
    lines = text.splitlines()
    for p in problems:
        for pid, a, b in spans:
            if a <= p["line"] <= b:
                p["panel"] = pid
                # the server points at the panel's first line; the expression it quotes says which line is meant
                quoted = re.match(r"expression '(.*?)':", p["message"])
                for n in range(a, b + 1) if quoted else ():
                    if n <= len(lines) and quoted.group(1) in lines[n - 1]:
                        p["line"], p["column"] = n, max(lines[n - 1].find(quoted.group(1)), 0) + 1
                        break
                break
        row = lines[p["line"] - 1] if 0 < p["line"] <= len(lines) else ""
        at = max(p["column"] - 1, 0)
        here = re.match(r"(?:-\s+)?([A-Za-z][\w-]*)\s*:", row[at:])
        before = re.findall(r"([A-Za-z][\w-]*)\s*:", row[:at])
        if here:
            p["option"] = here.group(1)
        elif before:
            p["option"] = before[-1]
    return problems


def without_panels(text: str, ids: set[str]) -> str:
    """The text with the entries of the named panels cut out (every other line is kept as it is)."""
    cut = set()
    for pid, a, b in _panel_spans(text):
        if pid in ids:
            cut.update(range(a, b + 1))
    return "\n".join(ln for i, ln in enumerate(text.splitlines(), 1) if i not in cut) + "\n"
