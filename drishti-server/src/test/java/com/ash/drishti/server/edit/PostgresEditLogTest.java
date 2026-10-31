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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The edit log on a shared PostgreSQL database (WRITE_BACK.md › The design and its reasons, section 11, the cluster): the same statements as on SQLite, from the complete
 * PostgreSQL schema file. Many claimants of one lane at once: exactly one takes it; a node cut off after another took its lane cannot
 * append or apply (its epoch is stale) and nothing it attempted is written; the new owner replays from the applied seq; approvals decide
 * once; overlays round-trip.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresEditLogTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    static HikariDataSource ds;

    @BeforeAll
    static void open() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(POSTGRES.getJdbcUrl());
        c.setUsername(POSTGRES.getUsername());
        c.setPassword(POSTGRES.getPassword());
        c.setMaximumPoolSize(12);
        ds = new HikariDataSource(c);
        ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("db/schema-postgres.sql"));
        p.execute(ds);
        p.execute(ds);                                                   // applied twice: idempotent
    }

    @AfterAll
    static void close() {
        ds.close();
    }

    @Test
    void manyClaimantsOneOwnerAndAZombieWritesNothing() throws Exception {
        EditLog log = new EditLog(ds);
        assertThat(log.postgres()).isTrue();
        log.ensureLanes(128);
        log.ensureLanes(128);
        int lane = 100;                                                   // a lane no other test of this class uses
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        Map<String, Long> won = new ConcurrentHashMap<>();
        for (int i = 0; i < 6; i++) {
            String node = "n" + i;
            pool.execute(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                log.claim(lane, node, 300).ifPresent(e -> won.put(node, e));
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(won).as("exactly one claimant takes the lane").hasSize(1);
        String owner = won.keySet().iterator().next();
        long epoch = won.get(owner);
        long seq = log.append(EditLogTest.change("PG-1", 3), lane, owner, epoch, 100);
        Thread.sleep(400);                                               // the owner is cut off: its lease runs out on the database's clock
        String next = owner.equals("n9") ? "n8" : "n9";
        long e2 = log.claim(lane, next, 10_000).orElseThrow();
        assertThat(e2).isGreaterThan(epoch);
        assertThatThrownBy(() -> log.append(EditLogTest.change("PG-2", 4), lane, owner, epoch, 100)).isInstanceOf(EditLog.Fenced.class);
        EditLog.Row row = log.unapplied(lane, 1).get(0);
        EditRecords records = new EditRecords(ds);
        assertThatThrownBy(() -> log.finish(row, owner, epoch, EditLog.APPLIED, null, null, c -> records.overlay(c, new EditRecords.Overlay(row.changeId(),
                "$.NOTIONAL", "trade", "PG-1", 3, 1, row.change().businessDate(), null, EditRecords.ACTIVE, "asha", Instant.now()))))
                .isInstanceOf(EditLog.Fenced.class);
        assertThat(records.overlays("trade", "PG-1")).isEmpty();
        log.finish(row, next, e2, EditLog.APPLIED, null, null, c -> {
            records.overlay(c, new EditRecords.Overlay(row.changeId(), "$.NOTIONAL", "trade", "PG-1", 3, 1, row.change().businessDate(), null,
                    EditRecords.ACTIVE, "asha", Instant.now()));
            records.bump(c, row.key(), row.changeId(), Instant.now());
        });
        records.forgetAll();
        assertThat(records.overlays("trade", "PG-1")).singleElement().extracting(EditRecords.Overlay::value).asString().isEqualTo("3");
        assertThat(records.version(row.key())).isEqualTo(1);
        assertThat(log.lane(lane).orElseThrow().appliedSeq()).isEqualTo(seq);
        assertThat(EditLog.intact(log.get(row.changeId()).orElseThrow())).isTrue();
    }

    @Test
    void lanesOnTwoNodesApplyEveryChangeOnceInOrder() throws Exception {
        EditLog log = new EditLog(ds);
        int lanes = 8;
        EditSettings s = EditSettings.defaults().withEnabled(true).withLanes(lanes);
        Map<String, List<Long>> applied = new ConcurrentHashMap<>();
        AtomicInteger total = new AtomicInteger();
        Lanes.Applier applier = (r, node, e) -> {
            applied.computeIfAbsent(r.key(), k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(r.seq());
            log.finish(r, node, e, EditLog.APPLIED, null, null, null);
            total.incrementAndGet();
        };
        Set<Integer> even = Set.of(0, 2, 4, 6);
        Set<Integer> odd = Set.of(1, 3, 5, 7);
        int n = 400;
        try (Lanes a = new Lanes(log, s, "pa", applier, () -> even); Lanes b = new Lanes(log, s, "pb", applier, () -> odd)) {
            a.start();
            b.start();
            Map<String, List<Long>> appended = new ConcurrentHashMap<>();
            for (int i = 0; i < n; i++) {
                Change c = EditLogTest.change("Q" + (i % 40), i);
                Lanes owner = a.epoch(Lanes.laneOf(c.key(), lanes)).isPresent() ? a : b;
                appended.computeIfAbsent(c.key(), k -> new ArrayList<>()).add(owner.append(c));
            }
            long until = System.currentTimeMillis() + 60_000;
            while (total.get() < n && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertThat(total.get()).isEqualTo(n);
            appended.forEach((k, v) -> assertThat(applied.get(k)).as(k).containsExactlyElementsOf(v));
        }
    }

    @Test
    void anApprovalIsDecidedOnce() {
        ChangeRequests r = new ChangeRequests(ds);
        Change c = EditLogTest.change("PG-3", 9);
        r.add(c, 1);
        assertThat(r.approve(c.id(), "kim", "", Instant.now()).map(ChangeRequests.Request::state)).isEqualTo(Optional.of(ChangeRequests.APPROVED));
        assertThat(r.decide(c.id(), ChangeRequests.REJECTED, "ben", "late", Instant.now())).isFalse();
    }
}
