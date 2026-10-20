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
package com.ash.drishti.plugin.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import com.datastax.oss.driver.api.core.CqlSession;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A day appears whole or not at all: while a day is reloaded (a new generation written, then named in {@code kind_days},
 * then the old one deleted), a reader polling the day's columns and documents sees either every row of the old load or every
 * row of the new one, never a mix and never a half-loaded day. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class CassandraDayAppearsWholeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    private static final String PAD = "x".repeat(1500);                   // documents of a realistic size, so the reload takes a while

    private static List<String> book(int trades, int mtm, String nettingSet) {
        List<String> lines = new ArrayList<>(trades);
        for (int i = 0; i < trades; i++) {
            String id = String.format("W-%05d", i);
            String doc = "{\"tradeId\":\"" + id + "\",\"mtm\":" + mtm + ",\"nettingSet\":\"" + nettingSet + "\",\"pad\":\"" + PAD + "\"}";
            lines.add(CassandraNode.line("whole", new DatedSourceContract.Row("trade", id, DAY, doc)));
        }
        return lines;
    }

    @Test
    void aReaderPollingDuringAReloadSeesOneLoadWholeOrTheOther() throws Exception {
        String node = CassandraNode.cassandra();
        CassandraLoader.Result first = CassandraNode.load(CassandraNode.args(node, "whole"), book(3000, 1, "NS-1"));
        assertThat(first.days()).singleElement().satisfies(d -> assertThat(d.replaced()).isZero());
        try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
            p.start(DatedSourceContract.context(CassandraNode.settings(node, "whole", "whole-cassandra")));
            AtomicBoolean loading = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger oldSeenWhileLoading = new AtomicInteger();
            AtomicInteger newSeen = new AtomicInteger();
            ConcurrentLinkedQueue<String> wrong = new ConcurrentLinkedQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                int i = 0;
                while (!stop.get()) {
                    try {
                        p.refresh();
                        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.of(DAY)).orElseThrow();
                        double first0 = (Double) c.value("mtm", 0);
                        int expected = first0 == 1 ? 3000 : 5000;
                        String ns = first0 == 1 ? "NS-1" : "NS-2";
                        for (int r = 0; r < c.size(); r++) {
                            if ((Double) c.value("mtm", r) != first0 || !ns.equals(c.value("nettingSet", r))) {
                                wrong.add("a mix of loads in one read: row " + r + " of " + c.size());
                                break;
                            }
                        }
                        if (c.size() != expected) {
                            wrong.add("a half day: " + c.size() + " rows of load mtm=" + first0);
                        }
                        String id = String.format("W-%05d", i++ % 3000);
                        double doc = p.fetch(EntityRef.of("trade", id), AsOf.of(DAY)).orElseThrow().data().get("mtm").asDouble();
                        if (doc != 1 && doc != 2) {
                            wrong.add("document " + id + " mtm " + doc);
                        }
                        if (first0 == 1 && loading.get()) {
                            oldSeenWhileLoading.incrementAndGet();
                        } else if (first0 == 2) {
                            newSeen.incrementAndGet();
                        }
                    } catch (Exception e) {
                        wrong.add("a read failed: " + e);
                    }
                }
            });
            loading.set(true);
            // the reload: more trades, other values, few writes in flight so it takes long enough to be read during
            CassandraLoader.Result second = CassandraNode.load(CassandraNode.args(node, "whole", "--in-flight", "4", "--batch-rows", "4"),
                    book(5000, 2, "NS-2"));
            loading.set(false);
            long until = System.nanoTime() + 3_000_000_000L;
            while (newSeen.get() < 3 && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            stop.set(true);
            reader.join();
            assertThat(wrong).isEmpty();
            assertThat(oldSeenWhileLoading.get()).as("reads of the old load while the new one was written").isPositive();
            assertThat(newSeen.get()).isPositive();
            int oldGeneration = first.days().get(0).generation();
            assertThat(second.days()).singleElement().satisfies(d -> assertThat(d.replaced()).isEqualTo(oldGeneration));
            // the old generation's partitions are gone (whole-partition deletes)
            try (CqlSession s = CassandraNode.session(node)) {
                for (short b = 0; b < 4; b++) {
                    assertThat(s.execute("SELECT count(*) FROM whole.trade WHERE business_date = ? AND generation = ? AND bucket = ?", DAY, oldGeneration, b)
                            .one().getLong(0)).isZero();
                }
            }
        }
    }
}
