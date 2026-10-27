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

"""The bank's own names in the demo data, from config/demo/names.yaml (2.9.4; docs/connectors/DEMO_DATA.md, "Your bank's names").

    names = DemoNames.load()                 # config/demo/names.yaml (or a path); the defaults when the file is missing
    names.mapping                            # {old name: new name}, only the names that change (empty: the defaults)
    names.rewrite(doc)                       # the document with every value that is an old name, or an old name followed by ",",
                                             # renamed (the suffix kept: "Drishti Bank plc, Singapore Branch"); in place, returned
    names.rewrite_line(line)                 # the same for one JSON line (unchanged text when nothing matches)

Applied as the demo lakes are loaded (lake demo, build_demo_lakes.py) and as the packs' samples are written (packsamples.files), never
to the committed test data. LEIs and BICs are identifiers and stay as they are.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FILE = ROOT / "config" / "demo" / "names.yaml"

#: the names the committed data carries
DEFAULTS = {"bank": "Drishti Bank", "short-name": "DRISHTI",
            "legal-entities": {"LE-NY": "Drishti Bank Securities Inc.", "LE-LDN": "Drishti Bank plc", "LE-FRA": "Drishti Bank Europe AG",
                               "LE-TKY": "Drishti Securities Japan K.K."}}


def _simple_yaml(text: str) -> dict:
    """names.yaml's own form without PyYAML: top-level "key: value" and one level of indented "key: value" under a "key:" line."""
    out: dict = {}
    section = None
    for raw in text.splitlines():
        body = raw.split(" #", 1)[0].rstrip() if not raw.lstrip().startswith("#") else ""
        if not body.strip() or ":" not in body:
            continue
        key, _, value = body.strip().partition(":")
        key, value = key.strip().strip('"\''), value.strip().strip('"\'')
        if raw[:1] in (" ", "\t") and section is not None:
            out[section][key] = value
        elif value:
            out[key], section = value, None
        else:
            out[key], section = {}, key
    return out


class DemoNames:
    def __init__(self, configured: dict | None = None, source: str = "defaults"):
        c = configured or {}
        self.source = source
        mapping: dict[str, str] = {}
        for key in ("bank", "short-name"):
            new = c.get(key)
            if isinstance(new, str) and new.strip() and new.strip() != DEFAULTS[key]:
                mapping[DEFAULTS[key]] = new.strip()
        les = c.get("legal-entities") or {}
        if not isinstance(les, dict):
            raise ValueError(f"{source}: legal-entities is a mapping of id to name (LE-NY: ...)")
        for le, new in les.items():
            old = DEFAULTS["legal-entities"].get(str(le))
            if old is None:
                raise ValueError(f"{source}: unknown legal entity {le} (the demo data has {', '.join(DEFAULTS['legal-entities'])})")
            if isinstance(new, str) and new.strip() and new.strip() != old:
                mapping[old] = new.strip()
        self.branches = {str(k): str(v).strip() for k, v in (c.get("branches") or {}).items() if str(v).strip() and str(v).strip() != str(k)}
        self.mapping = mapping
        # the longest old name first, so a legal entity is never taken for the bank ("Drishti Bank plc" before "Drishti Bank")
        self._order = sorted(mapping, key=len, reverse=True)

    @classmethod
    def load(cls, path: str | Path | None = None) -> "DemoNames":
        f = Path(path) if path else DEFAULT_FILE
        if not f.is_file():
            if path:
                raise FileNotFoundError(f"{f}: no such names file")
            return cls()
        text = f.read_text(encoding="utf-8")
        try:
            import yaml
            data = yaml.safe_load(text) or {}
        except ImportError:                              # a tool started without PyYAML: the file's simple form, read by hand
            data = _simple_yaml(text)
        if not isinstance(data, dict):
            raise ValueError(f"{f}: a names file is a mapping (bank, short-name, legal-entities, branches)")
        return cls(data, str(f))

    def __bool__(self) -> bool:
        return bool(self.mapping or self.branches)

    def value(self, v: str) -> str:
        if v in self.branches:
            return self.branches[v]
        if v in self.mapping:
            return self.mapping[v]
        for old in self._order:
            if v.startswith(old + ","):
                return self.mapping[old] + v[len(old):]
        return v

    def rewrite(self, node):
        if not self:
            return node
        if isinstance(node, dict):
            for k, v in node.items():
                node[k] = self.rewrite(v)
            return node
        if isinstance(node, list):
            return [self.rewrite(x) for x in node]
        return self.value(node) if isinstance(node, str) else node

    def rewrite_line(self, line: str) -> str:
        """One JSON line renamed; the text as it was when no old name is in it (the common case is not parsed)."""
        if not self or not any(old in line for old in (*self.mapping, *self.branches)):
            return line
        return json.dumps(self.rewrite(json.loads(line)), separators=(",", ":"), ensure_ascii=False)

    def lines(self) -> list[str]:
        """The mapping, said: one "old -> new" a line, or that nothing changes."""
        if not self:
            return [f"names ({self.source}): the defaults, nothing renamed"]
        out = [f"names ({self.source}): {len(self.mapping) + len(self.branches)} renamed (LEIs and BICs unchanged)"]
        out += [f"  {old} -> {new}" for old, new in {**self.mapping, **self.branches}.items()]
        out += [f"  {old}, <branch> -> {new}, <branch>" for old, new in self.mapping.items()]
        return out
