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

    private SourceRouter router(Map<String, String> routes, SourcePlugin... plugins) {
        var props = new SourcesProperties(routes, null, Map.of(), Duration.ofMillis(300), null);
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
}
