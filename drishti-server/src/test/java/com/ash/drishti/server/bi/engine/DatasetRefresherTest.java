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
        refresher = new DatasetRefresher(service, () -> List.of(ds), props, retry, dates, clock, audit, false);
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
        start("daily 06:00", new BiRefreshProperties(null, null, 2, null));
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
    void afterLoadRunsWhenTheLoadOfAKindTheDatasetReadsArrivesAndNotForOthers() throws Exception {
        start("after-load", new BiRefreshProperties(null, null, null, Duration.ofMillis(10)));
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
}
