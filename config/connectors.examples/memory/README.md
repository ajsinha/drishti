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
template: memory
category: template
summary: The news-and-markets pack held in the server's memory only (mem://news), never on a disk, filled by the in-memory lake's replenish jobs.
connectors:
- delta
files:
- news-store.yaml
doc: docs/guides/IN_MEMORY_LAKE_GUIDE.md
---
# In-memory lake connector template

A ready-made connector file for **data that may not, by contract, be saved on a disk**: the `delta` plugin with its root in a memory
store, `mem://<store>`. The tables are ordinary Delta tables (a `_delta_log` and Parquet files) whose files live in the server's memory
under a hard cap; the server's replenish jobs fill them; every page, search, BI dataset and Export data read them as they read a lake
on a disk.

## What it reads

`news-store.yaml` reads the news-and-markets pack's four kinds (`news`, `quote`, `news-topic`, `listed-company`) from the memory store
`news`, each table at `mem://news/<kind>`. The server writes them: the in-memory lake's replenish jobs read a feed (a URL answering JSON
Lines, a file, a connector's documents, or the news connector entity by entity) and commit each run as one Delta version.

## Switching the news to memory

1. Copy `news-store.yaml` to `config/connectors/news-store.yaml` (it replaces the pack's connector of that name).
2. Copy the block of [`config/examples/in-memory-lake.yaml`](../../examples/in-memory-lake.yaml) into `application.local.yaml` in the
   server's working folder: the store `news` (its cap, its contract) and the four tables with their jobs.
3. Restart. Admin → Lakes (or `drishti.py lake memory status`) shows the store and the tables filling in warm-up order.

Switching back: remove both and restart; the pack's own connector reads the disk lake again.

## Settings

| Setting | In the file | Meaning |
|---|---|---|
| `root` | `mem://news` | The memory store (its name after `mem://`); the tables are `mem://news/<kind>` |
| `domain` | none | A folder under the store, as on a disk |

A memory-only connector keeps no disk cache, no id-map files and no index segments whatever its other settings say.

## Data structure

Each kind is a Delta table at `mem://news/<kind>` in the delta connector's layout ([its README](../../../plugins/drishti-plugin-delta/README.md)):
columns `id`, `doc`, `business_date` and the promoted fields a table's `columns` lists, Parquet files compressed with zstd.

| Table | Key | Filled from (the example) |
|---|---|---|
| `news` | `NEWS_ID` | `data/testdata/news-and-markets/news/news.2026-10-18.jsonl.gz` |
| `quote` | `QUOTE_ID` | `data/testdata/news-and-markets/quote/quote.2026-10-18.jsonl.gz` |
| `news-topic` | `TOPIC_ID` | `config/packs/economy/news-and-markets/samples/news-topic.jsonl` |
| `listed-company` | `COMPANY_ID` | `config/packs/economy/news-and-markets/samples/listed-company.jsonl` |

## Examples

```bash
cp config/connectors.examples/memory/news-store.yaml config/connectors/news-store.yaml
cat config/examples/in-memory-lake.yaml >> application.local.yaml      # the store and its four tables (edit the sources for a live feed)
python3 tools/drishti.py lake memory status
python3 tools/drishti.py lake memory refill news
```

<!-- files:begin -->
| File | Plugin | Kinds | What it is |
|---|---|---|---|
| `news-store.yaml` | `delta` | `news-topic`, `listed-company`, `news`, `quote` | news-store held in memory only (mem://news), filled by the in-memory lake's replenish jobs |
<!-- files:end -->

## Troubleshooting

- `UP: mem://news is held in memory only and is empty now`: the warm-up has not filled it yet, or its job failed: `drishti.py lake memory jobs`.
- `refused: the store is full`: raise the store's `max-mb` (and restart: the launchers add it to `-XX:MaxDirectMemorySize`), or unpin a table so the eviction ladder may free it.
- Health red, *direct-memory limit*: the JVM was started without the stores' caps in `-XX:MaxDirectMemorySize`; use the shipped start scripts.
- `DRS-5219`: a report, a picture, an e-mail or an Ask of memory-only data was refused, as the contract asks; download the rows instead.

## See also

- [The in-memory lake guide](../../../docs/guides/IN_MEMORY_LAKE_GUIDE.md) and [its design](../../../docs/architecture/IN_MEMORY_LAKE.md).
- [The delta connector README](../../../plugins/drishti-plugin-delta/README.md) and [Connector files](../../../docs/connectors/CONNECTOR_FILES.md).

The Delta and Iceberg files here name a lake (`lake:`, `lake-tables:`); the lake itself, its root or catalog, storage and tables, is the matching
file in [config/lakes.examples/memory/](../../lakes.examples/memory/): copy both (docs/guides/DATA_LAKE_GUIDE.md).
