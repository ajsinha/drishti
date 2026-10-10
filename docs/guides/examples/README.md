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
# Examples

Eleven small, working Sutras, each with a JSON document to run it against and a note on what it shows. Together they use all twenty-one panel kinds, nested pivot groups and tree rows. Each opens in the workbench as a copy (**Build → New → Examples**, or **File → Open** with its two files) and is listed under **Help → Examples**.

Each example is `<name>.sutra.yaml` (the layout), `<name>.json` (a self-contained document, a few dozen rows at most) and `<name>.md` (what it shows, what to look for, which lines to copy).

| Example | Shows | Panel kinds |
|---|---|---|
| [all-panels-showcase](all-panels-showcase.md) | Every panel kind on one screen | all twenty-one |
| [metric](metric.md) | Big-number tiles: a toned figure, its change, unit and caption | `metric` |
| [pivot-row-groups](pivot-row-groups.md) | Nested row groups with subtotals and expand | `pivot` |
| [tree-table](tree-table.md) | Rows that hold child rows | `table`, `ladder` |
| [market-charts](market-charts.md) | Prices, closes and a volatility surface | `candlestick`, `line`, `surface` |
| [risk-distribution](risk-distribution.md) | VaR histogram, books scatter, limit gauge | `histogram`, `scatter`, `gauge` |
| [pnl-explain](pnl-explain.md) | Where P&L came from, and sensitivities | `waterfall`, `hbar`, `kv` |
| [relationships](relationships.md) | Group graph, netting-set tabs, linked entities | `graph`, `tabs`, `links` |
| [operations-status](operations-status.md) | Status, lifecycle, coupon ladder, notes | `status`, `timeline`, `ladder`, `markdown`, `provenance` |
| [exposure-profile](exposure-profile.md) | Exposure over tenor against a limit | `area` |
| [linked-sources](linked-sources.md) | Panels that read other entities with `source` (needs the live demo packs) | `tabs`, `area`, `pivot` |

A test (`RachanaExamplesTest` in the console tests) checks every example parses, that together they cover all twenty-one kinds, and that they include a nested pivot and a tree table, so the set cannot rot.

Three folders are not Sutra examples: [schemas/](schemas/) holds the JSON Schemas and JSON Lines of [Schema to pack](../SCHEMA_TO_PACK.md) (`pack make --schema docs/guides/examples/schemas`), [connector/](connector/) is the teaching source plugin built in the [Connector developer guide](../../connectors/CONNECTOR_DEVELOPER_GUIDE.md) (built and tested by the testkit contracts, not shipped), and [pack/helpdesk/](pack/helpdesk/) is the help-desk pack of the [Pack developer guide](../PACK_DEVELOPER_GUIDE.md).
