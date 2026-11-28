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

"""``lake build --format iceberg``: the staged rows of a build written into an Apache Iceberg table by Drishti Lake's loader
(``DrishtiLakeCli load`` in the server's exec jar or on LAKE_CLASSPATH, ``source.format: loader-rows``: tools/lake/loaderows.py). Delta stays the default format; Iceberg
is chosen per build (``--format iceberg``) or by a pack's Iceberg connector.

The table is ``<lake>/<domain>/<table>``, in the layout the Iceberg connector reads fastest (docs/guides/ICEBERG_CONNECTOR.md): rows
``(id, doc, business_date)``, the promoted columns beside them, a vector field (``--vector PNL``) as an optional ``list<double>`` column
whose missing elements stay null, each business date sorted by id. Each business date of the input replaces that date (the loader commits
one overwrite of the day), so re-running a day is safe; ``overwrite-partitions`` and ``append`` are Delta's and are refused here. Partition
columns (``--partition-by``) are kept as ordinary columns: an Iceberg table of this layout is partitioned by business date alone.

The rows go to the loader as one file a date (one JSON line a row: domain, kind, id, date, doc, columns) in the build's staging folder; the
loader sorts each day itself (spilling there)."""
from __future__ import annotations

import json
import pathlib

import lakeconfig
import stage
import writers


def lines(domain: str, table: str, by_day: dict, partition_names: list[str], types: dict | None = None):
    """The loader's lines for every staged row: its promoted values, and the partition values as columns. A promoted list or object
    that is neither a vector nor a list attribute (``types``: Stager.types(), a column's kind) goes as its JSON text, as the Delta
    writers store it (writers._cell); a list attribute (TRADE_IDS, TAGS) goes as its values' texts, which the loader writes as a
    list<string> column, and a list of numbers is a vector."""
    vectors = {n for n, k in (types or {}).items() if k == "vector"}
    lists = {n for n, k in (types or {}).items() if k == "list"}
    for day, parts in by_day.items():
        iso = day.isoformat()
        for part in parts:
            fixed = {n: v for n, v in zip(partition_names, part.values) if v is not None}
            for chunk in stage.read_rows(part.file, 2000):
                for ident, doc, promoted in chunk:
                    cols = {**fixed, **{k: _cell(k, v, vectors, lists) for k, v in promoted.items() if v is not None}}
                    yield json.dumps({"domain": domain, "kind": table, "id": ident, "date": iso, "doc": doc, "columns": cols},
                                     ensure_ascii=False, separators=(",", ":")) + "\n"


def _cell(name: str, v, vectors: set[str], lists: set[str] = frozenset()):
    if name in lists:                               # a list attribute: its values as texts, a list<string> column (section 2.4)
        return stage.list_texts(v)
    if isinstance(v, (list, dict)) and name not in vectors:
        return json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    return v


def write(lake, domain: str, table: str, by_day: dict, partition_names: list[str], workdir: pathlib.Path,
          jar: str | None = None, java: str | None = None, progress=None, settings: list[str] | None = None, types: dict | None = None) -> dict:
    """Streams the staged rows into ``<lake>/<domain>/<table>``; {format, table, rows, dates, engine}. Raises writers.WriteError with the
    loader's own last line when it fails (nothing of a day that did not commit is visible)."""
    total = sum(len(parts) for parts in by_day.values())
    return write_lines(lake, domain, table, lines(domain, table, by_day, partition_names, types), sorted(d.isoformat() for d in by_day), workdir,
                       jar, java, (lambda: progress(total, total)) if progress else None, settings,
                       sorted(n for n, k in (types or {}).items() if k == "vector"))


def write_lines(lake, domain: str, table: str, rows, dates: list[str], workdir: pathlib.Path, jar: str | None = None, java: str | None = None,
                done=None, settings: list[str] | None = None, vectors=()) -> dict:
    """The loader's lines (``lines``' form: domain, kind, id, date, doc, columns; a JSON text each) into the table; the same result as ``write``.
    Also the Python loader's (tools/ingest_jsonl.py ``--store iceberg``): every writer of Iceberg goes through this one."""
    import loaderows
    try:
        loaded = loaderows.load(rows, lake, domain, table, workdir, "iceberg", vectors=vectors, settings=settings, jar=jar, java=java)
    except loaderows.LoadError as e:
        raise writers.WriteError("the Iceberg load did not finish: " + str(e)) from None
    if done:
        done()
    return {"format": "iceberg", "engine": "native", "table": lakeconfig.locate(lake, domain, table), "rows": loaded["rows"],
            "dates": dates}
