/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.RetryPolicy;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.DatasetRefresher;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.RefreshSpec;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scheduler that rebuilds datasets on their {@code refresh:}: a clock schedule with the pack's business-day calendar, a failure retried by the shared
 * policy until the attempts are used, and {@code after-load}. The clock is ours, so a day passes in a line.
 */
class DatasetRefresherTest {

    static final ZoneId NY = ZoneId.of("America/New_York");

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return NY;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The lake, failing its first {@code failures} requests for a connector. */
    static final class Flaky implements SourceGateway {
        final TestLake lake;
        final AtomicInteger failures = new AtomicInteger();

        Flaky(TestLake lake) {
            this.lake = lake;
        }

        @Override
        public Optional<SourcePlugin> plugin(String connector, String kind) {
            if (failures.getAndDecrement() > 0) {
                throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + connector + "' is not running (connection refused)");
            }
            return lake.plugin(connector, kind);
        }

        /** Whether the lake's data versions are given (off: the failures count reads only). */
        volatile boolean versions;

        @Override
        public String dataVersion(String connector, String kind, LocalDate date) {
            return versions ? lake.dataVersion(connector, kind, date) : null;
        }

        @Override
        public List<EntityRef> ids(String kind, LocalDate date, int limit) {
            return lake.ids(kind, date, limit);
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date) {
            return lake.read(refs, date);
        }

        @Override
        public LocalDate currentDate() {
            return lake.currentDate();
        }
    }

    @TempDir Path store;
    TestLake lake;
    Flaky gateway;
    QueryService service;
    TestClock clock;
    DatasetRefresher refresher;
    QueryService.Loaded ds;
    Path stateFile;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        gateway = new Flaky(lake);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), gateway, Clock.systemUTC());
        clock = new TestClock(java.time.ZonedDateTime.of(2026, 10, 9, 5, 0, 0, 0, NY).toInstant());      // Friday 9 October 2026, 05:00 in New York
    }

    @AfterEach
    void tearDown() {
        if (refresher != null) {
            refresher.close();
        }
        service.close();
    }

    void start(String refresh, BiRefreshProperties props) {
        String yaml = TestLake.yaml().replace("version: 1\n", "version: 1\nrefresh: " + refresh + "\n");
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
        BusinessDates dates = new BusinessDates(new BusinessDateProperties("USNY", "America/New_York", null), clock);
        RetryPolicy retry = new RetryPolicy(true, Duration.ofMillis(50), 2.0, Duration.ofMillis(400), 0.0, 3);
        AuditLog audit = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {}

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        refresher = new DatasetRefresher(service, () -> List.of(ds), props, retry, dates, clock, audit, false, stateFile);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> dataset() {
        return (Map<String, Object>) refresher.status(ds).get("dataset");
    }

    void awaitStatus(String status) throws InterruptedException {
        for (int i = 0; i < 200 && !status.equals(dataset().get("status")); i++) {
            Thread.sleep(25);
        }
        assertThat(dataset().get("status")).isEqualTo(status);
    }

    @Test
    void aDailyScheduleWaitsForItsTimeThenRebuildsTheLayersAndPlansTheNextRun() throws Exception {
        start("\"daily at 06:00\"", BiRefreshProperties.defaults());
        refresher.tick();
        assertThat(dataset().get("nextRun")).isEqualTo(java.time.ZonedDateTime.of(2026, 10, 9, 6, 0, 0, 0, NY).toInstant().toString());
        assertThat(lake.scans.get()).isZero();
        clock.now = java.time.ZonedDateTime.of(2026, 10, 9, 6, 1, 0, 0, NY).toInstant();
        refresher.tick();
        awaitStatus("ok");
        assertThat(lake.scans.get()).isPositive();
        assertThat(dataset().get("lastRows")).isEqualTo(43L);
        assertThat(dataset().get("lastDate")).isEqualTo("2026-10-09");
        assertThat(dataset().get("trigger")).isEqualTo("schedule");
        assertThat(dataset().get("nextRun")).isEqualTo(java.time.ZonedDateTime.of(2026, 10, 10, 6, 0, 0, 0, NY).toInstant().toString());
        int scans = lake.scans.get();
        refresher.tick();
        assertThat(lake.scans.get()).as("not due again before its next time").isEqualTo(scans);
    }

    @Test
    void theStateAndTheHistoryOfARunSurviveARestart() throws Exception {
        stateFile = store.resolve("refresh").resolve("state.json");
        start("daily 06:00", BiRefreshProperties.defaults());
        refresher.tick();
        clock.now = java.time.ZonedDateTime.of(2026, 10, 9, 6, 1, 0, 0, NY).toInstant();
        refresher.tick();
        awaitStatus("ok");
        Map<String, Object> before = dataset();
        refresher.close();
        assertThat(java.nio.file.Files.isRegularFile(stateFile)).isTrue();
        start("daily 06:00", BiRefreshProperties.defaults());                       // a new process reads the file
        Map<String, Object> after = dataset();
        assertThat(after.get("status")).isEqualTo("ok");
        assertThat(after.get("lastRun")).isEqualTo(before.get("lastRun"));
        assertThat(after.get("nextRun")).isEqualTo(before.get("nextRun")).isEqualTo(java.time.ZonedDateTime.of(2026, 10, 10, 6, 0, 0, 0, NY).toInstant().toString());
        assertThat(after.get("lastRows")).isEqualTo(43L);
        assertThat(after.get("runs")).isEqualTo(1L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> history = (List<Map<String, Object>>) after.get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("trigger", "schedule").containsEntry("status", "ok").containsEntry("rows", 43L);
        refresher.runNow(ds, "wes");                                                // the next run adds to the history, newest first
        awaitStatus("ok");
        for (int i = 0; i < 100 && ((List<?>) dataset().get("history")).size() < 2; i++) {
            Thread.sleep(25);
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> two = (List<Map<String, Object>>) dataset().get("history");
        assertThat(two).hasSize(2);
        assertThat(String.valueOf(two.get(0).get("trigger"))).startsWith("manual (wes)");
    }

    @Test
    void aRunInProgressWhenTheServerStoppedIsShownInterruptedAndAnUnreadableFileIsIgnored() throws Exception {
        stateFile = store.resolve("refresh").resolve("state.json");
        java.nio.file.Files.createDirectories(stateFile.getParent());
        java.nio.file.Files.writeString(stateFile, "{\"jobs\":{\"file:dataset:lake-test\":{\"schedule\":\"daily 06:00\",\"status\":\"running\",\"runs\":3,\"history\":[]}}}");
        start("daily 06:00", BiRefreshProperties.defaults());
        assertThat(dataset().get("status")).isEqualTo("interrupted");
        assertThat(dataset().get("runs")).isEqualTo(3L);
        refresher.close();
        java.nio.file.Files.writeString(stateFile, "not json at all");
        start("daily 06:00", BiRefreshProperties.defaults());
        assertThat(dataset().get("status")).isNotEqualTo("interrupted");
    }

    @Test
    void businessDaysUseThePackCalendarAndSkipTheWeekendAndTheHoliday() throws Exception {
        start("business-days 06:00", BiRefreshProperties.defaults());
        refresher.tick();
        clock.now = java.time.ZonedDateTime.of(2026, 10, 9, 6, 1, 0, 0, NY).toInstant();
        refresher.tick();
        awaitStatus("ok");
        // Saturday and Sunday are skipped, and so is Columbus Day (Monday 12 October) in USNY: the next run is Tuesday the 13th
        assertThat(dataset().get("nextRun")).isEqualTo(java.time.ZonedDateTime.of(2026, 10, 13, 6, 0, 0, 0, NY).toInstant().toString());
    }

    @Test
    void aFailureIsRetriedWithTheSharedPolicyAndThenSucceeds() throws Exception {
        start("daily 06:00", BiRefreshProperties.defaults());
        refresher.tick();
        gateway.failures.set(1);
        clock.now = java.time.ZonedDateTime.of(2026, 10, 9, 6, 1, 0, 0, NY).toInstant();
        refresher.tick();
        awaitStatus("retrying");
        assertThat(dataset().get("failures")).isEqualTo(1);
        assertThat(String.valueOf(dataset().get("error"))).contains("connection refused");
        Instant retryAt = Instant.parse(String.valueOf(dataset().get("retryAt")));
        assertThat(Duration.between(clock.now, retryAt)).isEqualTo(Duration.ofMillis(50));       // the first wait of the policy, no jitter
        refresher.tick();
        assertThat(dataset().get("status")).as("the wait is not over").isEqualTo("retrying");
        clock.now = retryAt.plusMillis(1);
        refresher.tick();
        awaitStatus("ok");
        assertThat(dataset().get("failures")).isEqualTo(0);
        assertThat(dataset().get("error")).isNull();
        assertThat(dataset().get("trigger")).isEqualTo("retry");
    }

    @Test
    void whenTheAttemptsAreUsedTheRefreshIsLeftFailedUntilItsNextTime() throws Exception {
        start("daily 06:00", new BiRefreshProperties(null, null, 2, null, null));
        refresher.tick();
        gateway.failures.set(100);
        clock.now = java.time.ZonedDateTime.of(2026, 10, 9, 6, 1, 0, 0, NY).toInstant();
        refresher.tick();
        awaitStatus("retrying");
        clock.now = Instant.parse(String.valueOf(dataset().get("retryAt"))).plusMillis(1);
        refresher.tick();
        awaitStatus("failed");
        assertThat(dataset().get("retryAt")).isNull();
        assertThat(dataset().get("failures")).isEqualTo(2);
    }

    @Test
    void aBurstOfAfterLoadSignalsRefreshesEachDateOnceAndUnchangedDataNotAgain() throws Exception {
        // QA3B-07: every signal arriving while no refresh ran started one more full refresh; one runs and one waits now, each date once
        lake.versions.put(TestLake.DAY1, "files-1");
        lake.versions.put(TestLake.DAY2, "files-1");
        gateway.versions = true;
        start("after-load", new BiRefreshProperties(null, null, null, Duration.ofMillis(20), null));
        lake.scanDelayMillis = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        java.util.List<java.util.concurrent.Future<?>> sent = new java.util.ArrayList<>();
        for (int c = 0; c < 4; c++) {
            sent.add(pool.submit(() -> {
                for (int i = 0; i < 250; i++) {
                    refresher.onLoad("trade", i % 2 == 0 ? TestLake.DAY1 : TestLake.DAY2);
                }
            }));
        }
        for (var f : sent) {
            f.get();
        }
        pool.shutdown();
        awaitStatus("ok");
        for (int stable = 0, last = -1; stable < 8; ) {                     // until the refreshes are over: no read for 400 ms
            Thread.sleep(50);
            int now = lake.scans.get();
            stable = now == last ? stable + 1 : 0;
            last = now;
        }
        long runs = ((Number) dataset().get("runs")).longValue();
        assertThat(runs).as("refreshes for 1,000 signals over two dates").isBetween(1L, 4L);
        int scans = lake.scans.get();
        for (int i = 0; i < 200; i++) {
            refresher.onLoad("trade", TestLake.DAY1);                    // the same data again
        }
        Thread.sleep(400);
        assertThat(lake.scans.get()).as("the same data is not read again").isEqualTo(scans);
        assertThat(dataset().get("status")).isEqualTo("ok");
    }

    @Test
    void afterLoadRunsWhenTheLoadOfAKindTheDatasetReadsArrivesAndNotForOthers() throws Exception {
        start("after-load", new BiRefreshProperties(null, null, null, Duration.ofMillis(10), null));
        refresher.tick();
        clock.now = clock.now.plusSeconds(86_400);
        refresher.tick();
        assertThat(lake.scans.get()).as("a clock does nothing for after-load").isZero();
        refresher.onLoad("cashflow", TestLake.DAY2);
        Thread.sleep(150);
        assertThat(lake.scans.get()).isZero();
        refresher.onLoad("trade", TestLake.DAY1);
        awaitStatus("ok");
        assertThat(dataset().get("lastDate")).isEqualTo("2026-10-08");
        assertThat(dataset().get("lastRows")).isEqualTo(43L);
        assertThat(String.valueOf(dataset().get("trigger"))).startsWith("after-load");
        assertThat(DatasetRefresher.afterLoad(ds.dataset())).isTrue();
    }

    @Test
    void theScheduleVocabularyIsTheSubscriptionsAndABadOneIsAProblemAtValidation() {
        assertThat(RefreshSpec.normalise("0 */15 * * *")).isEqualTo("cron 0 0 */15 * * *");
        assertThat(RefreshSpec.normalise("daily at 06:00")).isEqualTo("daily 06:00");
        assertThat(RefreshSpec.normalise("business days at 07:30")).isEqualTo("business-days 07:30");
        for (String ok : List.of("hourly", "weekly mon 07:00", "monthly last 18:00 business", "after-load trade", "weekdays 18:30")) {
            assertThat(DatasetParser.parse(TestLake.yaml().replace("version: 1\n", "version: 1\nrefresh: \"" + ok + "\"\n")).problems()).as(ok).noneMatch(DatasetParser.Problem::error);
        }
        DatasetParser.Result bad = DatasetParser.parse(TestLake.yaml().replace("version: 1\n", "version: 1\nrefresh: \"whenever\"\n"));
        assertThat(bad.problems()).anyMatch(p -> p.error() && p.path().equals("refresh") && p.message().contains("not a schedule"));
        DatasetParser.Result table = DatasetParser.parse(TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    refresh: { schedule: \"daily 06:00\" }"));
        assertThat(table.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        assertThat(table.dataset().tables().get("trades").schedule()).isEqualTo("daily 06:00");
        assertThat(Dataset.Refresh.NONE.scheduled()).isFalse();
    }

    @Test
    void aRefreshForADateTheSourceHasNoDataForFallsBackToTheLatestAndSaysSo() throws Exception {
        clock.now = java.time.ZonedDateTime.of(2026, 10, 14, 6, 0, 0, 0, NY).toInstant();   // two business days past the last data (9 October)
        start("daily 06:00", BiRefreshProperties.defaults());
        refresher.runNow(ds, "wes");
        awaitStatus("warning");
        assertThat(String.valueOf(dataset().get("error"))).startsWith("warning: 0 rows for 2026-10-1").contains("latest data 2026-10-09");
        assertThat(dataset().get("lastDate")).isEqualTo("2026-10-09");
        assertThat((Long) dataset().get("lastRows")).isPositive();
        QueryService.Caller risk = new QueryService.Caller("rita", java.util.Set.of("risk"), true,
                com.ash.drishti.server.bi.dataset.UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
        QueryService.Result r = service.query(ds, new QueryRequest(List.of(), List.of("Trades"), List.of(), null, null, List.of(), 100, "auto"), risk, false);
        assertThat(((Number) r.columns().get(0).values()[0]).doubleValue()).as("the dataset answers from the latest date, not from an empty layer").isEqualTo(40.0);
        refresher.runNow(ds, "wes");                                                         // a second refresh keeps it so
        for (int i = 0; i < 200 && "running".equals(dataset().get("status")); i++) {
            Thread.sleep(25);
        }
        QueryService.Result again = service.query(ds, new QueryRequest(List.of(), List.of("Trades"), List.of(), null, null, List.of(), 100, "auto"), risk, false);
        assertThat(((Number) again.columns().get(0).values()[0]).doubleValue()).isEqualTo(40.0);
    }
}
