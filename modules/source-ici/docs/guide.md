<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
# Source ICI

ICI is a generic cross-asset booking system. What it trades was not stated, so it is MOCKED as a generic cross-asset booking system (rates, FX, equity, credit and commodity tickets; PascalCase sections Header, Instrument, Parties, Dates; YYYYMMDD dates).

## What the module ships

| Folder | What |
|---|---|
| `connectors/` | delta, files, iceberg, kafka: copy one to `config/connectors/` under the name its first line gives |
| `mappings/ici-trade.yaml` | the native fields to the canonical trade fields |
| `dataload.yaml` | the inbox routing rules |

## Bind it

```yaml
# config/connectors/trade-ici.yaml (from connectors/delta.yaml)
plugin: delta
settings:
  lake: main
  lake-tables: trading.trades_shared
  where: SOURCE_SYSTEM = 'ICI'
  set: { SOURCE_SYSTEM: ICI }
  map: mappings/ici-trade.yaml
```

and in `config/bindings.yaml` add `trade-ici` to the `trade` kind's `read:` list (with `union: true`); the Kafka template, bound as `live:`,
ticks the blotter and writes edits back.

## Load a file

The inbox takes `ici-<date>.jsonl.gz` by this module's rule (the site's own rules are read first):

```bash
cp ici-2026-11-03.jsonl.gz data/dataload/inbox/
touch data/dataload/inbox/ici-2026-11-03.jsonl.gz.ready      # the marker goes in last: a file without it is never touched
```

The file is JSON Lines in ICI's own native style; the connector's `set:` stamps `SOURCE_SYSTEM = ICI` into each stored row.
