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

"""Writes the small, synthetic Iceberg tables drishti-iceberg's interop tests read, with pyiceberg (pure Python, independent
of the Java library), and what pyiceberg reads back from each of their snapshots (expected.json).

    uv run --no-project --with pyiceberg --with pyiceberg-core --with pyarrow --with sqlalchemy python tools/iceberg/fixtures.py OUT

OUT is reached through the link /tmp/drishti-iceberg-fixtures (so the paths inside the tables name no one's home folder);
the tests read the tables with relocate: file:///tmp/drishti-iceberg-fixtures -> <the test resources>. The tables are
committed under plugins/drishti-plugin-iceberg/src/test/resources/iceberg-fixtures; run this only to change them.

Covered: format versions 1 and 2; every primitive type, structs, lists and maps; every partition transform (identity,
bucket, truncate, year, month, day, hour); partition evolution; schema evolution (add, rename, widen int to long, float to
double, decimal precision, drop, reorder); a file without field ids added with a name mapping; several snapshots (time
travel); the external-table shapes of ICEBERG_EXTERNAL_CONNECTOR.md section 12 (a Spark-style table partitioned by day of a
timestamp, a Snowflake-style table with identifier fields, nested structs, decimals and timestamptz, a current-only
reference table). Delete files are written by the Java library in the tests themselves (pyiceberg does not write them).

Exempt from the JSONL rule (docs/guides/DATA_GUIDE.md; tools/test_generators_write_jsonl.py): every other generator writes JSON
Lines and a loader builds the tables, but these fixtures test the Iceberg reader itself, so they must be real Iceberg tables (and a
plain Parquet file) written by an independent library, not rows Drishti's own loaders turned into tables.
"""
import base64
import datetime as dt
import decimal
import json
import os
import shutil
import sys
import uuid
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
from pyiceberg.catalog.sql import SqlCatalog
from pyiceberg.partitioning import PartitionField, PartitionSpec
from pyiceberg.schema import Schema
from pyiceberg.table.sorting import SortField, SortOrder
from pyiceberg.transforms import (BucketTransform, DayTransform, HourTransform, IdentityTransform, MonthTransform,
                                  TruncateTransform, YearTransform)
from pyiceberg.types import (BinaryType, BooleanType, DateType, DecimalType, DoubleType, FixedType, FloatType, IntegerType,
                             ListType, LongType, MapType, NestedField, StringType, StructType, TimestampType,
                             TimestamptzType, TimeType, UUIDType)

LINK = "/tmp/drishti-iceberg-fixtures"
UTC = dt.timezone.utc


def jsonable(v):
    """A value as the tests compare it (ICEBERG_EXTERNAL_CONNECTOR.md section 3, decimals as text for exactness)."""
    if v is None or isinstance(v, (bool, int, str)):
        return v
    if isinstance(v, float):
        return v
    if isinstance(v, decimal.Decimal):
        return str(v)
    if isinstance(v, dt.datetime):
        if v.tzinfo is not None:
            return v.astimezone(UTC).replace(tzinfo=None).isoformat() + "Z"
        return v.isoformat()
    if isinstance(v, (dt.date, dt.time)):
        return v.isoformat()
    if isinstance(v, uuid.UUID):
        return str(v)
    if isinstance(v, (bytes, bytearray)):
        return base64.b64encode(bytes(v)).decode()
    if isinstance(v, dict):
        return {str(k): jsonable(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        if v and all(isinstance(x, tuple) and len(x) == 2 for x in v):
            return {str(k): jsonable(x) for k, x in v}       # a map read as key/value pairs
        return [jsonable(x) for x in v]
    return str(v)


def rows_of(table, snapshot_id):
    t = table.scan(snapshot_id=snapshot_id).to_arrow()
    out = []
    for r in t.to_pylist():
        out.append({k: jsonable(v) for k, v in r.items()})
    return out


def record(expected, root, name, table):
    table = table.refresh()
    snaps = []
    for s in table.metadata.snapshots:
        snaps.append({"id": s.snapshot_id, "timestamp-ms": s.timestamp_ms, "sequence-number": s.sequence_number,
                      "rows": rows_of(table, s.snapshot_id)})
    expected[name] = {"metadata": os.path.relpath(table.metadata_location.replace("file://", ""), root),
                      "format-version": table.metadata.format_version, "snapshots": snaps}


def main(out):
    out = Path(out).resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    if os.path.islink(LINK):
        os.unlink(LINK)
    os.symlink(out, LINK)
    cat = SqlCatalog("fx", uri=f"sqlite:///{out}/catalog.db", warehouse=f"file://{LINK}")
    cat.create_namespace("fx")
    expected = {}
    try:
        build(cat, expected)
    finally:
        os.unlink(LINK)
    (out / "catalog.db").unlink()
    (out / "expected.json").write_text(json.dumps(expected, indent=1, sort_keys=True) + "\n")
    print("written", out)


def build(cat, expected):
    # ---------------------------------------------------------------- v1: unpartitioned, then partitioned by identity
    s = Schema(NestedField(1, "id", LongType(), required=True), NestedField(2, "name", StringType()),
               NestedField(3, "amount", DoubleType()), NestedField(4, "day", DateType()), NestedField(5, "flag", BooleanType()))
    t = cat.create_table("fx.v1_basic", s, properties={"format-version": "1"})
    arrow = pa.Table.from_pylist([
        {"id": 1, "name": "alpha", "amount": 1.5, "day": dt.date(2026, 9, 29), "flag": True},
        {"id": 2, "name": "beta", "amount": None, "day": dt.date(2026, 9, 30), "flag": False},
        {"id": 3, "name": None, "amount": -2.25, "day": None, "flag": None}], schema=t.schema().as_arrow())
    t.append(arrow)
    t.append(pa.Table.from_pylist([{"id": 4, "name": "delta", "amount": 4.0, "day": dt.date(2026, 10, 1), "flag": True}],
                                  schema=t.schema().as_arrow()))
    record(expected, LINK, "v1_basic", t)

    # ---------------------------------------------------------------- v2: every type, nested ones included
    s = Schema(
        NestedField(1, "id", StringType(), required=True), NestedField(2, "i", IntegerType()), NestedField(3, "l", LongType()),
        NestedField(4, "f", FloatType()), NestedField(5, "d", DoubleType()), NestedField(6, "dec", DecimalType(9, 2)),
        NestedField(7, "big", DecimalType(30, 4)), NestedField(8, "day", DateType()), NestedField(9, "at", TimeType()),
        NestedField(10, "ts", TimestampType()), NestedField(11, "tstz", TimestamptzType()), NestedField(12, "u", UUIDType()),
        NestedField(13, "fx", FixedType(4)), NestedField(14, "bin", BinaryType()), NestedField(15, "ok", BooleanType()),
        NestedField(16, "cp", StructType(NestedField(17, "code", StringType()), NestedField(18, "rating", IntegerType()))),
        NestedField(19, "tags", ListType(20, StringType(), element_required=False)),
        NestedField(21, "limits", MapType(22, StringType(), 23, DoubleType(), value_required=False)),
        NestedField(24, "legs", ListType(25, StructType(NestedField(26, "leg", IntegerType()), NestedField(27, "ccy", StringType())),
                                         element_required=False)),
        identifier_field_ids=[1])
    t = cat.create_table("fx.v2_types", s, properties={"format-version": "2"})
    rows = []
    for i in range(1, 6):
        rows.append({"id": f"R-{i:03d}", "i": i * 10, "l": 10_000_000_000 + i, "f": i / 4, "d": i / 3,
                     "dec": decimal.Decimal(f"{i}.{i:02d}"), "big": decimal.Decimal(f"123456789012345678901234.{i:04d}"),
                     "day": dt.date(2026, 9, 25 + i), "at": dt.time(18, i, 30, i * 1000),
                     "ts": dt.datetime(2026, 9, 30, 18, 30, i, i * 10), "tstz": dt.datetime(2026, 9, 30, 18, 30, i, tzinfo=UTC),
                     "u": uuid.UUID(int=i * 0x1000000000001), "fx": bytes([i, i + 1, i + 2, i + 3]), "bin": bytes(range(i)),
                     "ok": i % 2 == 0, "cp": {"code": f"CP-{i}", "rating": i} if i != 3 else None,
                     "tags": [f"t{i}", None, "x"] if i != 2 else [], "limits": {"usd": i * 1.5, "eur": None} if i != 4 else None,
                     "legs": [{"leg": 1, "ccy": "USD"}, {"leg": 2, "ccy": None}] if i % 2 else None})
    rows.append({k: None for k in rows[0]} | {"id": "R-NULL"})
    t.append(pa.Table.from_pylist(rows, schema=t.schema().as_arrow()))
    record(expected, LINK, "v2_types", t)

    # ---------------------------------------------------------------- each transform, a table of its own
    base = Schema(NestedField(1, "id", LongType(), required=True), NestedField(2, "code", StringType()),
                  NestedField(3, "n", IntegerType()), NestedField(4, "ts", TimestampType()), NestedField(5, "day", DateType()),
                  NestedField(6, "amt", DecimalType(10, 2)))
    data = []
    for i in range(1, 25):
        data.append({"id": i, "code": ["alpha", "albert", "beta", "bravo", "charlie"][i % 5] + str(i % 3), "n": i * 7 - 50,
                     "ts": dt.datetime(2024 + i % 3, 1 + i % 12, 1 + i % 27, i % 24, i, 0), "day": dt.date(2025, 1 + i % 12, 1 + i % 28),
                     "amt": decimal.Decimal(i * 37) / 10})
    transforms = {"t_identity": (2, IdentityTransform()), "t_bucket": (1, BucketTransform(4)), "t_bucket_str": (2, BucketTransform(3)),
                  "t_truncate_str": (2, TruncateTransform(2)), "t_truncate_int": (3, TruncateTransform(10)),
                  "t_truncate_dec": (6, TruncateTransform(50)), "t_year": (4, YearTransform()), "t_month": (4, MonthTransform()),
                  "t_day": (4, DayTransform()), "t_hour": (4, HourTransform()), "t_day_of_date": (5, DayTransform()),
                  "t_month_of_date": (5, MonthTransform())}
    for name, (src, tr) in transforms.items():
        spec = PartitionSpec(PartitionField(source_id=src, field_id=1000, transform=tr, name="p"))
        t = cat.create_table(f"fx.{name}", base, partition_spec=spec, properties={"format-version": "2"})
        t.append(pa.Table.from_pylist(data, schema=t.schema().as_arrow()))
        record(expected, LINK, name, t)

    # ---------------------------------------------------------------- partition evolution: day(ts), then identity(code) too
    spec = PartitionSpec(PartitionField(source_id=4, field_id=1000, transform=DayTransform(), name="ts_day"))
    t = cat.create_table("fx.partition_evolution", base, partition_spec=spec, properties={"format-version": "2"})
    t.append(pa.Table.from_pylist(data[:12], schema=t.schema().as_arrow()))
    with t.update_spec() as u:
        u.add_identity("code")
    t.append(pa.Table.from_pylist(data[12:], schema=t.schema().as_arrow()))
    record(expected, LINK, "partition_evolution", t)

    # ---------------------------------------------------------------- schema evolution, one change a snapshot
    s = Schema(NestedField(1, "id", StringType(), required=True), NestedField(2, "qty", IntegerType()),
               NestedField(3, "px", FloatType()), NestedField(4, "notional", DecimalType(9, 2)), NestedField(5, "old", StringType()),
               NestedField(6, "book", StringType()))
    t = cat.create_table("fx.schema_evolution", s, properties={"format-version": "2"})
    t.append(pa.Table.from_pylist([{"id": "A", "qty": 1, "px": 1.25, "notional": decimal.Decimal("10.50"), "old": "o1", "book": "B1"},
                                   {"id": "B", "qty": 2, "px": 2.5, "notional": decimal.Decimal("20.25"), "old": "o2", "book": "B2"}],
                                  schema=t.schema().as_arrow()))
    with t.update_schema() as u:
        u.add_column("desk", StringType())
    with t.update_schema() as u:
        u.rename_column("book", "portfolio")
    with t.update_schema() as u:
        u.update_column("qty", LongType())
        u.update_column("px", DoubleType())
        u.update_column("notional", DecimalType(18, 2))
    with t.update_schema() as u:
        u.delete_column("old")
    with t.update_schema() as u:
        u.move_first("desk")
    t.append(pa.Table.from_pylist([{"id": "C", "qty": 30_000_000_000, "px": 3.75, "notional": decimal.Decimal("1234567890123.45"),
                                    "portfolio": "B3", "desk": "D1"}], schema=t.schema().as_arrow()))
    record(expected, LINK, "schema_evolution", t)

    # ---------------------------------------------------------------- a file without field ids, through the name mapping
    s = Schema(NestedField(1, "id", StringType(), required=True), NestedField(2, "qty", LongType()),
               NestedField(3, "cp", StructType(NestedField(4, "code", StringType()))))
    t = cat.create_table("fx.name_mapping", s, properties={"format-version": "2"})
    plain = pa.Table.from_pylist([{"id": "N1", "qty": 5, "cp": {"code": "X"}}, {"id": "N2", "qty": 6, "cp": None}],
                                 schema=pa.schema([pa.field("id", pa.string(), nullable=False), pa.field("qty", pa.int64()),
                                                   pa.field("cp", pa.struct([("code", pa.string())]))]))
    folder = Path(LINK) / "fx.db" / "name_mapping" / "data"
    folder.mkdir(parents=True, exist_ok=True)
    pq.write_table(plain, folder / "plain-no-ids.parquet")
    t.add_files([f"file://{folder / 'plain-no-ids.parquet'}"])
    record(expected, LINK, "name_mapping", t)

    # ---------------------------------------------------------------- the external-table shapes (section 15.5)
    s = Schema(NestedField(1, "TRADE_ID", StringType(), required=True), NestedField(2, "SOURCE_SYSTEM", StringType()),
               NestedField(3, "EVENT_TS", TimestampType()), NestedField(4, "BOOK", StringType()),
               NestedField(5, "COUNTERPARTY", StringType()), NestedField(6, "MTM", DoubleType()), NestedField(7, "_FILE", StringType()))
    spec = PartitionSpec(PartitionField(source_id=3, field_id=1000, transform=DayTransform(), name="event_day"))
    t = cat.create_table("fx.spark_trades", s, partition_spec=spec, properties={"format-version": "2", "write.delete.mode": "merge-on-read"})
    trades = []
    for d in (28, 29, 30):
        for i in [7, 3, 11, 1, 9, 5]:                     # not sorted by id
            trades.append({"TRADE_ID": f"T-{i:03d}", "SOURCE_SYSTEM": "MUREX" if i % 2 else "CALYPSO",
                           "EVENT_TS": dt.datetime(2026, 9, d, 8 + i, 15), "BOOK": f"BOOK-{i % 3}", "COUNTERPARTY": f"CP-{i % 4}",
                           "MTM": round(1000.5 * i - 7 * d, 2), "_FILE": f"in-{d}.csv"})
    t.append(pa.Table.from_pylist(trades[:12], schema=t.schema().as_arrow()))
    t.append(pa.Table.from_pylist(trades[12:], schema=t.schema().as_arrow()))
    record(expected, LINK, "spark_trades", t)

    s = Schema(NestedField(1, "CPTY_ID", StringType(), required=True), NestedField(2, "AS_OF", DateType(), required=True),
               NestedField(3, "NAME", StringType()), NestedField(4, "LIMIT_AMT", DecimalType(18, 2)),
               NestedField(5, "UPDATED_AT", TimestamptzType()),
               NestedField(6, "ADDRESS", StructType(NestedField(7, "CITY", StringType()), NestedField(8, "COUNTRY", StringType()))),
               NestedField(9, "RATINGS", MapType(10, StringType(), 11, StringType(), value_required=False)),
               identifier_field_ids=[1, 2])
    spec = PartitionSpec(PartitionField(source_id=2, field_id=1000, transform=IdentityTransform(), name="AS_OF"))
    t = cat.create_table("fx.snowflake_counterparties", s, partition_spec=spec,
                         sort_order=SortOrder(SortField(source_id=1, transform=IdentityTransform())), properties={"format-version": "2"})
    cps = []
    for d in (29, 30):
        for i in range(4):
            cps.append({"CPTY_ID": f"CP-{i}", "AS_OF": dt.date(2026, 9, d), "NAME": f"Party {i}",
                        "LIMIT_AMT": decimal.Decimal(f"{(i + 1) * 1000000}.{d}"), "UPDATED_AT": dt.datetime(2026, 9, d, 17, i, tzinfo=UTC),
                        "ADDRESS": {"CITY": ["London", "Paris", "New York", "Tokyo"][i], "COUNTRY": ["GB", "FR", "US", "JP"][i]},
                        "RATINGS": {"SP": "A" + "+" * i, "MOODY": None}})
    t.append(pa.Table.from_pylist(cps, schema=t.schema().as_arrow()))
    record(expected, LINK, "snowflake_counterparties", t)

    s = Schema(NestedField(1, "CCY", StringType(), required=True), NestedField(2, "NAME", StringType()),
               NestedField(3, "DECIMALS", IntegerType()), identifier_field_ids=[1])
    t = cat.create_table("fx.ref_currencies", s, properties={"format-version": "2"})
    t.append(pa.Table.from_pylist([{"CCY": c, "NAME": n, "DECIMALS": d} for c, n, d in
                                   [("USD", "US dollar", 2), ("JPY", "Yen", 0), ("EUR", "Euro", 2), ("GBP", "Pound sterling", 2)]],
                                  schema=t.schema().as_arrow()))
    record(expected, LINK, "ref_currencies", t)


if __name__ == "__main__":
    main(sys.argv[1])
