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
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.DateCoverage;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Which store answers a dated read (QA 2026-10-01). DATA-12: a store that holds a date is authoritative for it, so an
 * entity it does not list then is not held and the store behind it is not asked; only a date the store does not hold
 * passes on. DATA-15: a read "as known at" an instant from a store that keeps no earlier versions fails naming the
 * store, never showing today's data.
 */
class StoreAuthorityTest {

    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);
    private static final Instant KNOWN = Instant.parse("2020-01-01T00:00:00Z");

    /** A dated store holding {@code ids} on each of {@code days}, with mtm 1 (or 2 for the lake); columns of mtm. */
    record Store(String name, Set<LocalDate> days, Set<String> ids, boolean tells, boolean timeTravel) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, true, true));
        }

        public void start(SourceContext c) {}

        private boolean holds(AsOf asOf) {
            return asOf.businessDate() == null || days.contains(asOf.businessDate());
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.LATEST);
        }

        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            return holds(asOf) && ids.contains(ref.id())
                    ? Optional.of(new EntityDocument(ref, DataNode.of(Map.of("from", name, "mtm", name.equals("lake") ? 2 : 1)),
                            new Provenance(name, 1, Instant.now(), false, asOf.businessDate())))
                    : Optional.empty();
        }

        public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
            return holds(asOf) ? ids.stream().sorted().map(i -> new EntityHit(EntityRef.of("trade", i), i, name)).toList() : List.of();
        }

        public Set<String> columnar(String kind) {
            return Set.of("mtm");
        }

        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            if (!holds(asOf)) {
                return Optional.empty();
            }
            String[] all = ids.stream().sorted().toArray(String[]::new);
            double[] mtm = new double[all.length];
            java.util.Arrays.fill(mtm, name.equals("lake") ? 2 : 1);
            return Optional.of(new ColumnSet(all, Map.of("mtm", mtm), Map.of(), asOf.businessDate()));
        }

        @Override
        public DateCoverage coverage(String kind, AsOf asOf) {
            return !tells ? DateCoverage.UNKNOWN : holds(asOf) ? DateCoverage.HELD : DateCoverage.NOT_HELD;
        }

        @Override
        public boolean timeTravel() {
            return timeTravel;
        }
    }

    /** A dated store that fails on every read (and so cannot tell what it holds). */
    record FailingDated(String name) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            throw new IllegalStateException("bad line");
        }
    }

    private static SourceRouter router(SourcePlugin... stores) {
        var props = new SourcesProperties(Map.of("trade", stores[0].manifest().name()), null, Map.of(), Duration.ofMillis(500), null, Map.of());
        return new SourceRouter(new SourceRegistry(List.of(stores), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** The recent store holds 09-30 and 09-29 and has dropped MX-6; the lake behind it holds every date and MX-6. */
    private static SourceRouter recentInFrontOfTheLake(boolean recentTells, boolean lakeTravels) {
        return router(new Store("recent", Set.of(D29, D30), Set.of("MX-1", "MX-2"), recentTells, false),
                new Store("lake", Set.of(D29, D30, LocalDate.of(2026, 9, 1)), Set.of("MX-1", "MX-2", "MX-6"), true, lakeTravels));
    }

    private static DrishtiException cause(Throwable t) {
        return (DrishtiException) (t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t);
    }

    @Test
    void anEntityTheStoreHoldingTheDateDoesNotListIsNotHeldAndTheLakeIsNotAsked() {
        SourceRouter r = recentInFrontOfTheLake(true, true);
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "MX-6"), AsOf.of(D29)).join())
                .satisfies(e -> {
                    assertThat(cause(e).errorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND);
                    assertThat(cause(e).getMessage()).contains("recent").contains("2026-09-29").contains("trade/MX-6");
                });
        assertThat(r.fetch(EntityRef.of("trade", "MX-1"), AsOf.of(D29)).join().provenance().source()).isEqualTo("recent");
        // the search agrees with the view: the lake's listing of the date is not merged in
        assertThat(r.list("trade", "", 100, Duration.ofSeconds(1), AsOf.of(D29), new SourceFailures()).hits())
                .extracting(h -> h.ref().id()).containsExactly("MX-1", "MX-2");
        assertThat(r.columns("trade", List.of("mtm"), AsOf.of(D29), Duration.ofSeconds(1)).orElseThrow().ids()).containsExactly("MX-1", "MX-2");
    }

    @Test
    void aDateTheStoreDoesNotHoldStillComesFromTheNextStore() {
        SourceRouter r = recentInFrontOfTheLake(true, true);
        LocalDate old = LocalDate.of(2026, 9, 1);
        EntityDocument d = r.fetch(EntityRef.of("trade", "MX-6"), AsOf.of(old)).join();
        assertThat(d.provenance().source()).isEqualTo("lake");
        assertThat(r.list("trade", "", 100, Duration.ofSeconds(1), AsOf.of(old), new SourceFailures()).hits()).extracting(h -> h.ref().id())
                .containsExactly("MX-1", "MX-2", "MX-6");
        assertThat(r.columns("trade", List.of("mtm"), AsOf.of(old), Duration.ofSeconds(1)).orElseThrow().ids()).contains("MX-6");
    }

    @Test
    void aStoreThatCannotTellKeepsTheFallThroughAndAFailingStoreStillStopsTheRead() {
        SourceRouter r = recentInFrontOfTheLake(false, true);
        assertThat(r.fetch(EntityRef.of("trade", "MX-6"), AsOf.of(D29)).join().provenance().source()).isEqualTo("lake");
        var failing = router(new FailingDated("recent"),
                new Store("lake", Set.of(D29), Set.of("MX-6"), true, true));
        assertThatThrownBy(() -> failing.fetch(EntityRef.of("trade", "MX-6"), AsOf.of(D29)).join())
                .satisfies(e -> assertThat(cause(e).errorCode()).isEqualTo(ErrorCode.SOURCE_FAILED));
    }

    @Test
    void knownAtOnAStoreWithoutTimeTravelFailsNamingItNeverWithTodaysData() {
        SourceRouter r = recentInFrontOfTheLake(true, true);
        AsOf known = new AsOf(D29, KNOWN);
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "MX-1"), known).join())
                .satisfies(e -> {
                    DrishtiException x = cause(e);
                    assertThat(x.errorCode()).isEqualTo(ErrorCode.NO_TIME_TRAVEL);
                    assertThat(x.errorCode().httpStatus()).isEqualTo(400);
                    assertThat(x.getMessage()).startsWith("DRS-1007").contains("recent").contains("known at");
                    assertThat(((SourceFailure) x).source()).isEqualTo("recent");
                });
        var failures = new SourceFailures();
        assertThat(r.list("trade", "", 100, Duration.ofSeconds(1), known, failures).hits()).isEmpty();
        assertThat(failures.asMap()).containsOnlyKeys("recent");
        assertThat(failures.asMap().get("recent")).contains("known at");
        var columnFailures = new SourceFailures();
        assertThat(r.columns("trade", List.of("mtm"), known, Duration.ofSeconds(1), columnFailures)).isEmpty();
        assertThat(columnFailures.asMap()).containsOnlyKeys("recent");
        // a date the recent store does not hold passes to the lake, which keeps versions and answers
        AsOf oldKnown = new AsOf(LocalDate.of(2026, 9, 1), KNOWN);
        assertThat(r.fetch(EntityRef.of("trade", "MX-6"), oldKnown).join().provenance().source()).isEqualTo("lake");
        // a store that cannot tell whether it holds the date is not asked either
        SourceRouter unsure = recentInFrontOfTheLake(false, true);
        assertThatThrownBy(() -> unsure.fetch(EntityRef.of("trade", "MX-6"), oldKnown).join())
                .satisfies(e -> assertThat(cause(e).errorCode()).isEqualTo(ErrorCode.NO_TIME_TRAVEL));
    }

    @Test
    void anUndatedSourceIsNotRefusedForKnownAt() {
        var r = router(new SourceRouterTest.Fake("reference", Set.of("trade"), Set.of("MX-1"), 0));
        assertThat(r.fetch(EntityRef.of("trade", "MX-1"), new AsOf(D29, KNOWN)).join().provenance().source()).isEqualTo("reference");
    }
}
