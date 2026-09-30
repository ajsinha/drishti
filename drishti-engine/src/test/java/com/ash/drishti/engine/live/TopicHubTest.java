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
package com.ash.drishti.engine.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TopicHubTest {

    /** A live source that ticks only when the test says so. */
    static final class Manual implements SourcePlugin {
        final List<Consumer<EntityDocument>> subs = new CopyOnWriteArrayList<>();
        final AtomicInteger opened = new AtomicInteger();
        final AtomicLong gen = new AtomicLong();

        public PluginManifest manifest() {
            return new PluginManifest("manual", "t", Set.of(), new SourceCapabilities(true, false, false));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.of(doc(ref));
        }

        EntityDocument doc(EntityRef ref) {
            return new EntityDocument(ref, DataNode.of(Map.of("v", gen.get())), new Provenance("manual", gen.get(), Instant.now(), true));
        }

        public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> l) {
            opened.incrementAndGet();
            subs.add(l);
            return () -> subs.remove(l);
        }

        void tick(EntityRef ref) {
            gen.incrementAndGet();
            subs.forEach(s -> s.accept(doc(ref)));
        }
    }

    private TopicHub hub(Manual m) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, null);
        var router = new SourceRouter(new SourceRegistry(List.of(m), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
        return new TopicHub(router, Executors.newScheduledThreadPool(2), new LiveProperties(Duration.ofMillis(30), null, null, null));
    }

    @Test
    void ticksWithinAFrameCoalesceAndTopicsShareOneSourceSubscription() throws Exception {
        Manual m = new Manual();
        try (TopicHub hub = hub(m)) {
            EntityRef ref = EntityRef.of("trade", "T1");
            List<Long> seenA = new CopyOnWriteArrayList<>();
            List<Long> seenB = new CopyOnWriteArrayList<>();
            Subscription a = hub.subscribe(ref, d -> seenA.add(d.provenance().generation()));
            Subscription b = hub.subscribe(ref, d -> seenB.add(d.provenance().generation()));
            assertThat(m.opened).hasValue(1);
            for (int i = 0; i < 20; i++) {
                m.tick(ref);
            }
            Thread.sleep(150);
            assertThat(seenA).hasSizeLessThanOrEqualTo(2).last().isEqualTo(20L);
            assertThat(seenB).last().isEqualTo(20L);
            a.close();
            assertThat(m.subs).hasSize(1);
            b.close();
            assertThat(m.subs).isEmpty();
            assertThat(hub.topicCount()).isZero();
        }
    }

    @Test
    void tenThousandListenersAllReceiveTheLatestGeneration() throws Exception {
        Manual m = new Manual();
        try (TopicHub hub = hub(m)) {
            EntityRef ref = EntityRef.of("trade", "T1");
            int n = 10_000;
            AtomicLong[] last = new AtomicLong[n];
            for (int i = 0; i < n; i++) {
                AtomicLong slot = last[i] = new AtomicLong();
                hub.subscribe(ref, d -> slot.set(d.provenance().generation()));
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                m.tick(ref);
                Thread.sleep(2);
            }
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                boolean all = true;
                for (AtomicLong l : last) {
                    all &= l.get() == 50;
                }
                if (all) {
                    break;
                }
                Thread.sleep(10);
            }
            for (AtomicLong l : last) {
                assertThat(l.get()).isEqualTo(50);
            }
            assertThat((System.nanoTime() - t0) / 1e6).isLessThan(5_000);
        }
    }
}
