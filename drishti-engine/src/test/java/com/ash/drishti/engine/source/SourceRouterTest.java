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

import com.ash.drishti.api.AsOf;
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
import java.time.LocalDate;
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

    @Test
    void connectorsServingOneKindAreAskedInConfigOrderAfterTheRoute() {
        for (int round = 0; round < 2; round++) {          // a second build is a reload: same order again
            var cfg = new java.util.LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
            for (String n : List.of("zulu", "alpha", "mike", "bravo", "kilo", "charlie", "yankee")) {
                cfg.put(n, new SourcesProperties.ConnectorSettings("dated", true, List.of("trade"), Map.of()));
            }
            var props = new SourcesProperties(Map.of("trade", "mike"), null, Map.of(), Duration.ofMillis(500), null, cfg);
            var router = new SourceRouter(new SourceRegistry(List.of(new Dated()), props, new JsonCodec()), props,
                    Executors.newVirtualThreadPerTaskExecutor());
            assertThat(router.candidates("trade")).extracting(p -> p.manifest().name())
                    .containsExactly("mike", "zulu", "alpha", "bravo", "kilo", "charlie", "yankee");
        }
    }

    @Test
    void boundConfigKeepsTheWrittenConnectorOrder() {
        var src = new java.util.LinkedHashMap<String, Object>();
        for (String n : List.of("s9", "s1", "s5", "s3", "s7", "s2")) {
            src.put("drishti.sources.connectors." + n + ".plugin", "dated");
            src.put("drishti.sources.connectors." + n + ".kinds[0]", "trade");
        }
        var props = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(src))
                .bind("drishti.sources", SourcesProperties.class).get();
        assertThat(props.connectors().keySet()).containsExactly("s9", "s1", "s5", "s3", "s7", "s2");
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

    /** A source whose last new data is an hour old. */
    public static final class Quiet implements SourcePlugin {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("quiet", "t", Set.of("trade"), new SourceCapabilities(true, false, false));
        }

        @Override
        public void start(SourceContext c) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public java.time.Instant lastUpdate() {
            return Instant.now().minusSeconds(3600);
        }
    }

    @Test
    void aSourceWithNothingNewForLongerThanItsStaleAfterIsStale() {
        var connectors = Map.of(
                "stream", new SourcesProperties.ConnectorSettings("quiet", true, List.of("trade"),
                        Map.of("stale-after", "15m")),
                "patient", new SourcesProperties.ConnectorSettings("quiet", true, List.of("trade"), Map.of("stale-after", "2h")),
                "silent", new SourcesProperties.ConnectorSettings("quiet", true, List.of("trade"), Map.of()));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofMillis(300), null, connectors);
        var registry = new SourceRegistry(List.of(new Quiet()), props, new JsonCodec());
        assertThat(registry.freshness("stream").stale()).isTrue();
        assertThat(registry.freshness("stream").staleAfter()).isEqualTo(Duration.ofMinutes(15));
        assertThat(registry.freshness("patient").stale()).isFalse();
        assertThat(registry.freshness("silent").stale()).isFalse();                  // no stale-after: never stale
        assertThat(registry.freshness("silent").lastUpdate()).isNotNull();
        assertThat(registry.freshness("nobody").lastUpdate()).isNull();
    }

    /** Columns for one date only, like a store that keeps recent history. */
    public static final class Recent implements SourcePlugin {
        private final String name;
        private final LocalDate holds;

        public Recent(String name, LocalDate holds) {
            this.name = name;
            this.holds = holds;
        }

        @Override
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        @Override
        public void start(SourceContext c) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public Set<String> columnar(String kind) {
            return Set.of("mtm");
        }

        @Override
        public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) {
            if (!holds.equals(asOf.businessDate())) {
                return Optional.empty();
            }
            return Optional.of(new com.ash.drishti.api.ColumnSet(new String[] {name}, Map.of("mtm", new double[] {1}), Map.of(), holds));
        }
    }

    @Test
    void aSearchOnADateOneStoreDoesNotHoldReadsTheNextThatDoes() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        LocalDate old = LocalDate.of(2019, 12, 5);
        var props = new SourcesProperties(Map.of("trade", "recent"), null, Map.of(), Duration.ofMillis(300), null, Map.of());
        var registry = new SourceRegistry(List.of(new Recent("recent", today), new Recent("history", old)), props, new JsonCodec());
        var router = new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
        assertThat(router.columns("trade", List.of("mtm"), AsOf.of(today), Duration.ofSeconds(2)).orElseThrow().ids()).containsExactly("recent");
        assertThat(router.columns("trade", List.of("mtm"), AsOf.of(old), Duration.ofSeconds(2)).orElseThrow().ids()).containsExactly("history");
        assertThat(router.columns("trade", List.of("mtm"), AsOf.of(LocalDate.of(2001, 1, 2)), Duration.ofSeconds(2))).isEmpty();
    }

    /** A store in front of another that fails (throws) on every read, with {@code failure}. */
    record Failing(String name, RuntimeException failure) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, true));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            throw failure;
        }
    }

    /**
     * DATA-03 / DATA-13: a store that fails is not one that does not hold the entity: the read stops with DRS-1003 naming
     * it, and the next store (with other data) is not asked; what the store says the reader can act on is in the error.
     */
    @Test
    void aFailingStoreStopsTheReadNamingItAndTheNextStoreIsNotAsked() {
        var behind = new Fake("lake", Set.of("trade"), Set.of("T1"), 0);
        var r = router(Map.of("trade", "recent"), new Failing("recent", new IllegalStateException("/data/recent/trade.jsonl: bad line")), behind);
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "T1")).join()).hasCauseInstanceOf(SourceFailure.class)
                .satisfies(e -> {
                    SourceFailure f = (SourceFailure) e.getCause();
                    assertThat(f.errorCode()).isEqualTo(ErrorCode.SOURCE_FAILED);
                    assertThat(f.source()).isEqualTo("recent");
                    assertThat(f.getMessage()).isEqualTo("DRS-1003 recent failed reading trade/T1").doesNotContain("/data/recent");
                });
        var lz4 = router(Map.of("trade", "lake"), new Failing("lake", new com.ash.drishti.api.UnreadableData(
                "trade 2026-09-30 cannot be read: the native Delta engine does not decompress LZ4 Parquet pages", new IllegalStateException())));
        assertThatThrownBy(() -> lz4.fetch(EntityRef.of("trade", "T1")).join()).cause().hasMessage(
                "DRS-1003 lake failed reading trade/T1: trade 2026-09-30 cannot be read: the native Delta engine does not decompress LZ4 Parquet pages");
        var failures = new SourceFailures();
        assertThat(lz4.fetchAll(List.of(EntityRef.of("trade", "T1")), Duration.ofMillis(200), AsOf.LATEST, failures)).isEmpty();
        assertThat(failures.asMap()).containsOnlyKeys("lake");
    }
}
