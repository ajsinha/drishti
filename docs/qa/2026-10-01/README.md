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
# Adversarial QA of Drishti 1.13.0 — 2026-10-01

Three independent QA passes looked for real defects in Drishti 1.13.0 (`develop` at `778bac1`, the release plus the
quant-service design document). They reported; they changed no product code. This page is the summary; each area has
its full activity log, findings, and the scripts that reproduce them.

| Area | Activity log | Findings | Extras |
|---|---|---|---|
| Security and access control | [security/LOG.md](security/LOG.md) | [security/FINDINGS.md](security/FINDINGS.md) | [server access matrix](security/matrix_server.md), [console access matrix](security/matrix_console.md), [scripts](security/scripts/) |
| Data correctness, connectors, robustness | [data/LOG.md](data/LOG.md) | [data/FINDINGS.md](data/FINDINGS.md) | [scripts](data/scripts/) (incl. the brute-force checker), quant output `data/logs-quant.txt` |
| UX, Rachana grammar, documentation | [ux/LOG.md](ux/LOG.md) | [ux/FINDINGS.md](ux/FINDINGS.md) | [scripts](ux/scripts/) (page sweep, contrast, grammar fuzz, doc audit) |

Screenshots of the UX pass (137 MB) were not committed; they were taken in the session's scratch folder.

## Result

| Severity | Security | Data | UX, grammar, docs | Total |
|---|---|---|---|---|
| High | 1 | 6 | 3 | **10** |
| Medium | 5 | 9 | 19 | **33** |
| Low | 6 | 7 | 23 | **36** |
| Info | 5 | 5 notes | 5 | **15** |

**In one sentence:** Drishti answers correctly when everything works — 4,640 randomised searches across seven stores
matched an independent brute-force evaluation exactly, about 250 documented settings match the code, about 190 grammar
fuzz cases get clean problem codes, there is no XSS anywhere and the access-control matrix holds on admin, ownership and
four-eyes — but it is too quiet and too forgiving when something fails: failing stores and bad input lines become
silent "nothing found", one loader is not atomic, retention trusts the newest date it sees, sessions outlive the user's
permissions, and masks are not applied on every path.

## High

| Id | Finding |
|---|---|
| [SEC-01](security/FINDINGS.md) | Disabling or demoting a user does not end their console session (roles carried in the cookie for 10 h; the server trusts token roles without a lookup). API tokens are cut off at once. **Fixed** in 6f886ab (test_sessions.py: test_disabling_a_user_ends_their_console_session, test_a_demoted_user_loses_the_role_at_once, test_the_check_is_cached_for_the_configured_time_and_admin_changes_clear_it; IdentityApiTest.consoleSessionsFollowTheUser; IdentityStoreContract.consoleSessionsEndAtSignOutAndForTheirUser) |
| [DATA-01](data/FINDINGS.md) | A source that fails or times out while listing makes a search look exact and empty (`partial: false`, 0 matches); a failed reindex drops the kind from type-ahead. **Fixed** in c00488a, 724328b (SearchFailuresTest, DeltaUnreadableTest.aFailedReindexKeepsThePreviousIdsAndHealthRecoversWhenTheTableReadsAgain) |
| [DATA-02](data/FINDINGS.md) | One unreadable line (truncated, NaN, a 25 MB document) silently erases a whole JSON-lines day; nothing is logged. **Fixed** in 5ecce29 (JsonlHostileLinesTest: oneTruncatedLineIsSkippedAndCountedNotTheWholeDay, nanAndInfinityAsPythonWritesThemAreReadAsNoValue, aDocumentBeyondJacksonsDefaultStringLimitIsReadAndOneBeyondMaxDocumentMbIsSkipped, anUnreadableFileIsIndexedOnceUntilItChanges) |
| [DATA-03](data/FINDINGS.md) | A failing store counts as "not held", so the next store silently answers with different data; view and search then disagree. **Fixed** in 8514970, c00488a (FileReadFailureTest, JdbcNotReachedTest, MongoNotReachedTest, DuckDbSourcePluginTest.aMissingFileIsDownUntilALoadWritesIt, SourceRouterTest.aFailingStoreStopsTheReadNamingItAndTheNextStoreIsNotAsked) |
| [DATA-04](data/FINDINGS.md) | PostgresLoader is not atomic: a killed or concurrent reload leaves days empty or half-loaded, and searches report them as exact. **Fixed** in 1420ab6 (PostgresLoaderReplaceTest; also 2cd7aa7 Redis, 6de321d MongoDB) |
| [DATA-05](data/FINDINGS.md) | One future-dated row with retention (`--keep-months`, `--keep-days`) irreversibly drops all history. **Fixed** in 1420ab6, 9b2099d, 2cd7aa7 (LoadGuardTest; aFutureDatedRowIsNotLoadedAndCannotMoveTheRetentionCutOff and retentionThatWouldDropMost… in each loader’s tests) |
| [DATA-06](data/FINDINGS.md) | JSON-lines rows with `doc` as an object and no `columns` get every promoted column null: searches, pick lists and desk P&L silently empty. **Fixed** in 5ecce29 (JsonlHostileLinesTest.docAsAnObjectWithoutColumnsIsPromotedLikeDocAsAString) |
| [GRAM-01](ux/FINDINGS.md) | A Sutra with a deeply nested expression (~800 parentheses) stops the server from starting (`StackOverflowError`). **Fixed** in c1ace31 (SutraRegistryTest.aDeeplyNestedSutraIsAProblemNotAFailedStart, PathologicalExpressionsTest.DefaultLimits.theServerStartsAndListsBothFilesAsProblems) |
| [GRAM-02](ux/FINDINGS.md) | The same file silently kills Sutra hot reload; later valid Sutras never load. **Fixed** in c1ace31 (SutraRegistryTest.hotReloadSurvivesADeeplyNestedSutraAndKeepsLoadingLaterOnes, SutraRegistryTest.anyFailureLoadingOneFileIsThatFilesProblem) |
| [UX-01](ux/FINDINGS.md) | Six visible tabs on live views freeze a seventh page (one live connection per tab; browsers allow six per host). **Fixed** in 1400225 (test_live_tabs_browser.py: test_many_live_tabs_and_a_live_workspace_leave_the_browser_free_and_all_keep_ticking[shared-worker], [leader]; test_live_hub.py) |

## Medium

| Id | Finding |
|---|---|
| SEC-02 | The server's token filter checks the raw URI: `/api/v1;x/…` and encoded paths skip it (non-sensitive metadata only; identity-bound endpoints fail closed). **Fixed** in fc82831 (PathBypassTest) |
| SEC-03 | Field masks apply to raw JSON, search, export, compare, history, Calc and pivots, but not to views, tables, panel records, live updates or panel CSV export. **Fixed** in 43075d9, d802261 (FieldMaskingTest.viewsMaskTheStripTitleTablesRecordsAndDerivedValues, theLiveStreamAndMonitorsCarryMaskedValues, alertsAndPhrasesCannotProbeAMaskedField; MaskTest) |
| SEC-04 | Impact (F8) applies no masks (masked values and relationships shown). **Fixed** in 43075d9 (FieldMaskingTest.impactNeitherListsWhatOnlyAMaskedFieldTiesInNorShowsAMaskedMeasure) |
| SEC-05 | Sign-out only deletes the browser cookie; a copied cookie keeps working. **Fixed** in 6f886ab (test_sessions.py::test_sign_out_ends_the_session_on_the_server; IdentityApiTest.consoleSessionsFollowTheUser) |
| SEC-06 | "Change password at first sign-in" is not enforced. **Fixed** in 6f886ab (test_sessions.py::test_a_password_change_asked_for_is_enforced) |
| DATA-07 | A promoted field with mixed types: the columns path orders text lexicographically and clamps huge numbers, disagreeing with documents. |
| DATA-08 | File connector reverse lookups skip effective-mode kinds (Impact misses groups other stores show). |
| DATA-09 | Duplicate ids in a JSON-lines day are counted twice (desk P&L wrong). **Fixed** in 5ecce29 (JsonlHostileLinesTest.aDuplicateIdKeepsItsLastLineEverywhere) |
| DATA-10 | A read racing an atomic JSON-lines replace can fail (502), using old offsets on the new file. **Fixed** in 5ecce29 (JsonlHostileLinesTest: readsRacingAnAtomicReplaceNeverFailOrReturnAnotherEntity, anIndexOverAFileRewrittenInPlaceNeverReturnsAnotherEntitysLine) |
| DATA-11 | RedisLoader merges into a day instead of replacing it; an interrupted load leaves views and searches disagreeing. |
| DATA-12 | Multi-store fall-through brings back an entity the recent store dropped. |
| DATA-13 | LZ4 Parquet: unclear 502, health UP, and the documented advice ("use hadoop") does not work. **Fixed** in 724328b (DeltaUnreadableTest.lz4PagesFailTheReadWithTheCodecAndWhatToDoAndHealthNamesTheDate, DeltaUnreadableTest.lz4FromArrowUnderTheHadoopEngineSaysWhatToDo) |
| DATA-14 | `quant.year_fraction`: 30E/360 computed as US 30/360; ACT/ACT ISDA as days/365.25. |
| DATA-15 | `knownAt` silently ignored by stores without time travel. |
| GRAM-03 | Very deep or long expressions: the view returns HTTP 500 without a problem code. **Fixed** in c1ace31 (ElLimitsTest.longChainsThatWouldOverflowEvaluationAreRefusedAtCompileTime, PathologicalExpressionsTest.LiftedLimits.anOverflowIsAPanelProblemOrAFileProblemNeverA500) |
| GRAM-04 | "Case never matters" is false for field names; unknown fields silently match nothing. |
| GRAM-05 | Many invalid Sutra inputs are silently accepted (duplicate keys, wrong types, extra YAML documents, unknown tags). |
| UX-02 | At phone width 77 of 85 pages scroll sideways; header items clipped. **Fixed** in 61ba1d9 (test_page_widths_browser.py: test_no_page_scrolls_sideways_and_images_fit[390], [1600], [2560]) |
| UX-03 | Help-centre screenshots overflow the page. **Fixed** in 8ad4307 (test_help.py: test_guide_screenshots_fit_their_column_and_open_full_size; test_page_widths_browser.py) |
| UX-04 | Faint text fails WCAG contrast in six of seven themes; the contrast test misses it. **Fixed** in 0cea59a (test_contrast.py: test_every_text_colour_reads_on_every_ground_in_every_theme, test_labels_on_accent_fills_read_in_every_theme, test_the_light_fallback_is_the_light_theme) |
| UX-05 | With the packs QUICKSTART recommends, Studio opens on an error; landing examples use ids that do not exist. |
| UX-06 | Workspaces cannot be created without the finance or logistics packs. |
| UX-07 | Alt+1..4 stops working once a workspace pane has focus. |
| UX-08 | A past date missing from the dated store silently shows live data, with a wrong reason. |
| UX-09 | Pivot row keys shown in scientific notation. |
| DOC-01 | GETTING_STARTED expects Java 21's version string. |
| DOC-02 | Calc is dead after the quickstart: installing the Python runtime (`tools/fetch-pyodide.sh`) is not mentioned. |
| DOC-03 | DEMO_DATA's `--trades N` replaces the whole trade table, not "the days it covers". |
| DOC-08 | API_GUIDE gives the wrong code for Notes' forbidden error. |
| DOC-09 | API_GUIDE's "complete" code list misses two codes. |
| DOC-13 | DEVELOPER_GUIDE and README list 11 plugins; there are 15. |
| DOC-16 | Several settings read by the code are documented nowhere. |
| DOC-18 | About 88 in-app help links go nowhere. |

Lows and infos are in each area's findings: inconsistent error responses (SEC-10, GRAM-06..08, UX-10..13), CSRF
protection relies on SameSite only (SEC-08, **Fixed** in 6f886ab: test_sessions.py: test_cross_site_writes_and_json_as_text_are_refused, test_configured_origins_are_allowed, test_sign_out_is_a_post_and_get_only_asks), type-ahead reveals masked names (SEC-07, **Fixed** in 43075d9: FieldMaskingTest.typeAheadNeitherShowsNorMatchesMaskedValues), readiness probe always DOWN with
security on (SEC-12), absolute paths in Sutra problems (SEC-11), tie order under limits (DATA-17), health UP for
unreadable tables (DATA-18, **Fixed** in 724328b, 7de60f8: DeltaUnreadableTest, RedisOutageHealthTest), quant edge cases (DATA-20), layout and Studio polish (UX-14..18), documentation mismatches
(DOC-04..22), and risky development defaults that are already documented (SEC-16).

## What held up

- **Search correctness:** 4,640 randomised searches vs brute force, 0 wrong answers, on files, Delta (native and Hadoop
  engines), DuckDB, PostgreSQL (table and query modes) and Redis.
- **Dates and history:** weekends, lookback, before-all-data, future dates, Delta time travel per version on both
  engines.
- **Derived kinds and impact totals** match independent sums on every store tested; `/search/columns` exact.
- **Concurrency:** DuckDB, files, Delta and Redis reloads under 12–16 reader threads with no anomalies; outages of
  PostgreSQL and Redis recover.
- **Durability:** the message state store with `state.durability: sync` lost nothing on `kill -9`; eviction and SSE
  `deleted` frames work.
- **Access control:** tokens (tampered, `alg: none`, wrong algorithm or secret, expired) refused; every admin endpoint
  refuses non-admins and API tokens; four-eyes holds; nobody reaches another user's notes, tokens, workspaces, layouts,
  pivots, snippets, monitors or alert rules; API tokens are read-only and cut off at once.
- **Input handling:** malformed search, YAML, kinds, ids, dates and oversize inputs give clean 4xx with problem codes;
  billion-laughs, recursive aliases, `!!java` tags and huge files rejected; SQL is bound and identifiers checked; no XSS;
  CSP as documented, including Calc's worker.
- **Pages:** 85 URLs at 390, 1600 and 2560 px in all 7 themes with no JavaScript errors, CSP violations or broken
  images; keyboard and ARIA basics in place; Calc survives hostile code (loops with Stop, memory bombs, huge output).
- **Docs:** QUICKSTART and GETTING_STARTED commands and outputs match from a fresh clone (apart from DOC-01/02);
  about 250 settings match their code defaults.
- **drishti.quant:** 109 checks against scipy and textbook values pass (apart from DATA-14 and DATA-20).

## Proposed fix order

| Wave | Findings | Why first |
|---|---|---|
| 1. Never lose or misreport data | DATA-04, DATA-05, DATA-01, DATA-03, DATA-13, DATA-18, DATA-02, DATA-06, DATA-09, GRAM-01, GRAM-02 | data loss and silent wrong answers; a bad file stopping the server |
| 2. Sessions and masks | SEC-01, SEC-05, SEC-06, SEC-02, SEC-03, SEC-04, SEC-07, SEC-08 | permissions must follow the user, and a mask must mean the same thing everywhere (decide the policy, then apply it on every path) |
| 3. Live connections | UX-01 | a desk with several screens freezes |
| 4. Correctness and UX | DATA-07, 08, 10, 11, 12, 14, 15, 17, 19–22; GRAM-03..09; UX-02..09 | wrong-but-visible results and broken pages |
| 5. Documentation and polish | DOC-01..22; the remaining lows and infos | accuracy of what users read |

Each fix gets a regression test built from the reproduction in the findings (the scripts are here for that).

## Coverage and limits

| Covered | Not covered |
|---|---|
| Stores: files, Delta (native, Hadoop), DuckDB, PostgreSQL (table, query), Redis; RabbitMQ state store | MongoDB, Iceberg, Aerospike, Kafka, ActiveMQ, S3 |
| Security: 101 server endpoint/method rows over 35 controllers × 9 identities; 42 console routes × 8 | single sign-on with a real provider, report runs, real pack-registry installs, monitor/alert streams per role |
| UX: 85 pages × 3 widths × 7 themes; keyboard; Calc; layout mode; Pivot; live streams | Firefox and Safari, real screen readers, Docker images; a synthetic drag in layout mode did not trigger (inconclusive) |
| Docs: QUICKSTART, GETTING_STARTED followed literally; DEMO_DATA's Delta path; 250 settings sampled | Windows (read-only), the other DEMO_DATA paths |

Scale: data passes used at most 10,000 trades a day, one container at a time, by the project's rule for this machine.

## How it was run

- Three passes in parallel, each on its own scratch server and console (ports 1898x/1798x), on the banking packs'
  samples and small generated books; containers one at a time, capped at 2 GB, removed afterwards. Nothing ran against
  the developer's own server or containers.
- **The security pass ran twice.** The first attempt's planning was stopped by a safety check, after which it reported
  that it had stopped; in fact it continued and exercised a scratch instance for about 20 minutes (its scripts are the
  `t_*.py` files in [security/scripts](security/scripts/)). The second, re-scoped review (access control, masking,
  input handling, configuration, by normal API calls and code review) produced the findings and matrices; it noticed
  the first one's activity on the shared scratch instance (users created, sign-ins failing) and its findings do not
  depend on it.
- The passes could not write their own findings files (the tooling refused), so their findings were recorded from
  their reports; the activity logs are their own.
