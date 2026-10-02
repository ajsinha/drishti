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
import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * DATA-22: with Redis down each read waited for its timeout (2 s for a 504 through the server) and each search 8 s.
 * While the connector's health says the store is down, reads and column reads now fail at once, and one recheck a
 * second closes the breaker as soon as the store answers again. Skipped where Docker is not reachable.
 */
class RedisFailFastTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @SuppressWarnings("resource")
    private static GenericContainer<?> redis() {
        GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
        redis.start();
        return redis;
    }

    private static String uri(GenericContainer<?> redis) {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    private static RedisSourcePlugin loadedPlugin(GenericContainer<?> redis) throws Exception {
        String line = "{\"domain\":\"ff\",\"kind\":\"trade\",\"id\":\"T-1\",\"date\":\"" + DAY + "\",\"doc\":\"{\\\"tradeId\\\":\\\"T-1\\\",\\\"mtm\\\":5}\","
                + "\"columns\":{\"mtm\":5}}";
        RedisLoader.Options o = RedisLoader.Options.parse(new String[] {"-", uri(redis), "--codec", "zstd"});
        try (RedisConnection c = RedisConnection.open(o.uri(), false, null, null, Duration.ofSeconds(10))) {
            new RedisLoader(o, c).load(new BufferedReader(new StringReader(line + "\n")));
        }
        RedisSourcePlugin p = new RedisSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("uri", uri(redis), "domain", "ff", "layout.trade.columns", "mtm", "refresh-seconds", "3600",
                "timeout-ms", "3000", "recheck-ms", "200")));
        assertThat(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY))).isPresent();
        return p;
    }

    private static long millis(ThrowingRunnable r) {
        long t0 = System.nanoTime();
        try {
            r.run();
        } catch (Exception e) {
            // the caller asserts the failure itself
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void readsFailAtOnceWhileRedisIsUnresponsiveAndResumeWhenItAnswers() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker");
        GenericContainer<?> redis = redis();
        RedisSourcePlugin p = null;
        var docker = DockerClientFactory.instance().client();
        boolean paused = false;
        try {
            p = loadedPlugin(redis);
            RedisSourcePlugin plugin = p;
            docker.pauseContainerCmd(redis.getContainerId()).exec();     // the connection stays open; nothing answers
            paused = true;
            assertThatThrownBy(() -> plugin.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY))).as("the first read waits for its timeout").isNotNull();
            assertThat(plugin.health()).startsWith("DOWN");
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> plugin.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY))).isInstanceOf(UnreadableData.class)
                        .hasMessageContaining("not reachable").hasMessageNotContaining(redis.getHost() + ":");
                assertThat(millis(() -> plugin.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY)))).as("a read while down").isLessThan(300);
                assertThat(millis(() -> plugin.columns("trade", List.of("mtm"), AsOf.of(DAY)))).as("a column read while down").isLessThan(300);
            }
            assertThat(plugin.health()).contains("reads fail at once");
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
            paused = false;
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (plugin.health().startsWith("DOWN") && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(plugin.health()).as("recovered within seconds of the store answering").startsWith("UP");
            assertThat(plugin.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY))).isPresent();
            assertThat(plugin.columns("trade", List.of("mtm"), AsOf.of(DAY))).isPresent();
        } finally {
            if (paused) {
                docker.unpauseContainerCmd(redis.getContainerId()).exec();
            }
            if (p != null) {
                p.close();
            }
            redis.stop();
        }
    }

    @Test
    void readsFailAtOnceOnceRedisHasGone() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker");
        GenericContainer<?> redis = redis();
        RedisSourcePlugin p = null;
        try {
            p = loadedPlugin(redis);
            RedisSourcePlugin plugin = p;
            redis.stop();                                               // the QA's docker stop
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (plugin.health().startsWith("UP") && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            for (int i = 0; i < 5; i++) {
                assertThat(millis(() -> plugin.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY)))).as("a read while down").isLessThan(300);
                assertThat(millis(() -> plugin.columns("trade", List.of("mtm"), AsOf.of(DAY)))).as("a column read while down").isLessThan(300);
            }
            assertThatThrownBy(() -> plugin.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DAY))).isInstanceOf(UnreadableData.class);
        } finally {
            if (p != null) {
                p.close();
            }
            redis.stop();
        }
    }
}
