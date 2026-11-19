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

"""The reject report of every kind, ``<kind>.<date>.rejects.csv`` (docs/plans/CSV_LOADERS.md section 10.2): one line per problem, in row
order: FILE, ROW (as a spreadsheet shows it: the header is row 1), KEY, COLUMN, FIELD, VALUE (cut to 80 characters), REASON (one code of
a fixed list), WHY (a sentence), FIX (what to do, when the loader can say)."""
from __future__ import annotations

import csv
from collections import Counter
from pathlib import Path

COLUMNS = ["FILE", "ROW", "KEY", "COLUMN", "FIELD", "VALUE", "REASON", "WHY", "FIX"]

#: every reason code a reject may carry, one list for every kind (PVF's own audit reasons keep their names)
CODES = ("MISSING_REQUIRED", "BAD_NUMBER", "BAD_INTEGER", "BAD_DATE", "BAD_TIMESTAMP", "BAD_BOOLEAN", "BAD_ENUM", "BAD_PATTERN",
         "UNMAPPED_VALUE", "BAD_ROW_SHAPE", "EXCEL_ERROR", "EXCEL_NUMBER_ID", "FORMULA", "DUPLICATE_KEY", "DUPLICATE_CHILD", "ORPHAN_CHILD",
         "NO_CHILDREN", "CONFLICTING_PARENT", "MISSING_LOOKUP", "AMBIGUOUS_LOOKUP", "CONDITION", "BAD_VECTOR", "UNKNOWN_LINK",
         "BAD_VALUE", "CONTRACT", "WRONG_BUSINESS_DATE", "DERIVED_FIELD")


class Problem(Exception):
    """One problem of one row: its reason code, the sentence saying why, and where it came from (column, field, value, fix)."""

    def __init__(self, reason: str, why: str, column: str = "", field: str = "", value=None, fix: str = ""):
        super().__init__(why)
        self.reason, self.why, self.column, self.field, self.value, self.fix = reason, why, column, field, value, fix


class Rejects:
    """The reject report of one kind and date, written as it goes (``path`` None: counted only, a dry run)."""

    def __init__(self, path: Path | None):
        self.path = Path(path) if path else None
        self.by_reason: Counter = Counter()
        self.rows: set = set()
        self.first: list[dict] = []
        self._f = open(self.path, "w", newline="", encoding="utf-8") if self.path else None
        self._w = csv.writer(self._f) if self._f else None
        if self._w:
            self._w.writerow(COLUMNS)

    def add(self, file: str, row, key, p: Problem) -> None:
        """One line: the problem ``p`` of row ``row`` (its key when it could be built)."""
        self.by_reason[p.reason] += 1
        self.rows.add((file, row))
        value = "" if p.value is None else str(p.value)
        line = {"FILE": file, "ROW": row, "KEY": key or "", "COLUMN": p.column, "FIELD": p.field, "VALUE": value[:80],
                "REASON": p.reason, "WHY": p.why, "FIX": p.fix}
        if len(self.first) < 5:
            self.first.append(line)
        if self._w:
            self._w.writerow([line[c] for c in COLUMNS])

    @property
    def count(self) -> int:
        """Problems listed (a row with three problems counts three)."""
        return sum(self.by_reason.values())

    def close(self) -> None:
        if self._f:
            self._f.close()
            self._f = None
