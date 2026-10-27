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
# Pack modelling guide: kinds, data and content in depth

The topics of a pack in depth, after [PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md) has built one end to end: large kinds and the lake layout, several source systems for one kind, derived kinds, samples, deep documents, vocabulary, About text and glossary, guides and help, roles and field masks, fragments and docstrings, and Calc snippets. These sections were part of the pack developer guide; they moved here so that guide stays readable, and its old headings point here.

Related: [PACK_LIFECYCLE_GUIDE.md](PACK_LIFECYCLE_GUIDE.md) (inheritance, tests, versions, the registry, loading and verifying) and [PACK_GENERATORS_GUIDE.md](PACK_GENERATORS_GUIDE.md) (generators, the README standard, JSON Lines).

## Large kinds: the lake layout

A kind with millions of entities a day (a bank's trades) is stored so that readers never load a whole day. The pack
declares, on its Delta connector, which document fields are also stored as columns, and how the table is sorted. From the
trading pack's `trade-endur` template (`trade-calypso` and `trade-murex` are the same but for `table:`):

```yaml
connectors:
  trade-endur:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [trade]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
      domain: trading
      table: { trade: trade_endur }    # the kind's table under <root>/trading/ (default: the kind's name)
      union: true                      # one part of a kind several connectors serve together (next section)
      source-name: trade-endur         # the name in provenance and Health
      layout:
        trade:
          columns: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, TRADE_STATUS, TRADE_VERSION,
                    BUY_SELL_CODE, CURRENCY, QUANTITY, TRADE_DATE, MATURITY_DATE, COUNTERPARTY, LEGAL_ENTITY, PORTFOLIO, TRADER_ID]
          sort-by: id               # each business day sorted by id, cut into files of file-rows
          file-rows: 250000
          row-group-rows: 1000      # opening one trade decodes one row group of documents
```

- **The writers follow it.** `drishti.py lake build` (and `lake demo`, which calls it for every table) writes the table this way: `id`, the
  document (`doc`), `business_date`, the partition columns it is given (`--partition-by PRODUCT_TYPE` for the trade tables) and one column per
  promoted field (a nested path such as `INDEX.NAME` is the column `INDEX__NAME`; numbers as `float64`, everything else as text). A bank's
  own loader (Spark, Databricks) writes the same columns; `drishti.py lake maintain relayout` rewrites an existing table into the layout, a
  day at a time, and leaves days already in it alone. It finds a table's layout by the table's folder (`trade_endur` is the table of the
  connector whose `table.trade` names it), and keeps a table's own partitions (`business_date` and `PRODUCT_TYPE` for the trade tables).
- **The server uses it.** Opening a trade reads one row group of one file (the id says which). Type-ahead indexes the
  id column alone. Searches, pick lists, the search form's drop-downs, derived kinds and impact (F8) read the columns, over every
  trade of the day, exactly, without parsing a document; a query that uses a field that is not a column reads documents as
  before. Field masks apply to column values exactly as to documents. BI datasets over the kind read the columns too.
- **Health tells you.** A table that lacks a declared column shows in Admin → Health:
  `UP (not laid out as the pack declares: trade (12 of 16 columns); searches read documents)`.
- **Choose the columns** a desk searches and lists by: the pick-list `columns:` of the kind, its `pivot` and `form` fields, the fields in its
  alerts, impact measures and derived kinds, and the link fields reverse lookups follow (`COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`). Each
  column costs little: repeated values (books, currencies, statuses) compress to almost nothing.
- **Flattened fields.** A value inside a list (the first leg's notional) cannot be a promoted column as it stands. The pack's `ingest`
  block names such values, and `lake build --pack` writes each as a plain field of the document and a promoted column:

  ```yaml
  ingest:
    trade:
      key: TRADE_ID                    # lake build --pack needs no --key
      flatten:
        NOTIONAL: LEGS[0].NOTIONAL
        INDEX_NAME: LEGS[0].INDEX.NAME
  ```

  A field the document already has is never replaced, and a path a document lacks adds nothing. Searches, Sutras, grouped pages and BI
  then read `NOTIONAL` without the document. A table that exists keeps its columns: the flattened columns appear in tables built from
  scratch (`--mode replace`, or a new lake), so rebuild a table to add them; until every system's table has them, a search over the
  three systems on that field reads documents. (`layout.<kind>.flatten` on a Delta connector is read the same way.)
- **Business dates.** Each table is partitioned by `business_date`; `lake build` writes one date per run (`--as-of`) or the dates a
  field of the documents names (`--date`), and a later run for the same date replaces it. The data-load signal tells a server a date landed
  ([DATA_LOADS.md](DATA_LOADS.md)).

The measured effect, for 1,000,000 trades a day, is in [PERFORMANCE.md](../admin/PERFORMANCE.md#a-book-of-a-million-trades-a-day); every option
of `lake build`, `lake demo` and `lake maintain` is in [TOOLS.md](TOOLS.md).

## Several source systems for one kind: the trading pack

Production rarely has one system of record for a kind. The trading pack serves `trade` from three booking systems, kept apart from
the source to the screen, and is the pattern to copy:

1. **One schema.** Every system's trade has the same 47 UPPER_SNAKE top-level attributes, nested the same way (`LEGS[] → INDEX,
   CASH_FLOWS[] → RESETS[]`, `EVENTS[]`), with `SOURCE_SYSTEM` saying where it came from ([PACKS.md, Trading](PACKS.md#trading) lists them).
   An extract that names an attribute otherwise is mapped on load (`BMO_LEGAL_ENTITY` is read as `LEGAL_ENTITY`;
   `tools/packgen/trades/schema.py`, `RENAMED`).
2. **Ids unique across systems.** Endur deal numbers (`1000001`…), Calypso `CLY-…`, Murex `MX-…`: an id opens one trade whichever system holds it.
3. **One table and one connector per system.** `<root>/trading/trade_endur`, `trade_calypso`, `trade_murex`, each partitioned by
   `business_date` and `PRODUCT_TYPE`, each read by its own connector with `union: true` and its own `table:`; the partition and promoted
   field names are the same in every table. A system's load (a rebuild, a late file) touches only its own table.
4. **One kind.** All three connectors serve `trade`, so a read finds a trade in whichever holds it, and a search, a type-ahead, the form's
   drop-downs and a BI table read the three together; each row keeps its `SOURCE_SYSTEM` (and the console labels results by source).
   The route names one of them (`trade: trade-endur`); the union is read whole whichever it names.
5. **One Sutra per system and product type**, matching on both fields, with sub types as `when:` panels and the shared panels in
   blocks (step 11, [Many Sutras for one kind](PACK_DEVELOPER_GUIDE.md#step-11-a-sutra-the-layout-you-want)); **Sutra coverage** proves every combination in the
   data has exactly one: on the demo lakes `GET /api/v1/packs/trading/coverage?kind=trade` answers `combinations: 132, none: 0, several: 0`,
   each row like `{'values': {'SOURCE_SYSTEM': 'CALYPSO', 'PRODUCT_TYPE': 'ABS', 'PRODUCT_SUB_TYPE': 'Asset-backed security'}, 'count': 96,
   'example': 'CLY-3000223', 'sutras': ['abs'], 'state': 'one'}`.
6. **Derived pages across systems.** `CTRD`, `PTRD`, `LTRD`, `ITRD` are derived kinds over `trade` (the `trade-pages` connector), so a
   counterparty's page gathers its trades from every system ([Derived kinds](#derived-kinds-entities-computed-from-other-kinds)).

The test data in `data/testdata/trades/{endur,calypso,murex}/` (5,000 trades per system over sixty business days: a day-1 snapshot and
daily changes) and `drishti.py lake demo` build the three tables; `tools/packgen/trades/` generates both the test data and the pack's
samples ([How the shipped packs are generated](PACK_GENERATORS_GUIDE.md#how-the-shipped-packs-are-generated)).

## Derived kinds: entities computed from other kinds

A derived kind is a kind no source holds: the server computes it from another kind by grouping and adding up. A
counterparty's trades from every system, a book's P&L from its trades, an agent's open tickets. A pack declares one as a
connector of the built-in plugin `derived` and routes the kind to it. The trading pack ships four (`CTRD`, `PTRD`, `LTRD`, `ITRD`);
the counterparty one:

```yaml
connectors:
  trade-pages:
    plugin: derived
    kinds: [counterparty-trades, portfolio-trades, legal-entity-trades, index-trades]
    settings:
      store: disk               # the pages on disk, the heap keeping each group's totals only (default heap)
      refresh: 60s              # rebuild age, only when a source cannot say its data's version (default 30s)
      counterparty-trades:
        from: trade             # the kind it is built from (read through the normal routing: here the three systems' union)
        group-by: $.COUNTERPARTY  # Rachana-EL over a member: its group, which is the derived entity's id
        id-field: COUNTERPARTY  # a field holding the key besides `id` (default: id only)
        members: TRADE_IDS      # the members' ids, sorted (default: members)
        max-members: 1000       # members listed (and rows); TRADE_COUNT and the totals count every member
        rows-field: TRADES      # where the per-member rows go (default: rows)
        fields:
          TRADE_COUNT: count
          NOTIONAL: sum first($.LEGS).NOTIONAL
          SOURCE_SYSTEMS: distinct $.SOURCE_SYSTEM
          PRODUCT_TYPES: distinct $.PRODUCT_TYPE
          CURRENCIES: distinct $.CURRENCY
          LAST_MATURITY: max $.MATURITY_DATE
          # the payments ladder over every member: PAY_7D, PAY_30D, PAY_90D and their _EVENTS counts
          PAY_7D: "sum sum(flatten($.EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)][?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 7], 'AMOUNT')"
          PAY_7D_EVENTS: "sum size(flatten($.EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)][?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 7])"
          # … PAY_30D, PAY_30D_EVENTS, PAY_90D, PAY_90D_EVENTS
        rows:                   # optional: one row per member, for the page's tables
          TRADE_ID: $.TRADE_ID
          SOURCE_SYSTEM: $.SOURCE_SYSTEM
          PRODUCT_TYPE: $.PRODUCT_TYPE
          NOTIONAL: first($.LEGS).NOTIONAL
          MATURITY_DATE: $.MATURITY_DATE
          INDEX_NAME: nonblank($.LEGS[0].INDEX.NAME, $.LEGS[1].INDEX.NAME, '')
          EVENTS: $.EVENTS
          # … TRADE_STATUS, BUY_SELL_CODE, CURRENCY, COUNTERPARTY, PORTFOLIO
routes:
  counterparty-trades: trade-pages
```

- **Keys.** Each value of `group-by` is one entity (a member whose `group-by` is empty, or whose `where` is false,
  counts in no group). The entity's id is the key; the document has `id`, the `id-field`, `derivedFrom` (shown as *Derived from*), each
  field, the members' ids (at most `max-members`, 1,000 by default), `memberCount` (all of them; *Member count*) and the rows when declared.
  (`derivedFrom` and `memberCount` are written by the engine and keep those names whatever your pack's convention.)
- **Fields.** `count`; `sum`, `avg`, `min`, `max` over an expression (values that are missing or not numbers are
  skipped); `distinct` (the sorted distinct values); `first` (the first member's value, members in id order).
- **`where`** keeps only the members that count (the help-desk pack's `where: $.STATUS != 'Closed'`).
- **Beyond grouping (2.9.2).** `median` is a field aggregate too; `also-group-by` puts a member in further groups (a region, an income
  group, `'ALL'`); `then` works fields out over the page (a ratio, a weighted average, `group(...)`); `sort` orders a list field;
  `tree` builds the group's own hierarchy from a parent column; `unlisted` keeps a page over everything to its figures; `search-fields`
  lets the type-ahead find a group by a page field. The gleif, us-equities and world-economy packs compute LEIG, LEIC, SIC and ECG this
  way ([GROUPINGS_ANALYTICS_GUIDE.md](GROUPINGS_ANALYTICS_GUIDE.md) Part III; every setting in
  [the derived connector's README](../connectors/types/derived/README.md)).
- **Links.** Declare the members field under `graph.fields` (`TRADE_IDS: { kind: trade, label: Trade }`) and the
  members show as links; an `id-field` that is already a link field (`COUNTERPARTY`) links the derived entity to its key's.
- **Business dates.** A picked date is computed from that date's members, so a derived kind has history wherever its
  members do, and *Data for* says which day.
- **`today()`** in a field's expression is the date the pages are computed for: the business date asked for, or today's calendar
  date (in `zone`, `America/New_York`) for the latest pages. The payments ladder counts the events from that date on.
- **Cost.** One computation reads every member once per business date, and is shared by every reader and by all the derived kinds of
  the connector built on the same kind. It reads from the columns where the source keeps every field it needs; otherwise it reads the
  day's documents in one pass (a Delta table streams the date file by file), keeping only the values it needs per member. A source that
  cannot read a day in one pass is read one member at a time, up to `max-scan` (50,000).
- **When it is rebuilt.** Every `version-check` (10 s) the computation asks the sources for their data's version; a new version (a load)
  rebuilds it in the background while the old pages serve. Nothing else rebuilds it: `refresh` is used only for a source that cannot say
  its version.
- **Where the pages live.** `store: heap` (the default) builds every page in memory: right for small books; `max-stream` (100,000)
  bounds the members it keeps, and Health says DEGRADED with a WARN when it cuts. `store: disk` (the trading pack's `trade-pages`)
  writes each member's key and row to a sorted store under `<data dir>/derived/<connector>` and keeps only each group's totals in the
  heap, so the pages are complete at any number of members (1.16 million trades a day in an 8 GB heap). Pages on disk are kept across
  restarts (`pages.json` beside them) and served at once while their data is unchanged.
- **Failures.** Health shows `DOWN` with the reason when a computation fails; a computation that runs out of memory fails cleanly
  (DEGRADED, the old pages serving, no reader waiting). Admin → Caches → Purge (or `drishti.py server caches purge trade-pages`) drops
  the pages; with `store: disk` the latest date is rebuilt from the lake at once. Every setting is in
  [CONFIGURATION.md](../admin/CONFIGURATION.md#derived--kinds-computed-from-other-kinds).
- **Everything else is a kind like any other.** Give it a mnemonic, a Sutra (or let inference lay it out), roles,
  alerts, a form and pick-list columns as usual. It shows in search (`CTRD TRADE_COUNT > 500`) and the type-ahead.
- **Mistakes fail the start of the connector.** A missing `group-by`, an unknown aggregate, a field without its expression, an
  expression that does not parse or a kind built from itself puts the connector under *failed to start* in Admin →
  Health, with the reason.

## Samples

The built-in `demo` source serves every enabled pack's `samples/` folder (when `DRISHTI_DEMO_ENABLED` is `true`, the default), so a pack
works with no database. The format:

`samples/catalog.json` lists every sample. The demo source needs only `kind` and `id`; give `title` and
`subtitle` too, since they are what type-ahead shows. From the trading pack:

```json
[
 { "kind": "trade", "id": "1000001", "title": "1000001", "subtitle": "Trade · ENDUR · ComSwap · CP-HARBORPT · USD 168.0m" },
 { "kind": "trade", "id": "1000002", "title": "1000002", "subtitle": "Trade · ENDUR · ComSwap · CP-LUMEN · USD 22.1m" }
]
```

`samples/<kind>.jsonl` holds the documents of one kind, **one document a line** (JSON Lines), in the catalogue's order;
each is the document itself plus a `_meta` block that becomes its provenance (shown in *How this view was built* and in
`F9`). One line of `config/packs/core/samples/desk.jsonl` (shortened):

```json
{"DESK_ID":"DESK-COMM","NAME":"Commodities","HEAD":"T. Brennan","BOOKS":[{"ID":"BOOK-COMM-1","TRADES":28,"MTM":7760837}, …],"VAR_99":10946782,"MTM":14609595,"LEGAL_ENTITY":"LE-NY", …,"_meta":{…}}
```

A line's id is its catalogue entry: the entry at its position when the document holds that id as a top-level value
(writers keep the catalogue's order), else the first top-level value the catalogue lists as an id of the kind. A kind
whose file would pass 50 MB is split into `samples/<kind>.part-001.jsonl`, `part-002` … (the generators' and `pack new`'s
`--sample-part-mb N`; the readers take the parts in order). The older layout, one `samples/<kind>/<id>.json` file per
document, is still read everywhere (the retired example packs keep it, and packs you wrote that way need no change);
new and shipped packs are written as JSONL (`tools/packgen/common/packsamples.py`, `pack new`, the pack wizard, the workbench's fragments).

| `_meta` key | Meaning |
|---|---|
| `source` | the name shown as the source system |
| `generation` | a version number shown with the source |
| `live` | `true`: the document ticks while someone watches it |
| `walk` | `{ field: step }`: numeric fields, named at the top level or by a dotted path into the document (`LEGS.1.CASH_FLOWS.0.RATE`), that random-walk on each tick by a normal draw whose standard deviation is `step` (usually within a step or two, but unbounded); a step of 1 or more keeps whole numbers. No code needed. |

An entry with no document (no line, or in the older layout no file) is skipped; one whose path would lead outside the
pack's `samples/` folder is skipped too.

**Samples, test data and lakes are three things.** `samples/` is a small, hand-checked set that makes the pack work on its own (765
trades); `data/testdata/` is the committed, production-sized test data the demo lakes are built from (15,000 trades over seven
business days); the lakes are what the connectors read. With the demo source on, a sample answers before the lake for an id both hold.

Realistic samples matter: they should look like what the real system would hold. `tools/samplegen` is a small,
standard-library-only toolkit for that:

| Module | What it gives |
|---|---|
| `ids` | LEI (ISO 17442 check digits), ISIN (Luhn), CUSIP, UTI, UPI, BIC |
| `dates` | business-day calendars (USNY, GBLO, EUTA, JPTO, CATO and joint calendars), tenors, schedules with stubs, day counts (ACT/360, ACT/365F, 30/360, 30E/360, ACT/ACT) |
| `curves` | zero curves with log-linear discount factors, forwards and the published pillar table |
| `legs` | fixed and floating legs with every calculation period: fixing dates, year fractions, fixings or projected rates, amounts, DFs, PVs and status |
| `blocks` | execution, lifecycle and audit, confirmation, clearing, regulatory reporting, settlement instructions, valuation and P&L history |

`tools/samplegen/test_samplegen.py` checks the library against known values.

## Modelling deep documents: legs, cashflows and fixings

A trading system holds a trade as a tree: the **deal**, its **legs**, each leg's **cash flows**, and each floating flow's **resets**
(fixings), with the deal's **events** beside them (tranches with their payments, shipments with their stops and events are the same
shape). Keep that shape in the document: a flat list of every cash flow with a leg number loses what belongs to what, and the Sutra can
show the nesting directly. The trading pack's common schema is the worked example:

| Level | Field of the document | Fields it carries |
|---|---|---|
| deal | the 45 deal attributes, `LEGS`, `EVENTS` | `TRADE_ID`, `SOURCE_SYSTEM`, `PRODUCT_TYPE`, `PRODUCT_SUB_TYPE`, `COUNTERPARTY`, `PORTFOLIO`, `CURRENCY`, the dates … |
| `LEGS[]` | one element per leg: 1 (a cap, a cash deal), 2 (a swap), more (a commodity swap with a fee leg) | `LEG_ID`, `LEG_TYPE` (FIXED, FLOAT, PHYSICAL, OPTION), `LEG_ECONOMIC_TYPE` (Pay, Receive), `NOTIONAL`, `CURRENCY`, `PRICE_UNIT`, `INDEX {NAME, GROUP, SUB_GROUP}`, `RESET_CONVENTION`, `SETTLEMENT_TYPE` |
| `LEGS[].CASH_FLOWS[]` | one element per calculation period (and the notional exchanges of a cross-currency swap) | `FLOW_ID`, `NOTIONAL`, `RATE`, `FLOAT_SPREAD`, `START_DATE`, `END_DATE`, `PAYMENT_DATE`, `RATE_DETERMINATION_DATE`, `STRIKE_PRICE` |
| `LEGS[].CASH_FLOWS[].RESETS[]` | one element per observation of the index in the period: one for a term rate, a business day each for a commodity average | `RESET_ID`, `RESET_DATE`, `RATE` … |
| `EVENTS[]` | cash and lifecycle events, each naming its leg by `LEG_ID` (blank for the deal's own) | `EVENT_ID`, `EVENT_TYPE`, `PAYMENT_TYPE`, `EVENT_DATE`, `AMOUNT` (signed: paid is negative), `CURRENCY` |

Rules that keep such a document honest (the generators' tests, `tools/packgen/trades/test_trades.py`, check them on every product and sub type
they book):

1. **Write what the extract writes.** Missing values as the system spells them (mostly `""`, a few `null`, `0` for amounts), numbers that
   arrive as text left as text; the Sutras read them with `nonblank(...)` rather than assuming a value. A real extract can have zero
   notionals and empty events: the screen must still read.
2. **Fixed flows simply have no resets.** Do not invent observations for a fixed flow; a floating flow's rate lies among its resets
   (the average, for a commodity average).
3. **Signs mean something.** An event's `AMOUNT` is negative when the bank pays it; the payment ladder nets by date on that sign.
4. **Every leg and flow has its id** (`LEG_ID`, `FLOW_ID`, `RESET_ID`), so events, Compare and the legs-side-by-side tabs can name them.
5. **Size.** A deep document is bigger than a flat one (a twelve-month commodity swap with daily resets is tens of kilobytes). That is fine
   for a view; keep the lake's rows to the sizes in [the lake layout](#large-kinds-the-lake-layout) and local test data small (10,000
   trades or fewer on a laptop).

Show it as the trading pack's `legs` block does: one `tabs` panel with `each: $.LEGS` and `layout: columns` (the legs side by side, each
holding its terms, its notional profile, its resets and its cash flows as a tree table that opens to the resets), as in
[the Rachana reference](RACHANA_REFERENCE.md#panels-inside-a-tab); and make the demo tick a nested cell with a path in `_meta.walk`
(see [Samples](#samples)). Without a Sutra, inference lays such a document out the same way. For more than a handful of legs, tabs stop
being the right control: use a `table` of the legs with `children`.

## Vocabulary: labels, formats and inference hints

Two optional files teach every screen the pack's words. They are read when the pack loads (and again whenever packs change, with no restart) and join the site's configuration at
the lowest precedence ([What becomes of each key](PACK_DEVELOPER_GUIDE.md#what-becomes-of-each-key)); a more specific pack's entry wins over
a parent's ([Inheritance](PACK_LIFECYCLE_GUIDE.md#inheritance)).

`config/formats.yaml` names formats a Sutra (or the vocabulary) can use as `fmt:`:

```yaml
formats:
  age0: { type: number, decimals: 0, suffix: " h" }       # 30 → "30 h"
```

Every key of a format, and the built-in ones, is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) (Formats).

`config/semantics.yaml` has four parts:

| Part | What it does | Example |
|---|---|---|
| `roles` | a field-name **regex** (case ignored) → how *inference* shows such fields (`role`, `fmt`, `tone`, `weight` for the strip, `dateOnly`). Tried before the core roles, in order | `{ role: age, pattern: "HOURS$", fmt: age0, weight: 80 }` shows `AGE_HOURS` as `30 h` |
| `labels` | field → label, **everywhere** (strips, tables, pick-list headings, a Sutra that gives no label). A field with no label is put in words (`OPEN_TICKETS` → *Open tickets*) | `AGE_HOURS: Age (h)` |
| `acronyms` | words kept in capitals when a field is put in words (`NET_MTM` → *Net MTM*, not *Net mtm*) | `core` lists `MTM`, `PV`, `DV01`, `CVA`, `ISDA` … |
| `idFields` | document fields that hold the id, so inference leads with it | `["TICKET_ID", "AGENT_ID", "CLIENT_ID"]` |

The roles only steer **inference**: a Sutra states its formats itself. Labels reach both. How inference scores a field and
how the hints fit in is in [INFERENCE.md](../architecture/INFERENCE.md). Mistake to avoid: a `labels` entry is global
to the server, so `STATUS: Ticket status` would relabel `STATUS` in every other pack that is loaded. Name labels for
fields that are yours (`AGE_HOURS`), and give a Sutra's strip entry an explicit `label:` for the shared ones.

## About text and glossary

The *About this page* drawer (`?` on a view; [how a user reads it](USER_GUIDE.md#about-this-page)) always shows where the
data came from and why the layout is what it is: those come from the engine. What only **you** can say is what a
kind is and what its figures mean. That is the pack's `config/about.yaml`, found beside `formats.yaml`,
`semantics.yaml` and `help.yaml`. The file is optional: without it the drawer still works.

![The drawer on an economy, with the world-economy pack's text](img/about/04-economy.jpg)

### The file

From `config/packs/market-risk/config/about.yaml` (generated; shown shortened):

```yaml
about: 1                           # the file's version, required
vocabulary:                        # entries to reuse, each under a name of your choosing
  var99:
    term: "Value at risk, 99%, 1 day"
    means: "The loss the portfolio is expected not to exceed on 99 of 100 trading days, read from the 1st percentile of the revalued scenario P&L (historical simulation)."
    unit: "USD"
    sign: "A loss, written as a positive number."
kinds:
  var:                             # a kind of this pack, or of a pack it extends
    title: Value-at-risk result
    about: >-                      # the sentence at the top of the drawer: a ${...} template over the document
      ${$.RESULT_ID} is a 1-day 99% historical VaR for ${coalesce($.DESK_NAME, $.DESK, 'this portfolio')}:
      ${fmt($.VAR_99, 'compact')} USD, ${fmt($.VAR_99 / $.LIMIT, 'pct0')} of its ${fmt($.LIMIT, 'compact')} limit,
      with ${$.EXCEPTIONS} exception(s) in 250 days.
    guide: market-risk             # slug[#anchor] of the pack's guide, linked from the drawer
    glossary:                      # one entry per field, keyed by its path (dots between names)
      VAR_99: { use: "var99" }     # reuse a vocabulary entry
      EXCEPTIONS:
        term: "Backtesting exceptions"
        means: "The days in the last 250 on which the day's loss was larger than the 99% VaR. Basel's traffic lights count them to judge the model."
        unit: "days"
        note: "0-4 is the Basel green zone, 5-9 amber and 10 or more red."
```

### Keys and rules

| Key | Where | Type | Rule |
|---|---|---|---|
| `about` (file) | top | integer | required, `1` (`DRS-2040` otherwise) |
| `vocabulary.<name>` | top | entry | `<name>` is a name without dots |
| `kinds.<kind>` | top | map | the kind must belong to this pack or a pack it `extends` (`DRS-2041`) |
| `title` | kind | text | plain |
| `about` | kind, `panels.<id>` | template | `${...}` expressions of the Rachana expression language, compiled when the pack loads (`DRS-2042`, with line and column) |
| `guide` | kind | `slug[#anchor]` | a help-centre guide; the console's help-link test checks it |
| `glossary.<key>` | kind | entry or `{ use: <name> }` | the key is a field path, dots between names, arrays not a step (`HISTORY.DATE`; the rule of `drishti.security.redact`); `use` must name a `vocabulary` entry visible to this pack (`DRS-2043`) |
| entry: `term`, `means`, `unit`, `sign`, `note` | entry | plain text | at most `drishti.about.max-text` (600) characters each (`DRS-2044`); never evaluated, so no `${}` |
| entry: `formula` | entry | plain text | shown as written; a derived kind's own fields get their formula automatically |
| entry: `values` | entry | map | the meaning of each value of an enumerated field, shown for the value on the page |
| `panels.<id>` | kind | `{ about }` | a note shown for that panel; the id should exist in some Sutra of the kind (`DRS-2046` warns otherwise) |

Unknown keys are errors (`DRS-2040`): the parser is strict, so a typo is found when the pack loads. A broken entry is
left out (the view and the drawer still work) and is reported with file, line and column on the admin *Sutras* page's
problems list and in `GET /api/v1/sutras/problems`, under the key `<pack>/<file>`.

### What the template sees: your readers' view, not the stored document

`about` text is rendered over the document **as the person asking may see it**. A field named in
`drishti.security.redact` reads `•••` in the sentence for a role without `raw`; do not try to work around it, and do not
put values in a glossary entry (entries are never evaluated). An expression that fails renders `—` and is counted
(`drishti.explain.template-errors`), never shown as an error. A rendered text is capped at `drishti.about.max-rendered`
(1,000) characters. Formats are the pack's named formats (`fmt($.X, 'compact')`, your `formats.yaml` names too).

![For a viewer the masked limit reads as the mask in the sentence (a demo: shipped setups do not mask `LIMIT`)](img/about/05-viewer-masked.jpg)

### Inheritance: `extends`

The catalogue is built from every loaded pack's file in the order `extends` defines, **most specific pack first**
(child first, parents right to left: the same order as `semantics.yaml`). A child can replace any entry by its key
(write the whole `{ term, means }` again) and cannot delete one. There is no inheritance between kinds. Put a shared
word in the lowest pack that owns the concept (`MTM` in `trading`, which `market-risk` and `counterparty-risk` reach
through `extends`), as a `vocabulary` entry, and point to it with `use:`.

How a field's entry is found ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md)): the kind's `glossary`
in the most specific pack, then a `vocabulary` entry with the field's last name, then, for a derived kind, the entry
generated from its definition ("Sum of `NOTIONAL` over the trades of the counterparty"), then nothing: the field shows no hint. The
explain answer (`GET /api/v1/views/<kind>/<id>/explain`) lists each shown field's entry with its `origin` (`helpdesk:glossary.PRIORITY`).

### Translations

A pack can say the same things in another language: `config/about.<lang>.yaml` beside `about.yaml` (`<lang>` is a language
tag such as `fr` or `fr-CA`). It has the same shape and the same strict parse (`DRS-2040` to `DRS-2044`, reported under
`<pack>/about.fr.yaml`) and is laid over the English file **key by key**: whatever it does not translate stays English,
so a first translation can be one kind, or one sentence. For a regional tag the plain language fills in before English
(`fr-CA`, then `fr`, then `en`). The language of an answer is `?locale=` on the explain request, else the user's *Language of
help text* on the account page, else the browser's `Accept-Language`; the first of those with an overlay wins, and the
answer's `locale` says which was used. Numbers in a template use the pack's named formats and are not localised. The
shipped example is `config/packs/market-risk/config/about.fr.yaml` (the VaR page in French).

### Generated packs

A pack built on **public data** (world-economy, news-and-markets, market-data) has its own refresh script in
`tools/publicdata/` (one a pack: fetch with a polite cached client, normalise, write, then the pack's check; licences and the
internal-only rule in each source class). To add a source or a script for a new pack, see [PUBLIC_DATA.md](PUBLIC_DATA.md#adding-a-source).

The shipped packs are generated (see [How the shipped packs are generated](PACK_GENERATORS_GUIDE.md#how-the-shipped-packs-are-generated)).
Their `config/about.yaml` (and any `about.<lang>.yaml`) is copied from a hand-kept source in the generator folder
(`tools/packgen/banking/about/<pack>.yaml`, `tools/packgen/<generator>/about.yaml`) by `make_packs.py` / `packbuild.py`: edit
that source and regenerate, never the generated file; `--check` fails when they differ. `sutra test` (with `help: { about: true }` in
`expect.yaml` and a `tests/help-coverage.txt` ratchet in the pack) keeps every shown field explained.

### Check it

- Load the pack: a file with a problem lists it (codes `DRS-2040` to `DRS-2044`, [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md)).
- Open an entity and press `?`: the first section is your sentence, filled in. Try it as a user whose role is not
  `raw`: masked fields read `•••`.
- `sutra lint config/packs/<pack>` warns (`DRS-2046`, `DRS-2047`) about a `panels.<id>` that matches no panel and about each field a Sutra
  shows that has no glossary entry (`ticket.v1.sutra.yaml:15 warning DRS-2047 field 'ASSIGNEE' is shown but has no glossary entry
  (kinds.ticket.glossary in config/about.yaml, or a vocabulary entry)`); `sutra test` reports *help coverage n/m* per Sutra;
  `drishti.py pack about-check` lists the gaps per Sutra and exits `1` while any remain; `pack check --strict` fails on them.
  `expect.yaml` takes `help: { coverage: 0.9, about: true }` (the share of shown fields with an entry, and that `about` renders without an error).
- The Build workbench has an **About** tab to write and preview this text over your samples: the card of the previewed entity, the problems with their lines and the lint warnings, kept with the design and exported as `config/about.yaml` in the pack fragment ([SCREEN_DESIGNER.md](SCREEN_DESIGNER.md#the-about-tab)). It is a draft until you put the file in the pack: approving the design's Sutra does not make the About text live.

## Guides and help

A pack's help appears in the help centre (`/help`) next to the core guides. `config/help.yaml`, from the help-desk pack:

```yaml
guides:
  - { slug: helpdesk-pack, category: start, title: "The help-desk pack", kind: guide, icon: headset,
      file: guides/helpdesk.md, summary: "Tickets, agents and clients." }
contextual:                     # optional: which guide F1 opens on a screen
  terminal: helpdesk-pack
```

The shipped packs file their guides under `category: packs` (*Help → Domain packs*): `core` has `core` and `banking-data-model`, `trading`
has `trading` and `trading-products`, and so on.

| Key | Meaning |
|---|---|
| `slug` | the guide's address: `/help/helpdesk-pack`. Must be unique across all packs. |
| `category` | where the card goes: `start`, `packs`, … (the categories in `drishti-console/config/help.yaml`) |
| `title`, `summary`, `icon` | the card. `icon` is a Bootstrap Icons name without the `bi-` prefix. |
| `kind` | `guide` or `tutorial`: the badge shown beside the title |
| `file` | the Markdown file, relative to the pack folder |

Screens for `contextual` include `landing`, `terminal`, `view`, `studio`, `workspace`, `monitor`, `alerts`,
`impact`, `admin`, `account` and `help`.

Write the guide in Markdown with the copyright header comment at the top. Describe the domain (what each kind
is, how they link, where the data comes from) rather than listing sample records, and include a **Finding
things** section like the one in [PACKS.md](PACKS.md#trading), so readers know the commands work on their own data too. Links to other help documents with a relative
path (`[Sutra guide](SUTRA_DEVELOPER_GUIDE.md)`) open inside the help centre.

## Roles and field masks

A pack can add roles. A role says which kinds its holder may open and what else they may do. From the shipped packs:

```yaml
roles:
  trader:      { kinds: [trade, ir-curve, repo-curve, fx-spot, …, counterparty, book, …] }    # trading
  credit-risk: { kinds: ["*"], raw: true, calc: true }                                        # counterparty-risk
```

| Field | Meaning |
|---|---|
| `kinds` | the kinds the role may open; `"*"` means all. Links to other kinds show disabled, with the reason. |
| `raw` | sees every field: nothing in `drishti.security.redact` is masked for the role (otherwise those fields read `•••` in raw JSON, views, search, Impact and the type-ahead) |
| `author` | may use the Build workbench and propose Sutras |
| `approve` | may approve Sutra proposals |
| `admin` | may administer users |
| `calc` | may use Calc, Python in the browser on what the role opens ([PYTHON_CALC.md](PYTHON_CALC.md#9-roles-who-may-use-calc)) |

The core always has `viewer`, `author`, `approver` and `admin`. Roles are given to users by an administrator; see
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md). Security is off by default for local development; roles take effect when
it is on.

A pack's roles appear in *Admin → Roles* marked **built-in**, read-only: change them in the pack (or, for a
generated pack, in its generator). An administrator can also define new roles there without touching any pack;
the dialog's *Add every kind of a pack* buttons fill in a pack's kinds in one click
([USER_GUIDE.md](USER_GUIDE.md#admin--roles-what-a-role-may-do)).

### Field masks: what a role without `raw` sees

What is hidden is **site** configuration, not a pack key: `drishti.security.redact` names fields, and a role without
`raw` sees every such field as `•••` on every path that shows or computes from its value (raw JSON, views, search,
Impact, the type-ahead, alert rules, Calc, the workbench's previews, BI datasets). A pack therefore decides *who is exempt*
(`raw: true` on its lead roles) and *which kinds a role may open*; the site decides *which fields are sensitive*.
Write your pack so that it works with any set of masked fields: a view that shows `•••` for a figure is correct, and
a test against sample documents always sees the unmasked values (tests run without roles).

How the setting works, and what it deliberately does not hide, is in
[API_GUIDE.md › Field masks](API_GUIDE.md#field-masks):

- **dotted paths**: a `redact` entry with dots names a field by the end of its path, so
  `lifecycle.timeline.description` masks every `description` under `lifecycle.timeline` and no other;
- **masks work on fields**, so a sensitive value copied into free text (a ticket's history line "Called J. Smith")
  stays readable unless the site turns on `drishti.security.mask-copies`, which scrubs exact copies of masked values
  from the other text of the same document. For the help-desk pack, a site that masks `CLIENT_NAME` should also turn it
  on, because `HISTORY` events may name the client;
- the setting list, with defaults, is in [CONFIGURATION.md](../admin/CONFIGURATION.md) (`drishti.security`).

Packs that carry personal or confidential fields should say so in their guide, so that an administrator knows what to
put in `redact`. The shipped setting masks the trader's identity on a trade (`TRADER_ID`, `TRADER_FIRST_NAME`, `TRADER_LAST_NAME`), the
counterparty's identifier (`counterpartyId`, which matches `COUNTERPARTY_ID`), and the `trader`, `patientName` and
`lifecycle.timeline.description` of the camelCase example packs, for exactly this reason. Names match in any case and convention (`counterpartyId`
masks `COUNTERPARTY_ID`); an entry that names no field of the loaded packs is logged at start-up as `DRS-6011`.

## Fragments and docstrings

A Sutra's panels need not be written out. The **fragment library** (2.7.0, [SUTRA_NEXT.md](../architecture/SUTRA_NEXT.md)) holds
generic, parameterised panels named after what they show. They are the **Drishti UI kit** (`config/ui-kit/fragments`,
`drishti.ui-kit.dir`): platform vocabulary the server and `sutra lint|test` always load, whatever packs are enabled, so every
Sutra may use them exactly like a shared block, and a pack needs to extend nothing for them:

```yaml
# config/packs/market-data/sutras/market-data/ir-curve.v1.sutra.yaml (the first screen built from fragments; now generated)
panels:
  - { use: curve-overview@1, with: { curve-code: CRV, curve-key: F2, table-key: F3 } }    # the zero-rate line, then the pillars
  - { use: series-line@1, with: { id: forwards, title: "1Y forward rates (%)", area: right, rows: $.FORWARDS, x: TENOR, y: RATE, fmt: price2 } }
  - { use: data-provenance@1 }
  - { use: linked-entities@1 }
```

The library today: `series-line`, `curve-table`, `curve-overview` (a composite of the two), `field-list`, `ranked-bars`,
`cashflow-ladder`, `event-timeline`, `key-figure`, `linked-entities`, `risk-links` (a trade's P&L vector, VaR and exposure, or why not), `data-provenance`, `explainer-note`, `measure-table`,
`region-map`, `source-footer` (a composite: data source, how the view was built, linked entities), `row-picker` (a table whose
rows, clicked, fire an event: the master of a master-detail screen; a fragment whose panels emit lists the events in `doc.emits`, one
whose panels read `@parent` says so in `doc.expects`; SUTRA_NEXT.md sections 6 to 8 wire them). The world-economy economics
screens are worked examples. What each one shows, its
parameters with their types and defaults, and examples are in its docstring; read them all with
`curl -s localhost:8080/api/v1/ui-kit | jq '.fragments[] | {ref, summary}'` (`/api/v1/sutras/library` is the older alias). The
kit also holds icons, images, logos and the brand (`config/ui-kit/assets`, `brand.yaml`; [SUTRA_NEXT.md, The Drishti UI
kit](../architecture/SUTRA_NEXT.md#3a-the-drishti-ui-kit)).

**Your pack's own fragments are namespaced.** A fragment or block in your pack's `sutras/` is `<pack>/<name>@N`
(`trading/deal-ticket@1`) and also answers to its plain name (`deal-ticket@1`) as before. Do not name one like a kit fragment: the
kit's wins the plain name, yours answers only qualified, and loading warns (`DRS-2048 warning: pack trading's key-figure has the
name of a Drishti UI kit fragment: the kit's wins; use trading/key-figure@1 for the pack's`).

**Writing a fragment.** A file `<name>.v<N>.fragment.yaml` (in the kit's `fragments/` for platform vocabulary, or in your pack's
`sutras/` for a pack's own) with
`fragment: <name>`, `version`, `params:` (each with its default; an empty default `''` leaves an optional key such as `code`, `key`
or `area` out), `panels:` whose text reads `%{param}`, and a complete **`doc:`**:

```yaml
rachana: 1
fragment: ranked-bars
version: 1
description: "Horizontal bars, one a row, labelled and sized by a value: the largest contributors at a glance."
params: { id: bars, title: By contribution, rows: $.ROWS, label: LABEL, value: VALUE, fmt: signed0, code: '', key: '', area: '' }
doc:
  summary: "Horizontal bars, one a row, labelled and sized by a value: the largest contributors at a glance."
  description: >-
    One horizontal bar a row of a list, labelled by one field and sized by another. Bars keep the order of the list.
  params:
    id:    { type: id, default: bars, meaning: "The panel id, unique on the screen" }
    title: { type: text, default: By contribution, meaning: "The title over the bars" }
    rows:  { type: expression, default: $.ROWS, meaning: "The list of rows, one bar each" }
    label: { type: field, default: LABEL, meaning: "The field of a row naming its bar" }
    value: { type: field, default: VALUE, meaning: "The field of a row giving its bar length" }
    fmt:   { type: format, default: signed0, meaning: "The named format of the value" }
    code:  { type: key, default: '', meaning: "The panel's mnemonic code; none when empty" }
    key:   { type: key, default: '', meaning: "The function key that brings the panel forward; none when empty" }
    area:  { type: area, default: '', meaning: "main (the default when empty) or right" }
  emits: []
  expects: []
  examples:
    - { use: ranked-bars@1, with: { id: by-book, title: "P&L by book", rows: $.BY_BOOK, label: BOOK, value: PNL, key: F2 } }
  since: "2.7.0"
panels:
  - id: %{id}
    kind: hbar
    title: "%{title}"
    code: %{code}
    key: %{key}
    area: %{area}
    rows: %{rows}
    label: %{label}
    value: %{value}
    fmt: %{fmt}
```

The docstring is checked strictly (`DRS-2055`; a fragment without a complete one is not loaded, so its uses fail with `DRS-2048`):
a one-line `summary` (at most 120 characters), a `description`, every parameter under `doc.params` with `type` (`text`,
`expression`, `field`, `format`, `key`, `area`, `id`, `number`, `boolean`, `kind`, `columns`), `default` (the same as under `params:`) and
`meaning`, and none it does not declare; `emits` and `expects` (`[]` until events and children arrive); at least one example that
is a use of this fragment with declared parameters (written with the plain name, `trade-table@1`, even for a pack's own); `since`.

**A list as a parameter (`columns`).** A parameter may be a list, typically a table's columns: declare it with a list default
(`columns: []`), document it as `type: columns`, and write `columns: %{columns}` in the panel. The Sutra passes the columns as a
table panel writes them, and they are filled in as one line of YAML flow, so labels and binds with quotes and commas keep exactly what
they say. The trading pack's derived pages use `trading/trade-table@1` this way:

```yaml
  - use: trading/trade-table@1
    with:
      title: "Trades (${size($.TRADES[?oneOf(@.TRADE_STATUS, ctl.status)])} of ${$.TRADE_COUNT})"
      code: TRD
      key: F2
      where: "oneOf(@.TRADE_STATUS, ctl.status)"
      columns:
        - { label: Trade, bind: "link(@.TRADE_ID, 'trade')" }
        - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
```

A text parameter is filled in as written, without the Sutra's quotes: written unquoted in the fragment (`where: %{where}`) an empty
one leaves the key out, but a value starting with `@`, a quote or a brace would not read, so quote such a parameter in the fragment
(`title: "%{title}"`), where an empty value is an empty text. The pack fragments that ship: trading's `deal-ticket`, `legs`,
`flow-schedule`, `events`, `payments`, `payments-metrics` and `trade-table` (generated, `tools/packgen/trades/sutras.py`);
news-and-markets' `quote-ranges`, `price-bars`, `headlines`, `headline-pipeline` and `snapshot-note` (`sutras/common/`, by hand).

**Composite fragments.** A fragment may use other fragments at a **pinned** version and pass parameters explicitly; it lists them
under `doc.uses`. At most three fragments deep, no cycles (`DRS-2048`):

```yaml
fragment: curve-overview
params: { rows: $.POINTS, curve-title: Zero rates (%), ... }
doc: { uses: [series-line@1, curve-table@1], ... }
panels:
  - use: series-line@1
    with: { title: "%{curve-title}", rows: "%{rows}", ... }
  - use: curve-table@1
    with: { rows: "%{rows}", ... }
```

**A Sutra's docstring.** A Sutra carries a `doc:` too (`summary`, `description`, `params` for its controls, `emits`, `expects`,
`examples` as URLs or commands that open it, `since`). Today it is a warning, shown on request: `sutra lint <pack> --docs` prints an incomplete one line by
line and counts the Sutras without one (`warning DRS-2055 19 of 20 Sutras have no doc: block`); an older `.block.yaml` without a
docstring is a warning too. The run still passes, with or without `--strict`. Write one whenever you touch a Sutra.

**Generated Sutras are fragment Sutras.** The pack generators write the Sutra Next form themselves (SUTRA_NEXT.md, open
question 4): `tools/packgen/banking/make_sutras.py` for core, market-data, market-risk and counterparty-risk (`FRAGMENT_PACKS`),
`tools/packgen/common/packbuild.py` for a pack whose spec sets `fragments=True` (world-economy's politics-and-society screens).
The writer (`tools/packgen/banking/kit_sutras.py`) turns a panel into a kit fragment use only when the fragment draws it exactly:

| The taxonomy's panel | Written as |
|---|---|
| a plain line (rows, x, y, format) | `series-line@1` |
| plain horizontal bars (rows, label, value, format, tone) | `ranked-bars@1` |
| a key/value list over an object (`rows:`, no columns) | `field-list@1` |
| a curve's pillars (Tenor, Instrument, Quote, Zero, DF) | `curve-table@1`; after the zero-rate line over the same rows, one `curve-overview@1` |
| how the view was built / linked entities | `data-provenance@1` / `linked-entities@1` |
| the data source and licence (`$.PROVENANCE`) last, then provenance and links | one `source-footer@1` |

Anything a fragment cannot say (a table's own columns, series, markers, pivots, controls that tune a panel) stays written in place.
Parameters equal to the fragment's default are left out. Every generated Sutra carries a generated `doc:`: the summary from the
kind's description (cut to 120 characters), a description listing its panels, strip and links, `params` for its controls, and two
examples opening the kind's first committed sample (`/v/equity/EQ-CSCA`, `EQ EQ-CSCA`); `DOCS` in `make_sutras.py` replaces a
generated docstring with a hand-written one (ir-curve). The trading pack's deal screens use the pack's own fragments
(`trading/deal-ticket@1`, `trading/legs@1`, `trading/flow-schedule@1`, `trading/events@1`, `trading/payments@1`, written by
`tools/packgen/trades/sutras.py` with complete docs) and the kit's footer. Lint it per pack (`sutra lint config/packs/trading`)
or the whole packs root (`sutra lint config/packs`): each pack folder (a `pack.yaml` beside a `sutras/` folder) loads its fragments
under its own namespace either way, so `trading/deal-ticket@1` resolves as it does on the server, and a pack may use another
pack's fragment by its qualified name when both are linted together. The root's `<pack>/tests` folders are skipped.

Each pack was moved only when its equivalence test (`PackSutraNextEquivalence` and one subclass per pack in drishti-server) drew
every screen identically, for a full and a masked reader, against the old inline layouts kept as test data
(`src/test/resources/sutra-next/<pack>/<name>.v2.sutra.yaml`). To move another generated pack: copy its current Sutras there as
version 2, switch the generator on, regenerate, and add the subclass.

**Changing a fragment.** Never change what a released version draws: write `name.v2.fragment.yaml` and move Sutras one at a time.
To prove a migration changes nothing, compare the view models before and after, as `SutraNextEquivalenceTest` does for ir-curve
(full and masked reader).

## Calc: Python snippets

Calc is a Python panel on any view (`Alt+C`): code that runs in the user's browser, reads the screen and Drishti with
the user's own rights, and draws tables and charts ([PYTHON_CALC.md](PYTHON_CALC.md)). A pack decides whether its
kinds offer it, and may ship starter code:

```yaml
python:
  enabled: true                 # Calc on the views of this pack's kinds
  snippets:                     # optional starters, offered in the panel's Snippets list
    - title: Member trades, summed from the screen
      description: The netting set's member trades as a DataFrame, notional by product.
      kinds: [netting-set]      # where it is offered; none: every kind of this pack
      code: |
        trades = view.tables["Member trades"]
        trades.groupby("Product", as_index=False)["Notional"].sum()
  dir: python                   # optional: the folder of snippet files (default python/)
```

Longer snippets read better as files: `config/packs/<pack>/python/<name>.py`, in name order after the inline ones, each
starting with three comment lines (after the copyright header, which is not shown to the user). From
`config/packs/market-risk/python/stress_heatmap.py`:

```python
# title: Stress results: scenarios by desk, limits and the worst case
# description: Every stress result in one search, pivoted scenario by desk with totals; each desk's worst scenario and its use of the stress limit (results read in full for their limits), the scenarios ranked by firm-wide loss, and a heatmap of the matrix (matplotlib).
# kinds: stress-result, stress-scenario

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
...
```

| Rule | |
|---|---|
| Where Calc is offered | on a view whose kind is owned by a pack with `python.enabled: true`, to users with a role with `calc` |
| Which snippets a view lists | from every active pack that enables Calc: those naming the view's kind in `kinds`, and those naming no kind from the pack that owns it |
| Limits | a file over 64 KB is skipped; at most 100 snippets a pack |
| When changes apply | when the pack reloads (`GET /api/v1/packs` carries them to the console) |
| Roles | give the pack's analyst roles `calc: true` ([Roles](#roles-and-field-masks)); a pack that enables Calc without such a role offers it only to roles that have it elsewhere |

The banking packs enable Calc with a library of 71 snippets (27 in `market-data`, 13 in `market-risk`, 9 in `trading`,
12 in `counterparty-risk`, 10 in `core`; `make_packs.py --check` prints `5 pack manifests up to date (71 Calc snippets)`): curves, FX, vol
surfaces, options, bonds, credit, VaR and its backtest, stress, FRTB, exposure, CVA, collateral, SA-CCR, desks, books and counterparties.
Their generator writes them from `tools/packgen/banking/snippets/<pack>/*.py` (read by `calc_snippets.py`). They share the pricing maths
of `drishti.quant`. Every one, with the sample entity it runs on, is in [PYTHON_CALC.md › Snippet catalogue](PYTHON_CALC.md#18-snippet-catalogue).
