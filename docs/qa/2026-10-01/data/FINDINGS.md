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
# Data, connectors and robustness QA findings — Drishti 1.13.0 (worktree at 778bac1, 2026-10-01)

Scratch servers -Xmx2g on :18982–18984; containers one at a time with -m 2g and removed (qa-data-pg postgres:17-alpine,
qa-data-redis redis:8.2, qa-data-rmq rabbitmq:4.1). Data: the banking samples (17,910 rows, 10 business days), an
8,000-trade book for 09-28..30, a 10,000-trade book for interruption tests. `scripts/checker.py` is an independent
brute-force evaluator over every document, compared with the server's search API. Activity: LOG.md; scripts in
scripts/ (checker, derived_check, impact_check, stress, flip, probe_after, kill_after, hostile_files, hostile_more,
delta_rewrite, delta_codec, dv.sh + DvDelete/DvProbe, qm_data, pub, mqcheck, sse, knownat, qa_quant); quant output
logs-quant.txt. Totals: 6 high, 9 medium, 7 low, plus notes.

## High
| Id | Finding | Where | Repro |
|---|---|---|---|
| DATA-01 | A source that fails or times out while listing makes a search look exact and empty: the router turns failures and timeouts into empty hit lists; StructuredSearch counts only scan caps and fetch losses as partial; a failed reindex drops the kind from type-ahead. LZ4 table on the native engine: `TRD where mtm < -100m` asOf 09-30 → matched 0, scanned 0, partial false (truth 6); suggest `TRD MX-3` → []; desk-pnl/DESK-RATES → 404. File connector with one `NaN` on the newest day → `mtm < 0` 0/0 partial false. Expected partial true or an error naming the source. Fix: report failed/timed-out sources in partial; keep the previous type-ahead index on a failed reindex. | SourceRouter.java:170-172; StructuredSearch.java:115,122; DeltaSourcePlugin.java:382 | scripts/delta_codec.py … LZ4 **Fixed** in c00488a, 724328b (SearchFailuresTest, DeltaUnreadableTest.aFailedReindexKeepsThePreviousIdsAndHealthRecoversWhenTheTableReadsAgain). |
| DATA-02 | One unreadable line erases a whole JSON-lines day: JsonlDay.index throws on the first bad line (default JsonFactory: 20 MB string and 1,000-deep nesting limits); FileSourcePlugin treats it as "went away". 09-01 one truncated line among 400 → search 0/0 partial, every raw read 404 DRS-1001; 09-17 one 25 MB document → every raw read 504, searches 3–16.7 s (re-indexed and failing on each request); 09-21 one NaN → 0/0 partial false. Nothing logged. Fix: skip and count bad lines, log them, raise or avoid the limits, cache a failed index until the file changes. | JsonlDay.java:51; FileSourcePlugin.java:229 | scripts/hostile_files.py, hostile_more.py **Fixed** in 5ecce29 (JsonlHostileLinesTest: oneTruncatedLineIsSkippedAndCountedNotTheWholeDay, nanAndInfinityAsPythonWritesThemAreReadAsNoValue, aDocumentBeyondJacksonsDefaultStringLimitIsReadAndOneBeyondMaxDocumentMbIsSkipped, anUnreadableFileIsIndexedOnceUntilItChanges). |
| DATA-03 | A failing store counts as "not held", so the next store silently answers with different data: site connector recent-files (file plugin) in front of the lake; one truncated line in recent/trading/2026-09-30/trade.jsonl → the 09-30 view and Live come from trading-store (mtm -342639050 vs -342639049), no error, no log; search for the same date matched 0, scanned 33, partial true: view and search disagree. Contradicts CONNECTOR_GUIDE §1 (a failing connector stops the read with DRS-1003). | router fall-through | combo server **Fixed** in 8514970, c00488a (FileReadFailureTest, JdbcNotReachedTest, MongoNotReachedTest, DuckDbSourcePluginTest.aMissingFileIsDownUntilALoadWritesIt, SourceRouterTest.aFailingStoreStopsTheReadNamingItAndTheNextStoreIsNotAsked). |
| DATA-04 | PostgresLoader is not atomic: deletes the day on its first row (autocommit), then COPYs 2,000-row batches on 8 autocommit connections. kill -9 at 1.2 s loading the 8k book over the 10k book → 09-28 3,000 rows, 09-29 3,000, 09-30 0; the server answers `mtm < 0` on 09-29 with 1484 of 3,000, partial false; MX-20000001 → 404. Plain reload under 12 readers → 4,103 of 38,694 reads 404 and 402 searches with wrong counts; with --recreate also 502s. DuckDB and files loaders: 0 anomalies. Fix: stage each day and swap it in one transaction (or attach/detach partitions). | PostgresLoader.java:194-203 | scripts/kill_after.py, stress.py  **Fixed** in 1420ab6 (PostgresLoaderReplaceTest; also 2cd7aa7 Redis, 6de321d MongoDB) |
| DATA-05 | One mis-dated row with retention wipes all history: the cut-off comes from the newest date in the load and no loader rejects future business dates. One row dated 2027-03-01 → PostgreSQL --keep-months 2 "dropped trading.entities_y2026m09" (all September); DuckDB --keep-days 30 "dropped 10 business dates", 0 rows kept. Irreversible from one upstream date typo. | PostgresLoader.finish 299-308; DuckDbLoader.java:338 | one future-dated row  **Fixed** in 1420ab6, 9b2099d, 2cd7aa7 (LoadGuardTest; per-loader future-date and retention tests) |
| DATA-06 | Rows whose `doc` is a JSON object and carry no `columns` get every promoted column null (only string `doc` is parsed for promotion; FILE_CONNECTOR §3 documents the object form). Hostile 09-15: `mtm < 0` → 0 of 400 partial false (truth 208); pick-list values None; every desk-pnl 404; raw reads fine. | JsonlDay.java:358-363,380-382 | hostile day 09-15 **Fixed** in 5ecce29 (JsonlHostileLinesTest.docAsAnObjectWithoutColumnsIsPromotedLikeDocAsAString). |

## Medium
| Id | Finding |
|---|---|
| DATA-07 | A promoted field with mixed types makes the columns path disagree with documents (hostile 09-03: mtm "N/A", 1e20, 1.2e22): `order by mtm desc` via columns is lexicographic text (N/A, '999478', '993066', …) vs numeric on documents (N/A, 1.2e22, 1e20, 11641928); `mtm > 1e19` 0 vs 2; the text column holds '9223372036854775807' for both (longValue() clamp). JsonlDay.sorted, line 471. **Fixed** in 494741c (JsonlMixedColumnsTest.aFieldWithNumbersAndTextKeepsEachValueAsTheDocumentHoldsIt, SearchOrderTest.aFieldWithNumbersAndTextOrdersAndComparesTheSameFromColumnsAndDocuments) |
| DATA-08 | File connector reverse lookups skip every effective-mode kind (FileSourcePlugin.java:277): impact of NS-SUMMIT-NY on the files profile lacks the counterparty group and the level-2 credit-limit group (267.0m); Delta, DuckDB, PostgreSQL, Redis show both. **Fixed** in 494741c (FileEffectiveReverseTest.anEffectiveKindIsLookedUpInEachEntitysVersionOnTheDate) |
| DATA-09 | Duplicate ids in a JSON-lines day are counted and listed twice (hostile 09-02): scans 410 for 400 entities, lists an id twice with different mtm, the view shows an arbitrary copy; DESK-RATES tradeCount 110 (truth 100), mtm 9.97bn (truth -21.8m). **Fixed** in 5ecce29 (JsonlHostileLinesTest.aDuplicateIdKeepsItsLastLineEverywhere). |
| DATA-10 | A read racing an atomic JSON-lines replace returns 502 (JsonlDay.document line 102 reopens the path with the old index's offsets): 11 of ~23k reads under flip.py + stress.py. Suspected, not reproduced: a read landing on another entity's line. **Fixed** in 5ecce29 (JsonlHostileLinesTest: readsRacingAnAtomicReplaceNeverFailOrReturnAnotherEntity, anIndexOverAFileRewrittenInPlaceNeverReturnsAnotherEntitysLine). |
| DATA-11 | RedisLoader merges into a day instead of replacing it (RedisLoader.java:61): the 10k book then the 8k book leaves 10,000 trades on 09-30; a trade dropped from the book is never removed by reloading; an interrupted load writes documents the day's columns do not list (views and searches disagree). **Fixed** in f047e6a (RedisLoaderReplaceTest.aLoadReplacesEachDayByDefault, whileADayIsReplacedReadersSeeTheOldDayThenTheNewOneNeverAMix; replacing each day is now the default, `--merge` opts in) |
| DATA-12 | Multi-store fall-through brings back an entity the recent store dropped: the recent 09-29 file omits MX-30000006; the view comes from the lake with 200 while search reports 0 of 7,999. **Fixed** in 8dbec96 (StoreAuthorityTest.anEntityTheStoreHoldingTheDateDoesNotListIsNotHeldAndTheLakeIsNotAsked, FileCoverageTest, RecentStoreApiTest.anEntityTheRecentStoreDroppedIsNotHeldOnItsDates) |
| DATA-13 | LZ4 Parquet fails unclearly and the advised fix does not work: native engine health stays UP, the API returns a generic 502 (the clear refusal of NativeCodecs.java:53 is only logged), searches give DATA-01's false-exact empty answers; the hadoop engine also 502 (LZ4Exception: Malformed input on arrow/delta-rs LZ4), so DELTA_CONNECTOR §16's "use hadoop" does not hold. **Fixed** in 724328b (DeltaUnreadableTest: lz4PagesFailTheReadWithTheCodecAndWhatToDoAndHealthNamesTheDate, lz4FromArrowUnderTheHadoopEngineSaysWhatToDo). |
| DATA-14 | `quant.year_fraction` returns other conventions (quant.py:99-104): 30E/360 computed as US 30/360 (2026-01-15→03-31 76/360 vs 75/360; 02-28→08-31 183/360 vs 182/360); ACT/ACT ISDA as days/365.25 (2026-01-01→2027-01-01 0.999316 vs 1.0). **Fixed** in 891bc73 (test_quant.py: test_30e_360_moves_the_31st_to_the_30th_on_both_dates, test_act_act_isda_splits_the_period_by_calendar_year) |
| DATA-15 | `knownAt` silently ignored by stores without time travel: knownAt=2020-01-01 on the files store returns today's data (200); Delta correctly 404. **Fixed** in 8dbec96, a9dc80a (StoreAuthorityTest.knownAtOnAStoreWithoutTimeTravelFailsNamingItNeverWithTodaysData, RecentStoreApiTest.knownAtOnAStoreWithoutTimeTravelIsRefusedNamingIt) |

## Low
| Id | Finding |
|---|---|
| DATA-16 | After a deletion-vector delete the id stays in type-ahead (DeltaTable.java:149 reads ids raw); opening it gives 404. |
| DATA-17 | Ties in ordered searches have no id tie-break (StructuredSearch.java:145,217): PostgreSQL returned different tie order under limit in 115 of 300 searches, JDBC query mode 117 of 300, other stores 0. **Fixed** in 494741c (SearchOrderTest.tiesAreBrokenByIdOnTheColumnsAndTheDocumentsPath, SearchOrderTest.entitiesWithNoSortValueComeLastInIdOrder) |
| DATA-18 | Health UP for unreadable Delta tables (broken log, truncated Parquet, LZ4); Redis stays UP ~20 s after the store is down. **Fixed** in 724328b, 7de60f8 (DeltaUnreadableTest, RedisOutageHealthTest.healthIsDownPromptlyWhenRedisStops). |
| DATA-19 | JDBC query mode: one failing part query makes every view of the kind fail with a generic DRS-1003 (the SQL error only in the log); a `columns.*` query that duplicates rows is accepted without warning. **Fixed** in 5807788 (QueryModeProblemsTest) |
| DATA-20 | quant edge cases: black_scholes/black76 at T=0 or sigma=0 at the money → NaN (quant.py:468); norm_ppf(1-1e-12) relative error 1e-9 (docstring claims < 1e-14); month_code_years("V6", "2026-10-20") negative. **Fixed** in 891bc73 (test_quant.py: test_options_at_expiry_or_zero_vol_are_worth_their_intrinsic_value, test_norm_ppf_is_accurate_in_the_far_tails, test_a_month_code_for_the_current_month_is_never_negative) |
| DATA-21 | Ids containing "/" cannot be opened through the API (Tomcat rejects %2F with an HTML 400). **Fixed** in 0da0cd9 (RecentStoreApiTest.anIdWithASlashOpensWithTheIdInTheQuery; test_entity_ids.py) |
| DATA-22 | With Redis down each read waits 2 s for a 504 and each search 8 s, instead of failing fast. **Fixed** in f047e6a (RedisFailFastTest) |

## Info
- `state.durability: none` lost all 600 acknowledged entities on kill -9 (documented); `sync` lost nothing.
- Document-path searches on Delta and DuckDB hit the 3 s budget at 8,000 documents, but say partial.
- Health shows a 0.02 GB budget as "0.1 of 0.0 GB" (rounding).
- PostgresLoader aborts the whole load on one `\u0000` or one duplicate id (loud, but leaves DATA-04's partial state). **Fixed** in 0bef73b (PostgresLoaderRowsTest): the line and id are named before anything is published; a duplicate keeps the last line, as the JSON-lines connector.
- Test tooling: DeletionVectorFixture writes `remove` without the old DV, so two sequential deletes produce an invalid log.

## What held up
4,640 randomised searches against brute force, 0 wrong answers (ties counted as passing when sort keys matched, see
DATA-17): files 1,000+, Delta native 600, Delta hadoop 600, DuckDB 680, PostgreSQL 660, JDBC query mode with NULLs 600,
Redis 660 — numbers with k/m suffixes, negatives and boundaries, case-varied text, contains, startswith, ISO dates,
not/and/or nesting, null and missing fields, ordering and limits up to 1,000; pick lists match. Dates: weekends roll
back to Friday; before all data "not held"; future date 400; lookback 10 calendar days inclusive, reads and searches
agree; Delta time travel exact per version on both engines. Derived kinds and impact totals match independent sums on
every store. /search/columns exact. Swaps under 12–16 reader threads with no anomalies: DuckDB 6 reloads, files 8,
Delta 30 versions, Redis 6; new versions visible to reads and searches together within 5–7 s. PostgreSQL and Redis
outages recover; deletion vectors honoured on both engines; a missing checkpoint recovered from JSON commits; JDBC
reverse lookups bind parameters; odd ids, BOM, CRLF and empty files handled; a killed DuckDB load is safe; RabbitMQ
`sync`, eviction, SSE `deleted` frames and type-ahead removal work. drishti.quant: 109 checks pass against scipy and
textbook values.

## Coverage
Tested: files, Delta (native and hadoop), DuckDB, PostgreSQL (table and query modes), Redis, the RabbitMQ state store.
Not tested: MongoDB, Iceberg, Aerospike, Kafka, ActiveMQ, S3.
