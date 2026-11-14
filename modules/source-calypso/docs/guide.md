<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
# Source Calypso

Calypso is the capital markets trading and risk system. Its trades arrive in the common UPPER_SNAKE trade schema, so the mapping is the identity.

## What the module ships

| Folder | What |
|---|---|
| `connectors/` | delta, files, iceberg, kafka: copy one to `config/connectors/` under the name its first line gives |
| `mappings/calypso-trade.yaml` | the native fields to the canonical trade fields |
| `dataload.yaml` | the inbox routing rules |

## Bind it

```yaml
# config/connectors/trade-calypso.yaml (from connectors/delta.yaml)
plugin: delta
settings:
  lake: main
  lake-tables: trading.trade_calypso          # a table of its own: no where: needed
  set: { SOURCE_SYSTEM: CALYPSO }
  map: mappings/calypso-trade.yaml
```

and in `config/bindings.yaml` add `trade-calypso` to the `trade` kind's `read:` list (with `union: true`); the Kafka template, bound as `live:`,
ticks the blotter and writes edits back.

## Load a file

The inbox takes `calypso-<date>.jsonl.gz` by this module's rule (the site's own rules are read first):

```bash
cp calypso-2026-11-03.jsonl.gz data/dataload/inbox/
touch data/dataload/inbox/calypso-2026-11-03.jsonl.gz.ready      # the marker goes in last: a file without it is never touched
```

The file is JSON Lines in Calypso's own native style; the connector's `set:` stamps `SOURCE_SYSTEM = CALYPSO` into each stored row.
