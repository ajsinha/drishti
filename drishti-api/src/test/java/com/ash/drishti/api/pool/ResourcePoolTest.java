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
package com.ash.drishti.api.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResourcePoolTest {

    /** A connection that counts how many are open. */
    private static final class Fake implements AutoCloseable {
        private final AtomicInteger open;
        boolean closed;

        Fake(AtomicInteger open) {
            this.open = open;
            open.incrementAndGet();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                open.decrementAndGet();
            }
        }
    }

    private final AtomicInteger open = new AtomicInteger();

    @AfterEach
    void resetSite() {
        SitePool.reset();
    }

    private ResourcePool<Fake> pool(Map<String, String> settings) {
        return new ResourcePool<>("test", "jdbc", PoolSettings.resolve(settings), () -> new Fake(open));
    }

    @Test
    void defaultSizeIsTwentyAndAConnectorCanOverrideIt() {
        assertThat(PoolSettings.resolve(Map.of()).size()).isEqualTo(20);
        assertThat(PoolSettings.resolve(Map.of("pool-size", "7")).size()).isEqualTo(7);
        assertThat(PoolSettings.resolve(Map.of("pool-size", "7")).connectionTimeout().toSeconds()).isEqualTo(10);
    }

    @Test
    void theSiteDefaultIsInheritedAndAConnectorKeyWins() {
        SitePool.configure(Map.of("pool-size", "35", "pool-leak-threshold", "2m"));
        assertThat(PoolSettings.resolve(Map.of()).size()).isEqualTo(35);
        assertThat(PoolSettings.resolve(Map.of()).leakThreshold().toMinutes()).isEqualTo(2);
        assertThat(PoolSettings.resolve(Map.of("pool-size", "5")).size()).isEqualTo(5);
    }

    @Test
    void aBadSettingNamesItself() {
        assertThatThrownBy(() -> PoolSettings.resolve(Map.of("pool-size", "many"))).hasMessageContaining("pool-size");
        assertThatThrownBy(() -> PoolSettings.resolve(Map.of("pool-size", "0"))).hasMessageContaining("pool-size");
        assertThatThrownBy(() -> PoolSettings.resolve(Map.of("pool-connection-timeout", "soon"))).hasMessageContaining("pool-connection-timeout");
    }

    @Test
    void neverMoreThanTheSizeOpenAtOnce() throws Exception {
        try (ResourcePool<Fake> pool = pool(Map.of("pool-size", "3", "pool-connection-timeout", "200ms"))) {
            List<ResourcePool<Fake>.Lease> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                held.add(pool.borrow());
            }
            assertThat(pool.stats().inUse()).isEqualTo(3);
            assertThat(open.get()).isEqualTo(3);
            assertThatThrownBy(pool::borrow).isInstanceOf(PoolExhaustedException.class).hasMessageContaining("3 of 3 in use");
            assertThat(pool.stats().timeouts()).isEqualTo(1);
            held.forEach(ResourcePool.Lease::close);
            assertThat(pool.stats().inUse()).isZero();
            assertThat(pool.stats().idle()).isEqualTo(3);
        }
        assertThat(open.get()).isZero();                       // closing the pool closed what was idle
    }

    @Test
    void aConnectionIsReusedNotReopened() throws Exception {
        try (ResourcePool<Fake> pool = pool(Map.of("pool-size", "2"))) {
            Fake first;
            try (var l = pool.borrow()) {
                first = l.get();
            }
            try (var l = pool.borrow()) {
                assertThat(l.get()).isSameAs(first);
            }
        }
    }

    @Test
    void anInvalidatedLeaseIsClosedNotReturned() throws Exception {
        try (ResourcePool<Fake> pool = pool(Map.of("pool-size", "2"))) {
            Fake broken;
            try (var l = pool.borrow()) {
                broken = l.get();
                l.invalidate();
            }
            assertThat(broken.closed).isTrue();
            assertThat(pool.stats().open()).isZero();
        }
    }

    @Test
    void stressWithInjectedErrorsEndsWithNothingInUse() throws Exception {
        try (ResourcePool<Fake> pool = pool(Map.of("pool-size", "5", "pool-connection-timeout", "30s"));
                ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicInteger failures = new AtomicInteger();
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                int n = i;
                all.add(threads.submit(() -> {
                    try (var l = pool.borrow()) {
                        assertThat(pool.stats().inUse()).isLessThanOrEqualTo(5);
                        if (n % 3 == 0) {
                            throw new IllegalStateException("injected");
                        }
                        if (n % 7 == 0) {
                            l.invalidate();
                        }
                    } catch (IllegalStateException e) {
                        failures.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : all) {
                f.get();
            }
            assertThat(failures.get()).isGreaterThan(100);
            assertThat(pool.stats().inUse()).isZero();
            assertThat(pool.stats().waiting()).isZero();
            assertThat(pool.stats().open()).isLessThanOrEqualTo(5);
        }
        assertThat(open.get()).isZero();
    }

    @Test
    void aFailingOpenReleasesItsPlace() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (ResourcePool<Fake> pool = new ResourcePool<>("test", "jdbc", PoolSettings.resolve(Map.of("pool-size", "1", "pool-connection-timeout", "200ms")),
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new java.sql.SQLException("database down");
                    }
                    return new Fake(open);
                })) {
            assertThatThrownBy(pool::borrow).hasMessageContaining("database down");
            try (var l = pool.borrow()) {                      // the one place was given back
                assertThat(l.get()).isNotNull();
            }
        }
    }

    @Test
    void aBorrowHeldPastTheThresholdIsLoggedWithTheBorrowersStack() throws Exception {
        List<String> logged = new CopyOnWriteArrayList<>();
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(ResourcePool.class.getName());
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                StringBuilder sb = new StringBuilder(r.getMessage());
                if (r.getThrown() != null) {
                    for (StackTraceElement e : r.getThrown().getStackTrace()) {
                        sb.append('\n').append(e);
                    }
                }
                logged.add(sb.toString());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(h);
        try (ResourcePool<Fake> pool = pool(Map.of("pool-size", "2", "pool-leak-threshold", "150ms"))) {
            ResourcePool<Fake>.Lease leaked = pool.borrow();       // deliberately not closed for a while
            long until = System.currentTimeMillis() + 5_000;
            while (logged.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertThat(logged).hasSize(1);
            assertThat(logged.get(0)).contains("connection leak suspected in the test pool").contains("aBorrowHeldPastTheThresholdIsLogged");
            assertThat(pool.stats().leaksDetected()).isEqualTo(1);
            assertThat(pool.stats().longestBorrowMillis()).isGreaterThanOrEqualTo(150);
            Thread.sleep(400);
            assertThat(logged).hasSize(1);                         // once per borrow
            leaked.close();
            assertThat(pool.stats().inUse()).isZero();
            assertThat(pool.stats().longestBorrowMillis()).isZero();
        } finally {
            jul.removeHandler(h);
        }
    }

    @Test
    void extraIdleConnectionsAreClosedAfterTheIdleTimeoutButMinIdleStays() throws Exception {
        try (ResourcePool<Fake> pool = pool(Map.of("pool-size", "4", "pool-min-idle", "1", "pool-idle-timeout", "100ms"))) {
            List<ResourcePool<Fake>.Lease> held = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                held.add(pool.borrow());
            }
            held.forEach(ResourcePool.Lease::close);
            assertThat(pool.stats().idle()).isEqualTo(4);
            long until = System.currentTimeMillis() + 5_000;
            while (pool.stats().idle() > 1 && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertThat(pool.stats().idle()).isEqualTo(1);
        }
    }
}
