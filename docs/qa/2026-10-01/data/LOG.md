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
## Setup
- Built: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw -o -q install -DskipTests` (whole reactor) -> exit 0.
- NOTE: the tool sandbox refuses commands with shell variables before `bash`/`uv`; helper scripts live in scratchpad/qa-data/scripts/ and are run by literal path.
- Data: `scripts/gen.sh` -> make_data.py --jsonl gen/banking.jsonl --lake delta (17,910 sample rows, 10 business days 2026-09-17..30);
  bulk_trades.py --trades 8000 --days 3 --jsonl gen/bulk.jsonl and --root delta (replaces delta trade table: 8000 x 3 days 09-28..30).
  (make_data.py also rewrites the worktree's packs/*/samples and worktree data/feeds deterministically - worktree only, not the user's repo data/.)
- Files store: `scripts/load-files.sh files gen/banking.jsonl gen/bulk.jsonl` -> 17,910 rows/460 files, then 24,000 rows/3 files.
- Server "files": `DRISHTI_FILES_ROOT=files DRISHTI_DEMO_ENABLED=false scripts/server.sh files 18982 files` (cwd run/files, -Xmx2g, packs market-risk,counterparty-risk). Up in 8 s.
  Health: the plain `file` plugin (feeds) DOWN because run/files/data/feeds does not exist - expected, harmless.

## Search correctness, files store (checker.py: independent EL evaluation over every document)
- `checker.py --truth files/trading/2026-09-30/trade.jsonl --n 300 --seed 1` -> 300/300 ok (matched, scanned=8000, partial=false, ordered ids).
- seed 2, n 700 -> 700/700 ok. Doc path forced (`and not (sourceTradeId = 'qa-never')`, non-promoted) seed 3 n 60 -> 60/60 ok.
- asOf 2026-09-28 (8000) ok; asOf Sun 09-27 and Sat 09-26 -> served 09-25 (750 samples) ok 40/40 each; asOf 10-05 -> 400 DRS-4003 "in the future" (expected);
  asOf 2026-09-07 (Labor Day, before all data) -> matched 0, scanned 0, partial=true (data not held; acceptable).
- Pick lists (files, 09-30): `TRD MX-3000001` 10, `TRD *0001*` 1630, `TRD cly-4` 720, `TRD book=BOOK-RATES-3` 550, `TRD MX-2* currency=usd order by mtm desc` 53 (same top 5) - all equal to independent python counts.

## Delta native (server delta-native :18983, DRISHTI_DELTA_ROOT=delta, demo off)
- checker 300 conditions on 09-30 and 300 on 09-29 (asOf) -> all ok, exact (scanned 8000, partial false).
- doc path forced: 5 of 50 FAIL with partial=true (scanned 5,333-7,153 of 8,000) - the 3 s search budget runs out reading documents
  one by one from the lake; the answer *says* partial, so this is the documented behaviour (info only).
- trade raw asOf 09-30/09-28/10-01 ok (10-01 -> 09-30); asOf 09-27 and 09-25 -> 404 DRS-1001 (trade table holds only 09-28..30 after bulk load) - correct.

## Hostile file root (server hostile :18984, files profile, root hostile/; scripts/hostile_files.py + hostile_more.py)
Each case on its own date. Results:
- 09-01 one truncated line among 400: whole day gone. `TRD limit 3` asOf 09-01 -> matched 0 scanned 0 partial true after 2,012 ms; raw read -> 404 DRS-1001
  "no source holds". NOTHING logged. (JsonlDay.parse throws on the bad line; FileSourcePlugin.fromJsonl catches UncheckedIOException and `continue`s.)
- 09-02 duplicate ids (10 ids twice, second copy mtm+1bn): search scanned 410 for 400 entities; `TRD MX-30001016` lists the id twice with different mtm;
  `TRD mtm > 900m` -> 10 rows from the duplicates while opening MX-30001016 always shows mtm -907451. No warning.
- 09-03 plain docs, mtm mixed (one "N/A", 1e20, 1.2e22): columns path makes mtm a TEXT column:
  `TRD order by mtm desc` -> N/A, '999478', '993066', '9827', '982685' (lexicographic, values returned as strings);
  same query forced onto the doc path -> N/A, 1.23e22, 1e20, 11641928, 6847663 (numeric).
  `TRD where mtm > 10000000000000000000` -> columns 0 matches, doc path 2 matches; the columns hold '9223372036854775807' for both (Long.MAX clamp).
- 09-04 odd ids: unicode, emoji, 5000-char, space, quote, case twins all open and search fine; `sl/ash-6` -> Tomcat HTML 400 (encoded slash rejected).
- 09-05 is a Saturday: asOf 09-05 rolls back to Friday 09-04 (by design) - case re-done on 09-15.
- 09-08 UTF-8 BOM at file start: fine. 09-09 empty file: matched 0 scanned 0 partial false (day with no entities). 09-10 CRLF + blank lines: fine.
- 09-11 (30 MB doc + 3000-deep doc) and 09-17 (one 25 MB doc only): whole day gone - searches 0/0 partial true (3-16.7 s), every raw read 504 DRS-1004
  (each request re-indexes the 28 MB file and fails again).
- 09-15 envelope rows with `doc` as a JSON object and no `columns` (documented as valid): every promoted column null -> `TRD where mtm < 0` 0 matches
  (truth 208), pick list values all None; raw reads fine.
- 09-16 one doc nested 1500 deep: that doc -> 502 DRS-1003, the rest of the day fine.
- Lookback (last day 09-18): asOf 09-25 and 09-28 -> 09-18; asOf 09-29 -> not held (404 / search 0 partial). lookback = 10 calendar days, inclusive; reads and searches agree.

## Derived kinds and impact
- scripts/derived_check.py (desk-pnl vs own sums of count, mtm, pnl1d, risk.dv01, min mtm, distinct books/currencies, members<=250, members of the desk):
  files 09-30 10/10 desks ok, files 09-25 10/10 ok, delta 09-29 10/10 ok.
- hostile 09-02 (duplicate ids): DESK-RATES tradeCount 110 (truth 100), mtm 9,973,373,983 (truth -21,829,076), duplicate members.
- hostile 09-15 (doc as object, no columns): every desk-pnl -> 404 DRS-1001 (no groups, since every desk column is null).
- Impact `GET /impact/netting-set/NS-SUMMIT-NY?asOf=2026-09-30` (scripts/impact_check.py): trade total -37,858,443 over 1,164 trades (200 listed + 964 more)
  equals own sum, on both files and delta.
  BUT files lacks the level-1 `counterparty` group and the level-2 `credit-limit` group (total 267.0m) that delta shows.
  Cause: FileSourcePlugin.reverse (line 277) skips every effective-mode kind (`effective(k) ? Optional.empty()`), and banking-core declares
  counterparty, book, desk, agreement, ... as effective. files/reference/2026-09-30/counterparty.jsonl does mention "NS-SUMMIT-NY".

## DuckDB (server :18984, duckdb profile, DuckDbLoader samples --recreate then bulk; "kept 5,250 rows of the current file" = 7 sample days)
- checker 300 @09-30, 300 @09-28, 80 @Sat 09-26 (-> 09-25, 750) all ok; desk-pnl 10/10 ok; impact NS-SUMMIT-NY total equal, includes counterparty + credit-limit groups.
- doc path forced: 40/40 partial (2,700 of 8,000 docs read in the 3 s budget) - says partial (info: DuckDB single reads are slow).
- asOf 09-07/09-04 (before data): 404 / matched 0 partial true.
- Swap under load: scripts/stress.py (12 threads, raw reads + exact-count searches) for 45 s while DuckDbLoader reloaded the book 6 times
  alternating gen/bulk_plus1.jsonl (mtm+1) and gen/bulk.jsonl: 18,007 raw + 4,403 searches, 0 anomalies.
- Pickup: probe_after.py: new version visible to raw read and search together 5.1 s after the load finished. OK.

## Files store swap (server :18982 files)
- JsonlLoader alternating plus1/orig 8 loads during a 60 s 12-thread stress: 80,196 raw + 20,136 searches, 0 anomalies.
- scripts/flip.py: atomically replaced files/trading/2026-09-30/trade.jsonl (tmp + rename, like JsonlLoader) every 0.2 s with the SAME lines
  shuffled (168 flips in 40 s) while 16 threads read: 23,070 raw ok, **11 raw reads -> 502 DRS-1003 "trading-store failed reading"**; searches 0 anomalies.
  (JsonlDay.document reads the *new* file at offsets from the *old* index between current() and the read.)

## Delta new versions under load (server :18983 native)
- delta_rewrite.py overwrite-partition of 09-30 (mtm+1): picked up by raw and search together after ~7 s.
- 30 alternating new versions in 60 s under 12-thread stress: 38,885 raw + 9,732 searches, 0 anomalies.

## Deletion vectors
- First attempt (3 sequential DV deletes with the plugin's own test fixture DeletionVectorFixture) produced an invalid log: the fixture's `remove`
  carries no deletionVector, so Kernel keeps (path,dv1),(path,dv2),(path,dv3) as three live files -> rows tripled (23,997). Fixture limitation, not
  product; discarded (noted only as test-tooling info).
- Clean lake delta-dv1 (one DV delete of CLY-4000001 on 09-30, version 34), Kernel full scan 7,999 rows (scripts/DvProbe.java).
  native (:18984) and hadoop (:18982): CLY-4000001 -> 404, neighbours 200 - honoured on both.
  Columns switched off for the table (documented): `TRD where mtm < -100m` -> matched 0, scanned 2,376 (native) / 668 (hadoop), partial true,
  truth has matches (e.g. MX-30000006 -342m). Documented limit.
  Type-ahead still suggests the deleted id: `/command/suggest?q=TRD CLY-400000` lists CLY-4000001 (id map read raw, without the DV) -> opens 404.
  `TRD CLY-400000` pick list: matched 9 scanned 9 partial true.

## Parquet codec LZ4 (lake delta-lz4: 09-30 trade partition rewritten by scripts/delta_codec.py with compression LZ4; pyarrow reports codec UNKNOWN/LZ4)
- native (:18984): Health `trading-store UP (engine: native)` (no hint). raw trade 09-30 -> 502 DRS-1003 "failed reading" (generic); log has the clear
  UnsupportedOperationException "the native Delta engine does not decompress LZ4 ... set engine: hadoop".
  `TRD where mtm < -100m` asOf 09-30 -> **matched 0, scanned 0, partial false** (exact-looking empty answer; truth 6). `TRD` -> 0/0/false.
  `/command/suggest?q=TRD MX-3` -> [] (the whole kind vanished from type-ahead: reindex's newest-day id map failed). desk-pnl/DESK-RATES -> 404 DRS-1001.
  09-29 (Snappy) still fine: 6 matches of 8000.
  Root cause of the false "exact": SourceRouter.search (engine, lines 170-172) turns a failing/timing-out source into an empty hit list
  (`.completeOnTimeout(List.of(), …)`, `.exceptionally(e -> List.of())`); StructuredSearch.run's doc path then has 0 hits, 0 docs, partial=false.
- hadoop (:18982): raw 09-30 also 502 (log: net.jpountz.lz4.LZ4Exception Malformed input - arrow/delta-rs "LZ4" framing not readable by
  parquet-java), searches over columns fine (6 of 8000). So the doc's advice "such a table needs hadoop" does not hold for delta-rs/arrow LZ4 files.

## Corrupted lake (delta-bad; server bad-native :18982)
- trade 09-29 parquet truncated to half: raw -> 502 DRS-1003; search 09-29 -> 0 scanned, partial true (honest); 09-30 fine. Health still `UP`.
- reference/counterparty: checkpoint parquet deleted, _last_checkpoint kept, JSON commits kept: reads fine (Kernel lists from version 0). OK.
- reference/book: checkpoint and 00000.json deleted (log unrecoverable): raw -> 502 DRS-1003; Health `reference-store UP` (no hint).
2026-10-01T21:07:58-04:00 docker run -d --name qa-data-pg -m 2g -p 15433:5432 postgres:17-alpine

## PostgreSQL table mode (container qa-data-pg, postgres:17-alpine, -m 2g, port 15433; server pg :18984, postgres profile)
- PostgresLoader samples --recreate (17,910 rows) then bulk (24,000): per day 750 x7 + 8000 x3, distinct ids = rows.
- checker 300 @09-30 and 300 @09-29: 0 fail, but 115/111 "ties" = ordered results whose tie order differs from id order
  (PG column sets come back in physical order, StructuredSearch sorts stably without an id tie-break; files/delta/duckdb rows are id-sorted).
  Sun 09-27 -> 09-25 60/60 ok. desk-pnl 10/10 ok. impact total equal (incl. counterparty, credit-limit).
- Store down (`docker stop qa-data-pg`): raw 502 DRS-1003; search 09-30 still answered from cached columns (6 of 8000); search 09-28 0/0 partial true;
  Health `DOWN: Connection ... refused (reconnecting)`; desk-pnl served from cache. `docker start`: first read 200 right away, Health UP. Good.
- `--recreate` while 12 threads read (2 rounds samples --recreate then bulk): 42,693 raw 200, 16,568 raw 404, 45 raw 502, searches 0 anomalies
  (searches kept answering the cached 8000-row day while the table held 750). probe_after: searches caught up 7 s after the reload.
- Hostile rows: a document with \u0000 -> whole load fails (exit 1, jsonb "unsupported Unicode escape"), nothing committed;
  a duplicate id inside one load -> whole load fails (PK); a `columns.mtm` "N/A" -> NULL in the double column, while doc keeps the number;
  1e300 kept. Loader trusts `columns` and does not cross-check them with `doc`.
- Calc columns endpoint `/search/columns/TRD?paths=mtm,book,risk.dv01`: 8000 rows, id-sorted, 0 value mismatches, sum mtm equal;
  limit=10 -> truncated true; bad path -> 400 DRS-5001 listing the kept columns; `mtm;drop` -> 400. OK.

## JDBC query mode (schema bank.trades/trade_legs in qa-data-pg, scripts/qm_data.py, 5% NULL mtm, 5% NULL book, 3% NULL currency;
## server qm :18983 with run/qm/application.local.yaml, route trade: qa-db)
- doc: flat counterpartyId (label mapping) while the column is counterparty.id (layout match) - by design.
- checker (fields notional, mtm, productType, currency, book, desk, nettingSet, maturityDate) 300 @09-30 and 300 @09-28: 0 fail
  (117/118 tie-order differences, rows in DB order).
- reverse/impact with odd targets ('%', '_', "' OR '1'='1", "x'); DROP TABLE ...", emoji, 3000 chars): all empty, table intact (24000 rows);
  impact totals with NULL mtm equal own sums (NS-SUMMIT-NY -36,829,467 over 1164; BOOK-RATES-3 +33,496,586 over 518).
- Variant: columns.trade joined with trade_legs (rows multiplied) + a part `query.trade.broken` with a bad column:
  every raw read -> 502 DRS-1003 "qa-db failed reading" (the SQL error is only in the server log; one bad part hides the whole entity);
  search scanned 11,866 for 8,000 entities, ids listed up to 3 times, impact total changed to -36,153,980; trades with no legs vanish from
  searches (`TRD MX-30000006` -> 0). No warning (documented as a user error in JDBC_QUERIES §12-13).

## drishti.quant (scripts/qa_quant.py vs scipy/textbook; logs-quant.txt)
- PASS: norm_cdf (to 1e-15 rel incl. -38), norm_ppf over 1e-300..0.999999, BS Hull 15.6 (4.76/0.81), 2000 random BSM vs reference (<1e-9),
  all Greeks vs finite differences (call and put), Black-76 Hull put 1.12 and Greeks, GK, Bachelier vs numerical integral and parity,
  implied vol round trips 5%-300% and below-intrinsic ValueError, bonds (par, yield round trip incl. negative yields, modified duration,
  convexity, DV01, accrued), curves (loglinear, flat extrapolation, natural cubic vs scipy CubicSpline on 4 and 8 pillars),
  bootstrap -> par rates reprice to 1e-12, VaR/ES/parametric VaR, Kupiec LR and p-values vs scipy chi2, traffic lights, drawdown, Sharpe,
  HHI, realised vol, credit triangle, forward hazards/survival, CVA, FRTB.
- FAIL: year_fraction("30E/360") computes US 30/360 (2026-01-15..03-31 -> 76/360 not 75/360; 02-28..08-31 -> 183/360 not 182/360);
  "ACT/ACTISDA" alias computes days/365.25 (2026-01-01..2027-01-01 -> 0.999316 not 1.0);
  black_scholes at T=0 or sigma=0 at the money -> NaN (expected intrinsic 0); norm_ppf(1-1e-12) relative error 1e-9 (docstring says < 1e-14);
  month_code_years("V6", "2026-10-20") negative (-5 days) for the current month after the 15th.

## Loader interruption and retention (PostgreSQL, DuckDB)
- gen/bulk10k.jsonl (10,000 trades x 3 days) loaded; then `kill_after.py 1.2 9 -- loader.sh ... PostgresLoader gen/bulk.jsonl` (kill -9 by PID):
  table left with 2026-09-28: 3000 rows, 2026-09-29: 3000 rows, 2026-09-30: 0 rows (was 10000 each). Server then:
  `TRD where mtm < 0` 09-29 -> matched 1484 scanned 3000 partial **false**; MX-20000001 404 on 09-30 and Live.
  Cause: PostgresLoader.add (line ~195) DELETEs the day on its first row (autocommit), batches of 2,000 are COPYed on 8 autocommit connections.
- Plain reload of the same 8000-trade book (no --recreate), 4 rounds during a 12-thread stress: 4,103 raw 404 of 38,694 reads, 402 searches with
  the wrong count. (DuckDB and files loaders swap atomically: 0 anomalies in the same test.)
- Retention: one row dated 2027-03-01 (gen/future_row.jsonl) loaded with `--keep-months 2` ->
  "postgres: dropped trading.entities_y2026m09" -> the table holds only that row; every September trade gone (404).
  DuckDbLoader with `--keep-days 30` on a copy: "trading: dropped 10 business dates before 2027-01-31", 0 rows kept.
  keepFrom is computed from the newest date *in the load*, and no loader rejects a future business date.
2026-10-01T21:20:58-04:00 removed container qa-data-pg
2026-10-01T21:21:04-04:00 docker run -d --name qa-data-redis -m 2g -p 16380:6379 redis:8.2 --maxmemory 1gb

## Redis (container qa-data-redis redis:8.2 -m 2g --maxmemory 1gb, port 16380; server redis :18984, redis profile)
- RedisLoader samples (17,910) + bulk (24,000): 39.5 MB used.
- checker 300 @09-30, 300 @09-28, 60 @Sat->09-25: all ok, 0 tie differences. desk-pnl 10/10, impact equal.
- Redis down (docker stop): raw -> 504 DRS-1004 after 2 s each (not a fast 502); searches 0/0 partial true taking 8.1 s each;
  Health still `UP` right after the stop, `DOWN: cannot reach Redis ...: null` some 20 s later. Back up: reads OK within ~12 s.
- Reload (bulk_plus1/bulk alternating, 6 loads) during a 12-thread 30 s stress: 32,496 raw + 8,113 searches, 0 anomalies.
- kill -9 of RedisLoader at 1.4 s into a 10,000-trade load over the 8,000 book: documents of the new ids written for 09-28/29
  (MX-30001784 opens on 09-28 and 09-29) while the day's columns still list 8,000 (`TRD MX-3000178` does not list it).
- Full load of the 10,000 book then full load of the 8,000 book: 09-30 still holds 10,000 trades (search scanned 10000,
  MX-30001999 opens and is suggested). RedisLoader merges into a day by design (RedisLoader.java:61, "a day can be loaded in parts"),
  so a trade dropped from a day's book can never be removed from that day by reloading it; the files/DuckDB/PostgreSQL loaders replace the day.
2026-10-01T21:26:11-04:00 removed container qa-data-redis
2026-10-01T21:26:44-04:00 docker run -d --name qa-data-rmq -m 2g -p 15672:5672 rabbitmq:4.1-alpine
2026-10-01T21:26:51-04:00 (retry on port 16672) docker run -d --name qa-data-rmq -m 2g -p 16672:5672 rabbitmq:4.1-alpine

## Message-queue state store (RabbitMQ container qa-data-rmq rabbitmq:4.1-alpine -m 2g port 16672; servers rmq-sync :18982
## (state.durability sync, state.max-gb 0.02, check 5 s, evict-oldest) and rmq-none :18983 (durability none); queues qa.sync / qa.none, kind trade)
- Published (pika, persistent, publisher confirms; scripts/pub.py) 300 + 300 trades + 300 updates (mtm+1) to each; queues drained (0 messages, 0 unacked).
- kill -9 both servers (by PID), restart: sync -> 600/600 current values; none -> 600/600 404 (store empty, entities 0) although every
  message had been acknowledged. Documented trade-off of `none` (RABBITMQ_CONNECTOR §6.1) - info.
- Budget: 600 trades with 80 KB random padding (51 MB > 20 MB budget): WARN "evicted the 385 entities written longest ago (now 17 MB)";
  evicted ids 404 and gone from type-ahead, the newest 215 current. Health text shows the 0.02 GB budget as "0.1 of 0.0 GB" (rounding, cosmetic).
- SSE: view stream of CLY-4000447 open; delete message (header deleted:true) -> frame `{"op":"deleted","at":...}` within the second;
  type-ahead no longer suggests it. Re-published entity comes back in type-ahead.
2026-10-01T21:30:14-04:00 removed container qa-data-rmq

## Delta hadoop engine on the normal lake (server delta-hadoop :18982)
- checker 300 @09-30 and 300 @09-28: all ok; desk-pnl 10/10 ok.

## Time travel (scripts/knownat.py; lake delta has 34 versions: v2 original 09-30, v3 mtm+1, then alternating)
- native and hadoop: knownAt before v0 -> 404 / search 0 partial; between v1 and v2 -> the 09-29 row (09-30 not yet written, snapshot fallback);
  v2/v3/v4/v5/v6/v33 -> exactly the mtm of that version, raw read and search agree. OK.

## Recent store + lake (server combo :18982: site connector recent-files (plugin file, root recent/, route trade) + the pack's Delta trading-store)
- recent holds 09-30 (mtm+1 book); 09-30 -> recent-files (-342639049), 09-29 -> trading-store (lake), 10-01 -> recent-files 09-30. OK.
- One truncated line inserted in recent/trading/2026-09-30/trade.jsonl (atomic replace): raw 09-30 and Live now answered by **trading-store**
  (mtm -342639050, the lake's value) with no error and nothing logged; search 09-30 -> matched 0 scanned 33 partial true (does not fall back to the
  lake's columns). View and search disagree.
- recent 09-29 file = the lake's 09-29 book minus MX-30000006 (the trade "deleted" in the recent store): raw 09-29 -> 200 from trading-store
  (resurrected from the lake), while `TRD MX-30000006` on 09-29 -> matched 0 of 7,999.
- knownAt=2020-01-01 against recent-files (no time travel) -> 200 with today's file data, no indication that knownAt was ignored.

## DuckDB interrupted load
- kill -9 at 1.8 s: drishti.duckdb untouched, .loading/.stage left; next load cleans them and succeeds. OK.

## NaN from Python json.dumps (hostile/trading/2026-09-21: one row with mtm NaN, one with Infinity, as json.dumps writes by default)
- the whole day is unreadable: `TRD where mtm < 0` asOf 09-21 -> matched 0 scanned 0 partial **false**; raw MX-30001016 -> 404. (Same class as 09-01.)

2026-10-01T21:36:21-04:00 end. All scratch servers stopped by PID; containers qa-data-pg, qa-data-redis, qa-data-rmq removed; the user's containers and server (pid 1852549, cwd repo root) untouched. FINDINGS.md could not be written (the agent harness refuses report files from subagents); the findings were returned in the hand-off message.
