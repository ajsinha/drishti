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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Fragment pools and circuit breakers per connector (wave B task 4, docs/architecture/BI_DESIGN.md section 6.6), and the connectors' capability
 * descriptors: a connector that fails five times opens its breaker and is refused at once with {@code DRS-9026} naming it, one fragment tries again after
 * the open time, its success closes the breaker; a connector runs at most its {@code max-fragments} at once.
 */
class FragmentPoolsTest {

    static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-09T10:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static ExecProperties props(int fragments) {
        return new ExecProperties(null, null, null, Duration.ofMillis(50), null, null, null, null, fragments, 3, Duration.ofSeconds(30),
                Map.of("slow-store", Map.of("max-fragments", 1)), null, null);
    }

    @Test
    void aFailingConnectorOpensItsBreakerAndIsTriedAgainLater() {
        MovingClock clock = new MovingClock();
        FragmentPools pools = new FragmentPools(props(8), clock);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> pools.run("lake", () -> {
                throw new DrishtiException(ErrorCode.QUERY_SOURCE, "lake is down");
            })).hasMessageContaining("lake is down");
        }
        assertThat(pools.state("lake")).isEqualTo(FragmentPools.State.OPEN);
        assertThatThrownBy(() -> pools.run("lake", () -> "never run")).isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-9026")
                .hasMessageContaining("'lake'").hasMessageContaining("circuit breaker is open");
        assertThat(pools.run("other", () -> "fine")).isEqualTo("fine");                      // one connector's breaker is its own
        clock.now.updateAndGet(t -> t.plusSeconds(31));
        assertThat(pools.run("lake", () -> "back")).isEqualTo("back");                       // half open: one try, which closes it
        assertThat(pools.state("lake")).isEqualTo(FragmentPools.State.CLOSED);
        // a refusal of the question is not the connector's failure
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> pools.run("lake", () -> {
                throw new DrishtiException(ErrorCode.QUERY_INVALID, "bad field");
            })).hasMessageContaining("bad field");
        }
        assertThat(pools.state("lake")).isEqualTo(FragmentPools.State.CLOSED);
        List<Map<String, Object>> stats = pools.stats();
        assertThat(stats).anyMatch(m -> "lake".equals(m.get("connector")) && "closed".equals(m.get("breaker")));
    }

    @Test
    void aConnectorRunsAtMostItsFragmentsAtOnce() throws Exception {
        FragmentPools pools = new FragmentPools(props(8), Clock.systemUTC());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> pools.run("slow-store", () -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pools.run("slow-store", () -> "second")).hasMessageContaining("DRS-9025").hasMessageContaining("max-fragments");
        release.countDown();
        t.join(5000);
        assertThat(pools.run("slow-store", () -> "third")).isEqualTo("third");
    }

    @Test
    void theCapabilityDescriptorSaysWhatAConnectorCanDo() {
        TestLake lake = new TestLake(true);
        ConnectorCapabilities trades = ConnectorCapabilities.of(lake.plugin("trading-lake", "trade").orElseThrow(), "trade", ExecProperties.defaults());
        assertThat(trades.sql()).isFalse();
        assertThat(trades.scanTier("cached")).isEqualTo("java");
        assertThat(trades.filterTypes()).contains("decimal");
        assertThat(trades.toMap()).containsEntry("connector", "trading-lake").containsEntry("maxFragments", 8);
        assertThatThrownBy(trades::sqlDialect).isInstanceOf(IllegalStateException.class);
    }
}
