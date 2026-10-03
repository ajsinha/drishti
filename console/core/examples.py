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

"""The Rachana examples (``docs/guides/examples``): each a ``<name>.sutra.yaml``, a self-contained ``<name>.json`` and a
``<name>.md`` note. Studio opens them and the help centre lists them. A name is served only if its three files are present
in the directory when asked, so a request can never name a path."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

_KIND = re.compile(r"^match:\s*\{[^}]*?\bkind:\s*([A-Za-z0-9_-]+)", re.M)
_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


@dataclass(frozen=True)
class Example:
    name: str
    title: str
    kind: str
    yaml: str
    json: str
    readme: str


class Examples:
    """Reads the examples directory on demand (the files are small); safe for concurrent reads."""

    def __init__(self, directory: Path, default: str = ""):
        self.directory = directory
        self.default = default

    def names(self) -> list[str]:
        """Every example with all three files, the default first, the rest alphabetically."""
        if not self.directory.is_dir():
            return []
        found = sorted(p.name[: -len(".sutra.yaml")] for p in self.directory.glob("*.sutra.yaml")
                       if _NAME.match(p.name[: -len(".sutra.yaml")]) and self._complete(p.name[: -len(".sutra.yaml")]))
        return sorted(found, key=lambda n: (n != self.default, n))

    def _complete(self, name: str) -> bool:
        return all((self.directory / f"{name}{ext}").is_file() for ext in (".sutra.yaml", ".json", ".md"))

    def get(self, name: str | None) -> Example | None:
        """The named example, or None: names are checked against the files present, never joined into a path first."""
        if not name or not _NAME.match(name) or name not in self.names():
            return None
        text = lambda ext: (self.directory / f"{name}{ext}").read_text(encoding="utf-8")  # noqa: E731
        readme = text(".md")
        heading = next((ln[2:].strip() for ln in readme.splitlines() if ln.startswith("# ")), name)
        yaml_text = text(".sutra.yaml")
        kind = _KIND.search(yaml_text)
        return Example(name, heading, kind.group(1) if kind else "", yaml_text, text(".json"), readme)

    def all(self) -> list[Example]:
        return [e for e in (self.get(n) for n in self.names()) if e]
