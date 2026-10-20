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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HistoryDay;
import com.ash.drishti.api.HistoryReads;
import com.ash.drishti.api.HistoryRequest;
import com.ash.drishti.api.PluginManifest;
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
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** {@link SourceRouter#history}: connectors in read order split a range by coverage; failures stay failures; knownAt needs time travel. */
class SourceRouterHistoryTest {

    private static final LocalDate D25 = LocalDate.of(2026, 9, 25);
    private static final LocalDate D28 = LocalDate.of(2026, 9, 28);
    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);
    private static final EntityRef MX = EntityRef.of("trade", "MX-1");

    private static List<LocalDate> week() {
        return List.of(D25, D28, D29, D30);
    }

    private static SourceRouter router(SourcePlugin... stores) {
        var props = new SourcesProperties(Map.of("trade", stores[0].manifest().name()), null, Map.of(), Duration.ofMillis(500), null, Map.of());
        return new SourceRouter(new SourceRegistry(List.of(stores), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static HistoryRequest request() {
        return HistoryRequest.of(MX, D25, D30).withDates(week());
    }

    @Test
    void theRecentStoreAnswersItsDatesAndTheLakeTheRest() {
        var recent = new StoreAuthorityTest.Store("recent", Set.of(D29, D30), Set.of("MX-1"), true, false);
        var lake = new StoreAuthorityTest.Store("lake", Set.of(D25, D28, D29, D30), Set.of("MX-1"), true, true);
        SourceHistory.Routed r = router(recent, lake).history(request());
        assertThat(r.days()).extracting(HistoryDay::date).containsExactly(D25, D28, D29, D30);
        assertThat(r.days()).extracting(d -> d.document().provenance().source()).containsExactly("lake", "lake", "recent", "recent");
        assertThat(r.failures()).isEmpty();
        assertThat(r.asked()).containsExactly("recent", "lake");
    }

    @Test
    void aStoreThatHoldsADateIsAuthoritativeForIt() {
        var recent = new StoreAuthorityTest.Store("recent", Set.of(D29, D30), Set.of(), true, false);   // dropped MX-1
        var lake = new StoreAuthorityTest.Store("lake", Set.of(D25, D28, D29, D30), Set.of("MX-1"), true, true);
        SourceHistory.Routed r = router(recent, lake).history(request());
        assertThat(r.days()).extracting(HistoryDay::date).containsExactly(D25, D28);
    }

    @Test
    void theLastDaysWithDataAreCutAfterEveryStoreAnswered() {
        var recent = new StoreAuthorityTest.Store("recent", Set.of(D30), Set.of("MX-1"), true, false);
        var lake = new StoreAuthorityTest.Store("lake", Set.of(D25, D28, D29, D30), Set.of("MX-1"), true, true);
        SourceHistory.Routed r = router(recent, lake).history(request().withLastDays(2));
        assertThat(r.days()).extracting(HistoryDay::date).containsExactly(D29, D30);
    }

    @Test
    void aFailingStoreKeepsItsDatesFailed() {
        SourceHistory.Routed r = router(new StoreAuthorityTest.FailingDated("broken"),
                new StoreAuthorityTest.Store("lake", Set.of(D25, D28, D29, D30), Set.of("MX-1"), true, true)).history(request());
        assertThat(r.days()).isEmpty();
        assertThat(r.failures()).extracting(SourceHistory.Failure::source).containsOnly("broken");
        assertThat(r.failures()).extracting(SourceHistory.Failure::date).containsExactly(D25, D28, D29, D30);
    }

    @Test
    void knownAtNeedsTimeTravel() {
        var recent = new StoreAuthorityTest.Store("recent", Set.of(D29, D30), Set.of("MX-1"), true, false);
        HistoryRequest known = new HistoryRequest(MX, D25, D30, week(), Set.of(), Instant.parse("2026-09-30T00:00:00Z"), 0, null, 0);
        assertThatThrownBy(() -> router(recent).history(known)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.NO_TIME_TRAVEL));
        var lake = new StoreAuthorityTest.Store("lake", Set.of(D25, D28, D29, D30), Set.of("MX-1"), true, true);
        assertThat(router(lake).history(known).days()).hasSize(4);
    }

    @Test
    void aSlowStoreGivesTimedOutDates() {
        SourcePlugin slow = new SourcePlugin() {
            public PluginManifest manifest() {
                return new PluginManifest("slow", "t", Set.of("trade"), new SourceCapabilities(false, false, false, true));
            }

            public void start(SourceContext c) {}

            public Optional<EntityDocument> fetch(EntityRef ref) {
                return Optional.empty();
            }

            public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
                Thread.sleep(2_000);
                return Optional.empty();
            }
        };
        long t0 = System.nanoTime();
        SourceHistory.Routed r = router(slow).history(request().withBudget(Duration.ofMillis(200)));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1_500));
        assertThat(r.failures()).isNotEmpty().allSatisfy(f -> assertThat(f.reason()).isEqualTo(HistoryReads.TIMED_OUT));
    }

    @Test
    void anUndatedKindKeepsNoHistory() {
        SourcePlugin undated = new SourcePlugin() {
            public PluginManifest manifest() {
                return new PluginManifest("api", "t", Set.of("trade"), new SourceCapabilities(false, false, false, false));
            }

            public void start(SourceContext c) {}

            public Optional<EntityDocument> fetch(EntityRef ref) {
                return Optional.empty();
            }
        };
        SourceRouter router = router(undated);
        assertThat(router.keepsHistory(MX, D30)).isFalse();
        assertThat(router.undatedSources("trade")).containsExactly("api");
        assertThat(router.history(request()).days()).isEmpty();
    }
}
