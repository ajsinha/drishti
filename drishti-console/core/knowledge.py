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

"""The Knowledge glossary for ``WHAT``: docs/knowledge/glossary.yaml, the same file tools/knowledge.py renders into the Knowledge pages'
Glossary sections, so a term ``WHAT`` opens is always the entry on the page (docs/knowledge/README.md).

``WHAT`` alone opens the Knowledge home; ``WHAT <term>`` opens the term's entry when the words are a term, its long name or a synonym
(any case, any spacing); anything else is a search of the Knowledge pages.
"""
from __future__ import annotations

import re
import threading
from dataclasses import dataclass
from pathlib import Path

import yaml

WORD = "WHAT"                 # a reserved command word (routes/terminal_routes.py, the server's PackLoader.RESERVED_WORDS)
HOME = "README.md"            # the Knowledge home, in docs/knowledge/
CATEGORY = "knowledge"        # its help-centre section (config/help.yaml)


def anchor(term: str) -> str:
    """An entry's anchor on its page, as tools/knowledge.py writes it: ``term-`` and the term in lower case, each run of other
    characters one hyphen."""
    return "term-" + re.sub(r"[^a-z0-9]+", "-", term.lower()).strip("-")


def normal(text: str) -> str:
    return " ".join(str(text or "").lower().split())


@dataclass(frozen=True)
class Entry:
    term: str
    name: str
    domain: str
    page: str            # the page's file name in docs/knowledge/
    title: str           # the page's title (the domain's)
    meaning: str
    command: str
    words: tuple[str, ...]

    @property
    def anchor(self) -> str:
        return anchor(self.term)


class Glossary:
    """The glossary file, read again when it changes; thread-safe for reads."""

    def __init__(self, docs_dir: Path):
        self.dir = Path(docs_dir) / "knowledge"
        self.path = self.dir / "glossary.yaml"
        self._lock = threading.Lock()
        self._stamp: float | None = None
        self._entries: list[Entry] = []

    def entries(self) -> list[Entry]:
        try:
            stamp = self.path.stat().st_mtime
        except OSError:
            return []
        with self._lock:
            if stamp != self._stamp:
                data = yaml.safe_load(self.path.read_text(encoding="utf-8")) or {}
                domains = data.get("domains") or {}
                out = []
                for e in data.get("terms") or []:
                    d = domains.get(e.get("domain")) or {}
                    words = [normal(w) for w in [e.get("term"), e.get("name"), *(e.get("synonyms") or [])] if normal(w)]
                    out.append(Entry(e["term"], e.get("name") or "", e.get("domain", ""), d.get("page", HOME), d.get("title", ""),
                                     " ".join(str(e.get("meaning", "")).split()), e.get("command") or "", tuple(dict.fromkeys(words))))
                self._entries, self._stamp = out, stamp
            return self._entries

    def find(self, words: str) -> Entry | None:
        """The entry the words name exactly (term, long name or synonym, any case and spacing); a trailing ``?`` or a plural ``s`` is
        forgiven (``WHAT netting sets?``)."""
        want = normal(words).rstrip("?").strip()
        if not want:
            return None
        for candidate in (want, want[:-1] if want.endswith("s") else None):
            if candidate:
                for e in self.entries():
                    if candidate in e.words:
                        return e
        return None

    def starting(self, words: str, limit: int = 8) -> list[Entry]:
        """Entries whose term, name or a synonym starts with the words (the type-ahead), exact matches first."""
        want = normal(words)
        exact = self.find(want) if want else None
        out = [exact] if exact else []
        for e in self.entries():
            if e is not exact and (not want or any(w.startswith(want) for w in e.words)):
                out.append(e)
            if len(out) >= limit:
                break
        return out
