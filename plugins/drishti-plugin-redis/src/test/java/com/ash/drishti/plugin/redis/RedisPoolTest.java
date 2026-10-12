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

import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.PoolStats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Redis connections are pooled: opened as the open ones get busy, never above pool-size, closed again when idle. */
class RedisPoolTest {

    @SuppressWarnings("resource")
    private static GenericContainer<?> redis() {
        GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
        redis.start();
        return redis;
    }

    @Test
    void theDefaultSizeIsTwentyAndAQuietConnectorHoldsOneConnection() {
        assertThat(PoolSettings.resolve(Map.of()).size()).isEqualTo(20);
        try (GenericContainer<?> redis = redis(); RedisConnection c = RedisConnection.open("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                false, null, null, Duration.ofSeconds(10))) {
            PoolStats s = c.stats();
            assertThat(s.max()).isEqualTo(20);
            assertThat(s.open()).isEqualTo(1);
            assertThat(s.inUse()).isZero();
        }
    }

    @Test
    void aBusyConnectorOpensMoreConnectionsUpToPoolSizeThenClosesTheIdleOnes() throws Exception {
        PoolSettings pool = PoolSettings.resolve(Map.of("pool-size", "3", "pool-min-idle", "1", "pool-idle-timeout", "100ms"));
        try (GenericContainer<?> redis = redis(); RedisConnection c = RedisConnection.open("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                false, null, null, Duration.ofSeconds(10), null, pool, 4)) {
            List<CompletableFuture<?>> all = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                all.add(c.async().ping().toCompletableFuture());
            }
            assertThat(c.stats().open()).isBetween(2, 3);                      // grew with the load, never past pool-size
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get();
            assertThat(c.stats().inUse()).isZero();                              // every command completed: nothing in flight
            long until = System.currentTimeMillis() + 5_000;
            while (c.stats().open() > 1 && System.currentTimeMillis() < until) {
                c.sweep();
                Thread.sleep(50);
            }
            assertThat(c.stats().open()).isEqualTo(1);                           // idle ones beyond pool-min-idle are closed
            assertThat(c.async().ping().get()).isEqualTo("PONG");   // and it still serves
        }
    }
}
