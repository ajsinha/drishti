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

"""The one CSV reader of the loaders, and the typed reading of one cell (docs/guides/BANK_DATA_GUIDE.md section 9). The rule behind every line:
**the file's format is declared, never guessed cell by cell.** A value that does not fit the declared format raises a Problem with its
reason code; nothing is silently reinterpreted.

The reader streams (Python's csv module, RFC 4180: quoted delimiters, line breaks and doubled quotes; LF or CRLF), so a file of several
million rows needs no more memory than a row. Every cell is text until a field's rule types it. Row numbers are as a spreadsheet shows
them: the header is row 1 (plus any skipped rows), the first data row is row 2."""
from __future__ import annotations

import codecs
import csv
import datetime as dt
import difflib
import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

from mrloader.common import PvfError
from mrloader.rejects import Problem

DELIMITERS = {",": ",", ";": ";", "\\t": "\t", "\t": "\t", "|": "|"}
DEFAULT_NULLS = ("NULL", "null", "N/A", "#N/A", "NaN", "-")
EXCEL_ERRORS = ("#REF!", "#VALUE!", "#DIV/0!", "#NAME?", "#NUM!", "#NULL!", "#SPILL!", "#CALC!")
DEFAULT_BOOLEANS = {True: ("TRUE", "Y", "YES", "1", "T"), False: ("FALSE", "N", "NO", "0", "F")}
#: the date formats a file may declare (csv.dates); ISO YYYY-MM-DD and YYYYMMDD are always read
DATE_FORMATS = {"YYYY-MM-DD": "%Y-%m-%d", "DD/MM/YYYY": "%d/%m/%Y", "MM/DD/YYYY": "%m/%d/%Y", "DD-MMM-YYYY": "%d-%b-%Y",
                "DD.MM.YYYY": "%d.%m.%Y", "YYYY/MM/DD": "%Y/%m/%d", "excel-serial": None}
_EXCEL_NUMBER = re.compile(r"^\d(\.\d+)?E\+\d+$", re.IGNORECASE)
_MIDNIGHT = re.compile(r"^(.+?)[ T]00:00(:00(\.0+)?)?$")
_TS = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$")
_NBSP = " "


@dataclass
class Dialect:
    """How a file is written: declared by the mapping's ``csv:`` block over the defaults (kinds/shared/csv.yaml)."""

    delimiter: str = ","
    encoding: str = "utf-8"
    decimal: str = "."
    thousands: str = ","
    dates: str = "YYYY-MM-DD"
    timezone: str | None = None
    nulls: tuple = DEFAULT_NULLS
    booleans: dict = field(default_factory=lambda: dict(DEFAULT_BOOLEANS))
    skip_rows: int = 0
    footer: str | None = None

    @classmethod
    def from_doc(cls, doc: dict | None) -> "Dialect":
        """A dialect from a ``csv:`` block (hyphenated keys); an unknown key or value is refused."""
        doc = dict(doc or {})
        d = cls()
        known = {"delimiter", "encoding", "decimal", "thousands", "dates", "timezone", "nulls", "booleans", "skip-rows", "footer"}
        bad = sorted(set(doc) - known)
        if bad:
            raise PvfError(f"csv: unknown key(s) {bad} (known: {sorted(known)})")
        if "delimiter" in doc:
            if str(doc["delimiter"]) != "auto" and str(doc["delimiter"]) not in DELIMITERS:
                raise PvfError(f"csv.delimiter {doc['delimiter']!r}: one of , ; \\t | or auto")
            d.delimiter = "auto" if str(doc["delimiter"]) == "auto" else DELIMITERS[str(doc["delimiter"])]
        for k in ("encoding", "decimal", "dates", "timezone", "footer"):
            if doc.get(k) is not None:
                setattr(d, k, str(doc[k]))
        if "thousands" in doc:
            d.thousands = "" if doc["thousands"] in (None, "") else str(doc["thousands"])
        if d.decimal == d.thousands:
            raise PvfError("csv.decimal and csv.thousands must differ")
        if d.dates not in DATE_FORMATS:
            raise PvfError(f"csv.dates {d.dates!r}: one of {sorted(DATE_FORMATS)}")
        try:
            codecs.lookup(d.encoding)
        except LookupError:
            raise PvfError(f"csv.encoding {d.encoding!r} is not an encoding") from None
        if "nulls" in doc:
            d.nulls = tuple(str(x) for x in doc["nulls"])
        if "booleans" in doc:
            b = doc["booleans"]
            d.booleans = {True: tuple(str(x).upper() for x in b["true"]), False: tuple(str(x).upper() for x in b["false"])}
        d.skip_rows = int(doc.get("skip-rows", 0) or 0)
        return d

    def describe(self) -> dict:
        return {"DELIMITER": self.delimiter, "ENCODING": self.encoding, "DECIMAL": self.decimal, "THOUSANDS": self.thousands,
                "DATES": self.dates, "TIMEZONE": self.timezone, "SKIP_ROWS": self.skip_rows, "FOOTER": self.footer}


def column_name(text: str) -> str:
    """A header cell as a column name: trimmed, a non-breaking space a space, upper-cased, every other character ``_`` (``Legal Entity``,
    ``legal_entity`` and ``LEGAL-ENTITY`` are one column)."""
    s = str(text).replace(_NBSP, " ").strip()
    return re.sub(r"[^0-9A-Z]+", "_", s.upper()).strip("_")


class CsvFile:
    """One CSV file opened for reading: its header (column names) and its rows, streamed. ``rows()`` yields (row number, {column: text
    or None}) or (row number, Problem) for a row of the wrong shape; blank rows and a footer row are skipped."""

    def __init__(self, path: Path, dialect: Dialect, rename: dict | None = None):
        self.path, self.d = Path(path), dialect
        self.name = self.path.name
        if not self.path.is_file():
            raise PvfError(f"{self.path}: no such file")
        self._f = open(self.path, "r", encoding=dialect.encoding, errors="strict", newline="")
        self._first_row = 1
        self.delimiter = dialect.delimiter
        try:
            head_lines = self._peek()
        except UnicodeDecodeError as e:
            raise self._encoding_error(e, 1) from None
        if self.delimiter == "auto":
            self.delimiter = max((",", ";", "\t", "|"), key=lambda c: head_lines.count(c))
        self._reader = csv.reader(self._f, delimiter=self.delimiter, strict=True)
        raw = self._next(header=True)
        if raw is None:
            raise PvfError(f"{self.name}: no header row")
        while raw and not raw[-1].strip():
            raw.pop()                                            # trailing empty header cells: their columns are dropped
        self.width = len(raw)
        names = [column_name(h) for h in raw]
        if rename:
            names = [rename.get(n, n) for n in names]
        if any(not n for n in names):
            raise PvfError(f"{self.name}: header column {names.index('') + 1} has no name")
        dups = sorted({n for n in names if names.count(n) > 1})
        if dups:
            raise PvfError(f"{self.name}: the header has two columns that both become {dups} (names compare upper-cased, other characters as _)")
        self.columns = names
        self.footer = re.compile(dialect.footer) if dialect.footer else None

    def _peek(self) -> str:
        """Honours a byte-order mark, the ``sep=;`` hint and ``skip-rows``; returns the header line's text (for ``delimiter: auto``)."""
        pos = self._f.tell()
        first = self._f.readline()
        lead = first.lstrip("\ufeff").strip()
        if lead.lower().startswith("sep=") and len(lead) == 5:
            self.delimiter = lead[4]
            self._first_row += 1
        else:
            self._f.seek(pos)
            if first.startswith("\ufeff"):
                self._f.read(1)
        for _ in range(self.d.skip_rows):
            self._f.readline()
            self._first_row += 1
        pos = self._f.tell()
        line = self._f.readline()
        self._f.seek(pos)
        return line

    def _encoding_error(self, e: UnicodeDecodeError, row) -> PvfError:
        other = "cp1252" if self.d.encoding.lower().replace("-", "") in ("utf8", "utf8sig") else "utf-8"
        return PvfError(f"{self.name} row {row}: a byte that is not {self.d.encoding} ({e.reason}): declare csv.encoding: {other} "
                        f"(Excel's plain CSV on Windows is cp1252) or export as CSV UTF-8")

    def _next(self, header: bool = False):
        start = self._reader.line_num + self._first_row
        try:
            return next(self._reader)
        except StopIteration:
            return None
        except csv.Error as e:
            raise PvfError(f"{self.name} row {start}: {e} (an unclosed quote opens on this row)") from None
        except UnicodeDecodeError as e:
            raise self._encoding_error(e, start) from None

    def rows(self) -> Iterator[tuple[int, dict | Problem]]:
        while True:
            row_no = self._reader.line_num + self._first_row
            cells = self._next()
            if cells is None:
                return
            if not cells or all(not c.strip() for c in cells):
                continue
            if self.footer and self.footer.search(cells[0]):
                continue
            while len(cells) > self.width and not cells[-1].strip():
                cells.pop()                                      # trailing empty cells (",,," after the last column)
            if len(cells) != self.width:
                yield row_no, Problem("BAD_ROW_SHAPE", f"{len(cells)} cells, the header has {self.width}")
                continue
            yield row_no, dict(zip(self.columns, cells))

    def missing(self, needed: list[str]) -> list[str]:
        """The needed columns the header lacks, each with its nearest matches."""
        out = []
        for n in needed:
            if n not in self.columns:
                near = difflib.get_close_matches(n, self.columns, n=2, cutoff=0.6)
                out.append(n + (f" (near: {', '.join(near)})" if near else ""))
        return out

    def close(self) -> None:
        self._f.close()


# ---- one cell ---------------------------------------------------------------------------------------------------------------------
def clean(text, d: Dialect, column: str = "", field_name: str = ""):
    """A cell's text, trimmed (spaces and non-breaking spaces), or None when empty or a null word; a spreadsheet's error or a formula
    rejects the row; a leading ``'`` (the spreadsheet's text prefix) is removed."""
    if text is None:
        return None
    s = str(text).replace(_NBSP, " ").strip()
    if s == "" or s in d.nulls:
        return None
    if s.upper() in EXCEL_ERRORS:
        raise Problem("EXCEL_ERROR", f"a spreadsheet error {s}, not a value", column, field_name, s, "fix the formula in the source and export again")
    if s.startswith("="):
        raise Problem("FORMULA", "a formula's text, not its value", column, field_name, s, "export values, not formulas")
    if s.startswith("'"):
        s = s[1:]
    return s


def number(s: str, d: Dialect, column: str = "", field_name: str = "", decimal: str | None = None) -> float:
    """A number in the file's declared format: a sign, an exponent, an accounting negative ``(1,234.50)``, a trailing minus; never a
    currency symbol or an undeclared separator."""
    dec = decimal or d.decimal
    t = s
    neg = False
    if t.startswith("(") and t.endswith(")"):
        neg, t = True, t[1:-1].strip()
    if t.endswith("-") and len(t) > 1:
        neg, t = (not neg), t[:-1].strip()
    th = d.thousands
    if th and th in t:
        whole = t.split(dec)[0].lstrip("+-")
        groups = whole.split(th)
        if not (1 <= len(groups[0]) <= 3 and all(len(g) == 3 for g in groups[1:])):
            raise Problem("BAD_NUMBER", f"the thousands separator {th!r} is in the wrong place", column, field_name, s)
        t = t.replace(th, "")
    if dec != ".":
        if "." in t:
            raise Problem("BAD_NUMBER", f"'.' is not this file's decimal separator ({dec!r})", column, field_name, s)
        t = t.replace(dec, ".")
    if not re.fullmatch(r"[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?", t):
        raise Problem("BAD_NUMBER", f"not a number in the file's format (decimal {dec!r}, thousands {th!r}); no currency symbol or code",
                      column, field_name, s, "export plain numbers")
    x = float(t)
    if not math.isfinite(x):
        raise Problem("BAD_NUMBER", "not a finite number", column, field_name, s)
    return -x if neg else x


def integer(s: str, d: Dialect, column: str = "", field_name: str = "") -> int:
    x = number(s, d, column, field_name)
    if not float(x).is_integer():
        raise Problem("BAD_INTEGER", "a whole number is required", column, field_name, s)
    return int(x)


def rate(s: str, d: Dialect, column: str = "", field_name: str = "", percent: bool = False) -> float:
    """A fraction (0.05). ``5%`` is refused unless the field says ``percent: true``, which reads ``5%`` and ``5`` as 0.05."""
    if s.endswith("%"):
        if not percent:
            raise Problem("BAD_NUMBER", "a percentage where a fraction is expected (0.05, not 5%)", column, field_name, s,
                          "export fractions, or set percent: true on the field")
        return number(s[:-1].strip(), d, column, field_name) / 100
    x = number(s, d, column, field_name)
    return x / 100 if percent else x


def date(s: str, d: Dialect, column: str = "", field_name: str = "", fmt: str | None = None) -> str:
    """YYYY-MM-DD from ISO or YYYYMMDD (always), or the one declared format; a midnight time is accepted; a two-digit year is refused;
    an Excel serial only when ``excel-serial`` is declared."""
    fmt = fmt or d.dates
    t = s
    m = _MIDNIGHT.match(t)
    if m:
        t = m.group(1)
    for f in ("%Y-%m-%d", "%Y%m%d"):
        try:
            if (f == "%Y%m%d" and len(t) == 8) or (f == "%Y-%m-%d" and len(t) == 10):
                return dt.datetime.strptime(t, f).date().isoformat()
        except ValueError:
            pass
    if fmt == "excel-serial" and re.fullmatch(r"\d{4,6}", t):
        return (dt.date(1899, 12, 30) + dt.timedelta(days=int(t))).isoformat()
    pyfmt = DATE_FORMATS.get(fmt)
    if pyfmt and pyfmt not in ("%Y-%m-%d",):
        try:
            v = dt.datetime.strptime(t, pyfmt).date()
            if re.search(r"\d{4}", t):
                return v.isoformat()
        except ValueError:
            pass
    hint = "declare csv.dates (DD/MM/YYYY, MM/DD/YYYY, DD-MMM-YYYY, DD.MM.YYYY) in the mapping or export ISO dates"
    if re.fullmatch(r"\d{4,6}", t):
        hint = "looks like a spreadsheet's date serial: export ISO dates, or declare dates: excel-serial"
    raise Problem("BAD_DATE", f"not a date in the file's format {fmt}", column, field_name, s, hint)


def timestamp(s: str, d: Dialect, column: str = "", field_name: str = "") -> str:
    """ISO-8601 with a zone, written in UTC; a timestamp without a zone needs csv.timezone."""
    t = s.replace(" ", "T", 1)
    if not _TS.match(s):
        raise Problem("BAD_TIMESTAMP", "not an ISO-8601 timestamp (2026-10-29T06:10:00Z)", column, field_name, s)
    t = t.replace("Z", "+00:00")
    try:
        v = dt.datetime.fromisoformat(t)
    except ValueError:
        raise Problem("BAD_TIMESTAMP", "not a real time", column, field_name, s) from None
    if v.tzinfo is None:
        if not d.timezone:
            raise Problem("BAD_TIMESTAMP", "a timestamp without a zone", column, field_name, s, "declare csv.timezone (America/Toronto) or export UTC with Z")
        from zoneinfo import ZoneInfo
        v = v.replace(tzinfo=ZoneInfo(d.timezone))
    return v.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S") + "Z"


def boolean(s: str, d: Dialect, column: str = "", field_name: str = "") -> bool:
    u = s.upper()
    for v, words in d.booleans.items():
        if u in words:
            return v
    raise Problem("BAD_BOOLEAN", f"not one of {list(d.booleans[True])} or {list(d.booleans[False])}", column, field_name, s)


def ident(s: str, column: str = "", field_name: str = "") -> str:
    """An id is always text, as written; one a spreadsheet turned into a number is refused."""
    if _EXCEL_NUMBER.match(s):
        raise Problem("EXCEL_NUMBER_ID", "looks like a spreadsheet turned an id into a number", column, field_name, s,
                      "export the column as text")
    return s
