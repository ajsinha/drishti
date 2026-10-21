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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live tables (RUPAKA.md section 12): a table kept in memory by the engine and maintained incrementally from the entities a source pushes. Updates by key,
 * values that did not change, retention by rows and by age, deletions, the burst of updates merged into one write, and the reader's row rules and masks applied
 * to every answer (an update changes the rows, never what a reader may see).
 */
public class LiveTablesTest {

    /** The lake's trades as pushable entities. */
    public static class LiveLake extends TestLake {
        public final Map<EntityRef, EntityDocument> docs = new ConcurrentHashMap<>();
        final Map<EntityRef, List<Consumer<EntityDocument>>> listeners = new ConcurrentHashMap<>();
        final AtomicLong generation = new AtomicLong(1);
        final AtomicLong reads = new AtomicLong();
        volatile boolean pushes = true;

        public LiveLake() {
            super(true, 20);
            Collection<EntityRef> refs = super.ids("trade", current, 1000);
            super.read(refs, current).forEach(docs::put);
        }

        @Override
        public List<EntityRef> ids(String kind, java.time.LocalDate date, int limit) {
            return docs.keySet().stream().filter(r -> r.kind().equals(kind)).sorted(java.util.Comparator.comparing(EntityRef::id)).limit(limit).toList();
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, java.time.LocalDate date) {
            Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
            refs.forEach(r -> {
                EntityDocument d = docs.get(r);
                if (d != null) {
                    reads.incrementAndGet();
                    out.put(r, d);
                }
            });
            return out;
        }

        @Override
        public Subscription subscribe(String connector, EntityRef ref, Consumer<EntityDocument> listener) {
            if (!pushes) {
                return Subscription.NONE;
            }
            listeners.computeIfAbsent(ref, r -> new CopyOnWriteArrayList<>()).add(listener);
            return () -> listeners.get(ref).remove(listener);
        }

        /** The source changes a field of an entity and pushes it. */
        public void set(String id, String field, Object value) {
            EntityRef ref = EntityRef.of("trade", id);
            Map<String, Object> m = new LinkedHashMap<>(asMap(docs.get(ref).data()));
            m.put(field, value);
            EntityDocument next = new EntityDocument(ref, DataNode.of(m), new Provenance("test", generation.incrementAndGet(), Instant.now(), true));
            docs.put(ref, next);
            listeners.getOrDefault(ref, List.of()).forEach(l -> l.accept(next));
        }

        public void add(String id, String desk, double notional, double mtm) {
            EntityRef ref = EntityRef.of("trade", id);
            Map<String, Object> m = new LinkedHashMap<>(asMap(docs.values().iterator().next().data()));
            m.put("tradeId", id);
            m.put("desk", desk);
            m.put("notional", notional);
            m.put("mtm", mtm);
            docs.put(ref, new EntityDocument(ref, DataNode.of(m), new Provenance("test", generation.incrementAndGet(), Instant.now(), true)));
        }

        public void remove(String id) {
            EntityRef ref = EntityRef.of("trade", id);
            EntityDocument gone = EntityDocument.deleted(ref, new Provenance("test", generation.incrementAndGet(), Instant.now(), true));
            docs.remove(ref);
            listeners.getOrDefault(ref, List.of()).forEach(l -> l.accept(gone));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(DataNode n) {
            return (Map<String, Object>) n.unwrap();
        }
    }

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-09T10:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    @TempDir Path store;
    LiveLake lake;
    TestClock clock;
    QueryService service;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new LiveLake();
        clock = new TestClock();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    QueryService.Loaded start(String liveBlock) {
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofHours(1));
        service = new QueryService(props, live, new DuckDbEngine(props), lake, clock);
        String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    mode: live" + liveBlock);
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        return new QueryService.Loaded("file:dataset:live-test", Layers.key("file:dataset:live-test"), r.dataset(), "h1");
    }

    QueryService.Caller who(String user, boolean raw, String desk, String... roles) {
        SqlCompiler.Masks masks = (t, c) -> !raw && (c.equals("cpty") || t.equals("trades") && c.equals("mtm"));
        return new QueryService.Caller(user, Set.of(roles), true, UserAttributes.of(user, List.of(roles), desk, null), masks);
    }

    QueryService.Result run(QueryService.Loaded ds, String body, QueryService.Caller who) {
        try {
            return service.query(ds, QueryRequest.of(json.readTree(body)), who, false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static void eventually(BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(ok.getAsBoolean()).as("the condition holds within 5 s").isTrue();
    }

    static double mtmOf(QueryService.Result r) {
        return ((Number) r.columns().get(0).values()[0]).doubleValue();
    }

    // ---- maintained by key ---------------------------------------------------------------------------------------------

    @Test
    void theFirstQueryLoadsTheKindAndAnsweredFromMemory() {
        QueryService.Loaded ds = start("");
        QueryService.Result r = run(ds, "{\"measures\":[\"Trades\"]}", who("rita", true, "", "risk"));
        assertThat(r.meta().layer()).isEqualTo("live");
        assertThat(r.columns().get(0).values()[0]).isEqualTo(20L);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        assertThat(t.status().pushes()).isTrue();
        assertThat(t.status().subscribed()).isEqualTo(20);
        assertThat(t.status().state()).isEqualTo("live");
    }

    @Test
    void anUpdateReplacesTheRowByKeyAndANewEntityIsInserted() throws Exception {
        QueryService.Loaded ds = start("");
        QueryService.Caller risk = who("rita", true, "", "risk");
        double before = mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk));
        String id = lake.docs.keySet().iterator().next().id();
        double old = ((Number) lake.docs.get(EntityRef.of("trade", id)).data().get("mtm").asDouble()).doubleValue();
        lake.set(id, "mtm", old + 5000.0);
        eventually(() -> mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk)) == before + 5000.0);
        assertThat(run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).isEqualTo(20L);       // replaced, not added
        // a new entity: found by listing again (resync); here the table applies it directly the way the feed would
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        long g = t.generation();
        lake.add("T-NEW", "DESK-A", 1000.0, 123.0);
        t.apply(List.of(lake.docs.get(EntityRef.of("trade", "T-NEW"))));
        assertThat(t.generation()).isGreaterThan(g);
        assertThat(run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).isEqualTo(21L);
    }

    @Test
    void valuesThatDidNotChangeDoNotMoveTheGeneration() throws Exception {
        QueryService.Loaded ds = start("");
        run(ds, "{\"measures\":[\"Trades\"]}", who("rita", true, "", "risk"));
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        String id = lake.docs.keySet().iterator().next().id();
        long g = t.generation();
        long skipped = t.status().skipped();
        lake.set(id, "legs", List.of());                       // a field the table does not declare
        eventually(() -> t.status().skipped() > skipped);
        assertThat(t.generation()).isEqualTo(g);
    }

    @Test
    void aBurstOfUpdatesToOneEntityIsMergedAndTheLastValueWins() throws Exception {
        QueryService.Loaded ds = start("");
        QueryService.Caller risk = who("rita", true, "", "risk");
        run(ds, "{\"measures\":[\"Trades\"]}", risk);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        String id = lake.docs.keySet().iterator().next().id();
        long applied = t.status().applied();
        for (int i = 1; i <= 500; i++) {
            lake.set(id, "notional", 1_000_000.0 + i);
        }
        eventually(() -> ((Number) run(ds, "{\"fields\":[\"trades.id\"],\"measures\":[\"Notional\"],\"filters\":[{\"field\":\"trades.id\",\"op\":\"eq\",\"values\":[\"" + id + "\"]}]}", risk)
                .columns().get(1).values()[0]).doubleValue() == 1_000_500.0);
        assertThat(t.status().applied() - applied).as("500 pushes cost far fewer writes (merged per batch)").isLessThan(100);
    }

    @Test
    void aDeletionRemovesTheRowAndStartsANewEpoch() throws Exception {
        QueryService.Loaded ds = start("");
        QueryService.Caller risk = who("rita", true, "", "risk");
        run(ds, "{\"measures\":[\"Trades\"]}", risk);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        long epoch = t.epoch();
        lake.remove(lake.docs.keySet().iterator().next().id());
        eventually(() -> t.epoch() > epoch);
        assertThat(run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).isEqualTo(19L);
    }

    @Test
    void theTwoLiveColumnsAreQueryableAndGrowWithEachChange() throws Exception {
        QueryService.Loaded ds = start("");
        QueryService.Caller risk = who("rita", true, "", "risk");
        QueryService.Result all = run(ds, "{\"fields\":[\"trades.id\",\"trades.updateSeq\"],\"limit\":50}", risk);
        long max = all.columns().get(1).kind() == ArrowIpcWriter.Kind.INT64 ? java.util.Arrays.stream(all.columns().get(1).values()).mapToLong(v -> ((Number) v).longValue()).max().orElse(0) : -1;
        assertThat(max).isEqualTo(20);
        String id = lake.docs.keySet().iterator().next().id();
        lake.set(id, "mtm", 99_999.0);
        eventually(() -> run(ds, "{\"fields\":[\"trades.id\"],\"filters\":[{\"field\":\"trades.updateSeq\",\"op\":\"gt\",\"values\":[20]}]}", risk).rows() == 1);
        QueryService.Result changed = run(ds, "{\"fields\":[\"trades.id\"],\"filters\":[{\"field\":\"trades.updateSeq\",\"op\":\"gt\",\"values\":[20]}]}", risk);
        assertThat(changed.columns().get(0).values()[0]).isEqualTo(id);
    }

    // ---- retention -----------------------------------------------------------------------------------------------------

    @Test
    void retentionByRowsKeepsTheMostRecentlyChanged() throws Exception {
        QueryService.Loaded ds = start("\n    live: { rows: 5 }");
        QueryService.Caller risk = who("rita", true, "", "risk");
        assertThat(run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).isEqualTo(5L);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        assertThat(t.status().evicted()).isEqualTo(15);
        assertThat(t.epoch()).isGreaterThan(0);
        // the survivors are the last of the load; a change to an evicted one brings it back and pushes the oldest out
        long epoch = t.epoch();
        List<Object> kept = java.util.Arrays.asList(run(ds, "{\"fields\":[\"trades.id\"]}", risk).columns().get(0).values());
        String gone = lake.docs.keySet().stream().map(EntityRef::id).filter(i -> !kept.contains(i)).sorted().findFirst().orElseThrow();
        lake.set(gone, "mtm", 7.0);
        eventually(() -> t.epoch() > epoch);
        assertThat(run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).isEqualTo(5L);
        assertThat(run(ds, "{\"fields\":[\"trades.id\"]}", risk).columns().get(0).values()).contains(gone);
    }

    @Test
    void retentionByAgeDropsRowsThatHaveNotChanged() throws Exception {
        QueryService.Loaded ds = start("\n    live: { age: 10m }");
        QueryService.Caller risk = who("rita", true, "", "risk");
        assertThat(run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).isEqualTo(20L);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        clock.advance(Duration.ofMinutes(11));
        String id = lake.docs.keySet().stream().map(EntityRef::id).sorted().findFirst().orElseThrow();
        lake.set(id, "mtm", 1.0);                              // only this entity changes; the other 19 have been quiet for 11 minutes
        eventually(() -> ((Number) run(ds, "{\"measures\":[\"Trades\"]}", risk).columns().get(0).values()[0]).longValue() == 1L);
        assertThat(t.status().evicted()).isEqualTo(19);
    }

    // ---- readers -------------------------------------------------------------------------------------------------------

    @Test
    void everyReaderSeesTheUpdateThroughTheirOwnRowRulesAndMasks() throws Exception {
        QueryService.Loaded ds = start("");
        QueryService.Caller risk = who("rita", true, "", "risk");
        QueryService.Caller masked = who("ann", false, "", "risk");                       // sees all rows, but not mtm or cpty
        QueryService.Caller deskA = who("hana", true, "DESK-A", "desk-head");              // sees DESK-A rows only
        QueryService.Caller nobody = who("zed", true, "", "stranger");                     // no row rule covers this role
        String body = "{\"measures\":[\"Trades\",\"MTM\"]}";
        QueryService.Result r0 = run(ds, body, deskA);
        long deskATrades = ((Number) r0.columns().get(0).values()[0]).longValue();
        assertThat(deskATrades).isGreaterThan(0).isLessThan(20);
        // a DESK-A trade moves, a DESK-B trade moves
        String a = lake.docs.entrySet().stream().filter(e -> "DESK-A".equals(e.getValue().data().get("desk").asText())).map(e -> e.getKey().id()).sorted().findFirst().orElseThrow();
        String b = lake.docs.entrySet().stream().filter(e -> "DESK-B".equals(e.getValue().data().get("desk").asText())).map(e -> e.getKey().id()).sorted().findFirst().orElseThrow();
        double aOld = lake.docs.get(EntityRef.of("trade", a)).data().get("mtm").asDouble();
        double bOld = lake.docs.get(EntityRef.of("trade", b)).data().get("mtm").asDouble();
        double riskBefore = mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk));
        lake.set(a, "mtm", aOld + 1000);
        lake.set(b, "mtm", bOld + 20_000);
        eventually(() -> mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk)) == riskBefore + 21_000);
        // the whole-book reader saw both, the desk head only their own desk's, the masked reader no mtm at all, the stranger no rows
        assertThat(mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk))).isEqualTo(riskBefore + 21_000);
        QueryService.Result desk = run(ds, body, deskA);
        assertThat(desk.columns().get(0).values()[0]).isEqualTo(deskATrades);
        assertThat(((Number) desk.columns().get(1).values()[0]).doubleValue()).isEqualTo(((Number) r0.columns().get(1).values()[0]).doubleValue() + 1000);
        QueryService.Result m = run(ds, "{\"fields\":[\"trades.cpty\"],\"measures\":[\"Trades\"]}", masked);
        assertThat(m.rows()).isEqualTo(1);                                                // a masked column is never grouped on: one row, the mask
        assertThat(m.columns().get(0).values()[0]).isEqualTo("•••");
        assertThat(m.meta().masked()).contains("trades.cpty");
        assertThat(m.columns().get(1).values()[0]).isEqualTo(20L);
        assertThat(run(ds, "{\"measures\":[\"MTM\"]}", masked).columns().get(0).values()[0]).isNull();       // a masked measure reads null, update or no update
        assertThat(run(ds, body, nobody).columns().get(0).values()[0]).isEqualTo(0L);
    }

    // ---- sources that cannot push, and refusals ---------------------------------------------------------------------------

    @Test
    void aSourceThatCannotPushIsReadAgainOnTheResyncTimer() throws Exception {
        lake.pushes = false;
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofMillis(100));
        service = new QueryService(props, live, new DuckDbEngine(props), lake, clock);
        String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    mode: live");
        QueryService.Loaded ds = new QueryService.Loaded("file:dataset:poll", Layers.key("file:dataset:poll"), DatasetParser.parse(yaml).dataset(), "h");
        QueryService.Caller risk = who("rita", true, "", "risk");
        double before = mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk));
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        assertThat(t.status().pushes()).isFalse();
        String id = lake.docs.keySet().iterator().next().id();
        lake.set(id, "mtm", lake.docs.get(EntityRef.of("trade", id)).data().get("mtm").asDouble() + 10);
        eventually(() -> mtmOf(run(ds, "{\"measures\":[\"MTM\"]}", risk)) == before + 10);
    }

    @Test
    void aTableOverAnArrayCannotBeLive() {
        String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade, each: legs }\n    mode: live");
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems()).anyMatch(p -> p.error() && p.code().equals("DRS-9050") && p.message().contains("legs"));
        assertThat(r.ok()).isFalse();
    }

    @Test
    void retentionBelongsToLiveTablesAndIsChecked() {
        DatasetParser.Result cached = DatasetParser.parse(TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    live: { rows: 5 }"));
        assertThat(cached.problems()).anyMatch(p -> p.error() && p.path().endsWith(".live"));
        DatasetParser.Result bad = DatasetParser.parse(TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }",
                "source: { connector: trading-lake, kind: trade }\n    mode: live\n    live: { rows: 0, age: soon }"));
        assertThat(bad.problems()).anyMatch(p -> p.error() && p.path().endsWith("live.rows"));
        assertThat(bad.problems()).anyMatch(p -> p.error() && p.path().endsWith("live.age"));
        DatasetParser.Result good = DatasetParser.parse(TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }",
                "source: { connector: trading-lake, kind: trade }\n    mode: live\n    live: { rows: 500, age: 15m }"));
        assertThat(good.ok()).isTrue();
        assertThat(good.dataset().tables().get("trades").live().age()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aNameTheModelReservesCannotBeDeclared() {
        DatasetParser.Result r = DatasetParser.parse(TestLake.yaml().replace("      notional: decimal", "      notional: decimal\n      updateSeq: integer"));
        assertThat(r.problems()).anyMatch(p -> p.error() && p.path().endsWith("updateSeq"));
    }

    @Test
    void aMissingConnectorIsRefusedWithItsCodeAndStartsNothing() {
        QueryService.Loaded ds = start("");
        String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: nowhere, kind: trade }\n    mode: live");
        QueryService.Loaded broken = new QueryService.Loaded("file:dataset:broken", Layers.key("file:dataset:broken"), DatasetParser.parse(yaml).dataset(), "h");
        assertThatThrownBy(() -> run(broken, "{\"measures\":[\"Trades\"]}", who("rita", true, "", "risk"))).isInstanceOf(DrishtiException.class).hasMessageContaining("nowhere");
        assertThat(service.live().tables(broken.key())).isEmpty();
        assertThat(ds).isNotNull();
    }

    @Test
    void theTickCarriesTheGenerationAndTheStateAndTheTableIsClosedWhenDropped() throws Exception {
        QueryService.Loaded ds = start("");
        Map<String, Object> first = service.liveTick(ds);
        assertThat(first.get("state")).isEqualTo("live");
        long g = ((Number) first.get("generation")).longValue();
        lake.set(lake.docs.keySet().iterator().next().id(), "mtm", 424242.0);
        eventually(() -> ((Number) service.liveTick(ds).get("generation")).longValue() > g);
        List<Object> tables = new ArrayList<>((List<?>) service.liveTick(ds).get("tables"));
        assertThat(tables).hasSize(1);
        service.live().drop(ds.key());
        assertThat(service.live().tables(ds.key())).isEmpty();
        assertThat(lake.listeners.values().stream().mapToInt(List::size).sum()).as("subscriptions given back").isZero();
    }

    /** A named connector whose kinds leave the live table's out: the query and the tick say so (as a build does), never a live table that stays empty. */
    @Test
    void aNamedConnectorThatDoesNotServeTheKindIsSaidNotALiveTableWithNoData() {
        com.ash.drishti.api.SourcePlugin other = org.mockito.Mockito.mock(com.ash.drishti.api.SourcePlugin.class);
        org.mockito.Mockito.when(other.manifest()).thenReturn(new com.ash.drishti.api.PluginManifest("trading-lake", "1", Set.of("var", "pnl-explain"),
                new com.ash.drishti.api.SourceCapabilities(false, false, false, true)));
        lake = new LiveLake() {
            @Override
            public java.util.Optional<com.ash.drishti.api.SourcePlugin> plugin(String connector, String kind) {
                return "trading-lake".equals(connector) ? java.util.Optional.of(other) : super.plugin(connector, kind);
            }
        };
        QueryService.Loaded ds = start("");
        assertThatThrownBy(() -> run(ds, "{\"measures\":[\"Trades\"]}", who("rita", true, "", "risk"))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("connector 'trading-lake' does not serve kind 'trade' (its kinds: pnl-explain, var)")
                .hasMessageContaining("config/connectors/trading-lake.yaml");
        assertThatThrownBy(() -> service.liveTick(ds)).hasMessageContaining("does not serve kind 'trade'");
        assertThat(service.live().tables(ds.key())).as("no live table was started").isEmpty();
    }
}
