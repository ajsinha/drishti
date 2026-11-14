<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
# Source SwapOne

SwapOne is an OTC swaps system (mocked here: a synthetic look-alike, snake_case, a swap as a pay leg and a receive leg, epoch-millisecond update times).

## What the module ships

| Folder | What |
|---|---|
| `connectors/` | delta, files, iceberg, kafka: copy one to `config/connectors/` under the name its first line gives |
| `mappings/swapone-trade.yaml` | the native fields to the canonical trade fields |
| `dataload.yaml` | the inbox routing rules |

## Bind it

```yaml
# config/connectors/trade-swapone.yaml (from connectors/delta.yaml)
plugin: delta
settings:
  lake: main
  lake-tables: trading.trades_shared
  where: SOURCE_SYSTEM = 'SWAPONE'
  set: { SOURCE_SYSTEM: SWAPONE }
  map: mappings/swapone-trade.yaml
```

and in `config/bindings.yaml` add `trade-swapone` to the `trade` kind's `read:` list (with `union: true`); the Kafka template, bound as `live:`,
ticks the blotter and writes edits back.

## Load a file

The inbox takes `swapone-<date>.jsonl.gz` by this module's rule (the site's own rules are read first):

```bash
cp swapone-2026-11-03.jsonl.gz data/dataload/inbox/
touch data/dataload/inbox/swapone-2026-11-03.jsonl.gz.ready      # the marker goes in last: a file without it is never touched
```

The file is JSON Lines in SwapOne's own native style; the connector's `set:` stamps `SOURCE_SYSTEM = SWAPONE` into each stored row.
