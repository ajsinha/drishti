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

"""Loads a data domain into PostgreSQL for the JDBC connector's table mode: schema `<domain>`, table `entities`
(kind, id, business_date, doc jsonb), with the same business-day history as the Delta Lake. Idempotent: the
domain's table is recreated.

    uv run --with "psycopg[binary]" python tools/packgen/banking/make_data.py --postgres postgresql://drishti:drishti@localhost:5432/drishti

Indexes: the primary key (kind, id, business_date) serves dated reads; (kind, business_date) serves snapshot
dates; a GIN index on doc (jsonb_path_ops) serves reverse lookups.
"""
from __future__ import annotations

import re
from datetime import date

from samplegen.dates import Calendar
from samplegen.lake import final_rows

def write_domain(url: str, domain: str, kinds: dict[str, dict[str, dict]], end: date, days: int, cal: Calendar) -> int:
    import psycopg

    schema = domain.replace("-", "_")
    if not re.fullmatch(r"[a-z][a-z0-9_]*", schema):
        raise ValueError(f"bad domain name {domain!r}")
    rows = list(final_rows(kinds, end, days, cal))      # the latest knowledge, as the lake ends up after its correction
    with psycopg.connect(url, autocommit=False) as conn, conn.cursor() as cur:
        cur.execute(f"CREATE SCHEMA IF NOT EXISTS {schema}")
        cur.execute(f"DROP TABLE IF EXISTS {schema}.entities")
        cur.execute(f"""CREATE TABLE {schema}.entities (kind text NOT NULL, id text NOT NULL, business_date date NOT NULL,
                        doc jsonb NOT NULL, PRIMARY KEY (kind, id, business_date))""")
        with cur.copy(f"COPY {schema}.entities (kind, id, business_date, doc) FROM STDIN") as copy:
            for kind, id_, d, body in rows:
                copy.write_row((kind, id_, d, body))
        cur.execute(f"CREATE INDEX ON {schema}.entities (kind, business_date)")
        cur.execute(f"CREATE INDEX ON {schema}.entities USING gin (doc jsonb_path_ops)")
        cur.execute(f"ANALYZE {schema}.entities")
        conn.commit()
    print(f"postgres {schema}.entities: {len(rows)} rows ({len(kinds)} kinds)")
    return len(rows)
