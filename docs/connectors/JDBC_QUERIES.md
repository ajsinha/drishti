<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Connector queries: reading your own database with your own SQL

The `jdbc` connector reads a database in one of two ways. **Table mode** reads a table laid out for Drishti and writes
its own SQL ([POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md)). **Query mode**, the subject of this document, reads *your*
schema, as it is, with SQL you write: no table to reshape, no data to copy. A kind can have several queries, each with
one job: the entity itself, more parts of it from other tables, the ids for type-ahead, a day's fields for searches,
and the entities that reference another. This document explains each query, how they run, how their results become
documents, how they make a large book fast, and how to write them well.

## Contents

1. [The idea](#1-the-idea)
2. [Where the queries live](#2-where-the-queries-live)
3. [Parameters](#3-parameters)
4. [The entity: `query.<kind>`](#4-the-entity-querykind)
5. [More of the entity: `query.<kind>.<part>`](#5-more-of-the-entity-querykindpart)
6. [Type-ahead: `ids.<kind>`](#6-type-ahead-idskind)
7. [Searches and aggregates: `columns.<kind>`](#7-searches-and-aggregates-columnskind)
8. [Reverse lookups: `reverse.<kind>`](#8-reverse-lookups-reversekind)
9. [How columns become fields](#9-how-columns-become-fields)
10. [A complete example](#10-a-complete-example)
11. [Performance at scale](#11-performance-at-scale)
12. [Writing good queries](#12-writing-good-queries)
13. [Diagnosing](#13-diagnosing)
14. [Settings](#14-settings)
15. [Query mode or table mode?](#15-query-mode-or-table-mode)

---

## 1. The idea

Your trades live in your tables: `trades`, `trade_legs`, `cashflows`, `counterparties`. Drishti needs, for a kind:

| What Drishti needs | When | Query |
|---|---|---|
| one entity, as a document | a view opens (`TRD MX-20000001`) | `query.trade` |
| the rest of that entity, from other tables | the same moment | `query.trade.legs`, `query.trade.cashflows`, … |
| every id, for suggestions as you type | in the background, every minute | `ids.trade` |
| a few fields of every entity of a day | searches, pick lists, desk P&L, impact | `columns.trade` |
| which entities reference another | *Linked entities*, impact (F8) | `reverse.trade` |

Only `query.<kind>` is required. Each other query adds a capability, and each one is a plain `SELECT` your database
answers with its own indexes.

## 2. Where the queries live

In the connector's `settings`, either in a pack's `pack.yaml` (the pack owns its database) or in site configuration
(`application.local.yaml`, the operator owns it). A connector serves as many kinds as it has `query.<kind>` keys:

```yaml
connectors:
  trading-db:
    plugin: jdbc
    settings:
      url: ${DRISHTI_TRADES_URL:jdbc:postgresql://db:5432/trades}
      user: ${DRISHTI_TRADES_USER:drishti}
      password: ${DRISHTI_TRADES_PASSWORD}
      pool-size: 8
      query.trade: >
        SELECT … FROM trades WHERE trade_id = :id AND business_date = :asOf
      query.trade.legs: SELECT … FROM trade_legs WHERE trade_id = :id ORDER BY leg_no
      ids.trade: SELECT trade_id FROM trades WHERE business_date = :asOf
      columns.trade: SELECT trade_id, mtm, notional, book, netting_set FROM trades WHERE business_date = :asOf
      reverse.trade: SELECT trade_id FROM trades WHERE netting_set = :target AND business_date = :asOf
      query.counterparty: SELECT * FROM counterparties WHERE id = :id
routes:
  trade: trading-db
  counterparty: trading-db
```

YAML's `>` folds a long query onto several lines. Keep passwords in the environment.

## 3. Parameters

| Parameter | Value | Used by |
|---|---|---|
| `:id` | the entity's id (`MX-20000001`) | `query.<kind>`, `query.<kind>.<part>` |
| `:asOf` | the business date asked (a SQL `DATE`); today in `zone` (`America/New_York`) when none is asked | any query |
| `:target` | the id being referenced (`NS-SUMMIT-NY`) | `reverse.<kind>` |

Parameters are bound, never pasted into the SQL, so an id cannot change a query. A query may use a parameter several
times (`:id` twice in a sub-select is common). A query without named parameters may use `?` instead: in an entity
query each `?` is the id, in `ids` and `columns` queries the date, in a `reverse` query the target.

A query that uses `:asOf` makes the kind **dated**: picking an earlier business date in the top bar reads that date.

## 4. The entity: `query.<kind>`

The first row is the document:

- each column is a field, named from its label ([section 9](#9-how-columns-become-fields));
- a column named `json` is the whole document (JSON text), for a table that keeps documents;
- a column named `generation` (a number) is the version Drishti uses to tell an older read from a newer one;
- a column named `business_date` is the date the row is for (shown in provenance);
- a `json`/`jsonb` column, or one named in `json-columns`, becomes nested data.

A snapshot table (a row per entity per day) is read with the date itself; a table that keeps a row only when an entity
changes reads its latest row on or before the date:

```sql
-- every entity every day
SELECT * FROM trades WHERE trade_id = :id AND business_date = :asOf
-- a row when it changes
SELECT * FROM trades WHERE trade_id = :id
   AND business_date = (SELECT MAX(business_date) FROM trades WHERE trade_id = :id AND business_date <= :asOf)
```

No row means the source does not hold the entity on that date; Drishti asks the next source for the kind.

## 5. More of the entity: `query.<kind>.<part>`

An entity often spans tables: a swap's legs, a trade's cashflows, its counterparty's legal name. Rather than one join
that repeats the trade's columns on every leg and cashflow row, give each part its own query:

```yaml
query.trade.legs: SELECT leg_no, pay_receive, index_name, spread, fixed_rate FROM trade_legs WHERE trade_id = :id ORDER BY leg_no
query.trade.cashflows: SELECT pay_date, amount, currency FROM cashflows WHERE trade_id = :id AND pay_date >= :asOf ORDER BY pay_date
query.trade.counterparty: >
  SELECT c.id, c.legal_name, c.rating FROM counterparties c JOIN trades t ON t.counterparty_id = c.id
   WHERE t.trade_id = :id AND t.business_date = :asOf
part-shape.trade.counterparty: object
```

The document gains a field per part, named after it:

```json
{"tradeId": "MX-20000001", "notional": 100000000, "mtm": 1600000, "nettingSet": "NS-A",
 "legs": [{"legNo": 1, "payReceive": "PAY", "fixedRate": 3.25}, {"legNo": 2, "payReceive": "RECEIVE", "indexName": "SOFR"}],
 "cashflows": [{"payDate": "2026-12-15", "amount": 812500, "currency": "USD"}, …],
 "counterparty": {"id": "CP-1", "legalName": "Meridian Reinsurance Ltd", "rating": "A+"}}
```

- A part is a **list** of row objects (empty when it has no rows); `part-shape.<kind>.<part>: object` takes its first
  row as an object (null when none).
- **Parts run at once** with the entity's query, each on its own pooled connection, so a trade of three parts costs
  about one round trip, not four. Make `pool-size` at least one more than the most parts a kind has, times the views
  you expect at once.
- A part's columns follow the same naming and JSON rules as the entity's.
- A part replaces a field of the same name from the entity's query.
- **A part that fails fails the view**, with an error that names it: `DRS-1003 qa-db failed reading trade/MX-1: trade
  MX-1 cannot be read: its query query.trade.legs failed (SQL state 42703); Admin → Health and the server log say
  more`. The SQL and the database's own message are not shown to the reader (they can reveal the schema or values);
  the server log has them, and Health shows `DEGRADED: query.trade.legs failed: SQL state 42703: <message>` until the
  query runs again. The entity's own query (`query.trade`) is named the same way. A document without one of its parts
  is never shown as if it were whole.

Sutras address parts like any nested data: `legs[0].fixedRate`, a table panel over `cashflows`, a `kv` of
`counterparty.legalName`.

## 6. Type-ahead: `ids.<kind>`

Without it, a kind has no suggestions as you type. With it, the connector reads the ids at start and every
`refresh-seconds` (60) into Drishti's in-memory type-ahead index, so a keystroke never reaches the database:

```yaml
ids.trade: SELECT trade_id FROM trades WHERE business_date = (SELECT MAX(business_date) FROM trades)
ids.counterparty: SELECT id, legal_name FROM counterparties          # a second column is the title shown
```

`:asOf` is today. The index holds a million ids comfortably (sorted, searched by prefix); keep the query to one day's
ids, not every row of history.

## 7. Searches and aggregates: `columns.<kind>`

Searches (`TRD where mtm < -50m`), pick lists (`TRD book=BOOK-RATES-3`), derived kinds (desk P&L) and impact (F8) need
a few fields of **every** entity of a day. Without `columns.<kind>` they read documents one by one, at most 20,000
(`partial`). With it, one query returns the day:

```yaml
columns.trade: >
  SELECT trade_id, product_type, direction, currency, notional, mtm, pnl_1d, maturity_date, book, desk,
         counterparty_id, netting_set, dv01
    FROM trades WHERE business_date = :asOf
layout.trade.columns: productType, direction, currency, notional, mtm, pnl1d, maturityDate, book, desk, counterparty.id, nettingSet, risk.dv01
```

- **The first column is the id**; every other column is a field.
- **Which field a column is**: when the pack declares `layout.<kind>.columns` (usually it does), a column matches a
  declared path by name, ignoring case, dots and underscores: `counterparty_id` is `counterparty.id`, `dv01` would not
  match `risk.dv01` (alias it: `dv01 AS risk_dv01`). Without a declaration, the column's label is the path
  (`counterparty__id` becomes `counterparty.id`).
- **Numbers** (numeric SQL types) are kept as numbers; everything else as text, repeated values sharing one copy.
- The result is kept per kind and day by memory (`columns-cache-mb`, 1024) for `columns-seconds` (300).
- A day with no rows is a day the database does not hold: Drishti asks the next source for the kind (history in Delta
  Lake, for example).
- A search is answered from columns only when every field it reads is one of these columns; otherwise it reads
  documents.
- **One row per entity.** An id the query returns more than once (a join with a child table: trades × legs) is
  counted once, with its first row, and the connector says so in the server log and in Health:
  `UP (columns.trade returned 11,866 rows for 8,000 entities on 2026-09-30: 3,866 ids more than once (MX-30000001,
  …); each is counted once, with its first row. Return one row per entity …)`. Fix the query: an inner join also
  drops the entities without children (trades without legs), which nothing can detect.

At a million rows a day the result is about 230 MB in memory for 19 fields, read in seconds by a database with an index
on `business_date`; see [section 11](#11-performance-at-scale).

## 8. Reverse lookups: `reverse.<kind>`

"Which trades reference netting set `NS-SUMMIT-NY`?" The *Linked entities* panel and impact analysis ask it of every
kind. A `reverse.<kind>` query answers for its kind, with `:target`:

```yaml
reverse.trade: >
  SELECT trade_id FROM trades WHERE business_date = :asOf
     AND (netting_set = :target OR counterparty_id = :target OR book = :target)
```

Without it, a kind with `columns.<kind>` is answered from the day's columns (every text field equal to the target),
which needs no SQL at all. `reverse-index: false` turns reverse lookups off.

## 9. How columns become fields

| Column label | Field |
|---|---|
| `trade_id`, `TRADE_ID` | `tradeId` |
| `mtm`, `notional` | unchanged |
| `counterparty__id` | `counterparty__id` (a double underscore is kept; in `columns.<kind>` it reads as `counterparty.id`) |
| `"legalName"` (quoted) | `legalName` |
| `json` | the whole document |

Values: decimals become numbers (whole numbers as integers), dates `yyyy-MM-dd`, timestamps ISO instants, JSON columns
nested data; a JSON value that does not parse stays text, so one bad cell never fails a view.

## 10. A complete example

A trading schema in PostgreSQL:

```sql
CREATE TABLE trades (trade_id text, business_date date, product_type text, notional numeric, mtm numeric,
                     book text, netting_set text, counterparty_id text, PRIMARY KEY (trade_id, business_date));
CREATE INDEX ON trades (business_date);                     -- a day's ids and columns
CREATE INDEX ON trades (netting_set, business_date);        -- reverse lookups by netting set
CREATE TABLE trade_legs (trade_id text, leg_no int, pay_receive text, fixed_rate numeric, index_name text);
CREATE INDEX ON trade_legs (trade_id);
CREATE TABLE counterparties (id text PRIMARY KEY, legal_name text, rating text);
```

The connector:

```yaml
connectors:
  trading-db:
    plugin: jdbc
    settings:
      url: ${DRISHTI_TRADES_URL}
      pool-size: 12
      query.trade: SELECT * FROM trades WHERE trade_id = :id AND business_date = :asOf
      query.trade.legs: SELECT leg_no, pay_receive, fixed_rate, index_name FROM trade_legs WHERE trade_id = :id ORDER BY leg_no
      query.trade.counterparty: >
        SELECT c.* FROM counterparties c JOIN trades t ON t.counterparty_id = c.id WHERE t.trade_id = :id AND t.business_date = :asOf
      part-shape.trade.counterparty: object
      ids.trade: SELECT trade_id FROM trades WHERE business_date = (SELECT MAX(business_date) FROM trades)
      columns.trade: SELECT trade_id, product_type, notional, mtm, book, netting_set, counterparty_id FROM trades WHERE business_date = :asOf
      layout.trade.columns: productType, notional, mtm, book, nettingSet, counterparty.id
      reverse.trade: SELECT trade_id FROM trades WHERE netting_set = :target AND business_date = :asOf
      query.counterparty: SELECT * FROM counterparties WHERE id = :id
      ids.counterparty: SELECT id, legal_name FROM counterparties
routes:
  trade: trading-db
  counterparty: trading-db
```

What each question costs the database:

| Question | Queries | Typical cost |
|---|---|---|
| open `TRD MX-20000001` | `query.trade` + 2 parts, at once | three primary-key or index lookups |
| type `TRD MX-2000` | none (memory) | — |
| `TRD where mtm < -50m` on a day | `columns.trade` once per day, then memory | one index range scan of the day |
| desk P&L, impact, pick lists | the same day's columns | none after the first |
| *Linked entities* of a netting set | `reverse.trade` | one index lookup |

The connector's tests (`QueryModeTest`) run this shape against an in-memory database.

## 11. Performance at scale

- **The entity and its parts** are point lookups: index them by id (and date). Parts run in parallel, so latency is
  the slowest query, not the sum.
- **`ids.<kind>`** runs every minute: one day's ids, from an index on the date (an index-only scan where the database
  supports it).
- **`columns.<kind>`** is the one large read: a day of rows, a few narrow columns. Give the table an index starting
  with the date, keep the selected columns narrow (no documents, no large text), and let the result stream (the
  connector reads with a fetch size of 20,000). A million rows of 13 columns typically read in a few seconds and are
  then served from memory for `columns-seconds`.
- **`reverse.<kind>`** runs per question: index the link columns with the date.
- **The pool** (`pool-size`) bounds concurrent queries: parts of open views, the columns of a day, reverse lookups.
  Too small and views wait for a connection (5 s, then an error); too large and the database carries idle sessions.
- **For a kind of millions a day kept for years**, consider table mode ([POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md)):
  it partitions by month and needs no queries.

## 12. Writing good queries

- **Select columns, not `*`,** in `columns.<kind>` and parts: every column is read for every row.
- **One row per entity** in `columns.<kind>` and `ids.<kind>`; a join that multiplies rows (trades × legs) repeats
  ids (the connector counts each once and warns, [section 7](#7-searches-and-aggregates-columnskind)) and an inner
  join drops entities without children. Aggregate in SQL if you must join.
- **Keep business logic out**: fields as stored, so the Sutra and the pack decide how to show them.
- **Name columns as fields**: alias to the pack's paths (`dv01 AS risk_dv01`) rather than renaming in Sutras.
- **Bound reverse queries by date**: without `business_date = :asOf` a reverse lookup reads every day of history.
- **Test the SQL in your database's client first**, with the parameters filled in.

## 13. Diagnosing

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: <driver message> (reconnecting)` | the database is unreachable or the credentials are wrong | check `url`, `user`, `password`; connections reopen by themselves |
| a view fails with `… its query query.<kind>.<part> failed (SQL state …)`, Health `DEGRADED: query.<kind>.<part> failed: …` | that query does not run as written | Health and the server log have the database's message; run the query in a client with the parameters filled in |
| no suggestions for a kind | no `ids.<kind>`, or it failed | add it; check the server log |
| searches say `partial: true` | no `columns.<kind>`, a field the search reads is not a column, or a column did not match a declared path | add the column, alias it to the path |
| a part is missing from the document | its query returned no rows (a list part is `[]`, an object part `null`) | check the join and the date |
| views wait, then `no free connection in … pool` | `pool-size` too small for parts and concurrent views | raise it |
| Health `UP (columns.<kind> returned N rows for M entities …)` | `columns.<kind>` returns an entity more than once (a join); each is counted once, with its first row | make it one row per entity |
| entities missing from searches | `columns.<kind>` joins a child table with an inner join | select from the entity's table alone, or use a left join |

`GET /api/v1/admin/health` lists `kinds`, `parts`, `ids` and `columnSets` for the connector.

## 14. Settings

| Setting | Default | Meaning |
|---|---|---|
| `url`, `user`, `password` | empty | the connection; the driver comes with the server for PostgreSQL, otherwise on the class path |
| `pool-size` | `4` | connections kept |
| `query.<kind>` | — | the entity |
| `query.<kind>.<part>` | none | a part of the entity, as the field `<part>` |
| `part-shape.<kind>.<part>` | `list` | `object` takes the first row |
| `ids.<kind>` | none | ids (and a title) for type-ahead |
| `columns.<kind>` | none | a day's id and promoted fields |
| `layout.<kind>.columns` | none | the paths `columns.<kind>` provides (usually declared by the pack) |
| `reverse.<kind>` | none | ids referencing `:target` |
| `json-columns` | none | text columns holding JSON |
| `zone` | `America/New_York` | the business day's zone, for "today" |
| `refresh-seconds` | `60` | how often `ids.<kind>` runs |
| `columns-cache-mb`, `columns-seconds` | `1024`, `300` | memory for days of columns, and how long a day is kept |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | `jdbc` | the name shown in provenance and Health |

## 15. Query mode or table mode?

| | Query mode | Table mode |
|---|---|---|
| your schema | read as it is | loaded into Drishti's layout |
| SQL | yours, per kind | none |
| several tables per entity | parts | one document per row |
| type-ahead, searches, reverse | with `ids`, `columns`, `reverse` queries | built in |
| history for years | your tables' design | monthly partitions, retention by dropping months |
| best for | an existing database you read, kinds of up to hundreds of thousands a day | a store loaded for Drishti, a million entities a day |

---

## Appendix: walk-through and worked examples

Moved here from the former connector guides, so that everything about this connector is in one document.

### Walk-through, step by step

A kind can have several queries: the entity, its parts from other tables (`query.<kind>.<part>`), its ids for
type-ahead (`ids.<kind>`), a day's fields for searches (`columns.<kind>`) and its reverse lookups (`reverse.<kind>`).
[JDBC_QUERIES.md](JDBC_QUERIES.md) explains each in full; this chapter starts with the first.

#### The situation

Your trades live in a PostgreSQL table `desk.trades`, one row per trade per business date. You want `TRD <id>` to
read them, with history, without changing the schema.

#### The data

```sql
CREATE SCHEMA IF NOT EXISTS desk;
CREATE TABLE desk.trades (
  trade_id      text           NOT NULL,
  business_date date           NOT NULL,
  product       text,
  counterparty  text,
  notional      numeric(18,0),
  mtm           numeric(18,2),
  currency      text,
  PRIMARY KEY (trade_id, business_date)
);
INSERT INTO desk.trades VALUES
  ('MX-21770001', '2026-09-29', 'IRS', 'CP-ALDERSHOT', 50000000, 412300.00, 'USD'),
  ('MX-21770001', '2026-09-30', 'IRS', 'CP-ALDERSHOT', 50000000, 398150.00, 'USD');
```

#### Configure it

You write one SQL statement per kind under `query.<kind>`. Use `:id` for the entity id and `:asOf` for the business
date (a SQL `DATE`); each may appear several times. (A statement with a single `?` also works, bound to the id.)
A connector whose statement uses `:asOf` is **dated**: picked dates are passed to it, and on Live it receives the
current date.

**Site form:**

```yaml
drishti:
  sources:
    connectors:
      desk-db:
        plugin: jdbc
        kinds: [trade]                                       # optional: the query.* kinds are what it serves anyway
        settings:
          url: ${DESK_DB_URL:jdbc:postgresql://db.bank.example:5432/desk}
          user: ${DESK_DB_USER:}                             # credentials from the environment, never in the file
          password: ${DESK_DB_PASSWORD:}
          pool-size: 4                                       # connections, each opened on first use
          # the newest row on or before the date asked
          query.trade: >-
            SELECT trade_id, business_date, product, counterparty, notional, mtm, currency
            FROM desk.trades
            WHERE trade_id = :id
              AND business_date = (SELECT MAX(business_date) FROM desk.trades
                                   WHERE trade_id = :id AND business_date <= :asOf)
```

**Pack form** (the statement on one line, or as a YAML block scalar, as above):

```yaml
connectors:
  desk-db:
    plugin: jdbc
    kinds: [trade]
    settings:
      url: ${DESK_DB_URL:jdbc:postgresql://localhost:5432/drishti}
      user: ${DESK_DB_USER:drishti}
      password: ${DESK_DB_PASSWORD:drishti}
      pool-size: 4
      query.trade: >-
        SELECT trade_id, business_date, product, counterparty, notional, mtm, currency FROM desk.trades
        WHERE trade_id = :id AND business_date = (SELECT MAX(business_date) FROM desk.trades
        WHERE trade_id = :id AND business_date <= :asOf)
```

**How rows become documents.** The first row is the document. Each column becomes a field in camel case
(`trade_id` → `tradeId`); a `NUMERIC` value without decimals becomes an integer, others a decimal number; `DATE`
becomes `2026-09-30`, `TIMESTAMP` an ISO instant. Three column names are special:

| Column | Effect |
|---|---|
| `json` | its text is the whole document (`SELECT doc::text AS json FROM …`); other columns are ignored for the content |
| `generation` (a number) | the version shown in provenance; otherwise the read time |
| `business_date` (a `DATE`) | the date the row is for (provenance), also added as `businessDate` |

**JSON inside a row.** A column of type `json` or `jsonb` becomes nested data under its field name, so a trade with
its legs in one column reads as `legs[0].rate` in a Sutra. JSON kept in a text column needs naming:

```yaml
settings:
  query.trade: SELECT trade_id, notional, legs, extras FROM desk.trades WHERE trade_id = :id
  json-columns: extras            # a TEXT/VARCHAR column holding JSON; `legs` is jsonb and needs no listing
```

A cell that is not valid JSON stays as text, so one bad row never fails the view.

A statement for documents already stored as JSON:

```yaml
query.counterparty: SELECT doc::text AS json FROM desk.counterparties WHERE cpty_id = ?
```

The PostgreSQL driver ships with the server. For another database (Oracle, SQL Server, …) put its driver jar on the
class path or in the plugin folder (`DRISHTI_PLUGIN_DIR`).

#### Try it

```bash
docker compose -f deploy/compose.data.yaml up -d postgres          # user, password and database: drishti
docker compose -f deploy/compose.data.yaml exec -T postgres psql -U drishti -d drishti < desk.sql   # the SQL above
DESK_DB_URL=jdbc:postgresql://localhost:5432/drishti DESK_DB_USER=drishti DESK_DB_PASSWORD=drishti \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

```bash
curl -s "http://localhost:18480/api/v1/entities/trade/MX-21770001/raw?asOf=2026-09-30" | jq -c '{provenance, data}'
```

You should see (real output; the generation is the read time):

```json
{"provenance":{"source":"desk-db","generation":1790828309088,"fetchedAt":"2026-10-01T04:18:29.093601107Z","live":false,"businessDate":"2026-09-30"},
 "data":{"tradeId":"MX-21770001","businessDate":"2026-09-30","product":"IRS","counterparty":"CP-ALDERSHOT",
         "notional":50000000,"mtm":398150.0,"currency":"USD"}}
```

#### In the terminal

`TRD MX-21770001 <GO>`. Pick 29 September: `mtm` is `412,300`. The `counterparty` value `CP-ALDERSHOT` is a link
(Drishti recognises the id), so the counterparty opens with a click. The type-ahead does not offer `MX-21770001` (query
mode cannot search); users type the id.

#### Health, and when the database goes down

`health` is `UP`, `DOWN: not started`, or `DOWN: <driver message> (reconnecting)` after a failed read. The
connector starts even when the database is down: each pooled connection is opened on first use and reopened when
broken. While the database is down, reads fail with `DRS-1003 desk-db failed reading trade/MX-21770001` and health reads
(real output):

```text
DOWN: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections. (reconnecting)
```

`reads.lastError` in admin health carries the same message with the exception's name (`PSQLException: …`). When the
database is back, the next read reconnects and health returns to `UP`.

#### Common errors

| You see | Cause | Fix |
|---|---|---|
| the connector is missing from `/sources` (and not under `failures`); the log says `source plugin desk-db is installed but not configured (jdbc needs settings.url); it stays idle` | `url` empty (an unset variable with an empty default) | export the variable, or give a default |
| `DRS-1003 … failed reading`, health `DOWN: <driver message> (reconnecting)`, `reads.lastError` `PSQLException: …` | SQL error, wrong credentials, unreachable host | run the statement in `psql` with the id and date substituted |
| `No suitable driver` | the database's driver is not on the class path | put the jar in `DRISHTI_PLUGIN_DIR` |
| a picked date shows the latest data | the statement does not use `:asOf` | add the `business_date <= :asOf` condition |
| fields named `TRADE_ID` | — | they are converted: `TRADE_ID` and `trade_id` both become `tradeId` |
