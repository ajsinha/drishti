<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
# Source WSS

WSS is a treasury, FX and money-markets dealing system (mocked here: a synthetic look-alike, camelCase, one deal a document, buy and sell legs).

## What the module ships

| Folder | What |
|---|---|
| `connectors/` | delta, files, iceberg, kafka: copy one to `config/connectors/` under the name its first line gives |
| `mappings/wss-trade.yaml` | the native fields to the canonical trade fields |
| `dataload.yaml` | the inbox routing rules |

## Bind it

```yaml
# config/connectors/trade-wss.yaml (from connectors/delta.yaml)
plugin: delta
settings:
  lake: main
  lake-tables: trading.trades_shared
  where: SOURCE_SYSTEM = 'WSS'
  set: { SOURCE_SYSTEM: WSS }
  map: mappings/wss-trade.yaml
```

and in `config/bindings.yaml` add `trade-wss` to the `trade` kind's `read:` list (with `union: true`); the Kafka template, bound as `live:`,
ticks the blotter and writes edits back.

## Load a file

The inbox takes `wss-<date>.jsonl.gz` by this module's rule (the site's own rules are read first):

```bash
cp wss-2026-11-03.jsonl.gz data/dataload/inbox/
touch data/dataload/inbox/wss-2026-11-03.jsonl.gz.ready      # the marker goes in last: a file without it is never touched
```

The file is JSON Lines in WSS's own native style; the connector's `set:` stamps `SOURCE_SYSTEM = WSS` into each stored row.
