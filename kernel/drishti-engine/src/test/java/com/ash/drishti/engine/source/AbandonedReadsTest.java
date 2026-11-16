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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.ReadSlots;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * QA3A-01: reads whose caller has timed out must not stay queued for a connector's read slots. A burst of reads (a full pass over
 * every trade, many times over) with a short budget, while every slot is busy: once the budget is over the queue empties and the
 * threads end, and none of the abandoned reads ever reads.
 */
class AbandonedReadsTest {

    /** A store whose reads take one of {@code slots}, as the Delta connector's document reads do. */
    static final class Slotted implements SourcePlugin {
        final ReadSlots slots;
        final AtomicInteger read = new AtomicInteger();

        Slotted(int permits, int maxWaiting) {
            slots = new ReadSlots("document reads of slotted", permits, maxWaiting);
        }

        public PluginManifest manifest() {
            return new PluginManifest("slotted", "t", Set.of("trade"), new SourceCapabilities(false, false, false, false));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            slots.acquire();
            try {
                read.incrementAndGet();
                return Optional.of(new EntityDocument(ref, DataNode.of(Map.of("id", ref.id())), new Provenance("slotted", 1, Instant.now(), false, null)));
            } finally {
                slots.release();
            }
        }
    }

    private static SourceRouter router(SourcePlugin store) {
        var props = new SourcesProperties(Map.of("trade", "slotted"), null, Map.of(), Duration.ofMillis(500), null, Map.of());
        return new SourceRouter(new SourceRegistry(List.of(store), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static List<EntityRef> refs(int n) {
        List<EntityRef> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(EntityRef.of("trade", "T-" + i));
        }
        return out;
    }

    @Test
    void readsWhoseCallerTimedOutLeaveTheQueueAndNeverRead() throws Exception {
        Slotted store = new Slotted(2, 100_000);
        SourceRouter r = router(store);
        // every slot busy (a slow read of a large row group)
        store.slots.acquire();
        store.slots.acquire();
        int before = Thread.activeCount();
        long t0 = System.nanoTime();
        for (int round = 0; round < 5; round++) {                   // five passes of every trade, as signal after signal asked
            Map<EntityRef, EntityDocument> got = r.fetchAll(refs(2_000), Duration.ofMillis(150), AsOf.LATEST);
            assertThat(got).isEmpty();                                // nothing could be read within the budget
        }
        // the abandoned reads leave the queue soon after their budget, not when a slot frees
        long deadline = System.currentTimeMillis() + 5_000;
        while (store.slots.waiting() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(store.slots.waiting()).as("reads still queued for a slot after their callers gave up").isZero();
        assertThat(store.slots.abandonedCount()).isEqualTo(10_000);
        store.slots.release();
        store.slots.release();
        Thread.sleep(200);
        assertThat(store.read.get()).as("an abandoned read never reads").isZero();
        assertThat(Thread.activeCount()).isLessThanOrEqualTo(before + 4);
        assertThat(System.nanoTime() - t0).isLessThan(Duration.ofSeconds(10).toNanos());
        // with slots free again, a read goes through
        assertThat(r.fetchAll(refs(10), Duration.ofSeconds(2), AsOf.LATEST)).hasSize(10);
    }

    @Test
    void aFullQueueRefusesAtOnceWithAClearReason() throws Exception {
        Slotted store = new Slotted(1, 50);
        SourceRouter r = router(store);
        store.slots.acquire();
        Thread filler = Thread.ofVirtual().start(() -> r.fetchAll(refs(50), Duration.ofSeconds(3), AsOf.LATEST));
        long deadline = System.currentTimeMillis() + 3_000;
        while (store.slots.waiting() < 50 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(store.slots.waiting()).isEqualTo(50);
        long t0 = System.nanoTime();
        Throwable refused = null;
        try {
            r.fetch(EntityRef.of("trade", "T-late"), Duration.ofSeconds(3), AsOf.LATEST).join();
        } catch (java.util.concurrent.CompletionException e) {
            refused = e.getCause();
        }
        assertThat(System.nanoTime() - t0).as("refused at once, not after the budget").isLessThan(Duration.ofSeconds(1).toNanos());
        assertThat(refused).isInstanceOf(SourceFailure.class);
        assertThat(((SourceFailure) refused).reason()).contains("50 reads already wait").contains("busy");
        assertThat(store.slots.refused()).isEqualTo(1);
        store.slots.release();
        filler.join(5_000);
        assertThat(store.slots.waiting()).isZero();
        assertThat(store.read.get()).isEqualTo(50);
    }
}
