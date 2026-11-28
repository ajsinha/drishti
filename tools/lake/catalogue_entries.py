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

"""Make every table of a lake file explicit (docs/admin/TABLE_CATALOGUE.md, "Binding a bank's lake").

    python3 tools/lake/catalogue_entries.py [--lakes-dir DIR] [--write]

A table that has no ``uri:`` sits where the lake's layout puts it (``<root>/<domain>/<table>``): the fallback. This writes that location
once as the table's own entry, ``uri: ${lake.root}/<path>`` (the anchor follows the lake's root, so moving the lake still moves its
tables), with the ``format:`` and, when its kind has a contract, the ``contract: <kind>@<major>``. A bank edits the ``uri:`` to the real
location. Every other line of the file is kept as written; a table that already has a ``uri:`` is left alone. Without ``--write`` it
only says what it would add."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ANCHOR = "${lake.root}"


def contracts(roots: list[Path]) -> dict[str, str]:
    """kind -> ``<kind>@<major>`` for every kind with a data contract under the given folders."""
    out: dict[str, str] = {}
    for base in roots:
        for p in sorted(base.rglob("*.contract.yaml")):
            if "node_modules" in p.parts or "target" in p.parts:
                continue
            try:
                d = yaml.safe_load(p.read_text(encoding="utf-8"))
            except (yaml.YAMLError, OSError, UnicodeDecodeError):
                continue
            if isinstance(d, dict) and d.get("kind"):
                major = str(d.get("version") or "1").split(".")[0]
                out.setdefault(str(d["kind"]), f"{d['kind']}@{major}")
    return out


def _raw_format(text: str) -> str:
    m = re.search(r"^format:\s*(\S.*?)\s*(?:#.*)?$", text, re.M)
    return m.group(1) if m else "delta"


def entries(path: Path, known: dict[str, str]) -> tuple[str, list[str]]:
    """The lake file's text with the explicit entries added, and the table names changed."""
    text = path.read_text(encoding="utf-8")
    doc = yaml.safe_load(text) or {}
    tables = doc.get("tables") or {}
    if doc.get("catalog") or not tables:
        return text, []
    fmt = _raw_format(text)
    lines = text.splitlines()
    changed: list[str] = []
    for i, line in enumerate(lines):
        m = re.match(r"^  ([a-z0-9_-]+\.[A-Za-z0-9_.-]+):(.*)$", line)
        if not m or m.group(1) not in tables:
            continue
        name, rest = m.group(1), m.group(2).split("  #")[0].strip()
        e = tables[name] or {}
        if e.get("uri") or str(e.get("path", "")).startswith("/") or "://" in str(e.get("path", "")):
            continue
        domain, tbl = name.split(".", 1)
        kind = str(e.get("kind") or tbl)
        add = [f"uri: \"{ANCHOR}/{e.get('path') or f'{domain}/{tbl}'}\""]
        if "format" not in e:
            add.append(f"format: \"{fmt}\"" if "$" in fmt else f"format: {fmt}")
        if "contract" not in e and kind in known:
            add.append(f"contract: {known[kind]}")
        if rest in ("", "null", "~"):
            nxt = lines[i + 1] if i + 1 < len(lines) else ""
            if rest == "" and re.match(r"^    \S", nxt):               # a block entry: the new keys first, in the block's own indent
                lines[i] = line + "\n" + "\n".join("    " + a for a in add)
            else:
                lines[i] = f"  {name}: {{{', '.join(add)}}}"
        elif rest.startswith("{"):
            body = rest[1:]
            lines[i] = f"  {name}: {{{', '.join(add)}" + ("}" + body[1:] if body.startswith("}") else ", " + body.lstrip())
        else:
            continue
        changed.append(name)
    out = "\n".join(lines) + ("\n" if text.endswith("\n") else "")
    return out, changed


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lakes-dir", default=str(ROOT / "config" / "lakes"))
    ap.add_argument("--contracts", action="append", help="folder searched for *.contract.yaml (default: modules, config/packs, examples)")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args(argv)
    known = contracts([Path(c) for c in a.contracts] if a.contracts else [ROOT / "modules", ROOT / "config" / "packs", ROOT / "examples"])
    total = 0
    for f in sorted(Path(a.lakes_dir).rglob("*.yaml")):
        text, changed = entries(f, known)
        total += len(changed)
        if changed:
            print(f"{f}: {len(changed)} tables made explicit")
            if a.write:
                f.write_text(text, encoding="utf-8")
    print(f"{total} tables {'written' if a.write else 'would be written (add --write)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
