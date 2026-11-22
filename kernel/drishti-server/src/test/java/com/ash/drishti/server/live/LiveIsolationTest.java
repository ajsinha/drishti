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
package com.ash.drishti.server.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.rachana.data.ProtocolError;
import com.ash.rachana.data.Subscription;
import com.ash.rachana.data.TableProvider;
import com.ash.rachana.live.LiveTables;
import com.ash.rachana.live.spi.LiveStream;
import com.ash.rachana.live.spi.SnapshotSource;
import com.ash.rachana.table.LiveTableSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Session isolation of the live tables (the liveset engine's {@code LiveIsolationTest}, ported): one table shared by every reader, each
 * read and each change kept to the reader's own scope (a row rule by the person's desk), whole or paged from a lake; a masked value never
 * matches a filter; the audience is part of what a reader is (roles, masks, administrator).
 */
class LiveIsolationTest {

    static final Principal ANN = new Principal("ann", List.of("trader"));
    static final Principal BOB = new Principal("bob", List.of("trader"));
    static final Principal MASKED = new Principal("mo", List.of("masked"));

    static final String TRADES = """
            rachana: 1
            live-table: trade
            kind: trade
            snapshot: trade
            stream: trade
            key: TRADE_ID
            columns: [ { name: TRADE_ID }, { name: CCY }, { name: DESK }, { name: TRADER_ID } ]
            """;

    /** Ann sees desk FX, Bob desk RATES (the same roles, a row rule by the person's desk); TRADER_ID is masked for the masked role. */
    static DrishtiAuthoriser.Rights rights() {
        return new DrishtiAuthoriser.Rights() {
            public boolean mayOpen(Principal p, String kind) { return true; }
            public String signature(Principal p) { return p.securitySignature() + "|admin=" + p.roles().contains("admin"); }
            public DrishtiAuthoriser.RowRule rows(Principal p, String kind) {
                if (p.roles().contains("masked") || p.roles().contains("admin")) {
                    return null;
                }
                String desk = "ann".equals(p.user()) ? "FX" : "RATES";
                return new DrishtiAuthoriser.RowRule("DESK == '" + desk + "'", r -> desk.equals(r.get("DESK")));
            }
            public UnaryOperator<Map<String, Object>> redact(Principal p) {
                return p.roles().contains("masked") ? r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.computeIfPresent("TRADER_ID", (k, v) -> Map.of("$masked", true));
                    return m;
                } : null;
            }
            public boolean masked(Principal p, String field) { return p.roles().contains("masked") && "TRADER_ID".equals(field); }
            public String readOnly(Principal p, LiveTableSpec spec) { return "read only"; }
            public List<String> formRoles(Principal p) { return p.roles(); }
        };
    }

    static EntityDocument trade(String id, String ccy, String desk) {
        Map<String, Object> d = new LinkedHashMap<>(Map.of("TRADE_ID", id, "CCY", ccy, "DESK", desk, "TRADER_ID", "TRDR-" + id));
        return new EntityDocument(EntityRef.of("trade", id), DataNode.of(d), new Provenance("lake", 1, Instant.now(), false));
    }

    static LiveTablesAdapterTest.Bindings trades(boolean paged) {
        LiveTablesAdapterTest.Bindings b = new LiveTablesAdapterTest.Bindings();
        for (EntityDocument d : List.of(trade("T1", "EUR", "FX"), trade("T2", "EUR", "RATES"), trade("T3", "USD", "FX"), trade("T4", "EUR", "FX"))) {
            b.docs.add(d);
        }
        if (paged) {
            b.store = new KindBindings.Store() {
                public String version() { return "v1"; }
                public boolean project(List<String> fields, KindBindings.Projection sink) {
                    b.docs.forEach(d -> sink.row(d.ref().id(), fields.stream().map(f -> d.data().get(f).unwrap()).toArray()));
                    return true;
                }
                public Map<String, DataNode> fetch(Collection<String> ids) {
                    Map<String, DataNode> out = new LinkedHashMap<>();
                    b.docs.stream().filter(d -> ids.contains(d.ref().id())).forEach(d -> out.put(d.ref().id(), d.data()));
                    return out;
                }
            };
        }
        return b;
    }

    static LiveTables engine(LiveTablesAdapterTest.Bindings b) {
        Map<String, LiveTableSpec> specs = new LinkedHashMap<>();
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            public SnapshotSource snapshot(String kind) { return new KindSnapshot(kind, b, List.of("TRADE_ID")); }
            public LiveStream stream(String kind) { return new KindStream(kind, b, List.of("TRADE_ID")); }
        }, LiveTables.Options.DEFAULTS.withUpkeepMs(0).withFlushMs(10), Clock.systemUTC());
        lt.authoriser(new DrishtiAuthoriser(specs::get, rights()));
        LiveTableSpec spec = LiveTableSpec.read(TRADES, "trade.live.yaml", DrishtiLiveTables.PROFILE_KEYS, f -> true);
        specs.put("trade", spec);
        lt.declare(spec);
        return lt;
    }

    static final Map<String, Object> EUR = Map.of("op", "==", "args", List.of(Map.of("col", "CCY"), Map.of("lit", "EUR")));

    static List<String> ids(LiveTables lt, Principal p, Object filter) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("columns", List.of("TRADE_ID"));
        q.put("sort", List.of(Map.of("col", "TRADE_ID")));
        if (filter != null) {
            q.put("filter", filter);
        }
        return LiveTablesAdapterTest.values(lt.rows("trade", q, p)).stream().map(r -> String.valueOf(r.get(0))).toList();
    }

    static List<String> upserted(List<Map<String, Object>> frames) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : frames) {
            if (f.get("data") instanceof Map<?, ?> d) {
                for (String part : List.of("add", "update")) {
                    if (d.get(part) instanceof List<?> l) {
                        l.forEach(r -> out.add(String.valueOf(((Map<?, ?>) r).get("TRADE_ID"))));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void aSharedTableKeepsEveryReadAndEveryChangeToTheReadersScope() throws Exception {
        for (boolean paged : new boolean[] {false, true}) {
            LiveTablesAdapterTest.Bindings b = trades(paged);
            try (LiveTables lt = engine(b)) {
                assertThat(ids(lt, ANN, EUR)).as("paged=" + paged).containsExactly("T1", "T4");
                assertThat(ids(lt, BOB, EUR)).containsExactly("T2");
                assertThat(ids(lt, ANN, Map.of("op", "==", "args", List.of(Map.of("col", "DESK"), Map.of("lit", "RATES"))))).isEmpty();  // a filter never reaches past the scope
                assertThat(lt.rows("trade", Map.of("filter", EUR), ANN).get("total")).isEqualTo(2L);
                // one record whole (the panel's Raw JSON): only inside the reader's scope
                Map<String, Object> byKey = Map.of("filter", Map.of("op", "==", "args", List.of(Map.of("col", "TRADE_ID"), Map.of("lit", "T2"))));
                assertThat(LiveTablesAdapterTest.values(lt.rows("trade", byKey, ANN))).isEmpty();
                assertThat(LiveTablesAdapterTest.values(lt.rows("trade", byKey, BOB))).hasSize(1);
                // live changes: a new RATES trade reaches Bob's stream only; a FX trade moved to RATES leaves Ann's
                List<Map<String, Object>> annFrames = new CopyOnWriteArrayList<>();
                List<Map<String, Object>> bobFrames = new CopyOnWriteArrayList<>();
                Subscription a = lt.subscribe("trade", new TableProvider.Subscribe(null, EUR, 0, null, false), annFrames::add, ANN);
                Subscription s = lt.subscribe("trade", new TableProvider.Subscribe(null, EUR, 0, null, false), bobFrames::add, BOB);
                b.live(trade("T5", "EUR", "RATES"));
                b.live(trade("T4", "EUR", "RATES"));
                LiveTablesAdapterTest.await(() -> upserted(bobFrames).containsAll(List.of("T5", "T4")));
                assertThat(upserted(annFrames)).doesNotContain("T5", "T4");
                assertThat(upserted(bobFrames)).contains("T5", "T4");
                assertThat(ids(lt, ANN, EUR)).containsExactly("T1");
                assertThat(ids(lt, BOB, EUR)).containsExactly("T2", "T4", "T5");
                a.close();
                s.close();
            }
        }
    }

    @Test
    void aMaskedColumnCanNeitherFilterNorOrderRows() throws Exception {
        try (LiveTables lt = engine(trades(false))) {
            Map<String, Object> probe = Map.of("op", "starts_with", "args", List.of(Map.of("col", "TRADER_ID"), Map.of("lit", "TRDR-")));
            assertThat(ids(lt, ANN, probe)).containsExactly("T1", "T3", "T4");
            assertThatThrownBy(() -> ids(lt, MASKED, probe)).hasMessageContaining("masked");   // a masked column cannot be probed: refused
            assertThat(lt.schemaDoc("trade", MASKED).toString()).contains("masked=true");
            assertThatThrownBy(() -> lt.rows("trade", Map.of("sort", List.of(Map.of("col", "TRADER_ID"))), MASKED))
                    .isInstanceOfSatisfying(ProtocolError.class, e -> assertThat(e.status()).isEqualTo(403));
        }
    }

    @Test
    void theAudienceIsPartOfWhatAReaderIsAndAnUnsignedCallerReadsNothing() throws Exception {
        try (LiveTables lt = engine(trades(false))) {
            Principal admin = new Principal("ada", List.of("admin"));
            assertThat(ids(lt, admin, null)).containsExactly("T1", "T2", "T3", "T4");
            assertThat(ids(lt, ANN, null)).containsExactly("T1", "T3", "T4");
            assertThat(rights().signature(admin)).isNotEqualTo(rights().signature(ANN));
            assertThatThrownBy(() -> lt.rows("trade", Map.of(), null)).isInstanceOf(ProtocolError.class);
            assertThat(Optional.ofNullable(lt.schemaDoc("trade", ANN).get("write"))).isEmpty();
        }
    }
}
