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
package com.ash.drishti.server.liveset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Session isolation (release blocker, owner rule: nothing per person in shared objects): two people with different visibility of the same
 * kind (a row scope by desk) run the same search and open the same declared set. Each sees only their own rows: pages, totals, live
 * changes on their streams and the panel's Raw JSON; a search's set filled for one is never read by the other.
 */
class LiveIsolationTest {

    static final Principal ANN = new Principal("ann", List.of("trader"));
    static final Principal BOB = new Principal("bob", List.of("trader"));

    /** Ann sees desk FX, Bob desk RATES: same roles, a row scope by the person's desk. */
    static LiveReader reader(Principal p, String kind) {
        String desk = "ann".equals(p.user()) ? "FX" : "RATES";
        return new LiveReader(p.securitySignature() + "|desk=" + desk, null,
                new LiveReader.Scope("desk=" + desk, Set.of("DESK"), d -> desk.equals(String.valueOf(d.get("DESK").unwrap()))), false);
    }

    /** Trades of two desks, a search that matches CCY = EUR (it knows nothing of desks), a live side, and optionally a paged store. */
    static final class Trades extends LiveQueryTest.Fake {
        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        volatile boolean paged;

        void trade(String id, String ccy, String desk) {
            Map<String, Object> m = new LinkedHashMap<>(Map.of("TRADE_ID", id, "CCY", ccy, "DESK", desk, "NOTIONAL", (long) id.hashCode() % 100));
            rows.put(id, m);
        }

        void tick(String id, String ccy, String desk) {
            trade(id, ccy, desk);
            EntityDocument d = new EntityDocument(EntityRef.of("trade", id), DataNode.of(new LinkedHashMap<>(rows.get(id))),
                    new Provenance("test", 1, Instant.now(), true));
            listeners.forEach(l -> l.accept(d));
        }

        @Override
        public boolean snapshot(String kind, String query, Consumer<EntityDocument> sink) {
            rows.forEach((id, m) -> sink.accept(new EntityDocument(EntityRef.of("trade", id), DataNode.of(new LinkedHashMap<>(m)),
                    new Provenance("lake", 1, Instant.now(), false))));
            return true;
        }

        @Override
        public Map<String, Object> search(String command, AsOf asOf, Principal who, UnaryOperator<DataNode> mask, Consumer<EntityDocument> sink) {
            searches.incrementAndGet();
            rows.forEach((id, m) -> {
                if ("EUR".equals(m.get("CCY"))) {
                    sink.accept(new EntityDocument(EntityRef.of("trade", id), mask.apply(DataNode.of(new LinkedHashMap<>(m))),
                            new Provenance("search", 0, Instant.now(), false)));
                }
            });
            Map<String, Object> said = new LinkedHashMap<>();
            said.put("kind", "trade");
            said.put("columns", List.of("$.TRADE_ID", "$.DESK", "$.CCY"));
            said.put("nodes", List.of(Map.of("nodeId", "n1")));
            return said;
        }

        @Override
        public Optional<StorePages.Store> store(String kind) {
            if (!paged) {
                return Optional.empty();
            }
            return Optional.of(new StorePages.Store() {
                @Override
                public String version() {
                    return "v1";
                }

                @Override
                public boolean project(List<String> fields, StorePages.Projection sink) {
                    rows.forEach((id, m) -> sink.row(id, fields.stream().map(m::get).toArray()));
                    return true;
                }

                @Override
                public Map<String, DataNode> fetch(Collection<String> ids) {
                    Map<String, DataNode> out = new LinkedHashMap<>();
                    ids.forEach(id -> {
                        if (rows.containsKey(id)) {
                            out.put(id, DataNode.of(new LinkedHashMap<>(rows.get(id))));
                        }
                    });
                    return out;
                }
            });
        }

        @Override
        public Optional<List<String>> fields(String query) {
            return Optional.of(List.of("CCY"));
        }
    }

    static Trades trades() {
        Trades t = new Trades();
        t.trade("T1", "EUR", "FX");
        t.trade("T2", "EUR", "RATES");
        t.trade("T3", "USD", "FX");
        t.trade("T4", "EUR", "FX");
        return t;
    }

    static List<String> ids(LiveSet.Page p) {
        return p.rows().stream().map(r -> String.valueOf(r.get("id"))).sorted().toList();
    }

    static List<String> upserted(List<Map<String, Object>> events) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            if (e.get("upserts") instanceof List<?> l) {
                l.forEach(u -> out.add(String.valueOf(((Map<?, ?>) u).get("id"))));
            }
        }
        return out;
    }

    @Test
    void theSameSearchGivesEachAudienceItsOwnSetAndNeitherReadsTheOthers() throws Exception {
        Trades t = trades();
        try (LiveSets sets = new LiveSets(t, Clock.systemUTC(), 20, 100).visibility(LiveIsolationTest::reader)) {
            LiveReader ann = sets.reader(ANN, "trade");
            LiveReader bob = sets.reader(BOB, "trade");
            LiveSet a = sets.query(LiveQueryTest.Q, AsOf.LATEST, ANN, ann, false);
            LiveSet b = sets.query(LiveQueryTest.Q, AsOf.LATEST, BOB, bob, false);
            assertThat(b).isNotSameAs(a);
            assertThat(b.spec().id()).isNotEqualTo(a.spec().id());
            assertThat(ids(a.page(0, 100, null, false, null, ann))).containsExactly("T1", "T4");
            assertThat(ids(b.page(0, 100, null, false, null, bob))).containsExactly("T2");
            // the other audience's set: refused as if it did not exist, and empty even if read past the check
            assertThatThrownBy(() -> sets.requireAudience(b.spec().id(), ann)).isInstanceOf(DrishtiException.class).hasMessageContaining("no live set");
            sets.requireAudience(a.spec().id(), ann);
            assertThat(b.page(0, 100, null, false, null, ann).total()).isZero();
            // the panel's Raw JSON
            assertThat(a.row("T2", ann)).isEmpty();
            assertThat(b.row("T2", bob)).isPresent();
            assertThat(b.row("T1", bob)).isEmpty();
            // live changes: a new RATES trade joins Bob's set only; a FX trade moved to RATES leaves Ann's
            List<Map<String, Object>> annEvents = new CopyOnWriteArrayList<>();
            List<Map<String, Object>> bobEvents = new CopyOnWriteArrayList<>();
            LiveSet.Window aw = a.window(ann, annEvents::add, 100);
            LiveSet.Window bw = b.window(bob, bobEvents::add, 100);
            t.tick("T5", "EUR", "RATES");
            t.tick("T4", "EUR", "RATES");
            aw.flush();
            bw.flush();
            assertThat(upserted(annEvents)).doesNotContain("T5", "T4");
            assertThat(upserted(bobEvents)).contains("T5", "T4");
            assertThat(ids(a.page(0, 100, null, false, null, ann))).containsExactly("T1");
            assertThat(ids(b.page(0, 100, null, false, null, bob))).containsExactly("T2", "T4", "T5");
            assertThat(annEvents.get(annEvents.size() - 1).get("total")).isEqualTo(1);
            assertThat(a.row("T5", ann)).isEmpty();
        }
    }

    @Test
    void whichNodeAnsweredIsKeptFromPeopleWhoAreNotAdministrators() throws Exception {
        Trades t = trades();
        try (LiveSets sets = new LiveSets(t, Clock.systemUTC(), 20, 100)) {
            Principal admin = new Principal("ada", List.of("admin"));
            LiveSet forAdmin = sets.query(LiveQueryTest.Q, AsOf.LATEST, admin, sets.reader(admin, "trade"), false);
            LiveSet forAnn = sets.query(LiveQueryTest.Q, AsOf.LATEST, ANN, sets.reader(ANN, "trade"), false);
            assertThat(forAnn).isNotSameAs(forAdmin);                          // the roles are part of the audience
            assertThat(sets.reader(ANN, "trade").admin()).isFalse();
            assertThat(sets.reader(admin, "trade").admin()).isTrue();
        }
    }

    @Test
    void aDeclaredSetIsSharedButEveryReadKeepsToTheReadersScope() throws Exception {
        for (boolean paged : new boolean[] {false, true}) {
            Trades t = trades();
            t.paged = paged;
            try (LiveSets sets = new LiveSets(t, Clock.systemUTC(), 20, 100).visibility(LiveIsolationTest::reader)) {
                LiveSetSpec spec = LiveSetSpec.parse(Map.of("blotter", "eur-trades", "kind", "trade", "query", "trade where CCY = 'EUR'",
                        "columns", List.of("TRADE_ID", "DESK", "CCY")), "eur-trades.blotter.yaml", null);
                sets.add(spec);
                LiveReader ann = sets.reader(ANN, "trade");
                LiveReader bob = sets.reader(BOB, "trade");
                LiveSet s = sets.open(spec, null);
                assertThat(sets.open(spec, null)).isSameAs(s);                // one set, shared
                assertThat(s.paged()).isEqualTo(paged);
                assertThat(ids(s.page(0, 100, null, false, null, ann))).as("paged=" + paged).containsExactly("T1", "T4");
                assertThat(s.page(0, 100, null, false, null, ann).total()).isEqualTo(2);
                assertThat(ids(s.page(0, 100, null, false, null, bob))).containsExactly("T2");
                assertThat(s.page(0, 100, null, false, "rates", ann).total()).isZero();   // a filter never reaches past the scope
                assertThat(s.row("T2", ann)).isEmpty();
                assertThat(s.row("T2", bob)).isPresent();
                List<Map<String, Object>> annEvents = new CopyOnWriteArrayList<>();
                List<Map<String, Object>> bobEvents = new CopyOnWriteArrayList<>();
                LiveSet.Window aw = s.window(ann, annEvents::add, 100);
                LiveSet.Window bw = s.window(bob, bobEvents::add, 100);
                t.tick("T6", "EUR", "RATES");
                aw.flush();
                bw.flush();
                assertThat(upserted(annEvents)).isEmpty();
                assertThat(upserted(bobEvents)).containsExactly("T6");
                assertThat(annEvents.get(0).get("total")).isEqualTo(2);
                assertThat(bobEvents.get(0).get("total")).isEqualTo(2);
                assertThat(s.total(ann)).isEqualTo(2);
                assertThat(s.row("T6", ann)).isEmpty();
                assertThat(s.row("T6", bob)).isPresent();
            }
        }
    }
}
