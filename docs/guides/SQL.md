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
SELECT counterparties.NAME     AS counterparty,
       MEASURE('MTM')         AS mtm,
       MEASURE('Trades')      AS trades,
       SUM(trades.NOTIONAL)   AS notional_local               -- an inline aggregate: a local measure
FROM   trades
JOIN   counterparties                                         -- no ON: the model's relationship is used
WHERE  trades.CURRENCY IN ('USD', 'EUR')
  AND  trades.TRADE_DATE >= DATE '2026-01-01'
GROUP  BY counterparties.NAME
HAVING MEASURE('MTM') < 0
ORDER  BY mtm
LIMIT  20
```

Every example on this page runs on the shipped model **`trading-risk`** (`config/bi/models/trading-risk.model.yaml`: tables `trades`,
`netting_sets`, `counterparties`, `desks`, `books`, columns in the packs' UPPER_SNAKE names) over the demo lakes
(`drishti.py lake demo`, [data/testdata/README.md](../../data/testdata/README.md)), asked as the seeded administrator. The demo lakes end on
**today's** business date, so your dates differ from the ones captured here. The figures were captured on the seven-day lakes of 2.5.0
(2026-10-07 to 2026-10-16); the lakes hold sixty business days since 2.6.5, and a figure may differ on yours. The statement above answered six counterparties, the first `["Northern Plains Energy Canada", -251066943.0, 528,
125556404805.7]`.

A filter on `trades` does not reach `netting_sets`: `MEASURE('Exposure (PFE peak)')` in the statement above is refused with `DRS-9156` (the
measures aggregate `[trades, netting_sets]`, which no many-to-one path connects) and the fix names the fields both reach. Without the `WHERE`,
`SELECT counterparties.NAME, MEASURE('MTM'), MEASURE('Exposure (PFE peak)') FROM trades JOIN counterparties GROUP BY counterparties.NAME` is
answered, each fact aggregated alone and joined (the warning says so).

Contents: [1](#1-where-you-use-it) where · [2](#2-the-dialect) the dialect · [3](#3-how-a-statement-compiles) how it compiles ·
[4](#4-composite-statements) composite statements · [5](#5-time) time · [6](#6-explain) explain · [7](#7-sql-view-datasets) SQL view datasets ·
[8](#8-sql-tests-of-a-model) SQL tests · [9](#9-problems-drs-9170-to-drs-9179) problems · [10](#10-the-api-and-the-cli) API and CLI

Related: [BI_DESIGN.md §3.4](../architecture/BI_DESIGN.md#34-drishti-sql-dsql-read-only-sql-over-models) (the design),
[EXPRESSIONS.md](EXPRESSIONS.md) (DEL, which measures are written in), [API_GUIDE.md](API_GUIDE.md#drishti-sql-apiv1bisql).

## 1. Where you use it

| Place | Scope | Since |
|---|---|---|
| `POST /api/v1/bi/sql`, `drishti.py bi sql` | one model, as the caller | 2.2.0 |
| A **SQL view dataset** (`source: { sql, model }`): a reviewed statement whose answer is a table | one model, as each reader | 2.2.0 |
| A model's **SQL tests** (`tests: [{ sql, expect }]`) | the model, as the test's role | 2.2.0 |
| Analyse SQL cells (Drishti SQL on the server) | one model, as the analyst | 2.2.0 |
| The PostgreSQL-wire endpoint for outside tools | one model | planned after 3.0.0 |
| AI and MCP | one model | planned for 2.9.0 |
| **Not** in measures, row rules or derived columns: those are DEL | — | — |

Free querying needs **read on the model** (as `POST /bi/mq` does): a person who may only view a report cannot send SQL to its model.

## 2. The dialect

One `SELECT` (or `WITH ... SELECT`), the ANSI subset below. Keywords are case-insensitive; names may be quoted (`"trades"."DESK"`).

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
  (`SUM(trades.NOTIONAL)` is `sum(trades.NOTIONAL)`, `COUNT(*)` is `count(trades)`, `COUNT(DISTINCT x)` is `distinct(x)`, `CASE WHEN c THEN a ELSE b END`
  is `a if c else b`), `WHERE` its `where`, `HAVING` its `having`, the time clause its `time`. Its answer is the model query's, with the columns named and
  ordered as the `SELECT` says; `meta.query` shows the model query and `meta.plan` its plan hash, the same as the MQ's.
* A **composite** statement (`WITH`, `UNION`, a subquery in `FROM`, a window over the answer) is a plan whose **leaves are model queries** (each secured
  and masked for the reader) and whose inner nodes are relational operators over their answers, run on the server in an in-memory engine that can read
  nothing else. A join, filter, sort or partition on a column masked for the reader is refused (`DRS-9161`); aggregating answers again is refused
  (`DRS-9179`: a measure belongs in the model).

Captured (the administrator, on `trading-risk`; the sixth of the seven demo days):

```
POST /api/v1/bi/sql {"model":"trading-risk@1","sql":"SELECT trades.DESK AS desk, MEASURE('Notional') AS notional, COUNT(*) AS n FROM trades
                     GROUP BY trades.DESK ORDER BY desk AS OF DATE '2026-10-15'"}
{"sql": 1, "model": {"id": "file:model:trading-risk", "name": "trading-risk", "version": "1.0.0", "implicit": false, "compat": false},
 "columns": [{"name": "desk", "kind": "field", "type": "string", "masked": false, "source": "trades.DESK"},
             {"name": "notional", "kind": "measure", "type": "decimal", "format": "amount0", "additive": true, "masked": false, "source": "Notional"},
             {"name": "n", "kind": "measure", "type": "integer", "additive": true, "masked": false, "source": "n"}],
 "rows": [["DESK-COMM", 888803742773.65, 5990], ["DESK-CREDIT", 344825753000.0, 1172], ["DESK-EQD", 428542341000.0, 1452], ...
          ["DESK-RATES", 596934185000.0, 1832], ["DESK-SFT", 225972124000.0, 672], ["DESK-STRUCT", 105176308000.0, 330]], "rowCount": 10,
 "meta": {"asOf": "2026-10-15", "layer": "rollup", "rollup": "by_desk_currency", "cached": false, "plan": "p_24cad7f700108507",
          "signature": "s_38f790bd34b573e4", "shape": "mq",
          "query": {"define": {"n": "count(trades)"}, "model": "trading-risk@1", "mq": 1, "select": {"fields": ["trades.DESK"], "measures": ["Notional", "n"]},
                    "shape": "grouped", "sort": [{"by": "trades.DESK", "desc": false}], "time": {"as-of": "2026-10-15"}}, ...}}
```

The rollup `by_desk_currency` of the model answered it. A `desk-head` (the model's row rule `DESK in user.desks`) asking the same statement gets
the rows of their own desks only, and a different `signature`.

## 4. Composite statements

```sql
WITH m AS (SELECT trades.COUNTERPARTY AS cp, MEASURE('MTM') AS mtm FROM trades GROUP BY trades.COUNTERPARTY),
     e AS (SELECT netting_sets.COUNTERPARTY AS cp, MEASURE('Exposure (EE peak)') AS ee FROM netting_sets GROUP BY netting_sets.COUNTERPARTY)
SELECT m.cp, m.mtm, e.ee
FROM   m LEFT JOIN e ON m.cp = e.cp
ORDER  BY m.cp
-- ["CP-ALDERSHOT", 470417505.43, 2599390291.0], ["CP-AURORA", 548784382.85, 1991425202.0], ...

SELECT trades.DESK AS desk, MEASURE('Notional') AS notional, RANK() OVER (ORDER BY notional DESC) AS r
FROM   trades GROUP BY trades.DESK ORDER BY r                  -- a window over the answer
-- ["DESK-COMM", 888803742773.65, 1], ["DESK-RATES", 596934185000.0, 2], ...

SELECT trades.DESK, MEASURE('Trades') FROM trades GROUP BY trades.DESK
UNION ALL
SELECT trades.CURRENCY, MEASURE('Trades') FROM trades GROUP BY trades.CURRENCY
-- 17 rows: the ten desks, then the seven currencies (["USD", 8562] among them)
```

Each `SELECT` over the model's tables is one model query asked as you (`meta.leaves` lists their plan hashes); the outer query joins, filters, ranks,
sorts and limits their answers. A `WITH` query is a plain `SELECT` over the model (no nested `WITH`, no window inside); at most
`drishti.bi.sql.max-leaves` (8) leaves. Captured (`rita`):

```
WITH d AS (SELECT trades.DESK AS desk, MEASURE('Notional') AS notional FROM trades GROUP BY trades.DESK AS OF DATE '2026-10-15')
SELECT d.desk, d.notional, RANK() OVER (ORDER BY d.notional DESC) AS r FROM d ORDER BY r LIMIT 3
{"sql": 1, "columns": [{"name": "desk", "type": "string", "masked": false}, {"name": "notional", "type": "decimal", "masked": false},
                       {"name": "r", "type": "integer", "masked": false}],
 "rows": [["DESK-COMM", 888803742773.65, 1], ["DESK-RATES", 596934185000.0, 2], ["DESK-EQD", 428542341000.0, 3]], "rowCount": 3,
 "meta": {"shape": "composite", "leaves": ["p_ed39a5d62d7577b8"], "signature": "s_38f790bd34b573e4", "cached": false, "warnings": [], "millis": 24.2}}
```

## 5. Time

One time per statement, written after `FROM` (or after the `SELECT`'s last clause): `AS OF DATE '2026-10-09'` (that business date), `FOR LIVE`
(the live state), `FOR WINDOW LAST 1 HOUR` (a sliding window of a history dataset; `SECOND`, `MINUTE`, `HOUR`, `DAY`), `FOR WINDOW FROM DATE '2026-10-01'
TO DATE '2026-10-09'`. Without one, the statement reads the current business date. In a composite statement a time given once applies to every leaf.

## 6. Explain

`EXPLAIN SELECT ...` answers the plan instead of the rows: its hash, the reader's security signature, the model queries, a composite's outer SQL, and the
plan as a tree and as text. A scan says *that* row rules applied, never their text. Captured (`EXPLAIN` before the statement of section 3):

```
plan: p_24cad7f700108507   signature: s_38f790bd34b573e4   time: as-of 2026-10-15
- Answer {binding={define={n=count(trades)}}, columns=[trades.DESK, Notional, n], shape=grouped}
  - Limit {n=500}
    - Sort {keys=[trades.DESK asc]}
      - Aggregate trades by [trades.DESK]: [Notional, n]  (rollup by_desk_currency)
        - SecureScan trades (trading-risk-trades@1.0.0)  rules: 1 applied  columns: [DESK, NOTIONAL]
```

The plan hash is the statement's own (`p_24cad7f700108507`, the same as the answer's `meta.plan` above): the SQL and its model query are one plan.

## 7. SQL view datasets

A dataset whose rows are a reviewed Drishti SQL statement over a model. Its statement is asked **as each reader** (their rules and masks), at the outer
query's date when it names none; the dataset declares its columns and grain like any dataset that is not a pack kind:

```yaml
rachana: 2
expressions: 2
dataset: desk-notional-sql
version: 1.0.0
source:
  model: trading-risk@1
  sql: "SELECT trades.DESK AS desk, MEASURE('Notional') AS notional, MEASURE('Trades') AS trades FROM trades GROUP BY trades.DESK"
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
  - { name: rates notional, sql: "SELECT MEASURE('Notional') FROM trades WHERE trades.DESK = 'DESK-RATES' AS OF DATE '2026-10-15'", expect: 596934185000, role: risk }
  - { name: by desk, sql: "SELECT trades.DESK, MEASURE('Trades') AS n FROM trades GROUP BY trades.DESK AS OF DATE '2026-10-15'", row: { trades.DESK: DESK-RATES }, column: n, expect: 1832 }
```

## 9. Problems: DRS-9170 to DRS-9179

Every refusal carries the code, the message, a `fix` (what to write) and the position (`at`, a character offset). Over HTTP it is `422` with
`path: "sql@<offset>"` and `fix: {at, write}`; `POST /bi/sql/validate` lists them under `problems`.

| Code | Meaning | Example | Fix |
|---|---|---|---|
| `DRS-9170` | not a statement of the dialect | `DELETE FROM trades`; `SELECT 1; SELECT 2` | one read-only `SELECT` |
| `DRS-9171` | a table or column the model lacks for you | `SELECT trades.DESK FROM trades JOIN limits ...` on `trading-risk` (no `limits` table) | the model's aliases (`FROM trades`) |
| `DRS-9172` | a join that is not a relationship | `JOIN desks ON trades.NOTIONAL = desks.id` | `JOIN desks` without `ON` |
| `DRS-9173` | a function the dialect does not take | `random()`, `read_parquet('/etc/passwd')` | the listed functions |
| `DRS-9174` | an aggregate over a measure; a measure in `WHERE` | `SUM(MEASURE('MTM'))`; `WHERE MEASURE('MTM') > 0` | the measure as it is; `HAVING` |
| `DRS-9175` | a window over a dataset's rows | `SUM(RANK() OVER (...))` | rank the answer's rows |
| `DRS-9176` | something outside the model | `FROM pack.trading.trade`, `read_parquet(...)` | the model's aliases |
| `DRS-9177` | two times in one statement | two `WITH` queries `AS OF` different dates | one time clause |
| `DRS-9178` | `SELECT DISTINCT` without `GROUP BY` | `SELECT DISTINCT trades.DESK FROM trades` | `GROUP BY trades.DESK` |
| `DRS-9179` | not (yet) in the dialect | `SELECT *`; `WHERE a = 1 OR b = 2`; `GROUP BY` over `WITH` queries | named columns; a derived column; a measure |

The model query's own codes also apply to what a statement compiles to (`DRS-9152` an unknown measure, `DRS-9156` not reachable, `DRS-9161` masked,
`DRS-9158` currencies). Captured:

```
POST /api/v1/bi/sql {"model":"trading-risk@1","sql":"SELECT trades.DESK FROM trades JOIN limits GROUP BY trades.DESK"}
422 {"title": "sql name", "status": 422, "code": "DRS-9171", "path": "sql@36", "fix": {"at": 36, "write": "FROM trades"},
     "detail": "DRS-9171 no table 'limits' in this model: its tables are [trades, netting_sets, counterparties, desks, books]",
     "problems": [{"code": "DRS-9171", "path": "sql@36", "detail": "no table 'limits' in this model: its tables are [trades, netting_sets, counterparties, desks, books]",
                   "fix": {"at": 36, "write": "FROM trades"}}]}
```

## 10. The API and the CLI

```bash
drishti.py bi sql "SELECT trades.DESK, MEASURE('MTM') FROM trades GROUP BY trades.DESK" --model trading-risk@1
drishti.py bi sql "EXPLAIN SELECT trades.DESK, MEASURE('MTM') FROM trades GROUP BY trades.DESK" --model trading-risk@1
drishti.py bi sql --file q.sql --model trading-risk@1 --format csv > answer.csv
```

The first, captured on the demo lakes (the latest day; `--server` / `DRISHTI_SERVER` and `DRISHTI_TOKEN` as for every command that talks to a server):

```
trades.DESK  MTM
-----------  -----------------
DESK-COMM    -429,967,635.39
DESK-CREDIT  360,552,941.89
DESK-EQD     -79,395,510.75
...
DESK-RATES   4,108,728,089.50
DESK-SFT     51,375,248.69
DESK-STRUCT  382,608,464.96
10 row(s)  shape mq  plan p_f6760cccf51a3ad5
```

`POST /api/v1/bi/sql {model, sql, format: json|csv|arrow}` and `POST /api/v1/bi/sql/validate {model, sql}`
([API_GUIDE.md](API_GUIDE.md#drishti-sql-apiv1bisql)); validate types each output column without reading data (`COUNT(*)`, a model measure that is a `count()` or
`distinct()`: `integer`; another measure: `decimal`; a field: its column's type), which a SQL view dataset's wizard proposes as the column types. Every statement is audited (`bi-sql`, with its text). Settings: `drishti.bi.sql.*`
([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibisql--drishti-sql)).
