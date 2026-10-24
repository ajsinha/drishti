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
---
connector: derived
category: built-in
summary: Computes kinds no source holds by grouping another kind and adding up (a counterparty's trades from every system, an agent's open tickets), once per business date, shared by every reader.
capabilities:
- fetch
- search
- history
settings:
- 'store'
- 'store-dir'
- 'refresh'
- 'version-check'
- 'max-scan'
- 'max-stream'
- 'zone'
- 'retire-after'
- 'rebuild-delay'
- 'source-name'
- '<kind>.from'
- '<kind>.group-by'
- '<kind>.where'
- '<kind>.fields.*'
- '<kind>.rows.*'
- '<kind>.id-field'
- '<kind>.members'
- '<kind>.rows-field'
- '<kind>.max-members'
auth: None; it reads its members through the server's own routing, with the reader's rights.
licence: Part of the Drishti engine (drishti-engine); no client library.
windows: Works the same on Windows (pure Java); store disk writes its pages under the data folder.
doc: docs/guides/PACK_MODELLING_GUIDE.md
---
# The derived connector

Built-in plugin `derived` (`drishti-engine/src/main/java/com/ash/drishti/engine/source/derived/`, `DerivedSourcePlugin` and
`DerivedKind`): a pack declares a connector of this plugin and routes the derived kinds to it. The trading pack's `trade-pages`
connector serves `counterparty-trades`, `portfolio-trades`, `legal-entity-trades` and `index-trades` (`CTRD`, `PTRD`, `LTRD`, `ITRD`).

## What it reads

The members of each derived kind (`<kind>.from`, such as `trade`) through the normal routing, so a union of several systems counts as
one kind. Each value of `group-by` over a member becomes one entity. One computation per business date reads every member once and is
shared by every reader and by all derived kinds of the connector built on the same kind; it reads the source's columns when they hold
every field it needs, else the day's documents in one pass, else one member at a time up to `max-scan`. Every `version-check` it asks
the sources for their data's version and rebuilds in the background on a new one (a load); `refresh` is used only for a source that
cannot say. A picked date is computed from that date's members, so a derived kind has history wherever its members do. It writes
nothing to any store; with `store: disk` it keeps its own pages under `<data dir>/derived/<connector>`.

## Settings

Not in the settings catalogue; the settings `DerivedSourcePlugin.start` and `DerivedKind.parse` read. Connector-wide:

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `store` | one of heap, disk | `heap` | `heap` builds every page in memory; `disk` writes each member's key and row to a sorted store and keeps only each group's totals in the heap |
| `store-dir` | path | `<data dir>/derived/<connector>` | The folder of the pages on disk (`store: disk`); kept across restarts |
| `refresh` | duration | `30s` | Rebuild age, only for a source that cannot say its data's version |
| `version-check` | duration | `10s` | How often the sources are asked for their data's version |
| `max-scan` | int | `50000` | Members read one at a time at most, for a source that cannot read a day in one pass |
| `max-stream` | int | `100000` | Members kept at most with `store: heap`; beyond it Health says DEGRADED |
| `zone` | string | `America/New_York` | The zone whose calendar date `today()` is for the latest pages |
| `retire-after` | duration | `60s` | How long replaced pages on disk are kept for reads still running |
| `rebuild-delay` | duration | `5s` | How long the rebuild after a purge waits for more purges before it reads |
| `source-name` | string | `derived` | The name in provenance and Health (and the folder under `derived/`) |

Per derived kind (`<kind>.` prefix; a key ending in `.from` declares one):

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `<kind>.from` | string | | **Required.** The kind it is built from (not itself) |
| `<kind>.group-by` | expression | | **Required.** Rachana-EL over a member: its group, which is the derived entity's id |
| `<kind>.where` | expression | none | Only members for which it holds count |
| `<kind>.fields.<name>` | string | none | `count`, or `sum`, `avg`, `min`, `max`, `distinct`, `first` followed by an expression |
| `<kind>.rows.<column>` | expression | none | One row per listed member, for a page's tables |
| `<kind>.id-field` | string | `id` | A field holding the key besides `id` |
| `<kind>.members` | string | `members` | The field listing the members' ids, sorted |
| `<kind>.rows-field` | string | `rows` | Where the per-member rows go |
| `<kind>.max-members` | int | `1000` | Members listed (and rows) at most; `memberCount` and the aggregates count all |

Every connector also takes `kinds`, `enabled` and the common settings (docs/connectors/CONNECTOR_FILES.md).

## Data structure

A derived page, for the group key `CP-MERIDIAN-RE` of `counterparty-trades` (the values are illustrative):

```json
{"id": "CP-MERIDIAN-RE", "COUNTERPARTY": "CP-MERIDIAN-RE", "derivedFrom": "trade",
 "TRADE_COUNT": 412, "NOTIONAL": 8.1e9, "CURRENCIES": ["EUR", "USD"], "LAST_MATURITY": "2041-06-15",
 "memberCount": 412, "TRADE_IDS": ["CLY-3000157", "MX-20000001", "…"],
 "TRADES": [{"TRADE_ID": "CLY-3000157", "PRODUCT_TYPE": "GOVT_BOND", "NOTIONAL": 17179094, "…": "…"}]}
```

- **Id rule:** the trimmed text of `group-by`; a member whose key is empty, or whose `where` is false, counts in no group.
- **Fields:** `id` and the `id-field` hold the key; `derivedFrom` and `memberCount` are written by the engine under those names; each
  `fields.<name>` holds its aggregate (`distinct` a sorted list, `first` the first member's value in id order; missing or non-numeric
  values are skipped by `sum`, `avg`, `min`, `max`); the members field lists at most `max-members` ids; the rows field holds the rows.
- **Search:** type-ahead matches the group keys; field searches (`CTRD TRADE_COUNT > 500`) are answered by the engine over the pages.

## Examples

The trading pack's connector (`config/packs/trading/pack.yaml`, shortened):

```yaml
connectors:
  trade-pages:
    plugin: derived
    kinds: [counterparty-trades, portfolio-trades, legal-entity-trades, index-trades]
    settings:
      refresh: 60s
      store: disk
      counterparty-trades:
        from: trade
        group-by: $.COUNTERPARTY
        id-field: COUNTERPARTY
        members: TRADE_IDS
        max-members: 1000
        rows-field: TRADES
        fields:
          TRADE_COUNT: count
          NOTIONAL: sum first($.LEGS).NOTIONAL
          CURRENCIES: distinct $.CURRENCY
          LAST_MATURITY: max $.MATURITY_DATE
        rows:
          TRADE_ID: $.TRADE_ID
          PRODUCT_TYPE: $.PRODUCT_TYPE
routes:
  counterparty-trades: trade-pages
```

A smaller one is the help-desk example's `agent-load` (`where: $.STATUS != 'Closed'`), step 14 of the pack developer's guide.

## Troubleshooting

- Under *failed to start*: `derived needs at least one <kind>.from setting`; or `<kind>: group-by is required (an expression over a
  <from>, such as $.book)`, `<kind>: a derived kind cannot be built from itself`, `<kind>.fields.<name>: '…' must start with count,
  sum, avg, min, max, distinct or first`, `… needs an expression, such as $.mtm`, `… is not an expression: …`.
- `UP (nothing computed yet)`: the first computation runs about 15 s after start, or on the first read.
- `DEGRADED: out of memory building the pages (lower max-stream, or give the server more heap)`: the old pages keep serving; use
  `store: disk` for a large book.
- `DEGRADED: partial: <kinds> built from the first N of M trade documents (max-stream …): raise max-stream, with heap for it`:
  `store: heap` cut the members; raise `max-stream` or use `store: disk`.
- `DOWN: <reason>`: a computation failed.
- Pages look stale: Admin → Caches → Purge (`drishti.py server caches purge trade-pages`); with `store: disk` the latest date is
  rebuilt at once.

## See also

- [Derived kinds in the pack modelling guide](../../../guides/PACK_MODELLING_GUIDE.md#derived-kinds-entities-computed-from-other-kinds): keys, fields, links, dates, cost, storage.
- [Connector files](../../CONNECTOR_FILES.md) and [Connectors and routes](../../CONNECTORS.md).
- [The connector developer's guide](../../CONNECTOR_DEVELOPER_GUIDE.md) (the README standard).
