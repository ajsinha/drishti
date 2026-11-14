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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The live result set: merge by key and version, no gap at start, back-pressure on a burst, paged windows, retention, and the edit gate. */
class LiveSetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static LiveSetSpec spec(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blotter", "trade");
        m.put("kind", "trade");
        m.put("key", "TRADE_ID");
        m.put("version", "VERSION");
        m.put("changed-by", "UPDATED_BY");
        m.put("changed-at", "UPDATED_AT");
        m.put("columns", List.of("TRADE_ID", "BOOK", "NOTIONAL"));
        m.putAll(extra);
        return LiveSetSpec.parse(m, "trade.blotter.yaml", "trading");
    }

    static EntityDocument trade(String id, long version, String book, Object at) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("TRADE_ID", id);
        d.put("VERSION", version);
        d.put("BOOK", book);
        d.put("NOTIONAL", 1_000_000L * version);
        d.put("UPDATED_BY", "kim");
        d.put("UPDATED_AT", at == null ? "2026-11-14T10:02:11Z" : at);
        return new EntityDocument(EntityRef.of("trade", id), DataNode.of(d), new Provenance("trade-kafka", version, Instant.now(), true));
    }

    static LiveSet started(LiveSetSpec spec, Predicate<DataNode> holds, List<EntityDocument> snapshot, AtomicReference<Consumer<EntityDocument>> live,
            Clock clock) throws Exception {
        LiveSet s = new LiveSet(spec, null, holds, clock);
        s.start(l -> {
            live.set(l);
            return () -> { };
        }, sink -> snapshot.forEach(sink));
        return s;
    }

    @Test
    void changesMergeByKeyAndVersionWithoutDuplicatesOrGoingBack() throws Exception {
        AtomicReference<Consumer<EntityDocument>> live = new AtomicReference<>();
        LiveSet s = started(spec(Map.of()), d -> !"CXL".equals(d.get("BOOK").unwrap()), List.of(trade("T1", 1, "B1", null), trade("T2", 3, "B1", null)), live,
                Clock.systemUTC());
        assertThat(s.size()).isEqualTo(2);
        live.get().accept(trade("T1", 2, "B2", null));         // newer: replaces
        live.get().accept(trade("T1", 2, "B9", null));         // the same version again: dropped
        live.get().accept(trade("T2", 1, "B7", null));         // older: dropped
        live.get().accept(trade("T3", 1, "B3", null));         // new: inserted
        assertThat(s.current("T1").version()).isEqualTo(2);
        assertThat(String.valueOf(s.current("T1").doc().get("BOOK").unwrap())).isEqualTo("B2");
        assertThat(String.valueOf(s.current("T2").doc().get("BOOK").unwrap())).isEqualTo("B1");
        live.get().accept(trade("T3", 2, "CXL", null));        // no longer holds the condition: leaves
        live.get().accept(EntityDocument.deleted(EntityRef.of("trade", "T2"), new Provenance("trade-kafka", 9, Instant.now(), true)));
        assertThat(s.size()).isEqualTo(1);
        assertThat(s.describe()).containsEntry("staleDropped", 2L);
    }

    @Test
    void whatArrivesWhileTheSnapshotIsReadIsAppliedAfterItByVersion() throws Exception {
        AtomicReference<Consumer<EntityDocument>> live = new AtomicReference<>();
        LiveSet s = new LiveSet(spec(Map.of()), null, d -> true, Clock.systemUTC());
        s.start(l -> {
            live.set(l);
            return Subscription.NONE;
        }, sink -> {
            live.get().accept(trade("T1", 5, "LIVE", null));   // the stream runs ahead of the snapshot
            sink.accept(trade("T1", 4, "SNAPSHOT", null));
            sink.accept(trade("T2", 1, "SNAPSHOT", null));
        });
        assertThat(s.current("T1").version()).isEqualTo(5);
        assertThat(String.valueOf(s.current("T1").doc().get("BOOK").unwrap())).isEqualTo("LIVE");
        assertThat(s.size()).isEqualTo(2);
    }

    @Test
    void aBurstOfTenThousandUpdatesReachesAViewerCoalescedOrAsOneResync() throws Exception {
        AtomicReference<Consumer<EntityDocument>> live = new AtomicReference<>();
        LiveSet s = started(spec(Map.of()), d -> true, List.of(), live, Clock.systemUTC());
        List<Map<String, Object>> got = new CopyOnWriteArrayList<>();
        LiveSet.Window w = s.window(null, got::add, 5_000);
        long t0 = System.nanoTime();
        for (int v = 1; v <= 5; v++) {
            for (int i = 0; i < 2_000; i++) {
                live.get().accept(trade("T" + i, v, "B" + (i % 7), null));
            }
        }
        long applyMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(w.flush()).isTrue();
        assertThat(got).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> up = (List<Map<String, Object>>) got.get(0).get("upserts");
        assertThat(up).hasSize(2_000).allSatisfy(r -> assertThat(r.get("version")).isEqualTo(5L));
        assertThat(applyMs).as("10,000 updates applied in %d ms", applyMs).isLessThan(5_000);
        // more records waiting than the viewer's capacity: one resync, nothing queued without bound
        got.clear();
        for (int i = 0; i < 10_000; i++) {
            live.get().accept(trade("N" + i, 1, "B", null));
        }
        assertThat(w.flush()).isTrue();
        assertThat(got).hasSize(1);
        assertThat(got.get(0)).containsEntry("type", "resync").containsEntry("total", 12_000);
        assertThat(w.flush()).isFalse();
    }

    @Test
    void aPagedViewerIsSentChangesOfItsRowsAndOnlyTheCountOfOthers() throws Exception {
        AtomicReference<Consumer<EntityDocument>> live = new AtomicReference<>();
        LiveSet s = started(spec(Map.of()), d -> true, List.of(trade("T1", 1, "B", null), trade("T2", 1, "B", null)), live, Clock.systemUTC());
        List<Map<String, Object>> got = new ArrayList<>();
        LiveSet.Window w = s.window(null, got::add, 100);
        w.paged(Set.of("T1"));
        live.get().accept(trade("T2", 2, "B", null));
        live.get().accept(trade("T3", 1, "B", null));
        w.flush();
        assertThat(got.get(0)).containsEntry("countOnly", true).containsEntry("total", 3);
        live.get().accept(trade("T1", 2, "B", null));
        w.flush();
        assertThat((List<?>) got.get(1).get("upserts")).hasSize(1);
    }

    @Test
    void retentionDropsRowsPastTheirAgeAndBeyondTheCapOldestFirst() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Consumer<EntityDocument>> live = new AtomicReference<>();
        LiveSetSpec spec = spec(Map.of("retention", Map.of("ttl", "8h", "on", "UPDATED_AT", "max-rows", 2)));
        LiveSet s = started(spec, d -> true, List.of(trade("OLD", 1, "B", "2026-11-14T01:00:00Z"), trade("A", 1, "B", "2026-11-14T09:00:00Z"),
                trade("B", 1, "B", "2026-11-14T10:00:00Z"), trade("C", 1, "B", "2026-11-14T11:00:00Z")), live, clock);
        assertThat(s.trim()).isEqualTo(2);
        assertThat(s.current("OLD")).isNull();
        assertThat(s.current("A")).isNull();
        assertThat(s.size()).isEqualTo(2);
        assertThat(s.describe()).containsEntry("expired", 2L);
    }

    @Test
    void anEditPassesOnlyWhenTheConfigurationAndTheRightsAllowItAndTheVersionIsCurrent() throws Exception {
        Map<String, Object> edit = Map.of("edit", "cells", "key", "trade_id", "fields",
                List.of(Map.of("id", "trade_id", "bind", "TRADE_ID", "readonly", true), Map.of("id", "book", "bind", "BOOK")),
                "save", Map.of("target", "source", "command", "amend-trade"));
        LiveSetSpec spec = spec(Map.of("edit", edit));
        List<String> audit = new CopyOnWriteArrayList<>();
        List<JsonNode> sent = new CopyOnWriteArrayList<>();
        LiveSets sets = new LiveSets(new LiveSets.Sources() {
            @Override
            public boolean snapshot(String kind, String query, Consumer<EntityDocument> sink) {
                sink.accept(trade("T1", 7, "B1", null));
                return true;
            }

            @Override
            public Subscription subscribe(String kind, Consumer<EntityDocument> listener) {
                return Subscription.NONE;
            }

            @Override
            public Predicate<DataNode> condition(String query) {
                return d -> true;
            }
        }, Clock.systemUTC(), 50, 100).audit((a, b, c, d) -> audit.add(a + " " + b + " " + d));
        try (sets) {
            sets.add(spec);
            Principal ann = new Principal("ann", List.of("trader"));
            JsonNode body = JSON.readTree("{\"entity\": \"T1\", \"basedOn\": 7, \"items\": [{\"field\": \"BOOK\", \"from\": \"B1\", \"to\": \"B2\"}]}");
            // read-only by configuration: the write-back is off on this server
            assertThatThrownBy(() -> sets.edit("trade", ann, body)).isInstanceOf(DrishtiException.class).hasMessageContaining("editing is off");
            sets.submit((p, b) -> {
                sent.add(b);
                return List.of(Map.of("id", "chg-1", "state", "awaiting"));
            });
            // read-only by rights: a crafted request from someone without the edit power is refused and audited
            sets.access((p, s) -> p.roles().contains("trader") ? null : p.user() + " does not hold the edit power");
            assertThatThrownBy(() -> sets.edit("trade", new Principal("rita", List.of("viewer")), body)).isInstanceOf(DrishtiException.class)
                    .hasMessageContaining("does not hold the edit power");
            assertThat(audit).anyMatch(a -> a.startsWith("rita edit-refused"));
            assertThat(sets.readOnly(new Principal("rita", List.of("viewer")), spec)).isNotNull();
            assertThat(sets.readOnly(ann, spec)).isNull();
            // a stale version: refused, saying who changed it
            JsonNode stale = JSON.readTree("{\"entity\": \"T1\", \"basedOn\": 6, \"items\": [{\"field\": \"BOOK\", \"from\": \"B1\", \"to\": \"B2\"}]}");
            assertThatThrownBy(() -> sets.edit("trade", ann, stale)).isInstanceOf(DrishtiException.class)
                    .hasMessageContaining("is at version 7, changed by kim").hasMessageContaining("you edited version 6");
            // editable: handed to the write-back under the declaration's form, with the version seen
            assertThat(sets.edit("trade", ann, body)).hasSize(1);
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).path("sutra").asText()).isEqualTo("live:trade");
            assertThat(sent.get(0).path("basedOn").asLong()).isEqualTo(7);
            assertThat(sets.rules()).extracting(r -> r.field().recordField()).containsExactly("TRADE_ID", "BOOK");
        }
    }

    @Test
    void aDeclarationWithProblemsSaysThemAll() {
        assertThatThrownBy(() -> LiveSetSpec.parse(Map.of("blotter", "Bad Id", "kind", "trade", "columns", List.of("A"), "retention", Map.of("ttl", "soon"),
                "links", List.of(Map.of("label", "x", "href", "https://elsewhere"))), "x.blotter.yaml", null))
                .hasMessageContaining("blotter: names it").hasMessageContaining("retention.ttl").hasMessageContaining("href is a path");
    }
}
