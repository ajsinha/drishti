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

Twelve small, working Sutras, each with a JSON document to run it against and a note on what it shows. Together they use all twenty-two panel kinds, nested pivot groups and tree rows. Each opens in the workbench as a copy (**Create → New → Examples**, or **File → Open** with its two files) and is listed under **Help → Examples**.

Each example is `<name>.sutra.yaml` (the layout), `<name>.json` (a self-contained document, a few dozen rows at most) and `<name>.md` (what it shows, what to look for, which lines to copy). The documents are small teaching documents that use the production attribute names in UPPER_SNAKE (`$.TRADE_ID`, `$.POSITIONS`), as the shipped packs, their Sutras and every other guide do; [linked-sources](linked-sources.md) reads the packs' own entities. A Sutra reads field names exactly as the document writes them.

| Example | Shows | Panel kinds |
|---|---|---|
| [all-panels-showcase](all-panels-showcase.md) | Every panel kind on one screen | all twenty-two |
| [metric](metric.md) | Big-number tiles: a toned figure, its change, unit and caption | `metric` |
| [map](map.md) | A country on the world map and its own regions | `map`, `kv` |
| [pivot-row-groups](pivot-row-groups.md) | Nested row groups with subtotals and expand | `pivot` |
| [tree-table](tree-table.md) | Rows that hold child rows | `table`, `ladder` |
| [market-charts](market-charts.md) | Prices, closes and a volatility surface | `candlestick`, `line`, `surface` |
| [risk-distribution](risk-distribution.md) | VaR histogram, books scatter, limit gauge | `histogram`, `scatter`, `gauge` |
| [pnl-explain](pnl-explain.md) | Where P&L came from, and sensitivities | `waterfall`, `hbar`, `kv` |
| [relationships](relationships.md) | Group graph, netting-set tabs, linked entities | `graph`, `tabs`, `links` |
| [operations-status](operations-status.md) | Status, lifecycle, coupon ladder, notes | `status`, `timeline`, `ladder`, `markdown`, `provenance` |
| [exposure-profile](exposure-profile.md) | Exposure over tenor against a limit | `area` |
| [linked-sources](linked-sources.md) | Panels that read other entities with `source` (needs the live demo packs) | `tabs`, `area`, `pivot` |

A test (`drishti-console/tests/test_examples.py`; the Java `SutraCliTest` also runs `sutra test` over this folder) checks every example parses, that together they cover all twenty-two kinds, and that they include a nested pivot and a tree table, so the set cannot rot.

Three folders are not Sutra examples: [schemas/](schemas/) holds the JSON Schemas and JSON Lines of [Schema to pack](../BUILD_GUIDE.md#8-the-pack-designer-create--new-pack) (`pack make --schema docs/guides/examples/schemas`), [comswap/](comswap/) two small synthetic exports of deep trading JSON, every value invented by `tools/packgen/comswap_synth.py` (`book-sample.jsonl`, five product types; `comswap-sample.jsonl`, swaps; one deal a line; [A deep trading JSON](../BUILD_GUIDE.md#9-a-deep-trading-json-from-one-file-to-a-deployed-pack)), and [pack/helpdesk/](pack/helpdesk/) is the help-desk pack of the [Pack developer guide](../BUILD_GUIDE.md). The teaching source plugin of the [Connector developer guide](../DEVELOPER_GUIDE.md#1013-a-worked-example-the-dayfolder-plugin) is a Maven module of its own, [examples/connector](../../../examples/connector/) (built and tested by the testkit contracts, not shipped).
