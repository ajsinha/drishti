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

"""Anonymised samples: the documents a generated pack keeps (``samples/``, ``tests/``, ``catalog.jsonl``, the examples the synthetic samples
draw from, the drafts' previews) are rewritten so that no real name, id or amount of the source data travels with the pack.

The rewrite is deterministic and consistent: the same real value becomes the same token everywhere (in every field, every kind and every
document of one request), so keys stay unique, links between kinds still resolve, and a pack generated twice from the same files is the
same. What is kept and what is replaced, by field name and by the values seen:

* kept: blanks and nulls, booleans, ISO dates and timestamps (the dates of a deal are its shape, not its identity), and short repeated
  vocabulary (a status, a side, a currency, a unit, a product type: at most 12 distinct values, each seen at least twice) unless the field
  is named like a party, a person, a book or an account;
* replaced by a token: every other text, as ``<FIELD>-0001`` (``COUNTERPARTY-0003``, ``PORTFOLIO-0001``); id-like texts made only of
  digits keep their length (``00042`` -> ``10007``);
* integers named like an id: remapped (100001, 100002 ... in order of first appearance); small integers (under 100: legs, versions,
  sequence numbers) kept; other numbers scaled by a fixed factor per field (between 0.6 and 1.4), rounded as the original was.

``Anonymiser().documents(docs_by_kind)`` returns new documents; the input is not changed.
"""
from __future__ import annotations

import hashlib
import re

from .plan import ID_NAME, IDENTIFYING, ISO_DATE, ISO_DATETIME, blank

VOCAB_MAX = 12
NUMERIC = re.compile(r"^-?\d+(\.\d+)?$")
SHORT = 32


def _prefix(name: str) -> str:
    base = re.sub(r"(?i)(_?id|_?key|_?ref|_?code|_?name)$", "", name) or name
    p = re.sub(r"[^A-Za-z0-9]+", "", re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", base)).upper()
    return (p or "VALUE")[:14]


GENERIC = re.compile(r"(?i)^(name|id|code|key|label|title|value|type|description|text)$")


def qualified(parent: str, key: str) -> str:
    """A field's name for these rules: a generic name inside an object reads with its parent (INDEX.NAME is INDEX_NAME, not any NAME)."""
    return f"{parent}_{key}" if parent and GENERIC.match(key) else key


def _factor(name: str) -> float:
    h = int(hashlib.sha256(name.encode("utf-8")).hexdigest()[:8], 16)
    return 0.6 + (h % 8001) / 10000.0                  # 0.6 .. 1.4, the same for a field name every time


class Anonymiser:
    def __init__(self):
        self.tokens: dict = {}                         # real text -> token (one table for every field: a value shared by two kinds stays shared)
        self.counters: dict = {}
        self.ids: dict = {}                            # real integer id -> new id
        self.vocab: dict = {}                          # field name -> set of values kept as they are
        self.digits = 0

    # ---- what the samples say ------------------------------------------------------------------------------------------------
    def _collect(self, node, name: str, stats: dict) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                self._collect(v, qualified(name, k), stats)
        elif isinstance(node, list):
            for v in node:
                self._collect(v, name, stats)
        elif isinstance(node, str) and not blank(node):
            s = stats.setdefault(name, {})
            s[node] = s.get(node, 0) + 1

    def learn(self, docs: list) -> None:
        stats: dict = {}
        for d in docs:
            self._collect(d, "", stats)
        for name, counts in stats.items():
            if IDENTIFYING.search(name) or ID_NAME.search(name):
                continue
            if len(counts) <= VOCAB_MAX and sum(counts.values()) >= 2 * len(counts) and max(len(v) for v in counts) <= SHORT:
                self.vocab[name] = set(counts)
            elif len(counts) == 1 and max(len(v) for v in counts) <= SHORT:
                self.vocab[name] = set(counts)                 # one constant value (a source system code) is shape, not identity

    # ---- the rewrite --------------------------------------------------------------------------------------------------------------
    def text(self, name: str, v: str) -> str:
        if blank(v) or ISO_DATE.match(v) or ISO_DATETIME.match(v) or v in ("Y", "N", "y", "n", "true", "false", "TRUE", "FALSE"):
            return v
        if v in self.vocab.get(name, ()):
            return v
        if v in self.tokens:
            return self.tokens[v]
        if NUMERIC.match(v) and not ID_NAME.search(name) and not (v.isdigit() and len(v) > 1 and v.startswith("0")) and not IDENTIFYING.search(name):
            # a measure written as text ("78.5000"): scaled like a number, written back with as many decimals
            places = len(v.split(".")[1]) if "." in v else 0
            x = float(v) * _factor(name)
            return f"{x:.{places}f}" if places else str(int(round(x)))
        if v.isdigit():
            self.digits += 1                           # the same length where it fits (codes keep their width), always unique
            tok = str(10 ** (len(v) - 1) + self.digits) if len(v) > 1 else str(self.digits)
        else:
            p = _prefix(name)
            self.counters[p] = self.counters.get(p, 0) + 1
            tok = f"{p}-{self.counters[p]:04d}"
        self.tokens[v] = tok
        return tok

    def number(self, name: str, v):
        if isinstance(v, bool):
            return v
        if isinstance(v, int):
            if ID_NAME.search(name) and abs(v) >= 100:
                if v not in self.ids:
                    self.ids[v] = 100_001 + len(self.ids)
                return self.ids[v]
            if abs(v) < 100:
                return v
            return int(round(v * _factor(name)))
        if isinstance(v, float):
            if v == 0:
                return v
            text = repr(v)
            places = len(text.split(".")[1]) if "." in text and "e" not in text else 6
            return round(v * _factor(name), min(places, 6))
        return v

    def value(self, v, name: str = ""):
        if isinstance(v, dict):
            return {k: self.value(x, qualified(name, k)) for k, x in v.items()}
        if isinstance(v, list):
            return [self.value(x, name) for x in v]
        if isinstance(v, str):
            return self.text(name, v)
        if isinstance(v, (int, float)):
            return self.number(name, v)
        return v

    def documents(self, by_kind: dict) -> dict:
        """{kind: [document]} -> the same, anonymised."""
        allv = [d for docs in by_kind.values() for d in docs]
        self.learn(allv)
        return {k: [self.value(d) for d in docs] for k, docs in by_kind.items()}


def anonymise(by_kind: dict) -> dict:
    return Anonymiser().documents(by_kind)
