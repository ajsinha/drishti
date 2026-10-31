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
package com.ash.drishti.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * History of an entity a Drishti*net peer holds (QA1-01, QA1-02): the cache follows the peers' data versions, so a load on the peer shows
 * at once; a peer that cannot be reached is named (DRS-5052), never "no data", and such an answer is never cached.
 */
class HistoryPeersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T20:00:00Z"), ZoneOffset.UTC);

    /** A dated store with trades from 09-22 up to {@code last}; {@code ids} are the trades it holds. */
    static class Store implements SourcePlugin {
        final String name;
        final Set<String> ids;
        volatile LocalDate last;
        volatile String version;

        Store(String name, Set<String> ids, LocalDate last, String version) {
            this.name = name;
            this.ids = ids;
            this.last = last;
            this.version = version;
        }

        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        public void start(SourceContext c) {}

        public Optional<LocalDate> latestDate(String kind) {
            return Optional.of(last);
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.of(last));
        }

        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            LocalDate d = asOf.businessDate() == null ? last : asOf.businessDate();
            if (!ids.contains(ref.id()) || d.isBefore(LocalDate.of(2026, 9, 22)) || d.isAfter(last) || d.getDayOfWeek().getValue() > 5) {
                return Optional.empty();
            }
            return Optional.of(new EntityDocument(ref, DataNode.of(Map.of("TRADE_ID", ref.id(), "QUANTITY", d.getDayOfMonth())),
                    new Provenance(name, 1, Instant.now(), false, d)));
        }

        public Optional<String> dataVersion(String kind) {
            return Optional.ofNullable(version);
        }
    }

    /** One peer that the fallback names while it is up; while it is down it is a down holder of trade. */
    static final class Peers implements SourceRouter.Fallback {
        final Store peer;
        volatile boolean up = true;

        Peers(Store peer) {
            this.peer = peer;
        }

        public List<SourcePlugin> peers(EntityRef ref, AsOf asOf) {
            return up ? List.of(peer) : List.of();
        }

        public Optional<String> version(EntityRef ref, AsOf asOf) {
            return Optional.of(up ? "net:p{" + peer.version + "}" : "");
        }

        public List<String> downHolders(String kind) {
            return up ? List.of() : List.of("p (suspect)");
        }

        /** The oldest date the down peer's digest said it holds (null: it could not say). */
        volatile LocalDate heldFrom;

        public List<String> downHolders(String kind, LocalDate date) {
            return heldFrom != null && date.isBefore(heldFrom) ? List.of() : downHolders(kind);
        }

        public Optional<DrishtiException> unreachable(EntityRef ref) {
            return up ? Optional.empty() : Optional.of(new DrishtiException(ErrorCode.NET_PEER_FAILED, "no source here holds " + ref
                    + ", and the nodes that hold trade cannot be reached now: p (suspect)"));
        }
    }

    private final Store local = new Store("lake", Set.of("T-1"), TODAY, "L1");
    private final Store remote = new Store("net:p", Set.of("T-9", "T-8"), LocalDate.of(2026, 9, 30), "P1");
    private final Peers peers = new Peers(remote);

    private HistoryService service() {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, Map.of());
        SourceRouter router = new SourceRouter(new SourceRegistry(List.of(local), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
        router.fallback(peers);
        BusinessDates dates = new BusinessDates(new BusinessDateProperties("USNY", "UTC", null), CLOCK);
        return new HistoryService(router, dates, HistoryProperties.defaults(), new SearchProperties(null, null, Map.of("trade", List.of("TRADE_ID"))),
                Formats.defaults(), new ElCompiler(), HistorySpec::defaults, k -> "TRD");
    }

    private static HistoryService.Query days(String id, int n) {
        return new HistoryService.Query("trade", id, n, null, null, null, Set.of(), false, null, false, null);
    }

    private static String last(HistoryAnswer a) {
        return a.rows().get(a.rows().size() - 1).date();
    }

    @Test
    void aLoadOnThePeerShowsAtOnce() {
        HistoryService svc = service();
        HistoryAnswer first = svc.read(days("T-9", 3), HistoryService.Viewer.NONE);
        assertThat(last(first)).isEqualTo("2026-09-30");
        assertThat(first.cache()).isEqualTo("miss");
        assertThat(first.dataVersion()).contains("lake=L1").contains("net:p{P1}");
        assertThat(svc.read(days("T-9", 3), HistoryService.Viewer.NONE).cache()).isEqualTo("hit");
        // the peer loads 10-01: its digest carries the new version, and the history shows the new day (the local version is unchanged)
        remote.last = LocalDate.of(2026, 10, 1);
        remote.version = "P2";
        HistoryAnswer after = svc.read(days("T-9", 3), HistoryService.Viewer.NONE);
        assertThat(after.cache()).isEqualTo("miss");
        assertThat(last(after)).isEqualTo("2026-10-01");
    }

    @Test
    void aPeerThatCannotBeReachedIsNamedAndNothingIsCached() {
        HistoryService svc = service();
        peers.up = false;
        assertThatThrownBy(() -> svc.read(days("T-8", 3), HistoryService.Viewer.NONE)).isInstanceOfSatisfying(DrishtiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.NET_PEER_FAILED);
            assertThat(e.getMessage()).contains("p (suspect)");
        });
        assertThatThrownBy(() -> svc.read(days("T-8", 3), HistoryService.Viewer.NONE))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NET_PEER_FAILED));
        peers.up = true;                                                             // back: the rows, not a remembered failure
        HistoryAnswer back = svc.read(days("T-8", 3), HistoryService.Viewer.NONE);
        assertThat(back.rows()).hasSize(3);
        assertThat(back.cache()).isEqualTo("miss");
    }

    @Test
    void aPeerThatTimesOutIsATimeOutNotAFailure() {
        // QA1-02, QA1-19: a peer's HTTP time-out is DRS-1014 naming the node (DATA_ARCHITECTURE.md › History queries: an entity over time, section 16), not DRS-1003
        Store slow = new Store("net:p", Set.of("T-7"), TODAY, "P1") {
            @Override
            public com.ash.drishti.api.HistoryRead history(com.ash.drishti.api.HistoryRequest r) throws Exception {
                throw new java.net.http.HttpTimeoutException("request timed out");
            }
        };
        Peers timing = new Peers(slow);
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, Map.of());
        SourceRouter router = new SourceRouter(new SourceRegistry(List.of(local), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
        router.fallback(timing);
        HistoryService svc = new HistoryService(router, new BusinessDates(new BusinessDateProperties("USNY", "UTC", null), CLOCK),
                HistoryProperties.defaults(), new SearchProperties(null, null, Map.of("trade", List.of("TRADE_ID"))), Formats.defaults(),
                new ElCompiler(), HistorySpec::defaults, k -> "TRD");
        assertThatThrownBy(() -> svc.read(days("T-7", 3), HistoryService.Viewer.NONE)).isInstanceOfSatisfying(DrishtiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.HISTORY_TIMEOUT);
            assertThat(e.getMessage()).contains("the node p did not answer");
        });
    }

    @Test
    void aLocalEntityWithAPeerDownIsPartialOnlyWhenDaysAreLeft() {
        HistoryService svc = service();
        peers.up = false;
        HistoryAnswer five = svc.read(days("T-1", 5), HistoryService.Viewer.NONE);   // the five days are here: nothing is left to ask
        assertThat(five.partial()).isFalse();
        HistoryAnswer span = svc.read(new HistoryService.Query("trade", "T-1", null, null, "2026-09-15", null, Set.of(), false, null, false, null),
                HistoryService.Viewer.NONE);
        assertThat(span.partial()).isTrue();
        assertThat(span.problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo(ErrorCode.NET_PEER_FAILED.code());
            assertThat(p.message()).contains("p (suspect)");
        });
        assertThat(svc.read(new HistoryService.Query("trade", "T-1", null, null, "2026-09-15", null, Set.of(), false, null, false, null),
                HistoryService.Viewer.NONE).cache()).isEqualTo("miss");
    }

    @Test
    void aDownPeerCountsOnlyForTheDatesItMayHold() {
        // SOAK-01: the peer's data starts on 09-22, so the days 09-15 to 09-21 cannot be there: the answer is complete
        HistoryService svc = service();
        peers.up = false;
        peers.heldFrom = LocalDate.of(2026, 9, 22);
        HistoryService.Query span = new HistoryService.Query("trade", "T-1", null, null, "2026-09-15", null, Set.of(), false, null, false, null);
        HistoryAnswer complete = svc.read(span, HistoryService.Viewer.NONE);
        assertThat(complete.partial()).isFalse();
        assertThat(complete.problems()).isEmpty();
        // its data starts on 09-17: only the business days from 09-17 to the local data (09-22) may be there, and only they are named
        peers.heldFrom = LocalDate.of(2026, 9, 17);
        HistoryAnswer some = service().read(span, HistoryService.Viewer.NONE);
        assertThat(some.partial()).isTrue();
        assertThat(some.problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo(ErrorCode.NET_PEER_FAILED.code());
            assertThat(p.days()).containsExactly("2026-09-17", "2026-09-18", "2026-09-21");
        });
    }

    /** A service over {@link #local} whose fallback asks {@code peers} in order, with no node known to be down. */
    private HistoryService serviceWith(List<SourcePlugin> asked) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, Map.of());
        SourceRouter router = new SourceRouter(new SourceRegistry(List.of(local), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
        router.fallback(new SourceRouter.Fallback() {
            public List<SourcePlugin> peers(EntityRef ref, AsOf asOf) {
                return asked;
            }
        });
        return new HistoryService(router, new BusinessDates(new BusinessDateProperties("USNY", "UTC", null), CLOCK), HistoryProperties.defaults(),
                new SearchProperties(null, null, Map.of("trade", List.of("TRADE_ID"))), Formats.defaults(), new ElCompiler(), HistorySpec::defaults,
                k -> "TRD");
    }

    /** A peer whose history call fails with {@code e}, as a node that just died (or is not serving yet) does. */
    private static Store failing(Exception e) {
        return new Store("net:dead", Set.of("T-6"), TODAY, "D1") {
            @Override
            public com.ash.drishti.api.HistoryRead history(com.ash.drishti.api.HistoryRequest r) throws Exception {
                throw e;
            }
        };
    }

    @Test
    void aPeerThatRefusesTheConnectionIsDrs5052NamingItNotDrs1003() {
        // SOAK-02: the peer died a moment ago (no gossip has said so yet): DRS-5052 naming it, as a view says, never DRS-1003
        Store dead = failing(new java.net.ConnectException("Connection refused"));
        assertThatThrownBy(() -> serviceWith(List.of(dead)).read(days("T-6", 3), HistoryService.Viewer.NONE))
                .isInstanceOfSatisfying(DrishtiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.NET_PEER_FAILED);
                    assertThat(e.getMessage()).contains("dead (not answering").doesNotContain("DRS-1003");
                });
        // a peer that answers with a failure of its own is about that node too: DRS-5052, the server that answered is not at fault
        Store broken = failing(new java.io.IOException("HTTP 500 boom"));
        assertThatThrownBy(() -> serviceWith(List.of(broken)).read(days("T-6", 3), HistoryService.Viewer.NONE))
                .isInstanceOfSatisfying(DrishtiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.NET_PEER_FAILED);
                    assertThat(e.getMessage()).contains("the node dead that holds trade failed reading");
                });
        // a local entity whose earlier days were on the refusing peer: a partial answer whose problem is DRS-5052
        HistoryAnswer partial = serviceWith(List.of(dead)).read(new HistoryService.Query("trade", "T-1", null, null, "2026-09-15", null, Set.of(),
                false, null, false, null), HistoryService.Viewer.NONE);
        assertThat(partial.partial()).isTrue();
        assertThat(partial.problems()).allSatisfy(p -> assertThat(p.code()).isEqualTo(ErrorCode.NET_PEER_FAILED.code()));
    }

    @Test
    void aPeerThatRefusesTheConnectionIsPassedForTheNextHolder() {
        // SOAK-03: the first holder is not serving yet; the second holds the same trade: a complete answer, no problem
        Store dead = failing(new java.net.ConnectException("Connection refused"));
        Store alive = new Store("net:p", Set.of("T-6"), TODAY, "P1");
        HistoryAnswer a = serviceWith(List.of(dead, alive)).read(days("T-6", 3), HistoryService.Viewer.NONE);
        assertThat(a.partial()).isFalse();
        assertThat(a.rows()).hasSize(3);
    }
}
