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
# Changelog

## Unreleased — A million trades a day, for seven years
- **Delta Lake without Hadoop; Drishti on Windows** ([DELTA_CONNECTOR.md › Engines](docs/connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop),
  [WINDOWS.md](docs/guides/WINDOWS.md)). A new module, `drishti-deltalake`, is a Delta Kernel engine (`NativeEngine`)
  that never touches Hadoop's file systems: local lakes through `java.nio` (drive letters, backslashes and UNC shares),
  S3 through the AWS SDK v2, Parquet through parquet-java over its own input files (row groups pruned by statistics
  for `id = X`, only the asked columns decoded, its own Snappy/ZSTD/GZIP page codecs, no Hadoop `Configuration`), commit
  JSON and expressions through Kernel's Hadoop-free handlers; checkpoints, time travel and deletion vectors read as
  before. It only reads.
  - **The Delta connector's `engine` setting:** `native` (the default), `hadoop` (Kernel's default engine; also
    `abfs://`, `gs://`, HDFS) or `auto` (native on Windows); `DRISHTI_DELTA_ENGINE` sets it for every connector.
    Health says `UP (engine: native)`; the cache stats carry `engine`.
  - **Tests:** every Delta test runs on both engines (the dated-source contract, the layout, S3 in Docker, a maintained
    lake), plus a comparison of both engines' documents, id maps, columns, reverse lookups and time travel; deletion
    vectors written by the protocol; and the connector run in a class loader that refuses Hadoop's `FileSystem`,
    `Shell` and `Configuration`. Measured on a 10,000-trade lake, the native engine matches Hadoop's (a single read
    4.4 ms against 5.6 ms median; a cold id map 225 ms against 190–285 ms).
  - **Windows:** `tools/windows/start-server.ps1`, `start-console.ps1`, `load-delta.ps1`; a `windows` GitHub Actions
    job (windows-latest, Temurin 25) that runs the engine's, the connector's and the server's tests and checks a view,
    a search and a past date on a running server. Iceberg stays on Hadoop and is not supported on Windows (it is off
    unless the `iceberg` profile is used).
  - `DatedSourceContract` accepts a health of `UP (detail)`, as the server always has.
- **Calc: Python on any view** ([PYTHON_CALC.md](docs/guides/PYTHON_CALC.md)). `Alt+C` opens a drawer like F9's with a
  Python editor (CodeMirror, Python mode), Run (`Ctrl+Enter`), Stop, the output and a history of runs. The code runs in
  the browser, in Pyodide 314.0.7 (CPython 3.14 on WebAssembly) inside a Web Worker, with numpy, pandas, scipy,
  statsmodels and matplotlib; nothing runs on the server.
  - **The `drishti` module:** `view` (the screen: `doc`, `tables` as DataFrames for every panel that holds rows,
    `panels`, `business_date`), `get`, `search`, `columns` (whole columns of a day) and `history`, each an ordinary read
    with the user's session (roles and masking apply), with `_async` forms for browsers without JavaScript Promise
    Integration; `show()` (tables paged like Drishti's), `chart()` (ECharts in the theme), matplotlib figures as PNG;
    output capped per run.
  - **Packs opt in:** `python: { enabled: true, snippets: [...] }` in `pack.yaml` and `python/*.py` files; the server's
    pack model reads them and `/packs` carries them. The five banking packs ship starters (rate shift on a trade, MTM
    concentration on netting sets and counterparties, VaR and ES from the scenario P&L, a desk's P&L pivot, curve
    interpolation), generated from `tools/packgen/banking/calc_snippets.py`; `finance` has two inline ones.
  - **A role power, `calc`:** granted to `author`, `approver`, `admin` and the analysts' roles of the packs that offer
    Calc (`trader`, `risk`, `market-risk`, `credit-risk`), not to `viewer`; Admin → Roles has a tick box. Kept in a new
    table, `drishti_role_power`, created at start on existing databases.
  - **Server:** `GET /api/v1/calc/settings`, the user's snippets (`/api/v1/me/calc-snippets`, at most 50, in
    `drishti_preference`), and `GET /api/v1/search/columns/{kind}?paths=` (a day's promoted columns, redacted as a search,
    access-logged); `drishti.calc.*` settings.
  - **The runtime is not in git:** `tools/fetch-pyodide.sh` downloads the pinned release once, checks its SHA-256 and
    keeps only what Calc needs (53 MB); the console serves it at `/pyodide/<version>/` (cached for good), the console
    image installs it at build time, and `/healthz` and About say whether it is there. Only the Calc worker may compile
    WebAssembly (`'wasm-unsafe-eval'`, its own Content Security Policy); the page adds `worker-src 'self'`.
  - **Measured:** first open 1.3 s, first run with pandas about 2 s, later runs 5–100 ms; no request leaves the origin.
- **A number over time works again:** clicking a value on a view drew no chart, because the console's client sent the
  field (`path`) in a way that collided with its own argument (`/api/series` answered 500).
- **JDK 25 only.** Drishti builds with JDK 25 and produces Java 25 bytecode (the enforcer accepts only 25).
  - **Running:** run the server with `-XX:+UseCompactObjectHeaders`. Measured against JDK 21: 10–20% more requests a second, and 10% less live heap.
  - **Everywhere else:** the Docker image is `eclipse-temurin:25-jre` with compact object headers; CI, `.java-version`, the scripts and the documents say 25.
  - **Hadoop 3.4.3** replaces 3.4.2, which called `Subject.getSubject`; JDK 24 and later refuse that call, which broke Delta Lake and Iceberg reads.
  - **Native access:** the server jar's manifest grants it (`Enable-Native-Access: ALL-UNNAMED`) for RocksDB, SQLite, DuckDB and Hadoop.
- **Unconfigured plugins stay idle.** The MongoDB and Iceberg plugins no longer show `DOWN` when nobody configured them: without `uri` (or `root`) they are idle, as Redis is.
- **The ActiveMQ and RabbitMQ state store is durable by default and bounded per connector** ([ACTIVEMQ_CONNECTOR.md](docs/connectors/ACTIVEMQ_CONNECTOR.md#7-durability-and-disk-budget), [RABBITMQ_CONNECTOR.md](docs/connectors/RABBITMQ_CONNECTOR.md#6-durability-and-disk-budget)). `state.durability`: `sync` (default; the write-ahead log is synced on every write, so neither a crash nor a power loss loses an acknowledged message, at one disk sync per message), `wal` (survives a crash of the process) or `none` (the old behaviour: a crash can lose up to the 32 MB write buffer). A message is acknowledged only once the store has kept it; one it cannot keep is not acknowledged (RabbitMQ requeues it, ActiveMQ redelivers it a second later) and health reads `DOWN: <reason> (messages are not acknowledged and come again)`; unreadable messages are still acknowledged and counted as `rejected`. The store keeps only the latest value of each entity (level compaction instead of FIFO, write-ahead log capped at 64 MB), so disk follows the entities held, not the messages. `state.max-gb` is each connector's budget: `state.when-full: evict-oldest` (default) removes the entities written longest ago to stay under 90% of it and compacts, logged and counted in `evicted`, checked every `state.check-seconds` (60); `warn` keeps everything and says so in health. Cache figures add `durability`, `budgetMb` and `evicted`. The Kafka disk cache is unchanged.
- **Seven new panel kinds, twenty in all** ([PANELS.md](docs/guides/PANELS.md)): `waterfall` (P&L explain on every trade and on `pnl-explain`), `histogram` (the VaR scenario vector with VaR, ES and mean lines), `scatter` (books' VaR against P&L on legal entities), `candlestick` (daily bars on equities and commodities), `graph` (a counterparty's group, agreement, CSA and netting sets, nodes open their entity), `timeline` (each trade's lifecycle, from the new `lifecycle.timeline`) and `pivot` (MTM by book and currency, trades by book and family on desks). The server computes running totals, bins, aggregates and sort order within `drishti.panels` limits; `charts.js` draws them with the vendored ECharts, with a *Data* table under each chart; option values are checked at load (`DRS-2029`); CSV export, Studio snippets and the landing page count follow.
- **Delta Lake at scale** ([DELTA_CONNECTOR.md](docs/connectors/DELTA_CONNECTOR.md)). A pack declares a kind's lake layout (`layout.<kind>`: promoted columns, rows sorted by id, files of 250,000 rows, row groups of 1,000), and the writers and nightly maintenance keep it.
  - **Reads:** the connector never loads a day. An id map per day finds an entity's file; one row group is read per entity; type-ahead comes from the id column.
  - **Columns:** searches, pick lists, derived kinds, impact and reverse lookups read a day's promoted columns, cached and warmed for the newest day. A search over a million trades takes 120–320 ms and is exact, no longer `partial`.
  - **Log:** statistics leave out the document, so the log stays small. Maintenance re-sorts only the days that drifted from the layout.
- **Aerospike at scale** ([AEROSPIKE_CONNECTOR.md](docs/connectors/AEROSPIKE_CONNECTOR.md)). Data is stored as a record per entity per business day, an index record per entity and a dates record per kind, with promoted bins. This replaces a record per entity with a bin per day, which could not hold more than about four and a half years.
  - **Reads:** two key reads per entity. Type-ahead comes from the index set.
  - **Columns:** a day's promoted bins are read by filtered scans, run in parallel over partition ranges.
  - **Loader and retention:** the loader streams and writes in parallel; record TTL gives retention without a maintenance job.
- **PostgreSQL at scale** ([POSTGRES_CONNECTOR.md](docs/connectors/POSTGRES_CONNECTOR.md)). The `jdbc` connector's table mode now uses a partitioned table, and the old table form is still read.
  - **Layout:** a table per data domain, partitioned by month, with LZ4-compressed documents. The pack's promoted fields are columns, a `(kind, business_date, id)` index lists a day's ids, and an `entity_dates` catalogue records each kind's days.
  - **Reads:** type-ahead comes from memory instead of a `LIKE` query per keystroke. A single read is one primary-key probe. A day's columns are read in parallel by id range.
  - **Loading and retention:** `PostgresLoader` / `tools/load-postgres.sh` uses parallel `COPY` and replaces a day whole. `--keep-months` drops old partitions.
  - **Measured:** searches over a million trades take 100–225 ms. `make_data.py --postgres` and `pgload.py` are replaced by the loader.
- **Several queries per kind in the JDBC connector's query mode** ([JDBC_QUERIES.md](docs/connectors/JDBC_QUERIES.md)). This brings type-ahead, exact searches and reverse lookups to your own schema without reshaping it.
  - `query.<kind>.<part>`: parts of the entity from other tables, as nested lists or objects, run at once with the entity's query.
  - `ids.<kind>`: type-ahead from memory.
  - `columns.<kind>`: a day's promoted fields for searches, pick lists, derived kinds and impact.
  - `reverse.<kind>`: reverse lookups with `:target`.
- **Apache Iceberg connector** ([ICEBERG_CONNECTOR.md](docs/connectors/ICEBERG_CONNECTOR.md)): path-based tables, or REST catalogs (Polaris, Snowflake Open Catalog, Glue).
  - **Layout and reads:** the same as Delta Lake (promoted columns, rows sorted by id, small row groups).
  - **Correctness:** every read applies delete files; *known at* reads use snapshot time travel.
  - **Tooling:** `IcebergLoader` sorts each day externally; `IcebergMaintenance` handles retention, relayout and manifests. The `iceberg` profile and `tools/load-iceberg.sh`.
- **DuckDB connector** ([DUCKDB_CONNECTOR.md](docs/connectors/DUCKDB_CONNECTOR.md)): one embedded file holds every data domain, read in-process with no database server.
  - **Layout:** a schema per data domain, `<domain>.entities` with each day written sorted by id and the pack's promoted fields as columns, and `<domain>.entity_dates`.
  - **Reads:** one read-only DuckDB instance shared by every connector on the file; type-ahead from memory; point reads pruned by zone maps; a day's columns read in parallel by id range for searches, pick lists, derived kinds, impact and reverse lookups.
  - **Loading:** `DuckDbLoader` / `tools/load-duckdb.sh` parses in parallel, stages through the appender, writes the 1.5 storage format (documents ZSTD-compressed), replaces a day whole, keeps `--keep-days`, and renames the new file over the old one; a running server reopens it within `refresh-seconds`. The `duckdb` profile (`DRISHTI_DUCKDB_PATH`, `DRISHTI_DUCKDB_MEMORY`).
- **MongoDB connector** ([MONGODB_CONNECTOR.md](docs/connectors/MONGODB_CONNECTOR.md)): a document per entity per business date in a collection per data domain.
  - **Storage:** a narrow `<domain>_columns` collection holds the promoted fields, so a whole day reads 3–4 times faster.
  - **Reads:** point reads by `_id`; dates and ids come from the `{kind, date, id}` index; a day's columns are read in parallel id ranges.
  - **Retention:** `--keep-days`, or a TTL index.
  - **Tooling:** the `mongodb` profile and `tools/load-mongodb.sh`.
  - **Measured at 10,000 trades a day:** searches take 7–14 ms.
- **Redis connector** ([REDIS_CONNECTOR.md](docs/connectors/REDIS_CONNECTOR.md)): today and recent days in Redis memory, about 1 GB per million trades a day.
  - **Storage:** documents are compressed with zstd and a dictionary trained per kind, 7.4× smaller. Each day's promoted fields are stored column-wise in chunks of 10,000.
  - **Reads:** a single read takes about 5 ms. Searches over a million trades take 80–260 ms.
  - **Live and retention:** open views update through `<domain>:changes`; `--ttl-days` sets retention.
  - **Older days** go to the next store, Delta Lake.
  - **Tooling:** the `redis` profile and `tools/load-redis.sh`.
- **The file connector reads JSON lines** ([FILE_CONNECTOR.md](docs/connectors/FILE_CONNECTOR.md)): one file per kind per business day (`<root>/<domain>/<date>/<kind>.jsonl`), each line the loaders' row or a plain document. A day is indexed once, in parallel segments: ids with byte offsets, and the promoted fields as columns. A read is one positioned read, and searches read the columns. `tools/load-files.sh` writes the files and a `files` profile serves them. Type-ahead over many files is no longer quadratic.
- **A document for every remaining connector**: [S3_CONNECTOR.md](docs/connectors/S3_CONNECTOR.md), [REST_CONNECTOR.md](docs/connectors/REST_CONNECTOR.md), [KAFKA_CONNECTOR.md](docs/connectors/KAFKA_CONNECTOR.md), [ACTIVEMQ_CONNECTOR.md](docs/connectors/ACTIVEMQ_CONNECTOR.md), [RABBITMQ_CONNECTOR.md](docs/connectors/RABBITMQ_CONNECTOR.md), [FEEDS_CONNECTOR.md](docs/connectors/FEEDS_CONNECTOR.md) and [DEMO_CONNECTOR.md](docs/connectors/DEMO_CONNECTOR.md). Each covers when to use the connector, how its data is laid out or arrives, pack and site configuration, every read path and its cost, scale, failure and recovery with the exact health texts, security, diagnosing, every setting as the code reads it, and a production checklist. They are linked from the connector guide's chapters and the documentation index, whose "If you want to…" table had document-list rows mixed into it; those are back in the document list.
- **The connector, plugin and configuration guides corrected against the code**: REST answers below 400 other than 404 are documents and `timeout-ms` is both timeouts; S3 dates fall back to older folders and the undated object (no `mode.<kind>`), an empty listing serves every kind, a listing error stays in health until a listing succeeds; Kafka's index costs about 0.4–0.5 GB per million entities, ticks mode still replays the topic, and deletes are not pushed to views; the ActiveMQ and RabbitMQ state store has no write-ahead log, no dead-lettering and FIFO compaction; feeds keep only the last fetch's window and start fetched; demo walks are normal draws with the step as standard deviation, and reverse lookups find ids at any depth. `source-name`, the feeds' `user-agent` and the fixed timeouts are listed.
- **Documents by audience**: `docs/guides`, `docs/connectors`, `docs/admin` (with the runbooks) and `docs/architecture` (with the ADRs).
- **The landing page** counts 13 panel kinds (it said 12) and links to their guide.
- **Demo data in every store** ([DEMO_DATA.md](docs/connectors/DEMO_DATA.md)). `tools/load-delta.sh`, `tools/load-postgres.sh` and `tools/load-aerospike.sh` take the same `--trades N --days D` options, from the samples up to a million trades a day.
- **Recent history in one store, years in another.** A search on a business day that the first store does not hold (for example, older than Aerospike's TTL) is answered by the next store that does (for example, Delta Lake).
- **Engine:**
  - The plugin interface gains `columnar`/`columns` (`ColumnSet`). Derived kinds aggregate over columns and serve their last result while recomputing.
  - Impact lists 200 entities per group with a count of the rest.
  - The type-ahead index keeps ids sorted for a million entities per kind.
- **Booking-system trade ids.** Trades carry their booking system's id and `sourceSystem`/`sourceTradeId`: Murex `MX-`, Calypso `CLY-`, Endur `END-`, Imagine `IMG-`, Bloomberg TOMS `BBG-` and Wall Street Systems `WSS-`.
- **Generators:**
  - `tools/samplegen/bulk_trades.py --trades N --days D` writes a laid-out book, or JSON lines for Aerospike.
  - `tools/load-aerospike.sh --trades N --days D --ttl-days N` streams a book into Aerospike.
- **Fixes:**
  - A pick list over a book larger than the scan limit no longer fails.
  - Reverse lookups no longer merge in quadratic time.

## 1.12.0 — Derived kinds, notes, reports, plain words and a signed pack registry (2026-10-01)
- **A signed, versioned pack registry (ADR-018).** `tools/packreg/packreg.py` makes Ed25519 keys and publishes pack folders as reproducible, signed archives with an `index.json`.
  - **Installing:** Admin → Packs → **From the registry** installs, upgrades and rolls back packs.
  - **Checks before installing:** the SHA-256, the signature by a trusted publisher (`drishti.packs.registry.trusted-keys`), that files stay inside the pack with sizes bounded, and that `pack.yaml` names the same pack and version.
  - **Where installs go:** `data/packs/installed/`, which takes precedence over the shipped packs.
  - **Auditing:** installs, upgrades and rollbacks are audited with the publisher and hash.
- **Ask in plain words.** Type a phrase into the command line (`live trades over 5m in BOOK-RATES-3, biggest first`) or the search page's **In words** box.
  - **What you see:** the structured search it makes, how each part was read, and the words it did not understand. Nothing runs until you press **Run it**.
  - **How it works:** it is deterministic, with no AI service. The vocabulary is the server's kinds and each kind's fields and values, learned from its documents.
  - **API:** `GET /api/v1/phrase`.
- **Rachana-EL: text that is not a number now orders as text**, so ISO dates compare (`$.maturityDate < '2028-01-01'`). Before, any text comparison was false. Comparisons involving numbers are unchanged.
- **Who looked at what.** Every answered view, raw document, history read, search and CSV export is recorded with the person, the time, the entity or search, and the business date.
  - **Where to see it:** administrators see it in Admin → Access (filter by person, action, kind, id and dates) and from a view's **Viewed by**.
  - **Storage:** recording is asynchronous and batched, and it never slows a read. The log is kept 90 days (`drishti.access-log`) in the identity database (SQLite or PostgreSQL).
- **Scheduled reports.** A search can run on a schedule and deliver its results as CSV, either to the server's reports folder or to a webhook an administrator allows.
  - **Schedules:** `business-days 18:30`, `weekdays 07:00`, `daily`, `hourly` or `cron`.
  - **Runs:** a report runs as its owner, with their roles and redaction at the time it runs, and records each run.
  - **In the console:** a Reports page (Views → Reports) and **Schedule…** on the search page.
  - **API:** `/api/v1/me/reports` and `/api/v1/admin/reports`. Settings are under `drishti.reports`.
  - **Email:** not supported yet; it needs SMTP settings.
- **Fix:** CSV numbers are written in full (`199000000`, not `1.99E8`).
- **Notes.** Anyone who may open an entity can leave a note on it or on one of its fields, from **Notes** in the view's header or **Add a note** in a number's history window.
  - **Who can change them:** only the author edits a note; the author or an administrator deletes it. Every change is audited.
  - **In the view:** a field with a note shows a dot.
  - **Storage and API:** notes live in the identity database (SQLite or PostgreSQL), under `/api/v1/notes`.
- **Shared workspaces.** Share one of your workspaces with everyone, or with chosen roles and people.
  - **For readers:** they see it read-only, as you keep it, under **Shared with you**. Panes they may not open stay hidden, and they can save a copy as their own.
  - **API:** `/api/v1/me/workspaces/{name}/share` and `/api/v1/workspaces/shared`.
- **The trading pack's desk P&L** (`DPNL DESK-RATES`): a derived kind summing each desk's trades.
- **Derived kinds.** A pack can declare a kind computed from another, using the new built-in `derived` connector.
  - **How it works:** members are grouped by an expression, with `count`, `sum`, `avg`, `min`, `max`, `distinct` and `first`, and optional rows per member.
  - **Dates:** a picked date is computed from that date's members.
  - **Caching:** results are cached per date for a configurable `refresh`.
  - **Example:** the finance pack ships `book-pnl` (`BPNL RATES-NY-3`), a book's P&L summed from its trades, with a Sutra.
  - **Pack files:** connector settings in `pack.yaml` may be nested.
  - **Plugin API:** connectors can read other kinds through the routing with `SourceContext.reader()`.
- **One console, many servers (ADR-016).** List servers under `servers:` in the console's configuration, and people pick one and sign in to it.
  - **Sessions:** each server has its own session cookie, bound to that server. Switching keeps the others, and signing out ends only the current one.
  - **Top bar:** shows the current server and a menu to switch. `/servers` lists each server's state.
  - **Links:** `?srv=` and `/connect/<id>` open a link on its own server, and unlisted servers are reached only this way.
  - **Server:** a new public `GET /public/about` gives the picker the name, version and sign-in methods, and nothing about the data.
  - **Compatibility:** without `servers:`, nothing changes.

## 1.11.0 — Tokens, history and freshness (2026-10-01)
- **Data freshness.** Every view's footer says when its source last received new data ("updated 2 min ago"). A connector's `stale-after` setting marks it behind:
  - **On the view:** an amber banner.
  - **In Admin → Health:** a new **Last update** column, and the overall status turns `DEGRADED`.
  - **Sources that report it:** Kafka, ActiveMQ, RabbitMQ, Delta, file, feeds and demo.
  - **Pack defaults:** the trading stream (15 minutes) and the daily feeds (4 days).
  - **Where a value comes from:** the history window says which path, connector, business date and generation it came from, with a link to the raw document.
- **Field history.**
  - **In a view:** click a number to see that field over the last 10, 30, 90 or 250 business days, as a chart and a table with the date the data is for and its connector.
  - **On the search page:** **Compare with** runs a search on two dates, showing each number's change and marking entities added or removed.
  - **API:** `GET /api/v1/history/{kind}/{id}/series` and `GET /api/v1/search/compare`.
- **Personal API tokens, a Python client, and Excel.**
  - **Tokens:** made on My account → API tokens. A token is `drk_<id>_<secret>`, shown once and stored only as a SHA-256 hash. It reads as its owner (their current roles and packs), only while they are enabled, and never writes. It can have an expiry and a last-used time, and can be revoked. Admin → Tokens lists and revokes anyone's; every change is audited.
  - **CSV search:** `GET /api/v1/search/csv` returns any search as CSV for spreadsheets, with formula-like text neutralised.
  - **Python client:** `clients/python/drishti_client.py` uses the standard library only, with `field`, `document`, `view`, `search`, `diff` and `to_pandas`, plus a CSV command line.
  - **Docs:** `docs/guides/CLIENTS.md` covers Python, Power Query for Excel, and curl.
- **Sutra Studio is a YAML editor with completion.**
  - **Completion** comes from the served schema, by where the cursor is (Ctrl+Space, or as you type):
    - keys valid at that point (a panel's common keys plus its kind's options);
    - values for kind, area, key, fmt, tone and match.kind;
    - functions with their signatures inside expressions;
    - field paths after `$.` (from the entity being previewed, or pasted JSON) and `@.` (the elements of the panel's `rows`/`each`).
  - **Live checks:** unknown or duplicate keys, an option the panel's kind does not take, missing required keys, and values outside their lists. They show in the gutter and in the problems list, with jump-to-line.
  - **Field help** for the key under the cursor.
  - **Summary tab:** derived from the YAML; it replaces the Markdown Document tab.
  - **Kept:** Insert… and Jump to… work on YAML, New Sutra starts from a `rachana: 1` skeleton, and the Markdown toolbar and editor are gone.
- **Sutras are YAML, and only YAML (ADR-017 supersedes ADR-011).**
  - **File format:** a Sutra is one YAML file, `<name>.v<N>.sutra.yaml`, starting with `rachana: 1`, the language version (a missing or unknown version is `DRS-2009`). It can carry `description:` and `notes:` (plain text), and each panel a `description:`.
  - **Markdown removed:** Markdown Sutras are no longer read. A `.sutra.md` file, or a Sutra saved under a plain `.yaml` name, is reported in health with how to fix it (`DRS-2004`).
  - **Conversion:** all 228 shipped Sutras are converted; a one-off check proved each builds exactly the same layout as before. `tools/rachana/md_to_yaml.py` converts site Sutras. The generators write YAML.
  - **Schema:** `GET /api/v1/rachana/schema` returns the JSON Schema of a Sutra, generated from the grammar with this server's kinds and formats, for editor completion and checking.
  - **Docs:** every guide, tutorial and reference shows YAML Sutras (each complete example starts `rachana: 1` and is parsed by the tests); the runbook and troubleshooting cover `DRS-2004` and `DRS-2009` and converting old files.
- **Build gates: Error Prone and Spotless (ADR-007 amended).** Error Prone runs in every compile; its error-level checks and three more (non-atomic volatile updates, locks taken outside `try`, the default time zone) fail the build. Spotless checks unused imports, trailing whitespace, final newlines and indentation at `verify` (`./mvnw spotless:apply` fixes it). Fixed what they found: a validation callback whose result was discarded, a statistics counter updated non-atomically, a JDBC default date in the machine's time zone.
- **Fix: the packs menu could vanish for a minute after a server restart.** The console cached its one-pack fallback while the server was away; it now keeps the last list it knew, and never caches the fallback.
- **Load a pack while the server runs.** Admin → Packs → **Load** checks a pack that is on disk but not loaded (with the same checks as start-up; a clash is refused and nothing changes), records it in the pack overlay (`data/packs/added.yaml`, `DRISHTI_PACKS_OVERLAY`), and restarts the server inside its own process: sessions survive and live views reconnect. If the server cannot start with it, the overlay is put back. **Unload** takes back a pack loaded this way. API: `POST /api/v1/admin/packs/{name}/load|unload`.
- **All table controls sit in the table's heading:** the filter, the pager, ▲ ▼ and rows per page, in the panel's heading bar (or a strip above a table without one), never under the table; the page buttons are always shown, greyed when there is nowhere to go. Every table has the filter, ladders included; `search: false` (table and ladder panels) turns it off.
- **The table filter sits in the table's heading.** In a table panel it is in the panel's heading bar, and above pick lists and admin lists it has a strip of its own; it is no longer under the table. Only tables have it. A Sutra turns it off for a table panel with `search: false`; on any other panel kind the option is a problem (DRS-2023).
- **Studio keeps test entities per Sutra.** **+** keeps the entity being previewed, the list previews any of them, and **Run all** previews the Sutra in the editor against every one, listing those whose panels fail or that the Sutra cannot read. Kept per author on the server (`/api/v1/me/studio-tests/{sutra}`).
- **Every table sorts and filters.** Click a column heading to sort (numbers, amounts, percentages and dates as values; a third click restores the order). A box filters rows on any cell; `⧩` adds a filter per column, with `>`, `<`, `>=`, `<=`, `=` for numbers (`>200m`). Sort, filters, page and selection survive live updates.
- **Command history and aliases.** `↑` in an empty command line recalls earlier commands (50, kept by the server, so they follow you between browsers). On My account → Aliases, short words stand for longer commands (`MYBOOK` → `BOOK BOOK-RATES-1`), expanded by the server before the command is read. API: `/api/v1/command/history`, `/api/v1/command/aliases`.
- **Pack overviews.** Type a pack's code alone (`MKT <GO>`, or its name) to see every kind you may open, with its mnemonic, how many the sources hold, an example and the key fields; each mnemonic opens that kind's pick list. Every pack has a `code:` (BNK, MKT, TRDS, MRSK, CCR, LIQ, CLI, OPR, RTL, GENO, ECO, POLS, FIN, LOGI). API: `GET /api/v1/packs/{code or name}/overview`.
- **Pick lists show key fields for every generated pack.** Climate, economics, genomics, liquidity, operational risk, politics and retail declare `columns:` for their main kinds (for example `MTG` shows borrower, balance, rate, LTV, remaining years and days past due).
- **JDBC query mode: JSON columns become nested data.** Columns of type `json`/`jsonb`, and text columns listed in `json-columns`, are parsed, so `legs[0].rate` works in a Sutra. A cell that is not JSON stays text. A `jdbc` plugin with no `url` now stays idle (`PluginNotConfigured`) instead of failing.
- **The lake finds new tables without a restart.** A Delta connector without a fixed `kinds` list now lists its domain's tables at every reindex (a minute by default), so a kind loaded into the lake while the server runs is served within a minute.
- **Alert history survives restarts.**
  - **Storage:** fired alerts are kept in the identity database (`drishti_alert`): the newest `drishti.alerts.keep` (1,000) per user, pruned as they arrive.
  - **Numbering:** an alert's number is its row id, so it stays stable across restarts.
  - **Deleting a user:** removes their alerts.
- **Operational endpoints accept any role with the admin power.** That includes a role defined in Admin → Roles, not only the built-in `admin`.

## 1.10.2 — Themes and the top bar (2026-09-30)
- **The top bar is one row again.** Beside the brand come the command line, the business date and Live. Then the Views, Build, Admin and Help menus, which open leftwards so they stay on screen and keep their labels down to 1280 px. Then the tools. Below 1100 px the command line moves to a line of its own.
- **Fix: the active menu entry was hard to read** (accent text on an accent tint, such as *Terminal* in the Views menu on the terminal page). The page you are on now has bright text, a neutral highlight and an accent bar. The active menu button keeps its text colour and is marked by its underline.
- **Every theme shows its gradient.**
  - **What was wrong:** each theme mixes its two gradient colours into the chrome, but at one fixed strength. On Parchment, Crimson, Crimson dark and Wall Street that was too faint to see.
  - **Per-theme strengths:** each theme now sets its own (`--g-page`, `--g-bar`, `--g-head`, `--g-pnl`), and the light and crimson themes mix in more.
  - **Command line:** its row in the top bar is graded too.
- **Table links:** links inside tables are underlined (dotted), so ids read as links in every theme.

## 1.10.1 — Kafka ticks mode (2026-09-30)
- **Kafka `mode: ticks` works.**
  - **Behaviour:** a connector that keeps no state now drives the ticks of views a lake or database answers. Such a view is live (the green dot), subscribes to the stream, and repaints on each message, while reads still come from the store.
  - **API:** a new SPI method, `SourcePlugin.pushes(ref)` (default false). A plugin answers true for entities it pushes but does not serve.
  - **Before:** a view opened a live stream only when the document that answered was live.

## 1.10.0 — Banking and domain packs, users in a database, pick lists, a new top bar (2026-09-30)
- **Documentation, expanded with worked examples throughout.**
  - **New guides:** QUICKSTART.md (ten minutes to a live view), DEVELOPER_GUIDE.md (repository map, building and testing, project rules, the request path, recipes for endpoints, plugins, panel kinds, packs, pages, database tables and settings), RACHANA_GUIDE.md (a tutorial from a first Sutra to a full view) and CONNECTOR_GUIDE.md (getting your data in, a worked set-up per connector).
  - **Expanded:** RACHANA_REFERENCE.md is the complete reference (every panel kind and function, every problem code). PACKS.md is the full pack guide. The user guide, inference, live, troubleshooting, performance, runbooks and plan are expanded too.
  - **Help centre:** the new guides are in the in-app help centre.
- **Several servers, one database:** role and pack changes reach every server within `drishti.identity.refresh-seconds` (15).
- **Generated packs can declare pick-list columns** (`columns=` in `PackSpec`).
- **Build fix:** `drishti-messaging` pointed at the wrong parent POM path.
- **Security: operational endpoints are guarded when security is on.**
  - `/actuator/health` stays open for probes.
  - The rest of `/actuator` (metrics, Prometheus, info) needs an admin token, or the scrape token `DRISHTI_METRICS_TOKEN` as a bearer token.
  - `/api/docs` needs any valid token.
- **Console readiness:** `/readyz` answers 503 while the console cannot reach the server; `/healthz` stays the cheap liveness check.
- **Fixes:**
  - **Kafka:** an envelope message keyed by the bare id (not `kind/id`) is now indexed, so it can be read.
  - **Live ticks:** a live view's ticks come from the source it was read from, with a real stream before the demo samples, as reads already did.
  - **Unconfigured plugins:** Kafka or a feed with no settings now stays idle (`PluginNotConfigured`) instead of being reported as failed.
  - **Monitors:** monitor rows no longer repeat the error code.
- **The product's name and legal notices come from configuration.**
  - **Server:** `drishti.branding.*` (product, tagline, owner, copyright, notice), used by the About answer and sign-in messages.
  - **Console:** `ui.*` (product, tagline, product_native, product_meaning, copyright, notice), used by every page, the footer, the landing page, About and alert notifications.
  - **Other settings:** the seeded admin's display name (`seed-display-name`) and the feed connectors' user agent (a pack setting) are configured too.
  - **What stays:** source-file legal headers.
- **Fix: menus jumped from the left edge of the screen when opened.** The opening animation used `transform`, which Bootstrap also uses to place menus. Panels now sit under their button, set in CSS, and fade in using `translate`.
- **Pack guides describe domains, not sample records.**
  - **Removed:** "Try it" tables, sample counts and lists of sample ids.
  - **Added:** a "Finding things" section with generic commands (`<MN> <id>`, pick lists, `<MN> <field>=<value>`).
  - **Generators:** they produce the same output.
- **A new top bar, after MAYA's.**
  - **Layout:** two rows. Row one: the brand with its tagline, then Views, Build, Admin and Help as mega-menu panels (each entry with an icon and a line on what it does), then quiet round tools (live, alerts, packs, theme and the user's avatar menu with desk, roles, clock, account and sign-out). Row two: the command line across the width, and the business date.
  - **Pack menu:** it laid all packs out in one off-screen row. It now lists one pack per line, with what it covers, all/none and Apply.
- **Admin → Packs: switch packs off and on for everyone, at once.**
  - **Switching off:** the pack's kinds cannot be opened, its mnemonics and suggestions go, and it leaves every pack menu at the next click.
  - **Safeguards:** a pack another pack builds on stays on until that one is off, and switching one on switches on what it builds on.
  - **Storage:** the choice is kept in the identity database (`drishti_pack_state`), survives restarts, and is audited (`pack-enabled`, `pack-disabled`).
  - **Not loaded:** packs on disk that the server did not load are listed with how to load them.
  - **API:** `GET/PUT /api/v1/admin/packs[/{name}]`.
- **Pick lists, as on a Bloomberg terminal.** A command that names one entity opens it; one that names several shows a scrollable table to pick from, with the kind's key fields beside each row.
  - `TRD T-100` lists trades whose id starts with T-100, and `CPTY north` matches titles too. `TRD T-1*0` uses `*` as a wildcard. `TRD productType=Revolver` and `TRD notional > 10m and currency = usd` list by field value. They combine (`TRD T-1* desk=rates order by mtm desc`), and `TRD` alone lists every trade.
  - Case never matters, and text `=`/`!=` ignore case in every search.
  - Packs declare each kind's key fields under `columns:` in pack.yaml (trade, counterparty and book have them). Other kinds show their first plain fields.
  - `not status = matured` now means "not (status = matured)", and an unknown mnemonic in a search says so.
- **Every table pages and walks with the keyboard.**
  - **Pager:** first/previous/next/last, 25/50/100/250 rows per page (remembered), and ▲ ▼ buttons.
  - **Keys:** ↑ ↓ move the selection across pages, PgUp/PgDn page, Home/End jump, and Enter opens the row.
  - **Live updates:** tables that re-render keep their place.
- **Ids in tables are links.** A column bound to an id field (`tradeId`, `counterpartyId`, `bookRef`) links to the entity, so the trades in a book open with a click.
- **The command line's dropdown shows 25 suggestions** (`drishti.commands.suggest-limit`, `DRISHTI_SUGGEST_LIMIT`, up to 50), and the list scrolls.
- **Docs: every guide rewritten to be example-led.**
  - **New:** GETTING_STARTED.md, a from-zero walkthrough, also in the help centre as *Install and run*.
  - **Rewritten:** README quick start, the doc map, USER_GUIDE, PACKS, INFERENCE, TROUBLESHOOTING, OPERATIONS, CONFIGURATION (every setting), API_GUIDE (every endpoint, with curl), LIVE, PERFORMANCE, ARCHITECTURE, the runbooks, and the in-app guides.
- **Users, roles and saved documents in a database (JPA).**
  - **What moved:** users, their roles and packs, roles defined by administrators, saved workspaces, monitors, alert rules and settings, and the audit log now live in one database. It is reached only through JPA entities and Spring Data repositories (Hibernate), with no SQL in code.
  - **Databases:** SQLite is the default (one file, nothing to install); PostgreSQL is chosen with `DRISHTI_IDENTITY_DB_URL`.
  - **Schema:** one schema file per database (`db/schema-sqlite.sql`, `db/schema-postgres.sql`), applied idempotently at start, with no migrations. On PostgreSQL, Hibernate checks the entities against it.
  - **Upgrading:** 1.9 files are imported once and renamed `*.imported`.
  - **Tests:** the same tests run against both databases.
- **Admin → Roles.** Define roles in the browser: the kinds a role opens, plus raw JSON, author, approve and admin powers. Changes apply at the next request and are audited. Built-in roles from configuration and packs are shown read-only, and a role someone holds cannot be deleted (`DRS-6009`). There is an API at `/api/v1/admin/role-definitions`.
- **Docs:** USER_MANAGEMENT.md is rewritten as a step-by-step guide. PLUGIN_GUIDE.md now has a working example of every connector: settings tables taken from the code, data layouts, pack declarations, and how routing works.
- **Fix: the UI could freeze.** The page stayed on screen but typing did nothing and no suggestions appeared.
  - **Browser cause:** every view and the alerts bell held its own connection, and browsers allow six per site over HTTP/1.1. With three tabs (or a workspace and a tab) every other request waited forever.
  - **Browser fix:** each tab now opens one live channel carrying its views, workspace panes, bell and monitors. Subscriptions are added to it without reconnecting, and a tab hidden for 10 s gives its connection back.
  - **Console cause:** a stream whose tab had closed kept its connection to the server for good, because Starlette's cancel scope cancelled the clean-up.
  - **Console fix:** the console now closes such streams within seconds.
  - **Checks:** verified in Chrome (8 tabs: suggestions in 7 ms; closed tabs: no streams left on the server), with regression tests.
  - **Assets:** asset URLs now carry a fingerprint, so browsers pick up new scripts at once.
- **Gradient themes.** Every theme uses its own two gradient colours on its chrome:
  - **Where:** a soft wash over the page, a glass top bar with a gradient rule, a tinted view header and panel headers with accent bars, gradient accent buttons, and gradient text on titles and the brand.
  - **Readability:** panel bodies, tables and figures stay solid, so contrast is unchanged. The solid look is used when the system asks for more contrast or less transparency, and print is plain.
  - **Fix:** the top bar no longer overflows at laptop widths; the clock, live text and theme label give way first.
- **Delta Lake in S3, and lake maintenance.**
  - **Storage:** the Delta connector reads through a `LakeStore`, either a local folder or any Hadoop file system: `s3a://` for S3 and S3-compatible stores. This uses Hadoop's S3 module with only the AWS SDK modules it needs (no bundle).
  - **Settings:** `s3.*` shorthands, any `hadoop.fs.s3a.*` setting, and the AWS credential chain.
  - **Tests:** the dated contract passes on a lake served from an S3 API server in Docker.
  - **Maintenance:** `tools/lake/maintain.py` applies a retention window by business date, compacts, checkpoints and vacuums, on a daily schedule or once (with dry run), for local and S3 lakes. It logs JSON per table, and a failing table doesn't stop the others. A test proves Drishti's Java reader reads a lake it has maintained.
- **S3 connector.** Entity documents in Amazon S3 or any S3-compatible store, in the file connector's layout (undated, and dated folders by business date).
  - **Settings:** endpoint override for MinIO or on-prem stores, and static keys or the AWS credential chain.
  - **Search:** identifiers and dates are listed periodically for search.
  - **Safety:** identifiers cannot escape the prefix.
  - **Size:** only the SDK's S3 module and the JDK HTTP client (about 9 MB).
  - **Tests:** run against an S3 API server in Docker.
- **ActiveMQ and RabbitMQ connectors.** Live entities from queues and topics, built on a shared base (`drishti-messaging`).
  - **State:** a queue keeps no history, so each connector keeps the latest document of every entity in a persistent RocksDB state store (new persistent mode of the disk cache) that survives Drishti restarts, with a bounded memory cache.
  - **Messages:** documents or envelopes; deletes by empty body, null doc or header. Durable subscriptions and acknowledgement after storing mean nothing is lost while Drishti is down. Changes are pushed live, and search covers everything received.
  - **Reconnection:** ActiveMQ's failover transport, with interruptions reported in health; RabbitMQ's automatic recovery, with a first-connection supervisor.
  - **Tests:** a shared contract and an outage test run against real brokers in Docker (ActiveMQ Classic 6.1, RabbitMQ 4.1).
- **Pack inheritance (ADR-015).** `extends: [parent, …]` (and the older `requires:`) makes a pack inherit everything its parents bring.
  - **Precedence:** C3 linearisation. The child wins over its parents, the rightmost parent over those to its left, and a shared ancestor counts once.
  - **What can be overridden:** mnemonics, link fields, badges, roles, routes, connectors, Sutras (same `name@version`), labels and formats. Overrides apply globally and are logged and listed under Admin → Health.
  - **Still strict:** kinds are never overridden, and unrelated packs still may not clash.
  - **Packs:** `market-risk` and `counterparty-risk` now extend `[market-data, trading]`, and the generators write `extends:`.
- **Admin → Health.** Every connector's status (with the reason while it reconnects), reads, errors, p50/p99 latency, last error and caches. Every pack's Sutras, Sutra problems and connectors. Live streaming and server figures. An overall OK / DEGRADED / DOWN. Refreshes every 5 s, and `GET /api/v1/admin/health` serves the same for monitoring. Read statistics are recorded lock-free on the router's read path.
- **Every connector reconnects by itself.**
  - **JDBC:** pools lazy slots, so it starts with its database down, reconnects broken connections, and lists table kinds in the background until the database answers. Health reports the last error.
  - **Kafka:** a supervisor recreates the consumer after a fatal error or a broker that was down at start, with backoff from 1 s to 30 s, and resumes from the last offset applied instead of replaying.
  - **Aerospike:** the client keeps tending the cluster in the background.
  - **Others:** feeds, REST, files and Delta connect afresh on every call.
  - **Tests:** reconnect tests kill and restart a real PostgreSQL in Docker, and bring a Kafka broker up after the connector.
- **Rachana reference: a complete annotated example.** A swap Sutra with a comment on every line covers matching, templates, the strip, thirteen panels (kv, tabs, table with total and "more", ladders with filters and highlights, status, markdown, provenance, lines from the document and from a linked curve, bars, a gauge, links) and function keys. It comes with the document fragment it reads and a table of what each part does. `RachanaReferenceExampleTest` previews the block exactly as written against `T-10001`.
- **Docker verified.** The PostgreSQL and Aerospike contract tests pass against real containers.
  - **Aerospike 8.1:** needs 15,000 file descriptors (set in the test and in compose), and its image is pinned rather than `latest`.
  - **Aerospike connector:** now starts even when the cluster is not ready yet, and reconnects in the background.
  - **Compose:** host ports are configurable (`DRISHTI_PG_PORT`, `DRISHTI_KAFKA_PORT`, `DRISHTI_AEROSPIKE_PORT`).
- **W21 Personal settings.** Theme, landing page after sign-in, clock time zone, compact density, change-flash on or off, default search size, and up to 20 pinned entities on the terminal home.
  - **Storage:** kept on the server (`/api/v1/me/settings`, every value validated), so they follow the user to any browser.
  - **Console:** applies them on every page (the theme before first paint), saves theme changes from the theme menu, and offers **Pin** in every view.
- **W20 Single sign-on (OpenID Connect, ADR-014).**
  - **Console:** runs the authorization code flow with PKCE, state and nonce, and offers "Sign in with single sign-on" on the login page.
  - **Server:** verifies the ID token itself: signature against the provider's keys (JDK cryptography only; RSA, RSA-PSS or ECDSA; never `none` or an HMAC), issuer, audience, authorised party, expiry, not-before, issued-at and nonce.
  - **Users:** provider groups map to Drishti roles, and users are created on first sign-in. A local disable always wins, and the provider can't demote the last admin; every step is audited.
  - **Fix:** the console now sends users who aren't signed in to the login page for search, compare and export too.
- **W19 Sutra governance (ADR-013).** With review on (the default), a Studio save is a proposal: validated, with the author's note and the live text it was written against.
  - **Review:** approvers (new role flag `approve`, or admins) read the diff under **Studio → Reviews**, then approve (published and hot-reloaded) or reject with a reason; authors may withdraw.
  - **Safeguards:** four eyes, so nobody approves their own proposal. Approval is refused if the live Sutra changed after the proposal (`DRS-2006`), and two approvers cannot both decide one proposal.
  - **Record:** proposals are stored as JSON, and every step is audited.
  - **API:** `/api/v1/sutras/proposals…` and `/api/v1/sutras/{name}/history`.
  - **Settings:** `DRISHTI_SUTRA_REVIEW=false` restores direct saving.
- **W18 Export and share.**
  - **CSV:** any table-like panel downloads as CSV (shown numbers become plain numbers, UTF-8 with BOM), as do search results and comparisons.
  - **JSON:** the document downloads as JSON, redacted for the role.
  - **Print:** a print stylesheet gives a clean light page, or a PDF, in every theme.
  - **Share:** copies a link that reopens the view with the same business date and exact known-at instant (`/asof?ki=`).
  - **Safety:** exports read through the server like the screen, so entitlements and redaction apply.
- **W17 Structured search.** Type `TRD where notional >= 250m and assetClass = 'Rates' order by mtm desc limit 20` on the command line.
  - **Syntax:** friendly, over Rachana-EL: `and`, `or`, `not`, `=`, `contains`, `startswith`, and amounts like `1.5m`; nested fields by path.
  - **Server** (`GET /api/v1/search`): reads the kind's entities concurrently within `drishti.search.max-scan` (20,000) and `drishti.search.budget` (3 s). It evaluates the condition on each document as the caller may see it (a masked field never matches), then sorts and limits. It follows the business date and known-at.
  - **Console:** a results page whose columns are the query's fields, labelled from the taxonomy; **Watch as a monitor** turns the results into a live watchlist.
  - **Rachana-EL:** gains `contains()` and `startsWith()`; new error code `DRS-4004`.
- **W16 History.**
  - **Server:** `GET /api/v1/history/{kind}/{id}/diff` compares an entity between two business dates, or two "known at" instants, field by field. Arrays are matched by identifier or natural key (tenor, date, code, …), numbers get deltas, each side is redacted for the caller, and labels come from the taxonomy.
  - **Console:** a **Compare** page (from any dated view; filter changed, added or removed), and a **known at** clock beside a picked date for time travel (read in the business zone, sent as `X-Drishti-Known-At`, cleared with Live).
  - **Delta:** time travel before a table's first commit finds nothing, and after its last commit reads the latest (both were errors).
  - **Console fix:** a known-at choice could cache the wrong business date for the top bar.
  - **Top bar:** it no longer scrolls sideways when a date is picked on narrower screens.
- **Docs and tutorials.** New *Tutorial 5 · Build a domain pack* (the common pack builder, step by step). README, the user guide (mnemonics of every generated pack, from the manifests), PACKS.md, LIVE.md and the architecture's concurrency section brought up to date; OPERATIONS explains how the disk cache reclaims space.
- **Concurrency hardening, audited module by module.** Fixed:
  - **Disk cache:** RocksDB generations are reference counted. A nightly clear, a purge or a close never frees native memory under a running read (it could crash the JVM after a 30 s grace period); later calls miss instead of failing.
  - **Sign-in and administration:** changes are applied to the current record under per-user and admin locks. Parallel wrong guesses can no longer dodge lockout, a sign-in finishing late can no longer undo an admin's disable or password reset, and two admins can no longer disable each other at once.
  - **Live topics:** subscribers arriving together could leave a topic unconnected, so a live view never ticked. Topics now connect exactly once.
  - **Kafka:** the cold-miss reader uses a `ReentrantLock` with a timeout (was `synchronized`, pinning carrier threads for up to 5 s); a fetch racing a newer record or a tombstone no longer caches the stale document; listener entries go with their last listener (also in the demo source); close is safe against a late read.
  - **Delta:** partitions and logs load through async caches on virtual threads (no map lock held during Parquet reads).
  - **Hot paths:** a failed live rebuild backs off (0.5 s to 30 s) instead of spinning; alert-rule saves are serialised so none is lost; the audit log, preferences and Sutra registry use `ReentrantLock` instead of `synchronized`; a preference delete can no longer race a write.
  - **Snapshots:** the search index and the Sutra registry publish complete immutable snapshots (searches never see an empty index mid-rebuild), and hot reload survives an unexpected error.
  - **Stream limits:** the stream cap is taken atomically and shared by monitor streams, which now release their subscriptions on failure.
  - **Scheduler and shutdown:** the plugins' scheduler runs on virtual-thread workers; shutdown interrupts stream writers instead of waiting out a heartbeat.
  - **Tests:** race tests reproduce each hazard and fail on the old code.
- **Politics and society pack.** Two fictional polities, so nothing describes real parties or people, with real mechanics: seats by D'Hondt with a 5% threshold or by first past the post, fortnightly polls with 95% margins of error, bills through their readings with votes, regional results and social indicators.
- **Economics pack.** Seven real economies with illustrative figures: growth paths and output by sector, macro indicators with release calendars and surprises, central-bank decisions and guidance, forecasts with weighted scenarios, bilateral trade flows, labour markets, fiscal positions and consumer-price contributions.
- **Genomics and biology pack**, the first with no banking ties. Genes with GRCh38 locations, expression by tissue and known variants; variants (BRAF V600E, KRAS G12D, EGFR L858R, CFTR F508del, …) with HGVS names, dbSNP ids, significance and population frequencies; UniProt proteins with domains; pathways; tumour samples with variant calls; sequencing runs with quality by cycle; differential-expression studies; targeted-therapy trials. Reference facts are public; patient-level data is synthetic.
- **Retail-banking pack.** Customers with every product they hold, current and savings accounts with transactions (live balances), mortgages with amortisation, property and payments, credit cards with spend by category and statements, personal loans, branches with product mix and NPS, collections cases for delinquent cards and loans, and IFRS 9 portfolio segments with ECL by stage.
- **Fix:** a generated pack may no longer reuse a link field that already means another kind. Climate stress linked its NGFS scenario through `scenario`, which market-risk maps to stress scenarios; it is now `climateScenario`. The domain-packs test checks that no advertised example has a missing link.
- **Operational and non-financial risk pack.** Loss events (Basel event types, recoveries, timelines, the failed control), risk and control self-assessments, key risk indicators with amber and red thresholds (live), audit and regulatory issues with actions, operational-risk scenarios, third-party (vendor) risk, cyber incidents and SMA operational-risk capital per legal entity, on the banking-core desks and legal entities.
- **Liquidity-risk and climate-risk packs.** Liquidity: LCR and NSFR per legal entity, maturity ladders, HQLA holdings (the market-data bonds), funding sources (the banking counterparties), liquidity-stress survival and intraday liquidity. Climate: counterparty climate profiles, PCAF financed emissions per book, NGFS scenarios, climate stress per desk, physical-risk assets and the EU-taxonomy green asset ratio. Both are generated from the banking data, so entities line up, and are built by a new common builder (`tools/packgen/common/packbuild.py`) that writes the manifest, Sutras, samples, guide and lake after checking ids, links and Sutra paths. Kinds with no links no longer get an empty links panel.
- **Admins purge caches.** Admin → Caches lists what the engine and each connector hold (entries, memory, disk, hit counts) with a Purge button per cache and Purge all; `GET /api/v1/admin/caches`, `POST /api/v1/admin/caches/{name|all}/purge`. Every purge is audited. Plugins report and drop their caches through two new SPI defaults, `cacheStats()` and `purgeCaches()`.
- **Disk cache for live data.** A new `drishti-diskcache` module: a RocksDB store on local disk, bounded by size (FIFO: the oldest data goes first), without a write-ahead log, cleared every night at a configured time and zone by swapping in a fresh store (readers never wait). Each connector has its own store and directory, so connectors do not contend. The Kafka connector writes every message to it and reads memory, then disk, then Kafka. The trading stream uses it by default (`./data/cache/trading-stream`, 10 GB, cleared at 02:00 New York).
- **Bounded memory.** The Kafka connector keeps only where each entity's latest message is, plus a cache of recently read documents limited by `cache-mb`; a miss reads the one message back by offset, and messages for unwatched entities are not parsed. `mode: ticks` keeps nothing and only drives the ticks of open views. The Delta partition cache is limited by size (`cache-mb`), and the Aerospike reference index can be turned off (`reverse-index: false`). OPERATIONS.md lists every cache and its limit.
- **Kafka live source.** A `kafka` connector reads topics of entity documents (the latest message per entity is the entity, rebuilt from the start of the topic) and pushes every new message to open views, so they tick from a real stream. Envelope or whole-document messages, tombstones, no consumer-group commits. The trading pack declares `trading-stream` (off until `DRISHTI_STREAM_TRADING=true`); `tools/samplegen/stream.py` replays and ticks the trades; `deploy/compose.data.yaml` adds a Kafka broker. Tested in-process with an embedded KRaft broker, including end to end through the server. For Live, a real stream now wins over the demo samples.
- **PostgreSQL and Aerospike (R5).** The JDBC connector gains a table mode: every kind of a data domain in one PostgreSQL table `(kind, id, business_date, doc jsonb)`, dated (snapshot or effective), with search and reverse lookups by SQL/JSON path. A new Aerospike connector keeps a record per entity with a bin per business date. `make_data.py --postgres` and `tools/load-aerospike.sh` load the banking data; `deploy/compose.data.yaml` runs PostgreSQL 18 and Aerospike CE; the `postgres` and `aerospike` profiles switch the banking stores. The banking connectors are now `<domain>-store`.
- **One contract for every dated store.** `DatedSourceContract` (testkit) runs the same tests against Delta Lake, PostgreSQL and Aerospike (the database runs use Docker via Testcontainers and are skipped without it; the PostgreSQL SQL was also verified on PostgreSQL 18).
- **Public data feeds.** A `feed` connector with five feeds: NY Fed SOFR, ECB €STR, ECB euro reference FX rates (with USD crosses), US Treasury par yields, and FRED series. The market-data pack declares each as its own connector, off by default, with its own switch. Feed entities carry the feed in their id, keep history for picked dates, and report failures in health. Tests use recorded public responses.
- **Banking pack documentation (R4).** `tools/packgen/banking/make_docs.py` writes eight guides from the taxonomy and the data: an overview per pack (requirements, data domains and connectors, every kind with its mnemonic, identifiers, fields and links, example commands, roles, impact), the 125-product catalogue, the market-data catalogue and the cross-pack data model. Help gains a **Domain packs** category. The `trader` role can open the market and reference data its trades link to. Drill fails if the guides are out of date.
- **The Sutra guide** (Help → Layouts, Rachana and Sutras): a complete guide to writing Sutras, from a first Sutra to anatomy, labels and taxonomies, paths and expressions, formats and tones, all thirteen panel kinds with screenshots from the banking packs, matching, inference, business dates, imperfect data, Studio and a checklist. A test checks every screenshot it shows is served.
- **Banking data (R3).** `tools/packgen/banking/make_data.py` generates 1,791 consistent documents across the five banking packs: 750 trades in 125 products (rate swaps with real calculation periods that reprice to their MTM), 220 market-data objects, 66 netting sets with exposure, CVA, SA-CCR and collateral, limits, SIMM, VaR, stress, FRTB and P&L explain, and the reference data. Checks: references resolve, netting sets reconcile, every field a Sutra reads is present. `--lake data/delta` writes it as Delta tables by data domain with ten business days of history. The packs gain example commands.
- **`surface` panel kind** (the thirteenth): a grid over two axes drawn as a heatmap, with a **3D** toggle (drag to rotate). The volatility kinds use it. ECharts is now the full build, and ECharts GL is vendored with its one `new Function` replaced, so the strict CSP still holds.
- **Labels are optional.** A strip item or column without `label` is named after the field it reads. Resolution order: the Sutra, then the pack taxonomy (`labels:`), then the global taxonomy, then the field name in words with the `acronyms:` vocabulary (UTI, DV01, MTM…).
- **Tutorial 4 · Nested documents**: paths of any depth, tables over nested arrays, tabs per element, and a whole Sutra over a real trade. Every complete Sutra in the docs is now parsed by a test.
- Live views prefer live sources; a picked date prefers dated ones. The finance pack declares its own lake connector.
- **Banking packs.** The risk pack is split into five packs generated from one taxonomy: `banking-core`, `market-data`, `trading` (125 products), `market-risk` and `counterparty-risk`. Enabling a risk pack brings the packs it requires.
- **Pack dependencies, connectors and routes.** `pack.yaml` gains `requires:` (loaded dependencies first; cycles and missing packs refused), `connectors:` (one per data domain; several packs may declare the same connector identically) and `routes:` (which connector answers each kind). Delta Lake is organised by data domain (`data/delta/<domain>/<kind>/`): packs and domains are many-to-many. Users can open the kinds of the packs their active packs require.

## 1.9.0 — Wave 22, risk pack R1–R2, Markdown Sutras, realistic samples (2026-09-30)
- **Business dates (Wave 22, ADR-012).** A date box in the top bar: **Live** (the default) is the current business date on the New York calendar and streams; a **picked date** is a static snapshot, even when it is today. Weekends and holidays roll back to the previous business day. The console only stores the choice and sends `X-Drishti-As-Of`; the server resolves it (`GET /api/v1/business-date`, `DRS-4003`), and the date reaches every read: views, links, F8 impact, suggestions, raw JSON, monitors and Studio. Views state the date their data is for, and say so when a source keeps no history.
- **Delta Lake connector** (`delta`, Delta Kernel, no Spark). Tables live at `<root>/<domain>/<kind>/`, root configurable (default `./data/delta`), partitioned by `business_date`. Snapshot and effective (last change) tables, time travel with `knownAt`, date-aware reverse lookups and search. `tools/samplegen/lake.py` writes a pack's samples as a lake with ten business days of history plus a restatement.
- **Named connectors** (`drishti.sources.connectors.<name>: {plugin, kinds, settings}`) run a plugin several times, for example one lake per domain. For a picked date, dated sources are tried first.
- The file plugin reads dated folders (`<root>/<yyyy-MM-dd>/<kind>/`); the JDBC plugin binds `:id` and `:asOf`.
- **Imperfect documents never break a view.** A panel whose data is missing or has the wrong shape renders "No data available". Header fields and titles fall back quietly. Non-numbers are sent as `null`, never as invalid JSON `NaN`, and one bad chart cannot stop the others. Tested with every Sutra against empty, mistyped, reshaped and half-missing documents.
- Fix: the *crimson* theme now uses Bootstrap's light mode.
- **Realistic finance samples.** Every trade is now fully booked: an execution block (venue and MIC, timestamp, trader, sales), a lifecycle and audit history, confirmation, clearing, regulatory reporting (UTI, UPI, regimes), settlement instructions (BICs, SSIs), valuation (model, curves, leg PVs) and 20 days of P&L. Swaps carry every calculation period with fixing dates, year fractions, fixings or projected forwards, DFs and PVs, and the thin swaps' fixed rates are solved so their cashflows reprice to their MTM. Counterparties have LEIs, ratings, KYC and regulatory classification. Curves publish pillars, and SOFR has 20 days of fixings. Agreements, CSAs, limits, books and contract specs are complete. The swap with no Sutra is now an FpML-style document from another booking system. Generated by `packs/finance/tools/make_fixtures.py` on the new `tools/samplegen` toolkit (identifiers with check digits, calendars, schedules, day counts, curves, legs), which has its own tests.
- Inference: when rows have more fields than a table shows, it keeps the first column and the columns whose semantic role weighs most, instead of the first nine.
- Long values in field lists wrap instead of overflowing.
- **Risk pack, R2:** `packs/risk/tools/make_sutras.py` writes 170 Markdown Sutras from the taxonomy: one per product (terms, legs, schedule, market-data chart, risk by bucket, P&L, links) and one per market-data, risk and reference kind. Each explains its fields, market data and risk measures, so the Sutras are also the data dictionary. `trade_shape.py` fixes the trade-document contract shared with the data generator. New `PackSutrasTest` loads every pack's Sutras with zero problems, and `tools/drill.sh` refuses to merge if generated pack files are out of date.
- **Sutras are Markdown documents** (ADR-011). The standard file is `<name>.v<N>.sutra.md`: prose for people and AI assistants, with the layout in one fenced `sutra` block. Problems keep the Markdown file's line numbers, and a missing, duplicated or unclosed block is `DRS-2004`. Plain `*.yaml` Sutras still load. The finance and logistics Sutras are converted, and `tools/sutra_to_md.py` converts others.
- **Studio is a Markdown editor:** a toolbar with shortcuts, Markdown highlighting with Rachana inside the block, **Insert…** snippets (a `sutra` block, a strip field, all twelve panel kinds), a **Jump to…** outline, soft wrap and a rendered **Document** tab (rendered without raw HTML and with only safe links). Inference and Save produce Markdown Sutras.
- **Themes:** *crimson* and *crimson dark*, Maya's Harvard crimson and indigo palette, bring the total to seven themes. Contrast is checked like the others.
- Fix: a `gauge` panel whose value or maximum is not a number no longer breaks the page.
- **Studio, sample JSON:** a *Sample JSON* tab shows the entity's document (**Load entity JSON**). You can paste any JSON object and tick **Preview against this JSON** to render it with the unsaved Sutra, with no source needed. **Start from inference** works on pasted JSON. Server: `POST /api/v1/studio/preview` accepts `document`; `POST /api/v1/studio/inferred` infers from a document.
- **Risk pack, R1:** the taxonomy, a single machine-readable source (`packs/risk/tools/`) of 125 products in ten asset classes, 21 market-data kinds and 24 risk and reference kinds. Uniqueness of codes, kinds, mnemonics, ID prefixes and reference fields is checked (`python3 packs/risk/tools/taxonomy.py`).

## 1.8.0 — Wave 13c: packs per user (2026-09-30)
- **Admins assign packs to users** (user dialog → *Packs*). Users with several packs choose which to see from a **pack switcher** in the top bar, and the choice is saved to their account (`GET/PUT /api/v1/me/packs`).
- Each pack declares the `kinds` it owns, and a kind belongs to exactly one installed pack.
- **Enforcement is on the server:** kinds of packs that are not active for the user cannot be opened, and their mnemonics, suggestions, examples, starters, guides and alert suggestions disappear with them.
- `drishti.packs.default-for-users` sets what new users get.
- The plan records the pack roadmap: risk (R1–R5), then liquidity, climate, operational and non-financial risk, retail banking, genomics and biology, politics and society, and economics.

## 1.7.0 — Wave 15: F8 Impact (2026-09-30)
- **F8 Impact** (`/impact/{kind}/{id}`, `GET /api/v1/impact/{kind}/{id}`) answers "what depends on this?":
  - level 1: every entity that references it (reverse lookups across all known kinds, in parallel);
  - level 2: what those roll into, following fields each pack configures (finance: `nettingSet`, `creditLimit`; logistics: `vessel`, `destination`);
  - each group shows its count and the summed measure at stake (trade MTM, netting-set net MTM, shipment declared value);
  - kinds the caller may not open are counted, never shown.
  A SOFR move reaches its trades and their netting sets in about 1–6 ms.
- The demo source's reverse lookup also matches arrays of identifiers (for example `discountCurves`).
- Docs: an *F8 · Impact* guide in help; user guide, API guide, architecture and packs guide updated; README no longer lists F8 as unbuilt.

## 1.6.0 — Wave 14: monitors and alerts (2026-09-30)
- **Monitors** (`/m`): live watchlists of up to 50 entities of any kinds, each row showing its own strip. One multiplexed SSE stream per page (`/api/v1/me/monitors/{name}/stream`, a `row` event per changed entity). Starters come from packs (finance: *Credit watch*; logistics: *Fleet watch*). Saved per user.
- **Alerts** (`/alerts`):
  - per-user rules in Rachana-EL (`$.utilisation > 0.8`), compiled on save;
  - evaluated by the server on every tick of the watched entity, with no browser needed;
  - edge-triggered (fire on false → true, re-arm on false), so a rule already true when saved fires at once;
  - severities `info`/`warn`/`critical` and message templates.
  Packs suggest rules per kind. A bell with a count and toasts appear on every signed-in page, with optional browser notifications, and each view has an **Alert** shortcut.
- The most recent 200 alerts per user are kept in memory (a restart clears the history; rules persist).
- Docs: a *Monitors and alerts* guide in help; API guide, architecture, live, user guide and packs guide updated.

## 1.5.0 — Wave 13b: domain packs (2026-09-30)
- **Domain packs.** The core is now industry-neutral, and everything domain-specific lives in `packs/<name>/`:
  - Sutras, mnemonics, identifier patterns, reference fields, link badges, roles;
  - semantic hints and formats;
  - starter workspaces, help guides and sample data.
  Enable packs with `DRISHTI_PACKS=finance,logistics`. They load in order at the lowest precedence, so site configuration wins, and a clash between packs stops start-up with both names.
- New module `drishti-packs` (an `EnvironmentPostProcessor`; no core module depends on it). The server gains `GET /api/v1/packs`; About lists the active packs.
- **Finance pack:** everything that was finance-specific moved out of the core: the four Sutras, 13 mnemonics, reference patterns and fields, badges, `trader`/`risk` roles, finance semantic hints (MTM, DV01, notional, pips…), finance formats (`pct4`, `rate5`, `pips1`, `df4`, `bp1`), the mockup samples, starters and tutorials.
- **Logistics pack** (new), which proves the core is neutral:
  - shipments, reefer containers, vessels and ports;
  - a shipment Sutra (route, milestones, reefer temperature from the linked container);
  - logistics vocabulary (weights, temperatures, delays, knots, TEU) and formats (`temp1`, `hours0`, `knots1`);
  - an `ops` role, live samples, and a *Shipment tracker* workspace.
- The demo source reads pack sample directories and can tick any pack's samples through `_meta.walk`; no code is needed.
- Core semantic hints and formats are now neutral; the console's placeholders and examples are pack-driven.
- Docs: `PACKS.md`, ADR-010; architecture §8a; configuration, plugin guide, user guide, Rachana reference and deploy files updated.

## 1.4.0 — Wave 13: workspaces (2026-09-30)
- **Workspaces** (`/w`): 2–4 live views on one screen, in layouts of two or three columns, two by two, or one large with two stacked.
  - Each pane is a same-origin embedded view with its own live stream and keys.
  - A pane can **follow** another: a link clicked in the followed pane opens there (postMessage, origin-checked). `Alt+1…4` moves between panes.
  - Starters (*Credit desk*, *Rates*, *Cross-asset*) are config (`console/config/workspaces.yaml`).
- **Saved per user** on the server: `GET/PUT/DELETE /api/v1/me/workspaces[/{name}]`, validated (known layout, 1–4 panes, entitled entities, no follow cycles). They are stored in a new per-user `PreferenceStore` (atomic JSON, 64 KB per document, 50 per namespace), which is removed with the user.
- Views have an embed mode (`?embed=1`: no chrome, no breadcrumbs). The CSP allows same-origin frames only (`frame-ancestors 'self'`, `X-Frame-Options: SAMEORIGIN`).
- Docs: a Workspaces guide in help; updated user guide, API guide and architecture.

## 1.3.0 — Wave 12b: competitive landscape and mobile (2026-09-30)
- **Competitive landscape** (`/about/competitive`), in Maya's form. It compares categories, not vendors (market data terminals, trading and risk platform screens, low-code tools, BI), with a Yes/Partial/No matrix over twelve capabilities and, for each, the problem and how Drishti does it. It says plainly where Drishti is weaker (no market data content or analytics, no ecosystem or support). The rows are config (`console/config/competitive.yaml`).
- **Mobile.** Every page works on iPhone (~390 px) and Android (~412 px): stacked layouts; a swipeable F-key bar of tap targets; 16 px inputs (no iOS zoom on focus); safe-area insets; tables that scroll inside their panel; Studio hidden on phones. A web-app manifest and touch icon allow *Add to Home Screen*, which opens at the terminal. Checked with CDP mobile emulation: no page scrolls sideways at 390 px.

## 1.2.0 — Wave 12: help centre and About (2026-09-30)
- **Help centre** (`/help`), in Maya's help language:
  - a searchable catalogue of 25 guides in six categories;
  - three tutorials (your first view, your first Sutra, Sutra Studio) and a panel-kind guide;
  - captioned, copyable examples; tip/warning/note boxes; a table of contents; full-text search.
- The help centre renders the **repository's own docs** (Rachana reference, inference, API, configuration, operations, runbooks, ADRs, plan, release notes, changelog, notices), and links between them open in-app. There is one source of truth, so the help cannot rot separately from the docs.
- **Contextual help:** `F1` opens help for the current screen; every panel header has a **?** linking to its kind.
- **About** (`/about`): server and console versions, build time, Java, uptime, security mode, loaded Sutras, source health, licence and notices. The server gains `GET /api/v1/about` with Spring Boot build info.
- The console image ships the docs. A sample feed file (`data/feeds/fixing/SOFR-HISTORY.csv`) means the `file` source starts UP (`FIX SOFR-HISTORY <GO>`).
- **Doc rot fixed:** stale "arrives in Wave N" promises removed from the API guide, plugin guide, Rachana reference and configuration.

## 1.1.0 — Wave 11: user management (2026-09-30)
- New module `drishti-identity`:
  - users with profiles, roles and enabled flags;
  - PBKDF2-HMAC-SHA256 passwords with a policy; lockout after 5 failures;
  - atomic, owner-only JSON storage; an append-only audit log;
  - a guard so there is always an enabled admin.
- A development admin **`drishti-dev-admin` / `drishti-dev-admin123`** (role `admin`) is seeded on an empty store. The console warns until its password is changed; `DRISHTI_SEED_ADMIN=false` turns seeding off.
- Forced password changes are **off unless configured** (`force-password-change-on-create`, `force-password-change-on-reset`).
- Server: `/api/v1/auth/login` (console service token only), `/auth/me`, `/auth/password`, and `/api/v1/admin/**` (users CRUD, enable/disable, reset, audit, roles, status); an `admin` role.
- Console: sign-in through the server; `/account` (profile, change password); `/admin/users` (search, create, edit, enable/disable, reset, delete); `/admin/audit`. The users file and hashing tool are retired (ADR-009).
- Docs: `USER_MANAGEMENT.md`, ADR-009; updated operations, runbook, configuration.

## 1.0.0 — Wave 10: Studio, security, operations, release (2026-09-30)
- **Sutra Studio** (`/studio`):
  - a Rachana editor (vendored CodeMirror with a Drishti YAML mode);
  - Ctrl+Enter live preview of unsaved Sutras against any entity (about 45 ms), with problems listed by line;
  - "start from inference" (`SutraWriter` turns an inferred layout into an editable Sutra);
  - saving for authors when `drishti.rachana.studio-save` is on.
- **Security:**
  - console sign-in (PBKDF2 users file, signed expiring cookies, safe `next` redirects);
  - HS256 tokens to the server (algorithm pinned, constant-time signature, expiry);
  - per-role kinds, with denied links shown disabled with the reason;
  - suggestion filtering and raw JSON redaction.
- **Plugins:** `rest` (HTTP/JSON with headers and a generation from ETag) and `jdbc` (per-kind query, pooled connections, JSON column support).
- **Operations:** live Micrometer gauges; Grafana dashboard; Dockerfiles and compose; `OPERATIONS.md`, runbooks, `TROUBLESHOOTING.md`; `RELEASE_NOTES.md`.
- **Version:** 1.0.0.

## Wave 9: live updates
- The `demo` source ticks live entities with consistent random walks while someone watches (MTM, DV01, curves, spot, exposure; futures keep MTM, settlement and VM consistent).
- Engine:
  - `TopicHub`: one source subscription per entity, latest-wins, leading-edge 50 ms frames, single writer.
  - `ViewStream`: rebuild with the cached layout, then diff; rebuilds never overlap.
  - `PatchDiffer`; `LiveMetrics` (HdrHistogram rolling p50/p99).
- Server:
  - SSE `/api/v1/views/{kind}/{id}/stream` (a `view` event, then `frame` events), with a per-client latest-wins `FrameMailbox` that merges frames for slow clients, and heartbeats.
  - `/api/v1/health/live`.
- Console: `/api/stream` relay that renders changed panels with the same Jinja macros; `live.js` patches cells, panels and charts in place, flashes changes and shows `Live, p99 N ms`.
- Measured: p50 2.4 ms, p99 11 ms from tick to frame; 10,000 listeners on one topic all see the latest tick. Verified in real Chrome over the DevTools protocol.
- Docs: `LIVE.md`; `PERFORMANCE.md` updated.

## Wave 8: REST API and console entity views
- Server REST API under `/api/v1`: command, suggest, views, raw entities, sources and Sutras. RFC 7807 errors with `DRS` codes, springdoc at `/api/docs`, and a Micrometer `drishti.view` timer.
- Console terminal:
  - `/t` home;
  - `/go` dispatch;
  - `/v/{kind}/{id}` entity views that reproduce the four mockups: title line, strip, tabs and side-by-side legs, tables with totals, ladders, ECharts curves and exposure areas, bars, linked entities with badges, provenance.
- Bloomberg-style type-ahead dropdown: debounced, stale requests aborted, ARIA combobox. ↑/↓ move, Tab completes, Enter opens, Esc closes, `/` focuses.
- F-keys from the layout. F9 opens the raw JSON drawer, Alt+← goes back, and breadcrumbs follow the views opened in the browser tab.
- The CSP now also forbids inline `style` attributes (bar widths come from `data-w`), enforced by a test.
- Docs: `API_GUIDE.md`, `USER_GUIDE.md` (with the keyboard), `CONFIGURATION.md`.

## naming: Rachana and Sutra (ADR-008)
- **Rachana** (रचना) is the declarative screen grammar. **Sutra** is one layout written in it.
- The module `drishti-sutra` is now `drishti-rachana` (packages `com.ash.drishti.rachana`). The configuration prefix is `drishti.rachana.*`, the expression language is **Rachana-EL**, and the reference is `RACHANA_REFERENCE.md`.
- Sutra files, `Sutra`, `SutraRegistry` and the view label `Sutra irs-vanilla v3 + inference` keep their names.

## Wave 7: view pipeline, entity graph, type-ahead
- `drishti-graph`: config-driven `ReferenceCatalog` (identifier patterns and reference fields) and `BadgeRenderer` (`EE 4.1m`, `threshold 0`, `live`).
- `drishti-engine`:
  - `ViewPipeline` (fetch → match → fingerprint → cached layout → parallel link fetch within 40 ms → parallel panel binding);
  - `Binder` for all twelve panel kinds, with one error per panel rather than per view;
  - the `ViewModel` JSON contract, with pre-formatted, toned cells and document paths for live patches.
- Commands: config-driven `Mnemonics`, `CommandParser` (`TRD IRS-48213 <GO>`, a bare id, any case), `SuggestionService` (mnemonics, per-user recents, parallel plugin search within 30 ms) and `RecentEntities`.
- Golden ViewModel tests for all four mockups: strip, legs, cashflows and totals, curve with mark, link badges, ladder highlight, "10 more trades", exposure limit.
- Latency gate: warm p99 under 50 ms.
- `drishti-benchmarks` (JMH). Docs: `PERFORMANCE.md` with measured numbers.

## Wave 6: inference engine
- `drishti-inference`: config-driven `Semantics` (roles from field names and value classes, memoised), `ColumnInference`, six rules (legs → tabs, term structure → line/area, distribution → hbar, time series → ladder, arrays → table, nested objects → kv), and a packer with density limits.
- `LayoutMerger`: Sutra ⊕ inference, where the Sutra always wins. It produces an `EffectiveLayout` with the provenance label (`Sutra irs-vanilla v3 + inference` / `inference only`) and a per-panel explanation.
- Tests: an unknown product (equity option) renders; each reference entity stays usable without its Sutra; the gaps in the listed-future Sutra are filled.
- README rewritten to show the real state of the project: a status table per wave, what works, how to run it. It is now updated with every wave.
- Docs: `INFERENCE.md`.

## Wave 5: Rachana-EL, formats & matching
- Rachana-EL: lexer, recursive-descent parser (the EBNF is in `RACHANA_REFERENCE.md`), and immutable closure trees with cached compilation. Paths, filters, ternary, arithmetic, twelve pure functions, `link(...)`, and `${...}` templates.
- Every expression reports the document paths it reads, ready for dependency-driven live updates.
- Every expression in a Sutra is compiled at load; errors are reported against the file (`DRS-2101`).
- `Formats` (bundled `formats.yaml` plus a site override; true minus sign, grouping, percent, compact `4.1m`, dates) and `Tones`.
- `SutraMatcher`: highest-priority Sutra whose `where` holds; no match means inference only.
- Golden test: the four reference Sutras reproduce the mockups' header strips exactly. jqwik property tests check the arithmetic and comparisons.

## Wave 4: Sutra grammar
- `drishti-rachana`: immutable model (`Sutra`, `Match`, `Title`, `StripItem`, `Panel`, `Column`, twelve `PanelKind`s with per-kind required and optional options).
- A position-aware YAML reader and a validator that reports **every** problem with its line and column (`DRS-20xx`).
- `SutraRegistry`: `name@version` lookup, per-kind matching by priority, lock-free snapshot reads, `WatchService` hot reload with debounce, last good version kept on error, change listeners.
- `sutra.schema.json` for editors. The four reference Sutras for the mockups.
- Docs: `RACHANA_REFERENCE.md`.

## Wave 3: data model & sources
- `drishti-api`: `DataNode` (immutable tree; navigation never throws), `EntityRef`, `EntityDocument`, `Provenance`, and the `SourcePlugin` SPI with `search` (for the type-ahead) and `reverse`. `HitIndex` provides in-memory search.
- `drishti-common`: `DRS-nnnn` error codes, a streaming `JsonCodec`, and `ShapeFingerprinter` (canonical shape, FNV-1a 64; values and array lengths do not change it).
- `drishti-engine`: `PluginDiscovery` (class path plus isolated plugin jars), `SourceRegistry` (parallel start on virtual threads, failures isolated), and `SourceRouter` (config-driven routes, deadlines, partial `fetchAll`, parallel `search` under a time budget).
- Plugins: `demo` (36 reference entities) and `file` (JSON/CSV feed directory).
- The build is pinned to OpenJDK 21 by the enforcer.
- Docs: `PLUGIN_GUIDE.md`; ARCHITECTURE §7a (command suggestions).

## Wave 2: console shell & landing
- FastAPI + Jinja2 console (`console/`) with a layered config (YAML → local → env → CLI), a strict CSP and security headers.
- Five themes, tokens only in `tokens.css`: terminal (default), parchment, **wallstreet** (Bloomberg Terminal colour scheme), blue, green.
- Landing page: a canvas hero (JSON → `{◉}` → assembled, ticking panels) with replay, reduced motion and pause-when-hidden; stats strip; Sutra example; animated pipeline; capabilities; the four reference views.
- Every front-end asset is vendored (Bootstrap, Bootstrap Icons, ECharts). Tests fail on any external URL, inline script or inline handler.
- WCAG contrast is computed for every theme in tests.

## Wave 1: build foundation
- Maven reactor on `spring-boot-starter-parent` 3.5.16, Java 21, wrapper included.
- Modules: api, common, sutra, inference, graph, engine, two plugins (demo, file), server, testkit, it.
- The `DrishtiApplication` Spring Boot skeleton runs on virtual threads, with actuator and Prometheus.
- Repository gates: licence headers, 1500-line limit, architecture rules (ArchUnit).
- `tools/license_headers.py` to check and insert headers; GitHub Actions `fast.yml`.
- ADR-001 to ADR-007.
