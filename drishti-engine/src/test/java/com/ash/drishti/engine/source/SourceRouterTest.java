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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SourceRouterTest {

    /** A plugin holding a fixed set of ids, with an optional delay per read. */
    record Fake(String name, Set<String> kinds, Set<String> ids, long delayMs) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", kinds, new SourceCapabilities(false, false, true));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) throws InterruptedException {
            Thread.sleep(delayMs);
            return ids.contains(ref.id())
                    ? Optional.of(new EntityDocument(ref, DataNode.of(Map.of("from", name)), new Provenance(name, 1, Instant.now(), false)))
                    : Optional.empty();
        }

        public List<EntityHit> search(String kind, String text, int limit) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ids.stream().map(i -> new EntityHit(EntityRef.of("trade", i), i, name)).toList();
        }
    }

    /** A dated plugin, instantiated per named connector (it needs a public no-argument constructor). */
    public static final class Dated implements SourcePlugin {
        private String name = "dated";

        public PluginManifest manifest() {
            return new PluginManifest("dated", "t", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        public void start(SourceContext c) {
            name = c.setting("source-name", "dated");
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, com.ash.drishti.api.AsOf.LATEST);
        }

        public Optional<EntityDocument> fetch(EntityRef ref, com.ash.drishti.api.AsOf asOf) {
            return Optional.of(new EntityDocument(ref, DataNode.of(Map.of("from", name)),
                    new Provenance(name, 1, Instant.now(), false, asOf.businessDate())));
        }
    }

    @Test
    void aPickedDateGoesToDatedConnectorsFirstAndLiveKeepsTheRoute() {
        var connectors = Map.of("lake", new SourcesProperties.ConnectorSettings("dated", true, List.of("trade"), Map.of()));
        var props = new SourcesProperties(Map.of(), "live", Map.of(), Duration.ofMillis(500), null, connectors);
        SourcePlugin live = new Fake("live", Set.of("trade"), Set.of("T-1"), 0);
        var router = new SourceRouter(new SourceRegistry(List.of(live, new Dated()), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
        var past = router.fetch(EntityRef.of("trade", "T-1"), com.ash.drishti.api.AsOf.of(java.time.LocalDate.of(2026, 9, 29))).join();
        assertThat(past.data().get("from").asText()).isEqualTo("lake");
        assertThat(past.provenance().businessDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 29));
        assertThat(router.fetch(EntityRef.of("trade", "T-1")).join().data().get("from").asText()).isEqualTo("live");
    }

    private SourceRouter router(Map<String, String> routes, SourcePlugin... plugins) {
        var props = new SourcesProperties(routes, null, Map.of(), Duration.ofMillis(300), null, null);
        return new SourceRouter(new SourceRegistry(List.of(plugins), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void configuredRouteWinsThenFallsBack() {
        var r = router(Map.of("trade", "b"), new Fake("a", Set.of(), Set.of("T1", "T2"), 0), new Fake("b", Set.of("trade"), Set.of("T1"), 0));
        assertThat(r.fetch(EntityRef.of("trade", "T1")).join().provenance().source()).isEqualTo("b");
        assertThat(r.fetch(EntityRef.of("trade", "T2")).join().provenance().source()).isEqualTo("a");
        assertThat(r.candidates("curve")).extracting(p -> p.manifest().name()).containsExactly("a");
    }

    @Test
    void notFoundTimeoutAndNoSourceAreCoded() {
        var r = router(Map.of(), new Fake("slow", Set.of("trade"), Set.of("T1"), 2000));
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "T1")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e.getCause()).errorCode()).isEqualTo(ErrorCode.SOURCE_TIMEOUT));
        assertThatThrownBy(() -> r.fetch(EntityRef.of("curve", "X")).join())
                .satisfies(e -> assertThat(((DrishtiException) e.getCause()).errorCode()).isEqualTo(ErrorCode.NO_SOURCE_FOR_KIND));
    }

    @Test
    void fetchAllIsPartialAndSearchDropsSlowPlugins() {
        var r = router(Map.of(), new Fake("fast", Set.of(), Set.of("T1"), 0), new Fake("slow", Set.of(), Set.of("T9"), 1000));
        assertThat(r.fetchAll(List.of(EntityRef.of("trade", "T1"), EntityRef.of("trade", "NOPE")), Duration.ofMillis(200)))
                .containsOnlyKeys(EntityRef.of("trade", "T1"));
        long t0 = System.nanoTime();
        assertThat(r.search(null, "", 10, Duration.ofMillis(50))).extracting(EntityHit::title).containsExactly("T1");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
    }

    /** A stream that keeps nothing (Kafka in ticks mode): it serves no read but pushes every update for its kind. */
    static final class Ticks implements SourcePlugin {
        final java.util.List<java.util.function.Consumer<EntityDocument>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        public PluginManifest manifest() {
            return new PluginManifest("ticks", "t", Set.of("trade"), new SourceCapabilities(true, false, false));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public boolean pushes(EntityRef ref) {
            return ref.kind().equals("trade");
        }

        @Override
        public com.ash.drishti.api.Subscription subscribe(EntityRef ref, java.util.function.Consumer<EntityDocument> l) {
            listeners.add(l);
            return () -> listeners.remove(l);
        }
    }

    @Test
    void aTicksOnlyStreamMakesAStoredEntityLiveAndDrivesItsTicks() {
        Ticks ticks = new Ticks();
        var r = router(Map.of(), new Fake("lake", Set.of("trade"), Set.of("T-1"), 0), ticks);
        EntityRef t1 = EntityRef.of("trade", "T-1");
        assertThat(r.fetch(t1).join().data().get("from").asText()).isEqualTo("lake");        // the store answers the read
        assertThat(r.pushes(t1)).isTrue();                                                    // and the stream makes it live
        assertThat(r.pushes(EntityRef.of("curve", "C-1"))).isFalse();
        java.util.List<EntityDocument> got = new java.util.ArrayList<>();
        try (var sub = r.subscribe(t1, got::add)) {
            assertThat(ticks.listeners).hasSize(1);
            ticks.listeners.forEach(l -> l.accept(new EntityDocument(t1, DataNode.of(Map.of("from", "ticks")),
                    new Provenance("ticks", 2, Instant.now(), true))));
        }
        assertThat(got).singleElement().satisfies(d -> assertThat(d.data().get("from").asText()).isEqualTo("ticks"));
        assertThat(ticks.listeners).isEmpty();
    }
}
