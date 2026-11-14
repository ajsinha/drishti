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
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A search's set (a FORM's result, the search page's): the search's rows in its order and its summary; ticking over a kind with a live
 * binding on the live date, static otherwise; read afresh when run again; one set per masking, and a masked value never picks, orders or
 * filters rows.
 */
class LiveQueryTest {

    /** A kind of trades whose search matches CCY = EUR, ordered by NOTIONAL descending, with an optional live side. */
    static final class Fake implements LiveSets.Sources {
        final List<Map<String, Object>> store = new CopyOnWriteArrayList<>();
        final List<Consumer<EntityDocument>> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger searches = new AtomicInteger();
        volatile boolean liveBinding = true;

        void put(String id, String ccy, long notional, String cpty) {
            store.removeIf(m -> id.equals(m.get("TRADE_ID")));
            store.add(Map.of("TRADE_ID", id, "CCY", ccy, "NOTIONAL", notional, "CPTY", cpty));
        }

        void tick(String id, String ccy, long notional, String cpty) {
            put(id, ccy, notional, cpty);
            EntityDocument d = new EntityDocument(EntityRef.of("trade", id),
                    DataNode.of(new LinkedHashMap<>(Map.of("TRADE_ID", id, "CCY", ccy, "NOTIONAL", notional, "CPTY", cpty))),
                    new Provenance("test", 1, Instant.now(), true));
            listeners.forEach(l -> l.accept(d));
        }

        @Override
        public boolean snapshot(String kind, String query, Consumer<EntityDocument> sink) {
            return false;
        }

        @Override
        public Subscription subscribe(String kind, Consumer<EntityDocument> listener) {
            if (!liveBinding) {
                return Subscription.NONE;
            }
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public Predicate<DataNode> condition(String query) {
            return d -> "EUR".equals(String.valueOf(d.get("CCY").unwrap()));
        }

        @Override
        public String kindOf(String command) {
            return "trade";
        }

        @Override
        public Map<String, Object> search(String command, AsOf asOf, com.ash.drishti.kernel.api.security.Principal who, UnaryOperator<DataNode> mask,
                Consumer<EntityDocument> sink) {
            searches.incrementAndGet();
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Map<String, Object> m : store) {
                if (condition(command).test(mask.apply(DataNode.of(new LinkedHashMap<>(m))))) {
                    hits.add(m);
                }
            }
            hits.sort((a, b) -> Long.compare((Long) b.get("NOTIONAL"), (Long) a.get("NOTIONAL")));
            hits.forEach(m -> sink.accept(new EntityDocument(EntityRef.of("trade", String.valueOf(m.get("TRADE_ID"))),
                    mask.apply(DataNode.of(new LinkedHashMap<>(m))), new Provenance("search", 0, Instant.now(), false))));
            Map<String, Object> said = new LinkedHashMap<>();
            said.put("kind", "trade");
            said.put("mnemonic", "TRD");
            said.put("columns", List.of("$.CCY", "$.NOTIONAL", "$.CPTY"));
            said.put("labels", Map.of("$.CCY", "Ccy", "$.NOTIONAL", "Notional", "$.CPTY", "Counterparty"));
            said.put("matched", hits.size());
            said.put("scanned", store.size());
            return said;
        }
    }

    /** Masks CPTY, as a person without the raw right sees it. */
    static final UnaryOperator<DataNode> MASK = d -> {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d.unwrap() instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> m.put(String.valueOf(k), "CPTY".equals(k) ? "***" : v));
        }
        return DataNode.of(m);
    };

    static final String Q = "TRD where CCY = 'EUR' order by NOTIONAL desc limit 100";

    static List<String> ids(LiveSet.Page p) {
        return p.rows().stream().map(r -> String.valueOf(r.get("id"))).toList();
    }

    @Test
    void aFormOverALiveKindKeepsTheSearchsRowsAndOrderThenTicks() throws Exception {
        Fake f = new Fake();
        f.put("T1", "EUR", 5, "ACME");
        f.put("T2", "USD", 9, "ACME");
        f.put("T3", "EUR", 7, "ZETA");
        try (LiveSets sets = new LiveSets(f, Clock.systemUTC(), 20, 100)) {
            LiveSet s = sets.query(Q, AsOf.LATEST, null, false, null, false);
            assertThat(s.live()).isTrue();
            assertThat(ids(s.page(0, 100, null, false, null, null))).containsExactly("T3", "T1");   // the search's order, not arrival's
            assertThat(s.origin()).containsEntry("matched", 2).containsEntry("mnemonic", "TRD");
            assertThat(s.spec().columns()).extracting(c -> c.get("field")).containsExactly("CCY", "NOTIONAL", "CPTY");
            assertThat(s.spec().columns()).extracting(c -> c.get("label")).containsExactly("Ccy", "Notional", "Counterparty");
            assertThat(sets.implicit(s.spec().id())).isTrue();
            assertThat(sets.specs()).isEmpty();                                       // never listed among the declarations
            assertThat(s.spec().editable()).isFalse();

            List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
            LiveSet.Window w = s.window(null, events::add, 100);
            f.tick("T1", "EUR", 6, "ACME");                                           // a change of a row it holds: in place
            f.tick("T4", "EUR", 1, "ACME");                                           // a new match: in, after the search's rows
            f.tick("T3", "USD", 7, "ZETA");                                           // no longer matches: out
            w.flush();
            assertThat(ids(s.page(0, 100, null, false, null, null))).containsExactly("T1", "T4");
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).containsEntry("type", "rows");
            assertThat(String.valueOf(events.get(0).get("removes"))).isEqualTo("[T3]");
            assertThat(s.current("T1").doc().get("NOTIONAL").unwrap()).isEqualTo(6L);
            // the same search again shares the live set: nobody reads the search twice while it ticks
            assertThat(sets.query(Q, AsOf.LATEST, null, false, null, true)).isSameAs(s);
            assertThat(f.searches.get()).isEqualTo(1);
            // its stream finds it by the id the page gave
            assertThat(sets.open(sets.spec(s.spec().id()).orElseThrow(), null)).isSameAs(s);
        }
    }

    @Test
    void aPastBusinessDateOrAKindWithoutALiveBindingIsStaticAndReadAfreshWhenRunAgain() throws Exception {
        Fake f = new Fake();
        f.put("T1", "EUR", 5, "ACME");
        try (LiveSets sets = new LiveSets(f, Clock.systemUTC(), 20, 100)) {
            LiveSet past = sets.query(Q, AsOf.of(LocalDate.of(2026, 9, 30)), null, false, null, false);
            assertThat(past.live()).isFalse();
            assertThat(f.listeners).isEmpty();
            f.liveBinding = false;
            LiveSet now = sets.query(Q, AsOf.LATEST, null, false, null, false);
            assertThat(now.live()).isFalse();
            assertThat(now.spec().id()).isNotEqualTo(past.spec().id());
            f.put("T2", "EUR", 9, "ACME");
            assertThat(sets.query(Q, AsOf.LATEST, null, false, null, false).size()).isEqualTo(1);   // the same result while it is open
            LiveSet again = sets.query(Q, AsOf.LATEST, null, false, null, true);                      // Search pressed again: read afresh
            assertThat(again.size()).isEqualTo(2);
            assertThat(ids(again.page(0, 10, null, false, null, null))).containsExactly("T2", "T1");
            assertThat(sets.open(again.spec(), null)).isSameAs(again);
        }
    }

    @Test
    void maskedPeopleGetTheirOwnSetAndAMaskedValueNeverPicksSortsOrFiltersRows() throws Exception {
        Fake f = new Fake();
        f.put("T1", "EUR", 5, "ACME");
        f.put("T3", "EUR", 7, "ZETA");
        try (LiveSets sets = new LiveSets(f, Clock.systemUTC(), 20, 100)) {
            LiveSet plain = sets.query(Q, AsOf.LATEST, null, false, null, false);
            LiveSet masked = sets.query(Q, AsOf.LATEST, null, true, MASK, false);
            assertThat(masked).isNotSameAs(plain);
            assertThat(LiveSets.maskedSet(masked.spec().id())).isTrue();
            assertThat(LiveSets.maskedSet(plain.spec().id())).isFalse();
            assertThat(String.valueOf(masked.current("T1").doc().get("CPTY").unwrap())).isEqualTo("***");
            f.tick("T5", "EUR", 1, "SECRET");
            assertThat(String.valueOf(masked.current("T5").doc().get("CPTY").unwrap())).isEqualTo("***");   // live changes masked too
            // a set holding raw rows: a viewer's filter and sort read only what that viewer sees
            assertThat(plain.page(0, 10, null, false, "zeta", MASK).total()).isZero();
            assertThat(plain.page(0, 10, null, false, "zeta", null).total()).isEqualTo(1);
            assertThat(ids(plain.page(0, 10, "CPTY", true, null, MASK))).containsExactly("T1", "T3", "T5");   // all ***: by id
        }
    }

    @Test
    void aClosedSearchSetIsNotReopenedAsSomethingElse() {
        Fake f = new Fake();
        try (LiveSets sets = new LiveSets(f, Clock.systemUTC(), 20, 100)) {
            LiveSetSpec bare = new LiveSetSpec("qu-0011223344", "x", "trade", Q, null, null, null, null, List.of(), List.of(), List.of(), null, false,
                    List.of(), null, null, false, null);
            assertThatThrownBy(() -> sets.open(bare, null)).isInstanceOf(DrishtiException.class).hasMessageContaining("run the search again");
        }
    }
}
