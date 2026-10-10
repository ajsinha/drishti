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

"""The Delta Lake layout a pack declares for a kind, and the writer that follows it, so a table can hold a million
documents a business day for years and still answer a pick list or a single id quickly.

A Delta connector in a pack's pack.yaml declares it under settings.layout.<kind>:

    layout:
      trade:
        columns: [productType, notional, counterparty.id, risk.dv01]   # paths promoted to columns of their own
        sort-by: id                                                    # rows sorted by this within each business date
        file-rows: 250000                                              # at most this many rows per Parquet file
        row-group-rows: 10000                                          # at most this many rows per row group

Columns written: id STRING, doc STRING (the whole JSON document), business_date DATE (the partition), and one column
per promoted path, named after the path with "." as "__" (counterparty.id -> counterparty__id). A column whose
values are all numbers is DOUBLE, any other is STRING (booleans as true/false, numbers among strings and objects
as their JSON text); a path that is missing or null is null. Within a business date rows are sorted by id and cut
into files of file-rows each, so every file holds its own contiguous id range and its Delta statistics (min/max
per column, which deltalake writes by default) let a reader skip every other file. Kinds without a layout keep
id, doc and business_date only.

Needs pyarrow and deltalake to write, and pyyaml to read the packs (uv run --with deltalake --with pyarrow --with pyyaml).
"""
from __future__ import annotations

import json
import pathlib
from dataclasses import dataclass, field
from datetime import date

ROOT = pathlib.Path(__file__).resolve().parents[2]
PACKS = ROOT / "config" / "packs"
DOUBLE, STRING = "double", "string"
ONE_FILE = 1 << 40                             # target file size for one write: never cut by bytes, only by file-rows


@dataclass(frozen=True)
class Layout:
    """One kind's declared layout (see the module docstring)."""
    columns: list[str] = field(default_factory=list)
    sort_by: str = "id"
    file_rows: int = 250_000
    row_group_rows: int = 10_000

    @staticmethod
    def from_settings(settings: dict, kind: str) -> Layout | None:
        """The layout of `kind` in a connector's settings: nested (settings["layout"][kind], as pack.yaml writes it)
        or flattened to dotted keys with comma lists ("layout.trade.columns": "a,b,c", as the pack loader passes it)."""
        nested = (settings.get("layout") or {}).get(kind) if isinstance(settings.get("layout"), dict) else None
        prefix = f"layout.{kind}."
        flat = {k[len(prefix):]: v for k, v in settings.items() if isinstance(k, str) and k.startswith(prefix)}
        spec = {**flat, **(nested or {})}
        if not spec:
            return None
        cols = spec.get("columns") or []
        cols = [c.strip() for c in (cols.split(",") if isinstance(cols, str) else cols) if str(c).strip()]
        return Layout(columns=cols, sort_by=str(spec.get("sort-by", "id")), file_rows=int(spec.get("file-rows", 250_000)),
                      row_group_rows=int(spec.get("row-group-rows", 10_000)))

    @property
    def names(self) -> list[str]:
        return [column_name(p) for p in self.columns]


def layouts_for_domain(domain: str, packs: pathlib.Path = PACKS) -> dict[str, Layout]:
    """Every kind's layout declared by a Delta connector of `domain` in config/packs/*/pack.yaml."""
    import yaml

    out: dict[str, Layout] = {}
    for f in sorted(packs.glob("*/pack.yaml")):
        manifest = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        for conn in (manifest.get("connectors") if isinstance(manifest.get("connectors"), dict) else manifest.get("connector-templates") or {}).values():
            settings = conn.get("settings") or {}
            if conn.get("plugin") != "delta" or settings.get("domain") != domain:
                continue
            for kind in conn.get("kinds") or []:
                lay = Layout.from_settings(settings, kind)
                if lay:
                    out[kind] = lay
    return out


def column_name(path: str) -> str:
    """The physical column of a promoted path: counterparty.id -> counterparty__id."""
    return path.replace(".", "__")


def value_at(doc, path: str):
    for key in path.split("."):
        if not isinstance(doc, dict):
            return None
        doc = doc.get(key)
    return doc


def promote(doc: dict, layout: Layout) -> dict:
    """{column name: the value at its path} for one document (None where the path is missing)."""
    return {column_name(p): value_at(doc, p) for p in layout.columns}


def _number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def infer_types(columns: dict[str, list]) -> dict[str, str]:
    """DOUBLE where every non-null value is a number, else STRING (a column with no values at all is STRING)."""
    out = {}
    for name, values in columns.items():
        present = [v for v in values if v is not None]
        out[name] = DOUBLE if present and all(_number(v) for v in present) else STRING
    return out


def cell(v, kind: str):
    """A value as its column holds it: a float for DOUBLE (None if it is not a number), text for STRING."""
    if v is None:
        return None
    if kind == DOUBLE:
        return float(v) if _number(v) else None
    if isinstance(v, str):
        return v
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"))


def table_types(table_path: str) -> dict[str, str] | None:
    """The column types of an existing table (None if there is no table)."""
    from deltalake import DeltaTable

    if not DeltaTable.is_deltatable(str(table_path)):
        return None
    import pyarrow as pa
    schema = pa.schema(DeltaTable(str(table_path)).schema())
    return {f.name: DOUBLE if pa.types.is_floating(f.type) else STRING for f in schema}


def arrow_table(day: date, ids, docs, columns: dict[str, list], types: dict[str, str]):
    """The rows of one write: id, doc, business_date, then the promoted columns in `columns` order. `ids` and
    `docs` may be lists of str or Arrow arrays; `columns` holds raw values (see cell)."""
    import pyarrow as pa

    data = {"id": ids if isinstance(ids, (pa.Array, pa.ChunkedArray)) else pa.array(ids, pa.string()),
            "doc": docs if isinstance(docs, (pa.Array, pa.ChunkedArray)) else pa.array(docs, pa.string())}
    data["business_date"] = pa.array([day] * len(data["id"]), pa.date32())
    for name, values in columns.items():
        kind = types.get(name, STRING)
        data[name] = pa.array([cell(v, kind) for v in values], pa.float64() if kind == DOUBLE else pa.string())
    return pa.table(data)


def stats_columns(layout: Layout) -> str:
    """The columns a laid-out table keeps min/max statistics for: id, the date and the promoted columns. Not the
    document: its statistics would put two copies of a whole JSON document in the log for every file, and a log of
    years of daily files would grow large and slow to read."""
    return ",".join(["id", "business_date", *[column_name(p) for p in layout.columns]])


def keep_stats_small(table_path: str, layout: Layout, storage_options: dict | None = None) -> bool:
    """Sets delta.dataSkippingStatsColumns on an existing table when it differs; returns True when it changed."""
    from deltalake import DeltaTable

    dt = DeltaTable(str(table_path), storage_options=storage_options or None)
    want = stats_columns(layout)
    if dt.metadata().configuration.get("delta.dataSkippingStatsColumns") == want:
        return False
    dt.alter.set_table_properties({"delta.dataSkippingStatsColumns": want})
    return True


def write_file(table_path: str, table, layout: Layout | None, mode: str, day: date | None = None) -> None:
    """One write of rows already in order, as one Parquet file: mode "append", "overwrite" (the whole table, its
    schema replaced) or "overwrite-partition" (the business date `day` only; new columns merge into the schema)."""
    from deltalake import WriterProperties, write_deltalake

    kw: dict = {"partition_by": ["business_date"], "target_file_size": ONE_FILE}
    if layout:
        kw["writer_properties"] = WriterProperties(max_row_group_size=layout.row_group_rows, compression="SNAPPY")   # deltalake's default
        kw["configuration"] = {"delta.dataSkippingStatsColumns": stats_columns(layout)}   # applies when the table is created
    if mode == "overwrite":
        kw.update(mode="overwrite", schema_mode="overwrite")
    elif mode == "overwrite-partition":
        kw.update(mode="overwrite", schema_mode="merge", predicate=f"business_date = '{day.isoformat()}'")
    else:
        kw.update(mode="append")
    if table.column("doc").nbytes > 1_900_000_000:          # past what one string array's 32-bit offsets address
        import pyarrow as pa
        table = table.set_column(table.schema.get_field_index("doc"), "doc", table.column("doc").cast(pa.large_string()))
    write_deltalake(str(table_path), table.combine_chunks(), **kw)     # one batch: rows keep their order in the file


def sort_key(layout: Layout, types: dict[str, str]):
    """Rows (id, text, promoted) order by id, or by the sort-by column, nulls last, then id."""
    if layout.sort_by == "id":
        return lambda r: r[0]
    name = column_name(layout.sort_by)
    kind = types.get(name, STRING)
    return lambda r: (cell(r[2].get(name), kind) is None, cell(r[2].get(name), kind) or (0.0 if kind == DOUBLE else ""), r[0])


def write_partition(table_path: str, day: date, rows: list[tuple[str, str, dict]], layout: Layout | None, mode: str = "append",
                    promoted: bool = False) -> int:
    """Writes one business date's rows (id, document JSON, document) in `layout`: sorted, cut into files of
    file-rows each, row groups of row-group-rows. mode: "append", "overwrite-partition" (replaces that date) or
    "overwrite" (replaces the whole table). Without a layout: one write of id, doc, business_date, as before.
    promoted: the rows carry promote(document) in place of the document (saves memory on large writes).
    Returns the number of files written."""
    return write_days(table_path, {day: rows}, layout, mode, promoted)


def write_days(table_path: str, days: dict[date, list[tuple[str, str, dict]]], layout: Layout | None, mode: str = "append",
               promoted: bool = False) -> int:
    """write_partition for several business dates in as few commits as the layout allows: the k-th file of every
    date goes in the k-th write (one commit when every date fits in one file). The column types come from the
    table when it exists (and is not being replaced), else from these rows. Returns the number of files written."""
    import pyarrow as pa

    if mode == "overwrite-partition" and len(days) != 1:
        raise ValueError("overwrite-partition replaces one business date")
    names = layout.names if layout else []
    staged = {d: [(r[0], r[1], (r[2] if promoted else promote(r[2], layout)) if layout else {}) for r in rows] for d, rows in days.items()}
    existing = None if mode == "overwrite" else table_types(table_path)
    inferred = infer_types({n: [p[2][n] for rows in staged.values() for p in rows] for n in names})
    types = {n: (existing or {}).get(n, inferred[n]) for n in names}
    size = layout.file_rows if layout else max([1] + [len(r) for r in staged.values()])
    for rows in staged.values():
        rows.sort(key=sort_key(layout, types) if layout else (lambda r: r[0]))
    files, step = 0, 0
    while True:
        parts = [(d, rows[step * size:(step + 1) * size]) for d, rows in sorted(staged.items())]
        parts = [(d, rows) for d, rows in parts if rows or step == 0]
        if not parts:
            return files
        tables = [arrow_table(d, [p[0] for p in rows], [p[1] for p in rows], {n: [p[2][n] for p in rows] for n in names}, types)
                  for d, rows in parts]
        write_file(table_path, pa.concat_tables(tables), layout, mode if step == 0 else "append", parts[0][0])
        files += len(parts)
        step += 1
