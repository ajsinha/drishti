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
# Drishti SQL: read-only SQL over models

Drishti SQL (DSQL) is SQL whose **tables are the aliases of one model**, whose **measures are `MEASURE('name')`**, and which compiles into **the same
logical plan as a model query**: the same question asked in SQL, as a model query or by a report visual has one plan hash, one answer and one cache
entry per security signature. It is never sent to a connector as text, and it can never see more than the reader may: every name is resolved as the
reader sees the model (a hidden column does not exist), row rules and masks apply before anything is computed. Release 2.2.0 (wave A).

```sql
-- the model is named by the request (or the item), never inside the text
SELECT counterparties.counterpartyName AS counterparty,
       MEASURE('MTM')                  AS mtm,
       MEASURE('PFE peak')             AS pfe,
       SUM(trades.notional)            AS notional_local      -- an inline aggregate: a local measure
FROM   trades
JOIN   counterparties                                         -- no ON: the model's relationship is used
WHERE  trades.currency IN ('USD', 'EUR')
  AND  trades.tradeDate >= DATE '2026-10-01'
GROUP  BY counterparties.counterpartyName
HAVING MEASURE('MTM') < 0
ORDER  BY pfe DESC
LIMIT  20
```

Contents: [1](#1-where-you-use-it) where · [2](#2-the-dialect) the dialect · [3](#3-how-a-statement-compiles) how it compiles ·
[4](#4-composite-statements) composite statements · [5](#5-time) time · [6](#6-explain) explain · [7](#7-sql-view-datasets) SQL view datasets ·
[8](#8-sql-tests-of-a-model) SQL tests · [9](#9-problems-drs-9170-to-9179) problems · [10](#10-the-api-and-the-cli) API and CLI

Related: [BI_DESIGN.md §3.4](../architecture/BI_DESIGN.md#34-drishti-sql-dsql-read-only-sql-over-models) (the design),
[EXPRESSIONS.md](EXPRESSIONS.md) (DEL, which measures are written in), [API_GUIDE.md](API_GUIDE.md#drishti-sql-apiv1bisql).

## 1. Where you use it

| Place | Scope | Since |
|---|---|---|
| `POST /api/v1/bi/sql`, `drishti.py bi sql` | one model, as the caller | 2.2.0 |
| A **SQL view dataset** (`source: { sql, model }`): a reviewed statement whose answer is a table | one model, as each reader | 2.2.0 |
| A model's **SQL tests** (`tests: [{ sql, expect }]`) | the model, as the test's role | 2.2.0 |
| Analyse SQL cells, the PostgreSQL-wire endpoint for outside tools, AI and MCP | one model | waves B and C |
| **Not** in measures, row rules or derived columns: those are DEL | — | — |

Free querying needs **read on the model** (as `POST /bi/mq` does): a person who may only view a report cannot send SQL to its model.

## 2. The dialect

One `SELECT` (or `WITH ... SELECT`), the ANSI subset below. Keywords are case-insensitive; names may be quoted (`"trades"."desk"`).

| Allowed | Refused |
|---|---|
| `SELECT`, `WITH name AS (...)`, `UNION [ALL]` of compatible selects, `EXPLAIN SELECT ...` | DDL, DML, `ATTACH`, `COPY`, `PRAGMA`, `SET`, several statements (`DRS-9170`) |
| `FROM` the model's aliases (`FROM trades t`); `JOIN` without `ON`, or with `ON` equal to a relationship of the model; `LEFT JOIN` | `ON` with another predicate, comma, `RIGHT`, `FULL`, `CROSS`, `NATURAL` joins (`DRS-9172`) |
| `WHERE` a field compared with values: `=`, `<>`, `<`, `<=`, `>`, `>=`, `IN (...)`, `NOT IN`, `BETWEEN`, `LIKE 'x%'`, `'%x'`, `'%x%'`, `IS [NOT] NULL`, ANDed; `a = 'x' OR a = 'y'` on one field | an `OR` across fields, an expression on a field (`UPPER(x) = ...`): make it a derived column (`DRS-9179`) |
| `GROUP BY` the selected columns (by name, position or alias); `HAVING` a measure compared with a number | a selected column missing from `GROUP BY`, or the reverse (`DRS-9179`) |
| `ORDER BY` a selected column, measure, alias or position; `LIMIT n [OFFSET m]` | |
| Aggregates on columns: `SUM`, `AVG`, `MIN`, `MAX`, `COUNT(*)`, `COUNT(x)`, `COUNT(DISTINCT x)`, `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x)`, with `CASE`, `COALESCE`, `ABS`, `ROUND`, arithmetic inside: each becomes a **local measure** | an aggregate of a measure, `SUM(MEASURE('x'))` (`DRS-9174`) |
| `MEASURE('name')`, `MEASURE("name")`, by stable id `MEASURE('m_mtm')` | a measure in `WHERE` (use `HAVING`, `DRS-9174`) |
| Window functions over the **answer**: `RANK() OVER (ORDER BY mtm DESC)`, `DENSE_RANK`, `ROW_NUMBER`, `PERCENT_RANK`, `LAG`, `LEAD`, with `PARTITION BY` | a window over a dataset's rows (`DRS-9175`) |
| `SELECT ... FROM trades` without `GROUP BY` or aggregates: a **row extract** (`shape: rows`, with its row cap) | `SELECT DISTINCT` on a fact without `GROUP BY` (`DRS-9178`) |
| `AS OF DATE '2026-10-09'`, `FOR LIVE`, `FOR WINDOW LAST 1 HOUR`, `FOR WINDOW FROM DATE 'a' TO DATE 'b'` after `FROM` (one time per statement) | two different times in one statement (`DRS-9177`) |
| | table functions, file or URL readers (`read_parquet`), user functions, `random()` (`DRS-9173`, `DRS-9176`); another model, a connector or a kind (`DRS-9176`) |

Comments are `-- to the end of the line` and `/* ... */`. Text literals use single quotes (`'it''s'`); `DATE '2026-10-09'` and `TIMESTAMP '...'`
are dates.

## 3. How a statement compiles

The binder resolves every name against the model as the caller sees it, then:

* An **MQ-shaped** statement (one grouped `SELECT` over the model's tables: fields, measures, filters, having, order, limit) becomes **exactly one model
  query**. The selected columns are its fields (they must be the `GROUP BY`), `MEASURE('x')` its measures, an inline aggregate a local measure in DEL
  (`SUM(trades.notional)` is `sum(trades.notional)`, `COUNT(*)` is `count(trades)`, `COUNT(DISTINCT x)` is `distinct(x)`, `CASE WHEN c THEN a ELSE b END`
  is `a if c else b`), `WHERE` its `where`, `HAVING` its `having`, the time clause its `time`. Its answer is the model query's, with the columns named and
  ordered as the `SELECT` says; `meta.query` shows the model query and `meta.plan` its plan hash, the same as the MQ's.
* A **composite** statement (`WITH`, `UNION`, a subquery in `FROM`, a window over the answer) is a plan whose **leaves are model queries** (each secured
  and masked for the reader) and whose inner nodes are relational operators over their answers, run on the server in an in-memory engine that can read
  nothing else. A join, filter, sort or partition on a column masked for the reader is refused (`DRS-9161`); aggregating answers again is refused
  (`DRS-9179`: a measure belongs in the model).

Captured (`rita`, risk; the test model `lang-model` of one dataset `trades`):

```
POST /api/v1/bi/sql {"model":"lang-model@1","sql":"SELECT trades.desk AS desk, MEASURE('Notional') AS notional, COUNT(*) AS n FROM trades
                     GROUP BY trades.desk ORDER BY desk AS OF DATE '2026-10-08'"}
{"sql": 1, "model": {"id": "file:model:lang-model", "name": "lang-model", "version": "1.0.0", "implicit": false, "compat": false},
 "columns": [{"name": "desk", "kind": "field", "type": "string", "masked": false, "source": "trades.desk"},
             {"name": "notional", "id": "m_notional", "kind": "measure", "type": "decimal", "additive": true, "masked": false, "source": "Notional"},
             {"name": "n", "kind": "measure", "type": "integer", "additive": true, "masked": false, "source": "n"}],
 "rows": [["DESK-A", 92000.0, 8], ["DESK-B", 100000.0, 8], ["DESK-C", 108000.0, 8]], "rowCount": 3,
 "meta": {"asOf": "2026-10-08", "layer": "columns", "cached": false, "plan": "p_01efcdbb78ac26db", "signature": "s_3031a71137741749", "shape": "mq",
          "query": {"define": {"n": "count(trades)"}, "model": "lang-model@1", "mq": 1, "select": {"fields": ["trades.desk"], "measures": ["Notional", "n"]},
                    "shape": "grouped", "sort": [{"by": "trades.desk", "desc": false}], "time": {"as-of": "2026-10-08"}}, ...}}
```

As `hana` (a desk-head of DESK-A) the same statement answers one row, `["DESK-A", 92000.0, 8]`.

## 4. Composite statements

```sql
WITH m AS (SELECT trades.desk AS desk, MEASURE('MTM') AS mtm FROM trades GROUP BY trades.desk),
     l AS (SELECT limits.desk AS desk, MEASURE('Limit') AS lim FROM limits GROUP BY limits.desk)
SELECT m.desk, m.mtm, l.lim, m.mtm / l.lim AS usage
FROM   m LEFT JOIN l ON m.desk = l.desk
ORDER  BY m.desk

SELECT trades.desk AS desk, MEASURE('Notional') AS notional, RANK() OVER (ORDER BY notional DESC) AS r
FROM   trades GROUP BY trades.desk ORDER BY r                  -- a window over the answer

SELECT trades.desk, MEASURE('Trades') FROM trades GROUP BY trades.desk
UNION ALL
SELECT trades.currency, MEASURE('Trades') FROM trades GROUP BY trades.currency
```

Each `SELECT` over the model's tables is one model query asked as you (`meta.leaves` lists their plan hashes); the outer query joins, filters, ranks,
sorts and limits their answers. A `WITH` query is a plain `SELECT` over the model (no nested `WITH`, no window inside); at most
`drishti.bi.sql.max-leaves` (8) leaves. Captured (`rita`):

```
WITH d AS (SELECT trades.desk AS desk, MEASURE('Notional') AS notional FROM trades GROUP BY trades.desk AS OF DATE '2026-10-08')
SELECT d.desk, d.notional, RANK() OVER (ORDER BY d.notional DESC) AS r FROM d ORDER BY r
{"sql": 1, "columns": [{"name": "desk", "type": "string", "masked": false}, {"name": "notional", "type": "decimal", "masked": false},
                       {"name": "r", "type": "integer", "masked": false}],
 "rows": [["DESK-C", 108000.0, 1], ["DESK-B", 100000.0, 2], ["DESK-A", 92000.0, 3]], "rowCount": 3,
 "meta": {"shape": "composite", "leaves": ["p_5d0497af69d9a881"], "signature": "s_3031a71137741749", "cached": false, "warnings": [], "millis": 22.2}}
```

## 5. Time

One time per statement, written after `FROM` (or after the `SELECT`'s last clause): `AS OF DATE '2026-10-09'` (that business date), `FOR LIVE`
(the live state), `FOR WINDOW LAST 1 HOUR` (a sliding window of a history dataset; `SECOND`, `MINUTE`, `HOUR`, `DAY`), `FOR WINDOW FROM DATE '2026-10-01'
TO DATE '2026-10-09'`. Without one, the statement reads the current business date. In a composite statement a time given once applies to every leaf.

## 6. Explain

`EXPLAIN SELECT ...` answers the plan instead of the rows: its hash, the reader's security signature, the model queries, a composite's outer SQL, and the
plan as a tree and as text. A scan says *that* row rules applied, never their text. Captured (`hana`):

```
plan: p_01efcdbb78ac26db   signature: s_f82a28ede96c9a86   time: as-of 2026-10-08
- Answer {columns=[trades.desk, Notional, n], shape=grouped}
  - Limit {n=500}
    - Sort {keys=[trades.desk asc]}
      - Aggregate trades by [trades.desk]: [Notional, n]
        - SecureScan trades (lang-trade@1.0.0)  rules: 1 applied  columns: [desk, notional]
```

## 7. SQL view datasets

A dataset whose rows are a reviewed Drishti SQL statement over a model. Its statement is asked **as each reader** (their rules and masks), at the outer
query's date when it names none; the dataset declares its columns and grain like any dataset that is not a pack kind:

```yaml
rachana: 2
expressions: 2
dataset: lang-desk-sql
version: 1.0.0
source:
  model: lang-model@1
  sql: "SELECT trades.desk AS desk, MEASURE('Notional') AS notional, MEASURE('Trades') AS trades FROM trades GROUP BY trades.desk"
grain: { keys: [desk] }
columns:
  desk:     { type: string, role: key }
  notional: { type: decimal, aggregate: sum }
  trades:   { type: integer, aggregate: sum }
```

Check parses the statement (`DRS-9108` with the SQL code inside when it does not); a view that reaches its own model again is a cycle (`DRS-9109`).

## 8. SQL tests of a model

A model's `tests:` may ask Drishti SQL and expect a number: the statement runs as the test's `role:` (else the person checking), one figure is read
(`row: { field: value }` picks a row, `column:` a column; default the first number of a one-row answer) and compared with `expect` (and `tolerance`).
`POST /api/v1/bi/items/{id}/tests` runs them, and publishing a model refuses a failing one (`DRS-9043`).

```yaml
tests:
  - { name: DESK-A notional, sql: "SELECT MEASURE('Notional') FROM trades WHERE trades.desk = 'DESK-A' AS OF DATE '2026-10-08'", expect: 92000, role: bi-risk }
  - { name: by desk, sql: "SELECT trades.desk, MEASURE('Trades') AS n FROM trades GROUP BY trades.desk", row: { trades.desk: DESK-B }, column: n, expect: 8 }
```

## 9. Problems: DRS-9170 to DRS-9179

Every refusal carries the code, the message, a `fix` (what to write) and the position (`at`, a character offset). Over HTTP it is `422` with
`path: "sql@<offset>"` and `fix: {at, write}`; `POST /bi/sql/validate` lists them under `problems`.

| Code | Meaning | Example | Fix |
|---|---|---|---|
| `DRS-9170` | not a statement of the dialect | `DELETE FROM trades`; `SELECT 1; SELECT 2` | one read-only `SELECT` |
| `DRS-9171` | a table or column the model lacks for you | `SELECT trades.desk FROM trades JOIN desks ...` on a one-dataset model | the model's aliases (`FROM trades`) |
| `DRS-9172` | a join that is not a relationship | `JOIN desks ON trades.notional = desks.desk` | `JOIN desks` without `ON` |
| `DRS-9173` | a function the dialect does not take | `random()`, `read_parquet('/etc/passwd')` | the listed functions |
| `DRS-9174` | an aggregate over a measure; a measure in `WHERE` | `SUM(MEASURE('MTM'))`; `WHERE MEASURE('MTM') > 0` | the measure as it is; `HAVING` |
| `DRS-9175` | a window over a dataset's rows | `SUM(RANK() OVER (...))` | rank the answer's rows |
| `DRS-9176` | something outside the model | `FROM pack.trading.trade`, `read_parquet(...)` | the model's aliases |
| `DRS-9177` | two times in one statement | two `WITH` queries `AS OF` different dates | one time clause |
| `DRS-9178` | `SELECT DISTINCT` without `GROUP BY` | `SELECT DISTINCT trades.desk FROM trades` | `GROUP BY trades.desk` |
| `DRS-9179` | not (yet) in the dialect | `SELECT *`; `WHERE a = 1 OR b = 2`; `GROUP BY` over `WITH` queries | named columns; a derived column; a measure |

The model query's own codes also apply to what a statement compiles to (`DRS-9152` an unknown measure, `DRS-9156` not reachable, `DRS-9161` masked,
`DRS-9158` currencies). Captured:

```
422 {"title": "sql name", "status": 422, "code": "DRS-9171", "path": "sql@36", "fix": {"at": 36, "write": "FROM trades"},
     "detail": "DRS-9171 no table 'desks' in this model: its tables are [trades]",
     "problems": [{"code": "DRS-9171", "path": "sql@36", "detail": "no table 'desks' in this model: its tables are [trades]", "fix": {"at": 36, "write": "FROM trades"}}]}
```

## 10. The API and the CLI

```bash
drishti.py bi sql "SELECT trades.desk, MEASURE('MTM') FROM trades GROUP BY trades.desk" --model trading-risk@1
drishti.py bi sql "EXPLAIN SELECT trades.desk, MEASURE('MTM') FROM trades GROUP BY trades.desk" --model trading-risk@1
drishti.py bi sql --file q.sql --model trading-risk@1 --format csv > answer.csv
```

`POST /api/v1/bi/sql {model, sql, format: json|csv|arrow}` and `POST /api/v1/bi/sql/validate {model, sql}`
([API_GUIDE.md](API_GUIDE.md#drishti-sql-apiv1bisql)). Every statement is audited (`bi-sql`, with its text). Settings: `drishti.bi.sql.*`
([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibisql--drishti-sql)).
