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
 * list), {@code --replace}, and the date guard: a future-dated row is not loaded and cannot move the {@code --ttl-days}
 * cut-off, and dropping most of a kind's days needs {@code --force-drop}. Skipped where Docker is not reachable.
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

    /** The ids the day's columns list. */
    private static List<String> listed(String domain, LocalDate day) throws Exception {
        return redis(r -> {
            var meta = ColumnReader.meta(r, domain, "trade", day, TIMEOUT).orElseThrow();
            return List.of(ColumnReader.read(r, domain, "trade", day, meta, List.of(), TIMEOUT).orElseThrow().ids());
        });
    }

    /** The documents Redis holds for the day whose ids start with {@code prefix}. */
    private static long documents(String domain, String prefix, LocalDate day) throws Exception {
        return redis(r -> (long) ColumnReader.await(r.keys(RedisLayout.bytes(domain + ":trade:{" + prefix + "*}:" + RedisLayout.day(day))), TIMEOUT).size());
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

    @Test
    void replaceMakesADayExactlyWhatTheStreamHolds() throws Exception {
        load(rows("replaced", "X-", 3, D3), "--codec", "zstd");
        load(List.of(row("replaced", "X-0001", D3, 10), row("replaced", "X-0003", D3, 30)), "--codec", "zstd", "--replace");
        assertThat(listed("replaced", D3)).containsExactly("X-0001", "X-0003");
        assertThat(documents("replaced", "X-", D3)).isEqualTo(2);
        int daysOfX0 = redis(r -> ColumnReader.await(r.zrange(RedisLayout.bytes(RedisLayout.entity("replaced", "trade", "X-0000")), 0, -1), TIMEOUT).size());
        assertThat(daysOfX0).as("the days of the entity the stream no longer carries").isZero();
        load(List.of(row("replaced", "X-0004", D3, 40)), "--codec", "zstd");                    // without --replace a load merges
        assertThat(listed("replaced", D3)).containsExactly("X-0001", "X-0003", "X-0004");
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
