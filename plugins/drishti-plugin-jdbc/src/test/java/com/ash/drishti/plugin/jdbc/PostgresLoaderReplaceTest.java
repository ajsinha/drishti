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
package com.ash.drishti.plugin.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.ColumnSet;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A (kind, business date) is replaced whole for readers ({@link PostgresStage}): a load that fails or is killed half
 * way leaves every day as it was, readers never see a day missing or half loaded while it is reloaded, two loads of
 * one day leave one of them whole, and a day's columns are never read half old, half new. And dates are guarded
 * ({@code LoadGuard}): a future-dated row is not loaded and cannot move the retention cut-off, and retention refuses to
 * drop most of a domain without {@code --force-drop}. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresLoaderReplaceTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private static final String D0 = "2026-09-28";
    private static final String D1 = "2026-09-29";
    private static final String D2 = "2026-09-30";

    /** {@code n} trades of a day, each document and its promoted {@code mtm} carrying the load's version. */
    private static List<String> day(String domain, String date, int n, int version) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(row(domain, date, "T-%05d".formatted(i), version));
        }
        return out;
    }

    private static String row(String domain, String date, String id, int version) {
        return "{\"domain\":\"" + domain + "\",\"kind\":\"trade\",\"id\":\"" + id + "\",\"date\":\"" + date + "\",\"doc\":\"{\\\"v\\\":" + version
                + "}\",\"columns\":{\"mtm\":" + version + "}}";
    }

    private static void load(List<String> lines, String... options) throws Exception {
        Path f = Files.createTempFile("rows", ".jsonl");
        try {
            Files.write(f, lines, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(List.of(f.toString(), POSTGRES.getJdbcUrl(), "--user", POSTGRES.getUsername(), "--password",
                    POSTGRES.getPassword(), "--writers", "2", "--batch", "200"));
            args.addAll(List.of(options));
            PostgresLoader.main(args.toArray(new String[0]));
        } finally {
            Files.delete(f);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String query(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** A day as readers see it in one statement: "rows, versions, recorded rows", e.g. "1000 v1 1000". */
    private static String seen(Connection c, String domain, String date) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) || ' v' || coalesce(string_agg(DISTINCT doc->>'v', ','), '-') || ' ' || coalesce((SELECT rows FROM "
                     + domain + ".entity_dates WHERE kind = 'trade' AND business_date = '" + date + "')::text, '-') FROM " + domain
                     + ".entities WHERE kind = 'trade' AND business_date = '" + date + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String seen(String domain, String date) throws Exception {
        try (Connection c = connect()) {
            return seen(c, domain, date);
        }
    }

    private static long stages(String domain) throws Exception {
        return Long.parseLong(query("SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '" + domain
                + "' AND c.relname LIKE 'entities\\_load\\_%') + (SELECT count(*) FROM pg_namespace WHERE nspname LIKE '" + domain + "\\_\\_load\\_%')"));
    }

    /** How far a running load of version 2 has got: its rows in a stage, or in the table itself. */
    private static long written(String domain) throws Exception {
        long n = Long.parseLong(query("SELECT count(*) FROM " + domain + ".entities WHERE doc->>'v' = '2'"));
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '" + domain
                     + "' AND c.relname LIKE 'entities\\_load\\_%'")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            for (String t : names) {
                n += Long.parseLong(query("SELECT count(*) FROM " + domain + "." + t));
            }
        }
        return n;
    }

    @Test
    void aLoadThatFailsHalfWayLeavesEveryDayAsItWas() throws Exception {
        List<String> first = new ArrayList<>(day("failing", D1, 1000, 1));
        first.addAll(day("failing", D2, 1000, 1));
        load(first, "--recreate");
        for (String[] options : new String[][] {{}, {"--recreate"}}) {
            List<String> second = new ArrayList<>(day("failing", D1, 1000, 2));
            second.addAll(day("failing", D2, 1000, 2));
            second.set(1500, second.get(1500).replace("{\\\"v\\\":2}", "{not json"));   // its batch fails, after others are written
            assertThatThrownBy(() -> load(second, options)).isNotNull();
            assertThat(seen("failing", D1)).as(List.of(options).toString()).isEqualTo("1000 v1 1000");
            assertThat(seen("failing", D2)).as(List.of(options).toString()).isEqualTo("1000 v1 1000");
            assertThat(stages("failing")).as("the stage is dropped").isZero();
        }
    }

    @Test
    void aKilledLoadLeavesEveryDayAsItWasAndTheNextLoadClearsItsStageAway() throws Exception {
        List<String> first = new ArrayList<>(day("killed", D0, 1000, 1));
        first.addAll(day("killed", D1, 1000, 1));
        first.addAll(day("killed", D2, 1000, 1));
        load(first, "--recreate");
        String java = ProcessHandle.current().info().command().orElse("java");
        Process loader = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), PostgresLoader.class.getName(), "-", POSTGRES.getJdbcUrl(),
                "--user", POSTGRES.getUsername(), "--password", POSTGRES.getPassword(), "--writers", "2", "--batch", "100").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (OutputStream in = loader.getOutputStream()) {
                List<String> second = new ArrayList<>(day("killed", D0, 1000, 2));
                second.addAll(day("killed", D1, 1000, 2));
                second.addAll(day("killed", D2, 500, 2));
                for (String l : second) {
                    in.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                }
                in.flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (written("killed") < 500 && System.nanoTime() < deadline) {
                    Thread.sleep(100);                              // rows of the new load are being written
                }
                assertThat(written("killed")).as("the load was writing").isGreaterThanOrEqualTo(500);
                loader.destroyForcibly();                           // kill -9, the stream not ended
            } catch (java.io.IOException closed) {
                // the loader died first
            }
        } finally {
            loader.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        }
        assertThat(seen("killed", D0)).isEqualTo("1000 v1 1000");
        assertThat(seen("killed", D1)).isEqualTo("1000 v1 1000");
        assertThat(seen("killed", D2)).isEqualTo("1000 v1 1000");
        assertThat(stages("killed")).as("the killed load's stage is left").isEqualTo(1);
        List<String> third = new ArrayList<>(day("killed", D1, 900, 3));
        third.addAll(day("killed", D2, 900, 3));
        load(third);
        assertThat(seen("killed", D1)).isEqualTo("900 v3 900");
        assertThat(seen("killed", D2)).isEqualTo("900 v3 900");
        assertThat(stages("killed")).as("the killed load's stage is dropped by the next load").isZero();
    }

    @Test
    void readersNeverSeeAMissingOrHalfDayWhileItIsReloaded() throws Exception {
        load(day("busy", D2, 1500, 0), "--recreate");
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        Set<String> anomalies = ConcurrentHashMap.newKeySet();
        List<Thread> readers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            readers.add(Thread.ofPlatform().start(() -> {
                try (Connection c = connect()) {
                    while (!stop.get()) {
                        String s = seen(c, "busy", D2);
                        if (!s.matches("1500 v\\d 1500")) {
                            anomalies.add(s);
                        }
                        reads.incrementAndGet();
                    }
                } catch (Exception e) {
                    anomalies.add(e.toString());
                }
            }));
        }
        try {
            for (int v = 1; v <= 4; v++) {
                load(day("busy", D2, 1500, v), v % 2 == 0 ? new String[] {"--recreate"} : new String[0]);
            }
        } finally {
            stop.set(true);
            for (Thread r : readers) {
                r.join();
            }
        }
        assertThat(anomalies).as("what readers saw of the day other than 1,500 rows of one load").isEmpty();
        assertThat(reads.get()).isGreaterThan(20);
        assertThat(seen("busy", D2)).isEqualTo("1500 v4 1500");
    }

    @Test
    void twoLoadsOfOneDayAtOnceLeaveOneOfThemWhole() throws Exception {
        load(day("race", D2, 2000, 0), "--recreate");
        List<Throwable> failures = new ArrayList<>();
        Thread a = Thread.ofPlatform().start(() -> {
            try {
                load(day("race", D2, 2000, 1));
            } catch (Exception e) {
                synchronized (failures) {
                    failures.add(e);
                }
            }
        });
        Thread b = Thread.ofPlatform().start(() -> {
            try {
                load(day("race", D2, 2500, 2));
            } catch (Exception e) {
                synchronized (failures) {
                    failures.add(e);
                }
            }
        });
        a.join();
        b.join();
        assertThat(failures).isEmpty();
        assertThat(seen("race", D2)).isIn("2000 v1 2000", "2500 v2 2500");
    }

    @Test
    void aDaysColumnsAreNeverReadHalfOldHalfNew() throws Exception {
        load(day("mixed", D2, 1000, 1), "--recreate");
        CountDownLatch firstRangeRead = new CountDownLatch(1);
        AtomicBoolean reloaded = new AtomicBoolean();
        // the second id range is read only once the first is, and after a load has published the day again
        TableCatalog.Db db = new TableCatalog.Db() {
            @Override
            public <T> T with(TableCatalog.Work<T> work) throws Exception {
                try (Connection real = connect()) {
                    AtomicBoolean lowerRange = new AtomicBoolean();
                    Connection c = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p, m, args) -> {
                        if (m.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                            if (sql.contains(" id < ?") && !sql.contains(" id >= ?")) {
                                lowerRange.set(true);
                            } else if (sql.contains(" id >= ?") && !sql.contains(" id < ?") && reloaded.compareAndSet(false, true)) {
                                assertThat(firstRangeRead.await(30, TimeUnit.SECONDS)).isTrue();
                                load(day("mixed", D2, 1200, 2));
                            }
                        }
                        try {
                            return m.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
                    T out = work.run(c);
                    if (lowerRange.get()) {
                        firstRangeRead.countDown();
                    }
                    return out;
                }
            }
        };
        TableCatalog catalog = new TableCatalog(new EntityTable("mixed.entities", "kind", "id", "business_date", "doc", Map.of(), 10), db, "mixed-pg",
                k -> false, 10, Map.of("trade", List.of("mtm")), 2, 64, Duration.ofMinutes(5));
        assertThat(catalog.refresh(List.of())).isTrue();
        ColumnSet c = catalog.columns("trade", List.of("mtm"), LocalDate.parse(D2)).orElseThrow();
        assertThat(reloaded).as("the day was loaded again while it was read").isTrue();
        List<Double> versions = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            Double v = (Double) c.value("mtm", i);
            if (!versions.contains(v)) {
                versions.add(v);
            }
        }
        assertThat(c.size() + " rows of versions " + versions).isIn("1000 rows of versions [1.0]", "1200 rows of versions [2.0]");
    }

    @Test
    void aFutureDatedRowIsNotLoadedAndCannotMoveTheRetentionCutOff() throws Exception {
        List<String> history = new ArrayList<>(day("future", "2026-08-03", 10, 1));
        history.addAll(day("future", D2, 10, 1));
        load(history, "--recreate");
        assertThatThrownBy(() -> load(List.of(row("future", "2099-03-01", "T-TYPO", 1)), "--keep-months", "2", "--as-of", D2))
                .hasMessageContaining("1 rows not loaded").hasMessageContaining("business date is after");
        assertThat(query("SELECT count(*) FROM future.entities WHERE business_date > '2027-01-01'")).isEqualTo("0");
        assertThat(seen("future", "2026-08-03")).as("August stays: two months back from 2026-09-30").isEqualTo("10 v1 10");
        assertThat(seen("future", D2)).isEqualTo("10 v1 10");
        assertThat(query("SELECT count(*) FROM pg_class WHERE relname = 'entities_y2099m03'")).isEqualTo("0");
    }

    @Test
    void retentionThatWouldDropMostOfADomainNeedsForceDrop() throws Exception {
        List<String> history = new ArrayList<>(day("share", "2026-06-15", 10, 1));
        history.addAll(day("share", D2, 5, 1));
        load(history, "--recreate");
        assertThatThrownBy(() -> load(day("share", D2, 5, 2), "--keep-months", "1", "--as-of", D2))
                .hasMessageContaining("would drop 10 of 15 rows").hasMessageContaining("--force-drop");
        assertThat(seen("share", "2026-06-15")).isEqualTo("10 v1 10");
        load(day("share", D2, 5, 2), "--keep-months", "1", "--as-of", D2, "--force-drop");
        assertThat(seen("share", "2026-06-15")).isEqualTo("0 v- -");
        assertThat(seen("share", D2)).isEqualTo("5 v2 5");
    }
}
