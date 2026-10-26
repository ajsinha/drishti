<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Data contracts for computed measures

*Revision 1.0 · 2026-10-25 · Companion to [IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md#the-computed-measures-roadmap-280-to-2120-product-owner-2026-10-25)*

Drishti keeps **fundamental data** as it is and **computes every risk measure when asked**, for any scope, from trade-level inputs in one
common risk lake. Each document in this folder is the **data contract** of one measure, or of one group of related commands. It states
exactly:
- what an upstream engine must deliver;
- what Drishti builds from it, in memory and on disk;
- what Drishti answers.

This page holds the rules every contract shares. A contract states only where it differs.

**Who reads these:**
- **The teams that run the upstream engines** (risk engines, exposure simulators, pricing and sensitivity batches): what to write, where,
  when, and how it is checked.
- **Drishti developers:** the tables, the in-memory structures, the formulas, the API and the tests.
- **Risk managers and auditors:** where each number comes from and how to reproduce it.

## Contents

- [1. Status of each contract](#1-status-of-each-contract)
- [2. The common risk lake](#2-the-common-risk-lake)
- [3. Four kinds of data](#3-four-kinds-of-data)
- [4. Shared keys](#4-shared-keys)
- [5. Types, names and encodings](#5-types-names-and-encodings)
- [6. Dates, as-of and restatements](#6-dates-as-of-and-restatements)
- [7. Currencies, units and signs](#7-currencies-units-and-signs)
- [8. Vectors and other arrays](#8-vectors-and-other-arrays)
- [9. Delivery: writing a business date](#9-delivery-writing-a-business-date)
- [10. Validation and rejects](#10-validation-and-rejects)
- [11. What Drishti answers: computed kinds, the API, captures](#11-what-drishti-answers-computed-kinds-the-api-captures)
- [12. Entitlements, masking and audit](#12-entitlements-masking-and-audit)
- [13. Versioning a contract](#13-versioning-a-contract)
- [14. Retiring the stored version](#14-retiring-the-stored-version)
- [15. The sections of every contract](#15-the-sections-of-every-contract)
- [16. Contracts of every command](#16-contracts-of-every-command)

## 1. Status of each contract

**Scope (product owner, 2026-10-25): "Just fundamentals and VaR is good for now."** The active work is the contracts of every
command (the fundamental data, [section 16](#16-contracts-of-every-command)) and VaR ([VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance)). Every other computed measure is
**PARKED**: its contract stays here as a design, the stored kind it would replace stays active and unchanged, and nothing of it is built
until the product owner brings it back. Release numbers in the parked contracts are provisional.

**AS BUILT** describes code that exists and is tested. **PLANNED** is the contract the release will implement: column names and rules
may still change before it ships, and the document records each change.

| Contract (a name in code font is not written yet) | Commands (retired kind) | Release | Status |
|---|---|---|---|
| [VAR_GUIDE.md Part II](../guides/VAR_GUIDE.md#14-the-contract-at-a-glance) | VAR (`var`), VNODE (`var-node`): both retired; also PORT, POS and DESK figures | 2.8.0 | AS BUILT (part 1: retirement, derived positions, official captures; part 2: memory tiering, the computed kinds and the Python engine); Part II of the VaR guide since 2026-10-26 |
| [TRADING_GROUPINGS.md](TRADING_GROUPINGS.md) | CTRD, PTRD, LTRD, ITRD (`counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades`) | 2.9.0 | PARKED (design kept) |
| [TVAL.md](TVAL.md) | TVAL (`trade-valuation`) | 2.9.0 | PARKED (design kept) |
| [STR.md](STR.md) | STR (`stress-result`); SCN stays fundamental | 2.9.0 | PARKED (design kept) |
| [FRTB.md](FRTB.md) | FRTB (`frtb-sensitivity`) | 2.9.0 | PARKED (design kept) |
| [SIMM.md](SIMM.md) | SIMM (`simm`) | 2.9.0 | PARKED (design kept) |
| [PNL.md](PNL.md) | PNL (`pnl-explain`) | 2.9.0 | PARKED (design kept) |
| [NSET.md](NSET.md) | NSET (`netting-set`): terms kept, figures computed | 2.10.0 | PARKED (design kept) |
| [SACCR.md](SACCR.md) | SACCR (`sa-ccr`) | 2.10.0 | PARKED (design kept) |
| [COLL.md](COLL.md) | COLL (`collateral-balance`): holdings kept, values computed | 2.10.0 | PARKED (design kept) |
| [MC.md](MC.md) | MC (`margin-call`): calls kept, expected call computed | 2.10.0 | PARKED (design kept) |
| [EXP.md](EXP.md) | EXP (`exposure-profile`) | 2.10.0 | PARKED (design kept) |
| [LIM.md](LIM.md) | LIM (`credit-limit`): limits kept, usage computed | 2.10.0 | PARKED (design kept) |
| [GRP.md](GRP.md) | GRP (`counterparty-group`): members kept, usage computed | 2.10.0 | PARKED (design kept) |
| [CVA.md](CVA.md) | CVA (`cva`) | 2.10.0 | PARKED (design kept) |
| [WWR.md](WWR.md) | wrong-way risk (new) | 2.10.0 | PARKED (design kept) |
| `GROUPINGS_AND_ANALYTICS.md` | LEIG, LEIC, SIC, ECG, CORR, USEQ ratios, PXH analytics | 2.9.0 / 2.10.0 | PARKED (not written) |
| `CONCENTRATION.md` | large exposures (new); the OSFI report once its template is supplied | 2.11.0 | PARKED (not written) |
| `LIQUIDITY.md` | LCR, NSFR, NCCF, cash-flow gaps (new) | 2.11.0 | PARKED (not written) |
| `IRRBB.md` | ΔEVE, ΔNII (new) | 2.11.0 | PARKED (not written) |
| `CREDIT.md` | expected credit loss, credit VaR (new) | 2.11.0 | PARKED (not written) |

The table lists the contracts of the computed measures. Every command that exists today, stored or computed, also has a
machine-readable contract of its own data ([section 16](#16-contracts-of-every-command)); a retiring kind's contract names the
document above that replaces it.

## 2. The common risk lake

**One lake for every measure's inputs.** Today the inputs are spread over the domains `risk`, `credit`, `collateral` and `legal`, one
connector each. From 2.8.0 every input of every computed measure lives under the domain `risk` of one Delta root, and every risk pack
(market-risk, counterparty-risk, and later the balance-sheet packs) reads it through one connector, `risk-store`:

```
${DRISHTI_DELTA_ROOT}                         default ${drishti.data.dir}/delta
└── risk/                                     the common risk lake (domain: risk)
    ├── position/                             one Delta table per kind
    ├── pnl-vector/
    ├── scenario-set/
    ├── risk-hierarchy/
    ├── portfolio/
    ├── sensitivity/                          (2.9.0)
    ├── scenario-pnl/                         (2.9.0)
    ├── exposure-path/                        (2.10.0)
    └── ...
```

- **One table per kind:** a Delta table (Parquet files and a `_delta_log`) at `<root>/risk/<kind>/`, written by `drishti.py lake build`
  or by any Delta writer that follows the contract.
- **The masters stay where they are:** trades, counterparties, books, market data and GLEIF entities. Each is read through its own pack's
  connector, by id ([section 4](#4-shared-keys)). The risk lake holds only measure inputs and the hierarchies and portfolios that scope them.
- **The same layout on every storage:** a local disk, a shared file system, or S3 ([AWS_DELTA_LAKE.md](../architecture/AWS_DELTA_LAKE.md));
  Iceberg instead of Delta per table ([DRISHTI_ICEBERG.md](../architecture/DRISHTI_ICEBERG.md)).
- **Test data:** follows the repository's pattern: `data/testdata/market-risk/<kind>/*.jsonl.gz` (one file per business date), listed in
  `data/testdata/testdata.yaml`, built into the lake by `drishti.py lake demo`. Each contract's generator is named in its section 13.

## 3. Four kinds of data

| Kind of data | Who writes it | Where | Examples |
|---|---|---|---|
| **Fundamental: masters and terms** | Source systems, through their packs | Their own connectors | trades, counterparties, books, desks, legal entities, CSAs and agreements, market data, GLEIF |
| **Fundamental: engine inputs** | Upstream engines, once per business date | The common risk lake | P&L vectors, sensitivities, scenario P&L, exposure paths, cash flows |
| **Drishti-owned** | Drishti, from users' actions | The Drishti database and the content store ([DATABASE.md](../architecture/DATABASE.md), [CONTENT_STORE.md](../architecture/CONTENT_STORE.md)) | derived positions, portfolio definitions, baskets, captures, official end-of-day captures |
| **Computed answers** | Drishti, when asked | Nowhere: computed, cached in memory, and stored only as a capture | VaR, FRTB charge, EAD, EE/PFE, LCR … |

**The rule:** a computed figure is never stored in a fundamental or input table. A guard test fails the build when a table carries a
column a compute engine produces ([section 14](#14-retiring-the-stored-version)).

## 4. Shared keys

Every table in the risk lake joins to the masters and to each other through these keys. A contract that needs another key defines it.

| Key | Meaning | Format | Master (command) |
|---|---|---|---|
| `TRADE_ID` | The booking system's trade id | text; digits stay text (`"1000015"`), Murex `MX-…`, Calypso `CLY-…` | `trade` (TRD) |
| `SOURCE_SYSTEM` | The booking system | `ENDUR`, `CALYPSO`, `MUREX`, … | |
| `POSITION_ID` | A position: a holding in a book, netting its trades | `POS-<book>~<instrument key>` (derived by Drishti from 2.8.0) | `position` (POS) |
| `INSTRUMENT_ID` | The security or contract a position holds | ISIN, ticker, exchange contract, or the trade id for OTC | |
| `TT_BOOK` | The book | `BOOK-RATES-1` | `book` (BOOK) |
| `DESK` | The desk | `DESK-RATES` | `desk` (DESK) |
| `LEGAL_ENTITY` | The bank's legal entity | `LE-…` | `legal-entity` (LE) |
| `PORTFOLIO` | The booking portfolio on the trade | text | |
| `COUNTERPARTY` | The counterparty | `CP-…` | `counterparty` (CPTY) |
| `LEI` | The counterparty's Legal Entity Identifier | 20 characters | `lei-entity` (LEI) |
| `NETTING_SET` | A netting set under one agreement | `NS-…` | `netting-set` (NSET) |
| `CSA` | The credit support annex | `CSA-…` | `csa` (CSA) |
| `NODE_ID` | A node of a risk hierarchy | `MGMT~DESK-RATES` (hierarchy, `~`, node) | `risk-hierarchy` (HIER) |
| `SCENARIO_SET_ID` | A set of historical scenarios | `HS-509` | `scenario-set` (SSET) |
| `SCENARIO_ID` | A stress scenario | `SCN-…` | `stress-scenario` (SCN) |
| `BUSINESS_DATE` | The close of business a row describes | `YYYY-MM-DD` | |

**Scopes.** The scope grammar ([VAR_GUIDE.md section 5](../guides/VAR_GUIDE.md#5-scopes-portfolios-and-baskets)) addresses any set of trades through these keys: `BANK`, `DESK:`, `BOOK:`,
`POS:`, `TRD:`, `PORT:`, `BASKET:`, `CAPTURE:`, `WHERE:`, and from 2.8.0 `CPTY:`, `GROUP:` (a connected group, through GLEIF parents),
`NSET:` and `LE:`. Terms combine with `+` (union), `-` (difference) and `&` (intersection). Every measure takes the same scope text.

## 5. Types, names and encodings

- **Column names:** `UPPER_SNAKE`, as the trades use ([production-shaped trades](../connectors/DEMO_DATA.md)). Nested objects keep the
  same style (`LEGS[].NOTIONAL`).
- **Types:** JSON in the test data, Delta types in the lake:

| Contract type | JSON | Delta / Parquet |
|---|---|---|
| text | string | `string` |
| id | string (never a number, even when all digits) | `string` |
| integer | number | `long` |
| amount, rate | number | `double` (IEEE 754 binary64) |
| date | `"YYYY-MM-DD"` | `date` (or `string` in that form; both are read) |
| timestamp | `"YYYY-MM-DDThh:mm:ssZ"` (UTC) | `timestamp` |
| boolean | `true` / `false` | `boolean` |
| vector | array of numbers | `list<double>` (see [section 8](#8-vectors-and-other-arrays)) |
| record list | array of objects | kept inside the document (`struct` list), read by the Sutra; not a column the engines read |

- **Required and optional:** each contract's column table marks a column **required** (a row without it is rejected), **optional**
  (empty is allowed and means "not known") or **derived** (Drishti fills it; an upstream value is ignored).
- **Empty:** a missing key, `null` and `""` all mean empty. `0` is a value, never empty.
- **Text encoding:** UTF-8.

## 6. Dates, as-of and restatements

- **Every input row carries `BUSINESS_DATE`.** A table holds many business dates; the engines read one date at a time (the request's
  as-of, else the newest date present).
- **A business date is replaced as a whole.** Writing a date again (a rerun, a restatement) replaces every row of that date in that
  table, never a part of it (`lake build` replaces a day: [TOOLS.md section 4.4](../guides/TOOLS.md#44-re-running-a-day-replaces-that-day-only)).
  The previous version stays readable through Delta time travel ("as known at").
- **As-of on the screen and in the API:** `asOf=YYYY-MM-DD` selects the business date; a date later than the newest present reads the
  newest. "As known at" (`knownAt=`) reads a table version as it stood at that time, for audit.
- **Consistency across tables:** a measure's inputs for one date must belong to the same run. The engine checks the batch ids its contract
  names (for VaR: `SCENARIO_SET_ID` and `BATCH_ID`) and refuses to mix runs (section 10).
- **Hierarchies and portfolios are dated too.** Membership is resolved as of the request's date: a desk reorganisation on the 15th changes
  scopes from the 15th, not before.

## 7. Currencies, units and signs

- **Reporting currency:** every amount an engine aggregates is in **USD** unless its contract says otherwise. A contract that accepts
  other currencies names the FX source and the rate date (the business date's close, from the market-data pack).
- **Native amounts** may be kept beside the reporting amount (`PV_NATIVE`, `CURRENCY`), for display only.
- **Signs, from the bank's point of view:**
  - a P&L or a scenario P&L is **positive for a gain** and negative for a loss;
  - a quantity is **positive when long**, negative when short (`DIRECTION` says the same in words);
  - a market value is positive for an asset;
  - an exposure (EE, PFE, EAD) is **positive when the counterparty owes the bank**;
  - collateral **held** is positive and **posted** is negative.
- **VaR and ES are reported as positive numbers** (a loss of 1.9m is VaR 1.9m). Each contract restates its own convention in its
  section 5.
- **Units:** sensitivities state their unit in the contract (for example, a delta per 1 bp, in USD).

## 8. Vectors and other arrays

A **vector** is a fixed-length list of numbers that belongs to one row, such as a trade's P&L under each of 509 historical scenarios.

- **Storage:** a `list<double>` column (`PNL` for VaR) in Delta, float64. The rest of the row is ordinary columns; the vector is never
  inside a JSON document.
- **Length and order:** every vector of a table on one date has the same length and the same order. The order is defined by a reference
  row in another table (for VaR, the scenario set's `SCENARIO_DATES`). A vector of another length is rejected.
- **No missing elements:** a vector holds a number in every slot. A scenario a trade cannot be valued under is the engine's to fill
  (with its own rule, written in its run log), never `null` or `NaN`.
- **In memory:** Drishti may hold a hot copy at lower precision (float32) under a memory budget, while every sum is done in float64.
  The contract states which ([VAR_GUIDE.md section 25](../guides/VAR_GUIDE.md#25-one-business-date-in-memory)).
- **Test data:** a vector is written as a JSON array in the `.jsonl.gz` files and becomes the `list<double>` column at `lake build`
  (`layout.<kind>.vectors` in the pack's connector settings).

## 9. Delivery: writing a business date

The upstream engine does the same four things every business date:

1. **Write the date's rows** to each table of its contract, all of them, with one `BATCH_ID`.
2. **Write the reference rows** the vectors depend on (the scenario set), if they changed.
3. **Signal the load** for each table: `POST /api/v1/packs/{pack}/loads` with `{kind, businessDate, status: "ready", rows, batchId}`,
   or `drishti.py data landed` ([DATA_LOADS.md](../guides/DATA_LOADS.md)). A failed run signals `status: "failed"`, and the screens say so.
4. **Wait for nothing.** Drishti refreshes its caches, reloads the date for the engines (VaR: `POST /api/v1/var/reload`, done by the
   signal), checks the date against the contract's expectations (rows, completeness, timing), evaluates alerts, and tells the people the
   pack names.

**Expectations:** each contract says when its date is due (for example, "by 06:00 New York the next business day") and what counts as
complete. A date that is late or incomplete shows on Admin → Data loads and in `GET /api/v1/admin/health` (`dataLate`).

## 10. Validation and rejects

Every contract lists its own checks in its section 10. These hold for all:

| Check | When it fails |
|---|---|
| Required columns present and typed | The row is rejected (written to the load's reject file with the reason); the load reports `rejected` |
| Keys resolve | A `TRADE_ID`, `TT_BOOK`, `COUNTERPARTY` or `NETTING_SET` that no master knows on that date: the row is kept and flagged; the engine counts it under "unmapped" in the answer's provenance |
| Unique key per date | A duplicate of the table's key on one date: the load fails |
| Vector length and finiteness | A vector of the wrong length, or with `NaN` or infinity: the row is rejected |
| One run per date | Rows of one date with different `BATCH_ID`s, or a vector table whose `SCENARIO_SET_ID` differs from the scenario set's: the date is not loaded, and the engine answers the previous date with a warning |
| Completeness | Fewer rows than the contract's expectation (for example, fewer live trades valued than live trades booked): the load is `ready` but the date shows "incomplete" with the count missing |

Errors use the platform's DRS codes ([API_GUIDE.md](../guides/API_GUIDE.md#error-code-table)). Each contract lists the codes its
engine answers.

## 11. What Drishti answers: computed kinds, the API, captures

- **Computed kinds:** each measure is served as an ordinary kind by a reactive connector (`plugin: reactive`,
  [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md)). It opens by command (`VAR DESK DESK-RATES <GO>`), by link
  (`/v/<kind>/<scope>`), by API, by CLI, by search, and to AI tools, with the same entitlements everywhere. Its id is the scope text.
- **An API per measure** for programs and the explorer, under `/api/v1/<measure>` (VaR: `/api/v1/var`,
  [API_GUIDE.md](../guides/API_GUIDE.md#var-engine-apiv1var-280)). Every answer carries a **provenance** block: business date, the input
  tables' versions and batch ids, the scenario or parameter set, the rows read, unmapped rows, the computation time, and cache hit or miss.
- **Captures:** a user may capture any answer as an immutable record (who, when, scope, note, the inputs' versions, the figures and the
  vectors needed to reproduce it). It is kept in the Drishti database or content store, reopened by id (`CAPTURE:<id>` as a scope), and
  compared with another capture or with now.
- **Official end-of-day captures:** after a date's inputs are complete, Drishti captures each hierarchy node's figures as the official
  record (from 2.8.0). That is the only stored copy of a computed figure, and it is a capture, not an input.
- **Published official results (product owner, 2026-10-25).** So that other systems (reporting, regulatory feeds, a data warehouse) can
  read yesterday's official numbers as a table, every measure also **publishes its official end-of-day captures** to a separate,
  read-only results area of the lake, never mixed with the inputs:

  ```
  ${DRISHTI_DELTA_ROOT}
  ├── risk/                                   inputs (section 2): engines read these
  └── risk-results/                           published official results: engines never read these
      ├── var-official/                       one Delta table per measure
      ├── frtb-official/
      └── ...
  ```

  - **One table per measure** (`<measure>-official`), one row per hierarchy node (both hierarchies) per business date: `BUSINESS_DATE`,
    `HIERARCHY`, `NODE_ID`, `NODE_NAME`, `PARENT_ID`, `LEVEL`, the measure's headline figures (each contract's section 9 lists them),
    `CAPTURE_ID` (the official capture it was written from), `RESULT_VERSION` (1, then 2, 3 … on a restatement), `INPUT_VERSIONS` (the
    input tables' versions and batch ids, as JSON text), `COMPUTED_AT` (UTC), `CONTRACT_VERSION`.
  - **Written by Drishti only**, right after the official capture of the date, as a Delta commit per date. A restated input date
    produces a new official capture and appends its rows with the next `RESULT_VERSION`. Nothing is overwritten; the latest version of
    a date is the row set with the highest `RESULT_VERSION`.
  - **Never read back by an engine.** Engines compute from `risk/` only; a guard test fails the build when an engine's connector points
    at `risk-results/`. This keeps a published result from ever becoming an input.
  - **Kept 7 years**, like the inputs.
  - **Readers:** any Delta reader with read access to that folder, or Drishti itself through a read-only connector for the published
    history. Access is granted per folder by the storage, and the folder holds aggregate figures by node only (no trade or position rows),
    so publishing does not widen who sees trades.

## 12. Entitlements, masking and audit

- **As the caller:** every answer is computed over the trades the caller may see. A scope naming trades they may not see answers `403`.
- **Aggregate-only roles:** per role and hierarchy node, a role may see figures but no positions or trades below a node
  (`drishti.var.access` for VaR; each measure has the same setting under its own name).
- **Masked fields** stay masked in every answer, every export and every capture.
- **Audit:** computations of large scopes, captures, basket changes and reloads are audited with the user, the scope and the time.
- **Command switches:** an administrator can switch any command off (Admin → Commands). It then answers "not active" everywhere: command,
  link, API, CLI, search, exports and AI.

## 13. Versioning a contract

- Each contract has a version (`1.0`, `1.1`, …) at its top and a change table at its end.
- **Additive changes** (a new optional column) are a minor version. Engines may send it before Drishti reads it, and Drishti ignores
  columns it does not know.
- **Breaking changes** (a renamed or removed column, a changed unit, sign or order) are a major version. Both versions are read during
  one release, and the release notes say when the old one stops.
- A table may carry `CONTRACT_VERSION` (text) on every row. When it is absent, the current version is assumed.

## 14. Retiring the stored version

Every command that moves to the compute model retires its stored version **in the same release**; the two never coexist
(product owner, 2026-10-25). For each:
1. **Links keep working.** The old command, its old ids and every link open the computed page at the matching scope (`VAR-COMM` opens
   `VAR DESK:COMM`, built in 2.8.0).
2. **The stored kind is removed:** its samples, test data, Sutra, connector routes, about texts and generators.
3. **The numbers are reconciled first.** The computed figures are compared with the last stored ones on the test data, and the release
   notes explain every difference (for example, the stored `VAR-COMM` read 446.9m while the engine computed 1.97m on a sample and 17.5m on every live trade: VAR_GUIDE.md section 21.2).
4. **A guard test** (`tools/test_contracts.py`, `TheRetiredStoredVersions` for VaR) fails the build when a retired kind returns, or when an input table or a fundamental kind carries a column a compute
   engine produces (`VAR_99`, `ES_975`, `COMPONENT_VAR`, `EAD`, `PFE_PEAK`, `UTILISATION` …).
5. **Docs and CHANGELOG** say what was retired, what replaced it, and how old links behave.

## 15. The sections of every contract

Each contract in this folder has the same sections, in this order, so any two can be read side by side:

1. **Summary:** what the measure is, the commands, what is retired, the release and status.
2. **The measure in one page:** what it answers, for whom, and the formula in words.
3. **Inputs from upstream engines:** each table: path, grain, key, columns (name, type, required, unit, meaning, example), partitioning
   and sort, row counts, cadence and due time.
4. **Reference and master data read:** the other kinds read, by which key, and what for.
5. **Conventions specific to this measure:** signs, units, currencies, conventions that differ from this page.
6. **The computation:** the formula, step by step, with the regulation or methodology it follows, and its parameters (settings with
   defaults).
7. **What Drishti builds:** the structures in memory and on disk (aggregates per node, indexes, caches), their sizes, and what is loaded when.
8. **Scopes:** which scopes the measure answers, what is additive and what is not, and how a scope is resolved.
9. **What Drishti answers:** the computed kind's record (each field), the API (each endpoint, request and response with types), captures,
   and the CSV exports.
10. **Validation, errors and data quality:** the checks, the reject rules and the DRS codes.
11. **Entitlements and audit.**
12. **Performance and sizing:** the targets and the measured numbers.
13. **Test data and generators:** what the repository ships and how it is made.
14. **Retiring the stored version:** what is removed, how old ids map, and the reconciliation.
15. **A worked example:** small numbers, end to end, that a reader can check by hand.
16. **Open questions and the change table.**


## 16. Contracts of every command

Every command (mnemonic) of every pack has a **data contract of its own data**, so anyone can see the shape of what a command holds and
act on it: one YAML file beside its pack, `config/packs/<pack>/contracts/<kind>.contract.yaml`, and a section of its pack's page under
[packs/](packs/README.md) (the index of every command, its class and status). The pages are generated from the YAML files; the YAML files
are the contracts.

**What a contract file says** (checked against `tools/contracts.schema.json`):

| Key | What it holds |
|---|---|
| `kind`, `mnemonic`, `pack`, `version` | Which command; the contract's version ([section 13](#13-versioning-a-contract)), `"1.0"` today |
| `class` | `fundamental`, `input`, `drishti-owned` or `computed` ([section 3](#3-four-kinds-of-data)) |
| `status` | `active`, or `retiring` with `retiring-in` (the release) and `replaced-by` (the contract in this folder that replaces it) |
| `title`, `description`, `source` | The command's label, what it shows, and the upstream system (or `public data: <publisher>`, or `computed by Drishti: ...`) |
| `grain`, `key`, `id-pattern` | One sentence: one row per ...; the business key; the id patterns of the pack's graph |
| `dated-by`, `cadence` | The business-date field (`business_date` for the lake's partition, `snapshot`, or `on request`); `frequency` (`daily`, `intraday`, `static`, `on change`) and `due` |
| `storage` | The connector and plugin; for Delta the domain, the table path under the lake root, the promoted columns, the vectors, the partitions and the test-data folders |
| `fields`, `nested` | Each field: `name`, `type` ([section 5](#5-types-names-and-encodings), plus `record`, `text list`, `id list`, `date list`, `list` and `unknown`), `presence` (`required`, `optional`, `derived`), `unit`, `description`, `example`, `enum`, `pattern`, `link`; a figure that a compute engine will produce is `derived` with a `note` (and `retiring-in` when its record stays). `nested` lists the fields of each nested path (`TRADES[]`, `KYC`) the same way |
| `links`, `validation` | The fields that link to another kind; the rules: `required`, `unique` (key per date), `enum`, `pattern`, `link`, `range`, `vector-length` |
| `notes`, `review` | What a reader should know; the points a person must still confirm (anything the draft inferred rather than read) |

**How a contract is made and kept true** (`drishti.py contract`, [TOOLS.md](../guides/TOOLS.md)):
- `contract bootstrap KIND --write` drafts one from the pack (mnemonics, graph, connectors and their layout), its samples and test data
  (`data/testdata`), its `config/about.yaml` glossary (descriptions, units, values) and its Sutras (labels). The product owner's decisions
  that the data cannot tell (class, retirement, which fields are computed figures) come from `tools/contracts.policy.yaml`. What the draft
  infers instead of reads (required from 100% coverage, an enum from a few values, a number's type from its name, a missing unit or
  description) goes to `review`, never into the contract as a fact. From then on people own the file: confirm a point, edit the
  contract, remove the point.
- `contract check` fails when a command has no contract, a contract breaks the schema or no longer names its pack and command, a
  retirement differs from the policy, or the contract no longer matches the pack and its data (a field added or gone, a required field
  empty, a value outside an enum, a changed type, link or id pattern). `contract docs` writes the pages; `--check` fails when they are stale.
- Tests: `tools/test_contracts.py` (every contract against its data) and `drishti-console/tests/test_data_contracts.py` (one contract per
  command, valid, pages current).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
