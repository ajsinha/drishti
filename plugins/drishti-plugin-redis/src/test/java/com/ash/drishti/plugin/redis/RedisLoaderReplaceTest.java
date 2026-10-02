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
package com.ash.drishti.plugin.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a {@link RedisLoader} leaves when a load fails or is killed half way (no document the day's columns do not
 * list), replacing each day (the default, atomic for readers) or {@code --merge}, and the date guard: a future-dated row
 * is not loaded and cannot move the {@code --ttl-days} cut-off, and dropping most of a kind's days, or of a day's
 * entities, needs {@code --force-drop}. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisLoaderReplaceTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2").withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1).withStartupTimeout(Duration.ofMinutes(1)));

    private static final LocalDate D3 = LocalDate.of(2026, 9, 30);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static String uri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static String row(String domain, String id, LocalDate date, int mtm) {
        return "{\"domain\":\"" + domain + "\",\"kind\":\"trade\",\"id\":\"" + id + "\",\"date\":\"" + date + "\",\"doc\":\"{\\\"tradeId\\\":\\\"" + id
                + "\\\",\\\"mtm\\\":" + mtm + "}\",\"columns\":{\"mtm\":" + mtm + "}}";
    }

    private static List<String> rows(String domain, String prefix, int n, LocalDate date) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(row(domain, prefix + "%04d".formatted(i), date, i));
        }
        return out;
    }

    private static void load(List<String> lines, String... flags) throws Exception {
        String[] args = new String[flags.length + 2];
        args[0] = "-";
        args[1] = uri();
        System.arraycopy(flags, 0, args, 2, flags.length);
        RedisLoader.Options o = RedisLoader.Options.parse(args);
        try (RedisConnection c = RedisConnection.open(o.uri(), false, null, null, TIMEOUT)) {
            new RedisLoader(o, c).load(new BufferedReader(new StringReader(String.join("\n", lines) + "\n")));
        }
    }

    /** Works on a connection of its own. */
    @FunctionalInterface
    private interface Redis<T> {
        T run(RedisClusterAsyncCommands<byte[], byte[]> r) throws Exception;
    }

    private static <T> T redis(Redis<T> work) throws Exception {
        try (RedisConnection c = RedisConnection.open(uri(), false, null, null, TIMEOUT)) {
            return work.run(c.async());
        }
    }

    /** The ids the day's columns list, in its current generation. */
    private static List<String> listed(String domain, LocalDate day) throws Exception {
        return redis(r -> {
            String gen = ColumnReader.generation(r, domain, "trade", day, TIMEOUT);
            var meta = ColumnReader.meta(r, domain, "trade", day, gen, TIMEOUT).orElseThrow();
            return List.of(ColumnReader.read(r, domain, "trade", day, gen, meta, List.of(), TIMEOUT).orElseThrow().ids());
        });
    }

    /** The documents Redis holds for the day whose ids start with {@code prefix}, in any generation. */
    private static long documents(String domain, String prefix, LocalDate day) throws Exception {
        return redis(r -> (long) ColumnReader.await(r.keys(RedisLayout.bytes(domain + ":trade:{" + prefix + "*}:" + RedisLayout.day(day) + "*")), TIMEOUT)
                .size());
    }

    /** A plugin over the test domain, its trades' {@code mtm} promoted. */
    private static RedisSourcePlugin plugin(String domain) {
        RedisSourcePlugin p = new RedisSourcePlugin();
        p.start(com.ash.drishti.testkit.DatedSourceContract.context(java.util.Map.of("uri", uri(), "domain", domain, "layout.trade.columns", "mtm",
                "refresh-seconds", "3600")));
        return p;
    }

    private static double mtm(RedisSourcePlugin p, String id) throws Exception {
        return p.fetch(EntityRef.of("trade", id), AsOf.of(D3)).orElseThrow().data().get("mtm").asDouble();
    }

    private static List<String> days(String domain) throws Exception {
        return redis(r -> ColumnReader.await(r.zrange(RedisLayout.bytes(RedisLayout.days(domain, "trade")), 0, -1), TIMEOUT).stream()
                .map(b -> new String(b, StandardCharsets.UTF_8)).toList());
    }

    @Test
    void aLoadThatFailsHalfWayLeavesNoDocumentTheDaysColumnsDoNotList() throws Exception {
        load(rows("broken", "X-", 2, D3), "--codec", "zstd");
        List<String> second = new ArrayList<>(rows("broken", "N-", 500, D3));
        second.add("{\"domain\":\"broken\",\"kind\":\"trade\",\"id\":\"N-BAD\",\"date\":\"2026-09-30\"}");   // no document: the load fails
        assertThatThrownBy(() -> load(second, "--codec", "zstd", "--threads", "2")).hasMessageContaining("without domain, kind, id, date or doc");
        assertThat(listed("broken", D3)).containsExactly("X-0000", "X-0001");
        assertThat(documents("broken", "N-", D3)).as("documents of the failed load that no column lists").isZero();
        assertThat(documents("broken", "X-", D3)).isEqualTo(2);
    }

    @Test
    void aKilledLoadsDocumentsAreDeletedByTheNextLoadOfTheDomain() throws Exception {
        load(rows("killed", "X-", 2, D3), "--codec", "zstd");
        String java = ProcessHandle.current().info().command().orElse("java");
        Process loader = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), RedisLoader.class.getName(), "-", uri(), "--codec", "zstd",
                "--threads", "2", "--in-flight", "50").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (OutputStream in = loader.getOutputStream()) {
                for (String l : rows("killed", "N-", 2000, D3)) {
                    in.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                }
                in.flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (documents("killed", "N-", D3) < 100 && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                loader.destroyForcibly();                               // kill -9, the stream not ended
            } catch (java.io.IOException closed) {
                // the loader died first
            }
        } finally {
            loader.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        }
        assertThat(documents("killed", "N-", D3)).as("the killed load wrote documents no column lists").isGreaterThanOrEqualTo(100);
        // the killed load's loader key would expire within 90 seconds: here it goes at once
        redis(r -> {
            for (byte[] k : ColumnReader.await(r.keys(RedisLayout.bytes("killed:loader:*")), TIMEOUT)) {
                ColumnReader.await(r.del(k), TIMEOUT);
            }
            return null;
        });
        load(List.of(row("killed", "Y-0000", D3.minusDays(1), 1)), "--codec", "zstd");
        assertThat(documents("killed", "N-", D3)).as("deleted by the next load of the domain").isZero();
        assertThat(listed("killed", D3)).containsExactly("X-0000", "X-0001");
        int unfinished = redis(r -> ColumnReader.await(r.smembers(RedisLayout.bytes(RedisLayout.loads("killed"))), TIMEOUT).size());
        assertThat(unfinished).as("days of unfinished loads").isZero();
    }

    /**
     * DATA-11: the QA's case. The 10,000-trade book then the 8,000-trade book left 10,000 trades on the day (loads merged
     * by default): a trade dropped from the book could never be removed by reloading. A load now replaces each day.
     */
    @Test
    void aLoadReplacesEachDayByDefault() throws Exception {
        load(rows("book", "X-", 10, D3), "--codec", "zstd");
        load(rows("book", "X-", 8, D3), "--codec", "zstd", "--keep-replaced-seconds", "0");
        assertThat(listed("book", D3)).hasSize(8).doesNotContain("X-0008", "X-0009");
        assertThat(documents("book", "X-", D3)).as("the replaced generation's documents (kept 0 s)").isEqualTo(8);
        int daysOfX9 = redis(r -> ColumnReader.await(r.zrange(RedisLayout.bytes(RedisLayout.entity("book", "trade", "X-0009")), 0, -1), TIMEOUT).size());
        assertThat(daysOfX9).as("the days of the entity the stream no longer carries").isZero();
        RedisSourcePlugin p = plugin("book");
        try {
            assertThat(p.fetch(EntityRef.of("trade", "X-0009"), AsOf.of(D3))).isEmpty();
            assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D3)).orElseThrow().size()).isEqualTo(8);
            assertThat(p.search("trade", "x-0009", 5)).isEmpty();
        } finally {
            p.close();
        }
    }

    @Test
    void mergeAddsToADayAndKeepsWhatTheStreamDoesNotCarry() throws Exception {
        load(rows("merged", "X-", 3, D3), "--codec", "zstd");
        load(List.of(row("merged", "X-0001", D3, 10), row("merged", "X-0004", D3, 40)), "--codec", "zstd", "--merge");
        assertThat(listed("merged", D3)).containsExactly("X-0000", "X-0001", "X-0002", "X-0004");
        assertThat(documents("merged", "X-", D3)).isEqualTo(4);
        RedisSourcePlugin p = plugin("merged");
        try {
            assertThat(mtm(p, "X-0001")).isEqualTo(10.0);
            assertThat(mtm(p, "X-0002")).isEqualTo(2.0);
        } finally {
            p.close();
        }
        assertThatThrownBy(() -> RedisLoader.Options.parse(new String[] {"-", uri(), "--merge", "--replace"})).hasMessageContaining("contradict");
    }

    /** Replacing a day with a stream that drops most of it is a mistake until proven otherwise (a part meant to be merged). */
    @Test
    void replacingADayWithAFewOfItsEntitiesNeedsForceDrop() throws Exception {
        load(rows("partial", "X-", 10, D3), "--codec", "zstd");
        assertThatThrownBy(() -> load(List.of(row("partial", "X-0001", D3, 99)), "--codec", "zstd")).hasMessageContaining("would remove 9 of its 10 entities")
                .hasMessageContaining("--merge");
        assertThat(listed("partial", D3)).hasSize(10);
        assertThat(documents("partial", "X-", D3)).as("the refused load's documents are gone").isEqualTo(10);
        RedisSourcePlugin p = plugin("partial");
        try {
            assertThat(mtm(p, "X-0001")).isEqualTo(1.0);
        } finally {
            p.close();
        }
        load(List.of(row("partial", "X-0001", D3, 99)), "--codec", "zstd", "--force-drop");
        assertThat(listed("partial", D3)).containsExactly("X-0001");
    }

    /**
     * Atomic from the reader's view, as the PostgreSQL, DuckDB and file loaders are: while a replacing load runs, a
     * reader sees the whole old day (documents and columns), then the whole new day; never new documents with the old
     * columns. Before, documents were overwritten as they arrived.
     */
    @Test
    void whileADayIsReplacedReadersSeeTheOldDayThenTheNewOneNeverAMix() throws Exception {
        load(rows("swap", "X-", 4, D3), "--codec", "zstd");
        RedisSourcePlugin p = plugin("swap");
        java.io.PipedWriter feed = new java.io.PipedWriter();
        java.io.PipedReader in = new java.io.PipedReader(feed, 1 << 16);
        java.util.concurrent.CompletableFuture<Void> running = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                RedisLoader.Options o = RedisLoader.Options.parse(new String[] {"-", uri(), "--codec", "zstd", "--threads", "1"});
                try (RedisConnection c = RedisConnection.open(o.uri(), false, null, null, TIMEOUT)) {
                    new RedisLoader(o, c).load(new BufferedReader(in));
                }
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            for (int i = 0; i < 3; i++) {                               // X-0000..X-0002 with new values; X-0003 dropped
                feed.write(row("swap", "X-%04d".formatted(i), D3, 100 + i) + "\n");
            }
            feed.flush();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (documents("swap", "X-", D3) < 7 && System.nanoTime() < until) {
                Thread.sleep(20);                                       // the new documents are in Redis
            }
            assertThat(documents("swap", "X-", D3)).isEqualTo(7);
            assertThat(mtm(p, "X-0001")).as("mid-load: the old day's document").isEqualTo(1.0);
            assertThat(p.fetch(EntityRef.of("trade", "X-0003"), AsOf.of(D3))).as("mid-load: the old day still holds X-0003").isPresent();
            assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D3)).orElseThrow().size()).isEqualTo(4);
            feed.close();
            running.get(60, TimeUnit.SECONDS);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (mtm(p, "X-0001") != 101.0 && System.nanoTime() < until) {
                Thread.sleep(20);                                       // the day's announcement reaches the connector
            }
            assertThat(mtm(p, "X-0001")).as("after the switch: the new day's document").isEqualTo(101.0);
            assertThat(p.fetch(EntityRef.of("trade", "X-0003"), AsOf.of(D3))).isEmpty();
            assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D3)).orElseThrow().ids()).containsExactly("X-0000", "X-0001", "X-0002");
        } finally {
            feed.close();
            p.close();
        }
    }

    @Test
    void aFutureDatedRowIsNotLoadedAndCannotMoveTheRetentionCutOff() throws Exception {
        List<String> history = new ArrayList<>();
        for (LocalDate d = D3.minusDays(2); !d.isAfter(D3); d = d.plusDays(1)) {
            history.add(row("dated", "X-0000", d, 1));
        }
        load(history, "--codec", "zstd", "--ttl-days", "30", "--as-of", D3.toString());
        assertThatThrownBy(() -> load(List.of(row("dated", "X-TYPO", LocalDate.of(2099, 3, 1), 1)), "--codec", "zstd", "--ttl-days", "30", "--as-of",
                D3.toString())).hasMessageContaining("1 rows not loaded");
        assertThat(days("dated")).containsExactly("20260928", "20260929", "20260930");
    }

    @Test
    void retentionThatWouldDropMostOfAKindsDaysNeedsForceDrop() throws Exception {
        List<String> history = new ArrayList<>();
        for (LocalDate d : List.of(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26), D3)) {
            history.add(row("share", "X-0000", d, 1));
        }
        load(history, "--codec", "zstd");
        assertThatThrownBy(() -> load(List.of(row("share", "X-0000", D3, 2)), "--codec", "zstd", "--ttl-days", "1", "--as-of", D3.toString()))
                .hasMessageContaining("would drop 3 of 4 business days");
        assertThat(days("share")).hasSize(4);
        load(List.of(row("share", "X-0000", D3, 2)), "--codec", "zstd", "--ttl-days", "1", "--as-of", D3.toString(), "--force-drop");
        assertThat(days("share")).containsExactly("20260930");
    }
}
