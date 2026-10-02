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

import com.ash.drishti.testkit.DatedSourceContract;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * DATA-18: health goes DOWN within seconds of Redis going away, not at the next catalogue refresh (here an hour away):
 * the connection's state and failed reads count. Skipped where Docker is not reachable.
 */
class RedisOutageHealthTest {

    @Test
    @SuppressWarnings("resource")
    void healthIsDownPromptlyWhenRedisStops() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker");
        GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
        redis.start();
        RedisSourcePlugin p = new RedisSourcePlugin();
        try {
            p.start(DatedSourceContract.context(Map.of("uri", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379), "domain", "outage",
                    "source-name", "desk-redis", "refresh-seconds", "3600", "timeout-ms", "1000")));
            assertThat(p.health()).startsWith("UP");
            redis.stop();
            long until = System.nanoTime() + 5_000_000_000L;
            while (p.health().startsWith("UP") && System.nanoTime() < until) {
                Thread.sleep(100);
            }
            assertThat(p.health()).as("within 5 s of the store going away").startsWith("DOWN");
        } finally {
            p.close();
            redis.stop();
        }
    }
}
