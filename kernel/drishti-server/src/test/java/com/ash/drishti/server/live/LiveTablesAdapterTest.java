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
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The entry check of the switch to Rachana's live tables (I7B_DUPLICATES DD-6, cut line DC1): the eleven scenarios of the liveset
 * engine's {@code LiveSetTest} and {@code StorePagesTest}, each run on {@code rachana-live} through Drishti's adapter ({@link KindSnapshot}
 * and {@link KindStream} over {@link KindBindings} fed with Drishti's {@link EntityDocument}s, {@link DrishtiAuthoriser},
 * {@link DrishtiChangeSink}). Each test keeps the name of the case it ports.
 */
class LiveTablesAdapterTest {

    static final Set<String> PROFILE = DrishtiLiveTables.PROFILE_KEYS;

    /** Drishti's bindings for one kind, in memory: documents (whole), or a columnar store; the live listeners the test drives. */
    static class Bindings implements KindBindings {
        final List<EntityDocument> docs = new CopyOnWriteArrayList<>();
        final List<Consumer<EntityDocument>> listeners = new CopyOnWriteArrayList<>();
        volatile Runnable duringRead = () -> { };
        volatile Store store;

        void live(EntityDocument d) {
            listeners.forEach(l -> l.accept(d));
        }

        @Override
        public Optional<Store> store(String kind) {
            return Optional.ofNullable(store);
        }

        @Override
        public boolean documents(String kind, Consumer<EntityDocument> sink) {
            if (store != null) {
                throw new AssertionError("a paged table never reads every document");
            }
            duringRead.run();
            docs.forEach(sink);
            return true;
        }

        @Override
        public Map<String, DataNode> fetch(String kind, Collection<String> ids) {
            Map<String, DataNode> out = new LinkedHashMap<>();
            docs.forEach(d -> {
                if (ids.contains(d.ref().id())) {
                    out.put(d.ref().id(), d.data());
                }
            });
            return out;
        }

        @Override
        public AutoCloseable subscribe(String kind, Consumer<EntityDocument> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }

    static final String TRADE = """
            rachana: 1
            live-table: trade
            kind: trade
            snapshot: trade
            stream: trade
            key: TRADE_ID
            version: VERSION
            changed-by: UPDATED_BY
            changed-at: UPDATED_AT
            columns: [ { name: TRADE_ID }, { name: VERSION, type: integer }, { name: BOOK }, { name: NOTIONAL, type: integer },
                       { name: UPDATED_BY }, { name: UPDATED_AT, type: timestamp } ]
            """;

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

    static LiveTables engine(Bindings b, String decl, LiveTables.Options o, Clock clock) {
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            @Override
            public SnapshotSource snapshot(String kind) {
                return new KindSnapshot(kind, b, List.of("TRADE_ID"));
            }

            @Override
            public LiveStream stream(String kind) {
                return new KindStream(kind, b, List.of("TRADE_ID"));
            }
        }, (o == null ? LiveTables.Options.DEFAULTS : o).withUpkeepMs(0), clock);
        lt.declare(LiveTableSpec.read(decl, "trade.live.yaml", PROFILE, f -> true));
        return lt.openReads();
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> values(Map<String, Object> page) {
        return (List<List<Object>>) page.get("values");
    }

    static List<Object> col(Map<String, Object> page, int i) {
        return values(page).stream().map(r -> r.get(i)).toList();
    }

    static Map<String, Object> q(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("data");
    }

    static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 200 && !done.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
    }

    @Test
    void changesMergeByKeyAndVersionWithoutDuplicatesOrGoingBack() {
        Bindings b = new Bindings();
        b.docs.add(trade("T1", 1, "B1", null));
        b.docs.add(trade("T2", 3, "B1", null));
        LiveTables lt = engine(b, TRADE + "where: \"@.BOOK != 'CXL'\"\n", null, Clock.systemUTC());
        assertThat(lt.rows("trade", Map.of(), null).get("total")).isEqualTo(2L);
        b.live(trade("T1", 2, "B2", null));          // newer: replaces
        b.live(trade("T1", 2, "B9", null));          // the same version again: dropped
        b.live(trade("T2", 1, "B7", null));          // older: dropped
        b.live(trade("T3", 1, "B3", null));          // new: inserted
        Map<String, Object> p = lt.rows("trade", q("columns", List.of("TRADE_ID", "VERSION", "BOOK"), "sort", List.of(Map.of("col", "TRADE_ID"))), null);
        assertThat(values(p)).containsExactly(List.of("T1", 2L, "B2"), List.of("T2", 3L, "B1"), List.of("T3", 1L, "B3"));
        b.live(trade("T3", 2, "CXL", null));         // no longer holds the condition: leaves
        b.live(EntityDocument.deleted(EntityRef.of("trade", "T2"), new Provenance("trade-kafka", 9, Instant.now(), true)));
        assertThat(lt.rows("trade", Map.of(), null).get("total")).isEqualTo(1L);
        assertThat(lt.describe().get(0)).containsEntry("staleDropped", 2L);
    }

    @Test
    void whatArrivesWhileTheSnapshotIsReadIsAppliedAfterItByVersion() {
        Bindings b = new Bindings();
        b.docs.add(trade("T1", 4, "SNAPSHOT", null));
        b.docs.add(trade("T2", 1, "SNAPSHOT", null));
        b.duringRead = () -> b.live(trade("T1", 5, "LIVE", null));   // the stream runs ahead of the snapshot
        LiveTables lt = engine(b, TRADE, null, Clock.systemUTC());
        Map<String, Object> p = lt.rows("trade", q("columns", List.of("TRADE_ID", "VERSION", "BOOK"), "sort", List.of(Map.of("col", "TRADE_ID"))), null);
        assertThat(values(p)).containsExactly(List.of("T1", 5L, "LIVE"), List.of("T2", 1L, "SNAPSHOT"));
    }

    @Test
    void aBurstOfTenThousandUpdatesReachesAViewerCoalescedOrAsOneResync() throws Exception {
        Bindings b = new Bindings();
        LiveTables lt = engine(b, TRADE, LiveTables.Options.DEFAULTS.withFlushMs(30).withWindowCapacity(5_000), Clock.systemUTC());
        lt.rows("trade", Map.of(), null);
        List<Map<String, Object>> got = new CopyOnWriteArrayList<>();
        List<List<Object>> keys = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            keys.add(List.of("T" + i));
        }
        Subscription w = lt.subscribe("trade", new TableProvider.Subscribe(null, null, 0, keys, false), got::add, null);
        long t0 = System.nanoTime();
        for (int v = 1; v <= 5; v++) {
            for (int i = 0; i < 2_000; i++) {
                b.live(trade("T" + i, v, "B" + (i % 7), null));
            }
        }
        long applyMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(applyMs).as("10,000 updates applied in %d ms", applyMs).isLessThan(5_000);
        await(() -> got.stream().mapToLong(f -> f.get("data") instanceof Map<?, ?> d && d.get("total") instanceof Long n ? n : 0).max().orElse(0) >= 2_000
                && lastVersions(got).size() == 2_000 && lastVersions(got).values().stream().allMatch(v -> Long.valueOf(5).equals(v)));
        assertThat(got.size()).as("coalesced: %d patches for 10,000 updates", got.size()).isLessThan(200);
        assertThat(lastVersions(got)).hasSize(2_000).allSatisfy((k, v) -> assertThat(v).isEqualTo(5L));
        // more keys waiting than the viewer's capacity: one unrecoverable gap (the viewer resyncs), nothing queued without bound
        got.clear();
        Map<String, Object> at = lt.version("trade", null);
        Subscription small = lt.subscribe("trade", new TableProvider.Subscribe(at.get("epoch") + ":" + at.get("seq"), null, 0, keys.subList(0, 2_000),
                false), got::add, null);
        LiveTables tight = engine(b, TRADE, LiveTables.Options.DEFAULTS.withFlushMs(200).withWindowCapacity(100), Clock.systemUTC());
        tight.rows("trade", Map.of(), null);
        List<Map<String, Object>> burst = new CopyOnWriteArrayList<>();
        Subscription sw = tight.subscribe("trade", new TableProvider.Subscribe(null, null, 0, keys, false), burst::add, null);
        for (int i = 0; i < 2_000; i++) {
            b.live(trade("T" + i, 6, "B", null));
        }
        await(() -> burst.stream().anyMatch(f -> "gap".equals(f.get("t"))));
        assertThat(burst).anyMatch(f -> "gap".equals(f.get("t")) && Boolean.FALSE.equals(f.get("recoverable")));
        assertThat(sw.closed()).isTrue();
        w.close();
        small.close();
        tight.close();
        lt.close();
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> lastVersions(List<Map<String, Object>> frames) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> f : frames) {
            if (f.get("data") instanceof Map<?, ?> d && d.get("update") instanceof List<?> up) {
                for (Object r : up) {
                    Map<String, Object> row = (Map<String, Object>) r;
                    out.put(row.get("TRADE_ID"), row.get("VERSION"));
                }
            }
        }
        return out;
    }

    @Test
    void aPagedViewerIsSentChangesOfItsRowsAndOnlyTheCountOfOthers() throws Exception {
        Bindings b = new Bindings();
        b.docs.add(trade("T1", 1, "B", null));
        b.docs.add(trade("T2", 1, "B", null));
        LiveTables lt = engine(b, TRADE, LiveTables.Options.DEFAULTS.withFlushMs(10), Clock.systemUTC());
        List<Map<String, Object>> got = new CopyOnWriteArrayList<>();
        lt.subscribe("trade", new TableProvider.Subscribe(null, null, 0, List.of(List.of("T1")), false), got::add, null);
        b.live(trade("T2", 2, "B", null));
        b.live(trade("T3", 1, "B", null));
        await(() -> got.stream().anyMatch(f -> data(f) != null && Long.valueOf(3).equals(data(f).get("total"))));
        Map<String, Object> countOnly = got.stream().filter(f -> data(f) != null && Long.valueOf(3).equals(data(f).get("total"))).findFirst().orElseThrow();
        assertThat(data(countOnly)).doesNotContainKey("update");
        got.clear();
        b.live(trade("T1", 2, "B", null));
        await(() -> got.stream().anyMatch(f -> data(f) != null && data(f).get("update") != null));
        assertThat(got.stream().filter(f -> data(f) != null && data(f).get("update") != null).findFirst().orElseThrow())
                .satisfies(f -> assertThat((List<?>) data(f).get("update")).hasSize(1));
        lt.close();
    }

    @Test
    void inANewestFirstOrderANewOrChangedRowIsInsertedOnTopOfAPageAtTheTopAndOnlyCountedElsewhere() throws Exception {
        Bindings b = new Bindings();
        b.docs.add(trade("T1", 1, "B", "2026-11-14T10:00:00Z"));
        b.docs.add(trade("T2", 1, "B", "2026-11-14T10:00:01Z"));
        LiveTables lt = engine(b, TRADE + "sort: { col: UPDATED_AT, dir: desc }\n", LiveTables.Options.DEFAULTS.withFlushMs(10), Clock.systemUTC());
        assertThat(col(lt.rows("trade", q("columns", List.of("TRADE_ID")), null), 0)).containsExactly("T2", "T1");
        List<Map<String, Object>> top = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> below = new CopyOnWriteArrayList<>();
        lt.subscribe("trade", new TableProvider.Subscribe(null, null, 0, List.of(List.of("T2")), true), top::add, null);   // the first row
        lt.subscribe("trade", new TableProvider.Subscribe(null, null, 0, List.of(List.of("T1")), false), below::add, null); // scrolled down
        b.live(trade("T3", 1, "B", "2026-11-14T10:05:00Z"));     // a new trade
        b.live(trade("T1", 2, "B", "2026-11-14T10:05:01Z"));     // a changed one the top page does not hold
        await(() -> tops(top).containsAll(List.of("T3", "T1")) && below.stream().anyMatch(f -> data(f) != null && data(f).get("update") != null));
        assertThat(tops(top)).containsExactlyInAnyOrder("T3", "T1");
        assertThat(below).noneMatch(f -> data(f) != null && data(f).containsKey("top"));
        assertThat(lastVersions(below)).containsOnlyKeys("T1");              // T1 is on its page; T3 only moves its count
        assertThat(below).anyMatch(f -> data(f) != null && Long.valueOf(3).equals(data(f).get("total")));
        lt.close();
        // a declaration ordered otherwise (by book) never inserts: a new row's place is not the top
        Bindings c = new Bindings();
        c.docs.add(trade("T1", 1, "B", null));
        LiveTables byBook = engine(c, TRADE + "sort: { col: BOOK, dir: desc }\n", LiveTables.Options.DEFAULTS.withFlushMs(10), Clock.systemUTC());
        List<Map<String, Object>> other = new CopyOnWriteArrayList<>();
        byBook.subscribe("trade", new TableProvider.Subscribe(null, null, 0, List.of(List.of("T1")), true), other::add, null);
        c.live(trade("T9", 1, "Z", null));
        await(() -> other.stream().anyMatch(f -> data(f) != null && Long.valueOf(2).equals(data(f).get("total"))));
        assertThat(other).anyMatch(f -> data(f) != null && Long.valueOf(2).equals(data(f).get("total")))
                .noneMatch(f -> data(f) != null && data(f).containsKey("top"));
        byBook.close();
    }

    static List<Object> tops(List<Map<String, Object>> frames) {
        List<Object> out = new ArrayList<>();
        frames.forEach(f -> {
            if (data(f) != null && data(f).get("top") instanceof List<?> l) {
                l.forEach(k -> out.add(k instanceof List<?> kl ? kl.get(0) : k));
            }
        });
        return out;
    }

    @Test
    void retentionDropsRowsPastTheirAgeAndBeyondTheCapOldestFirst() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
        Bindings b = new Bindings();
        LiveTables lt = engine(b, TRADE + "retention: { ttl: 8h, on: UPDATED_AT, max-rows: 2 }\n", null, clock);
        lt.rows("trade", Map.of(), null);
        b.live(trade("OLD", 1, "B", "2026-11-14T01:00:00Z"));
        b.live(trade("A", 1, "B", "2026-11-14T09:00:00Z"));
        b.live(trade("B", 1, "B", "2026-11-14T10:00:00Z"));
        b.live(trade("C", 1, "B", "2026-11-14T11:00:00Z"));
        lt.upkeep();
        assertThat(col(lt.rows("trade", q("columns", List.of("TRADE_ID"), "sort", List.of(Map.of("col", "TRADE_ID"))), null), 0)).containsExactly("B", "C");
    }

    static final String EDITABLE = TRADE + """
            tray: true
            edit:
              edit: cells
              roles: [trader]
              fields:
              - { id: book, bind: BOOK }
              save: { target: source, command: amend-trade }
            """;

    @Test
    void anEditPassesOnlyWhenTheConfigurationAndTheRightsAllowItAndTheVersionIsCurrent() {
        Bindings b = new Bindings();
        b.docs.add(trade("T1", 7, "B1", null));
        List<Map<String, Object>> audit = new CopyOnWriteArrayList<>();
        List<JsonNode> sent = new CopyOnWriteArrayList<>();
        String[] off = {"editing is off on this server (edit.enabled in config/edit/edit.yaml)"};
        Map<String, LiveTableSpec> specs = new LinkedHashMap<>();
        DrishtiAuthoriser.Rights rights = new DrishtiAuthoriser.Rights() {
            public boolean mayOpen(Principal p, String kind) { return true; }
            public String signature(Principal p) { return String.join(",", p.roles()); }
            public DrishtiAuthoriser.RowRule rows(Principal p, String kind) { return null; }
            public UnaryOperator<Map<String, Object>> redact(Principal p) { return null; }
            public boolean masked(Principal p, String field) { return false; }
            public String readOnly(Principal p, LiveTableSpec spec) {
                return off[0] != null ? off[0] : p.roles().contains("trader") ? null : p.user() + " does not hold the edit power";
            }
            public List<String> formRoles(Principal p) { return p.roles(); }
        };
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            public SnapshotSource snapshot(String kind) { return new KindSnapshot(kind, b, List.of("TRADE_ID")); }
            public LiveStream stream(String kind) { return new KindStream(kind, b, List.of("TRADE_ID")); }
        }, LiveTables.Options.DEFAULTS.withUpkeepMs(0), Clock.systemUTC());
        lt.authoriser(new DrishtiAuthoriser(specs::get, rights)).audit(audit::add)
                .sink(new DrishtiChangeSink(specs::get, (p, body) -> {
                    sent.add(body);
                    return List.of(Map.of("id", "chg-1", "state", "awaiting", "needed", 1));
                }));
        LiveTableSpec spec = LiveTableSpec.read(EDITABLE, "trade.live.yaml", PROFILE, f -> true);
        specs.put("trade", spec);
        lt.declare(spec);
        Principal ann = new Principal("ann", List.of("trader"));
        Principal rita = new Principal("rita", List.of("viewer"));
        Map<String, Object> change = q("id", "01JCHANGE0001", "op", "update", "key", List.of("T1"), "basedOn", 7L, "set", Map.of("BOOK", "B2"),
                "from", Map.of("BOOK", "B1"));
        // read-only by configuration: the write-back is off on this server
        assertThatThrownBy(() -> DrishtiChangeSink.as(ann, () -> lt.write("trade", change, ann))).isInstanceOf(ProtocolError.class)
                .hasMessageContaining("editing is off");
        off[0] = null;
        // read-only by rights: a crafted request from someone without the edit power is refused and audited
        assertThatThrownBy(() -> DrishtiChangeSink.as(rita, () -> lt.write("trade", q("id", "01JCHANGE0002", "key", List.of("T1"), "basedOn", 7L,
                "set", Map.of("BOOK", "B2")), rita))).isInstanceOf(ProtocolError.class).hasMessageContaining("does not hold the edit power");
        assertThat(audit).anyMatch(e -> "write-refused".equals(e.get("event")) && String.valueOf(e.get("reason")).contains("rita"));
        assertThat(lt.schemaDoc("trade", rita)).doesNotContainKey("write");
        assertThat(lt.schemaDoc("trade", ann)).containsKey("write");
        // a stale version: refused, saying who changed it and at which version
        assertThatThrownBy(() -> DrishtiChangeSink.as(ann, () -> lt.write("trade", q("id", "01JCHANGE0003", "key", List.of("T1"), "basedOn", 6L,
                "set", Map.of("BOOK", "B2")), ann))).isInstanceOfSatisfying(ProtocolError.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(String.valueOf(e.error())).contains("kim").contains("7");
                });
        // editable: handed to the write-back as the person, under the declaration's form, with the version seen; four eyes holds it
        Map<String, Object> ans = DrishtiChangeSink.as(ann, () -> lt.write("trade", change, ann));
        assertThat(ans).containsEntry("state", "held");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("sutra").asText()).isEqualTo("live:trade");
        assertThat(sent.get(0).path("basedOn").asLong()).isEqualTo(7);
        assertThat(sent.get(0).path("id").asText()).isEqualTo("01JCHANGE0001");
        assertThat(sent.get(0).path("items").get(0).path("field").asText()).isEqualTo("BOOK");
        // the same change id again: its first answer, never handed over twice
        assertThat(DrishtiChangeSink.as(ann, () -> lt.write("trade", change, ann))).containsEntry("state", "held");
        assertThat(sent).hasSize(1);
        assertThat(DrishtiLiveTables.rules(List.of(spec))).extracting(r -> r.field().recordField()).containsExactly("BOOK");
        lt.close();
    }

    @Test
    void aDeclarationWithProblemsSaysThemAll() {
        assertThatThrownBy(() -> LiveTableSpec.read("""
                rachana: 1
                live-table: Bad Id
                kind: trade
                snapshot: trade
                key: A
                columns: [ { name: A } ]
                retention: { ttl: soon }
                links: [ { label: x, href: "https://elsewhere" } ]
                """, "x.live.yaml", PROFILE, f -> true)).satisfies(e -> {
                    String all = String.valueOf(e instanceof com.ash.rachana.SutraException s ? s.problems() : e.getMessage());
                    assertThat(all).contains("RCH-2020").contains("is not a table name").contains("retention.ttl");
                });
        assertThat(DrishtiLiveTables.problems(LiveTableSpec.read(TRADE + "links: [ { label: x, href: \"https://elsewhere\" } ]\n", "t.live.yaml", PROFILE,
                f -> true), f -> true, k -> Optional.empty())).anyMatch(p -> p.contains("href is a path"));
    }

    // ---- StorePagesTest ------------------------------------------------------------------------------------------------

    /** A narrow kind: POS_ID, BOOK, QTY in a columnar store, not in key order; counts what it is asked. */
    static final class Narrow extends Bindings {
        final String[] ids;
        final String[] books;
        final double[] qty;
        final Map<String, Integer> at = new java.util.HashMap<>();
        final AtomicInteger fetched = new AtomicInteger();

        Narrow(int n) {
            ids = new String[n];
            books = new String[n];
            qty = new double[n];
            for (int i = 0; i < n; i++) {
                ids[i] = String.format("P%07d", (i * 7919L) % n);
                books[i] = "B" + (i % 13);
                qty[i] = (i * 31L) % 1000;
                at.put(ids[i], i);
            }
            store = new Store() {
                @Override
                public String version() {
                    return "v1";
                }

                @Override
                public boolean project(List<String> fields, Projection sink) {
                    for (int i = 0; i < ids.length; i++) {
                        Object[] v = new Object[fields.size()];
                        for (int j = 0; j < v.length; j++) {
                            v[j] = switch (fields.get(j)) {
                                case "BOOK" -> books[i];
                                case "QTY" -> qty[i];
                                case "POS_ID" -> ids[i];
                                default -> null;
                            };
                        }
                        sink.row(ids[i], v);
                    }
                    return true;
                }

                @Override
                public Map<String, DataNode> fetch(Collection<String> want) {
                    Map<String, DataNode> out = new LinkedHashMap<>();
                    for (String id : want) {
                        fetched.incrementAndGet();
                        Integer i = at.get(id);
                        if (i != null) {
                            out.put(id, DataNode.of(new LinkedHashMap<>(Map.of("POS_ID", ids[i], "BOOK", books[i], "QTY", qty[i]))));
                        }
                    }
                    return out;
                }
            };
        }

        void tick(String id, String book, double q) {
            live(new EntityDocument(EntityRef.of("position", id), DataNode.of(new LinkedHashMap<>(Map.of("POS_ID", id, "BOOK", book, "QTY", q))),
                    new Provenance("test", 1, Instant.now(), true)));
        }

        void delete(String id) {
            live(EntityDocument.deleted(EntityRef.of("position", id), new Provenance("test", 1, Instant.now(), true)));
        }
    }

    static final String POSITION = DrishtiLiveTables.defaultName("position");

    static LiveTables positions(Bindings b) {
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            public SnapshotSource snapshot(String kind) { return new KindSnapshot(kind, b, List.of("POS_ID")); }
            public LiveStream stream(String kind) { return new KindStream(kind, b, List.of("POS_ID")); }
        }, LiveTables.Options.DEFAULTS.withUpkeepMs(0), Clock.systemUTC());
        lt.declare(DrishtiLiveTables.defaultSpec("position", List.of("$.BOOK", "$.QTY"), Set.of(), "POS", "POS_ID").orElseThrow());
        return lt.openReads();
    }

    static List<String> expected(Narrow n, Map<String, Map<String, Object>> live, Set<String> removed, String sort, boolean desc, String book) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (int i = 0; i < n.ids.length; i++) {
            all.put(n.ids[i], Map.of("BOOK", n.books[i], "QTY", n.qty[i]));
        }
        removed.forEach(all::remove);
        all.putAll(live);
        List<Map.Entry<String, Map<String, Object>>> e = new ArrayList<>(all.entrySet());
        if (book != null) {
            e.removeIf(x -> !book.equals(x.getValue().get("BOOK")));
        }
        Comparator<Map.Entry<String, Map<String, Object>>> c = Map.Entry.comparingByKey();
        if (sort != null) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Comparator<Map.Entry<String, Map<String, Object>>> v = (a, x) -> ((Comparable) a.getValue().get(sort)).compareTo(x.getValue().get(sort));
            c = (desc ? v.reversed() : v).thenComparing(Map.Entry.comparingByKey());
        }
        e.sort(c);
        return e.stream().map(Map.Entry::getKey).toList();
    }

    @Test
    void pagesAndTotalsEqualTheMergedOrderWithTheLiveSide() {
        Narrow n = new Narrow(2_000);
        LiveTables lt = positions(n);
        assertThat(lt.rows(POSITION, q("limit", 50L), null).get("total")).isEqualTo(2_000L);
        Map<String, Map<String, Object>> live = new LinkedHashMap<>();
        Set<String> removed = new HashSet<>();
        n.tick("P0000005", "B99", 5_000);                       // a store record changed: shadowed, sorts elsewhere now
        live.put("P0000005", Map.of("BOOK", "B99", "QTY", 5_000.0));
        n.tick("P9999999", "B1", 1.5);                          // a new record
        live.put("P9999999", Map.of("BOOK", "B1", "QTY", 1.5));
        n.tick("A0000000", "B2", 999.0);                        // a new record sorting first by key
        live.put("A0000000", Map.of("BOOK", "B2", "QTY", 999.0));
        n.delete("P0000010");                                   // store records deleted
        removed.add("P0000010");
        n.delete("P0001500");
        removed.add("P0001500");
        for (String sort : new String[] {null, "QTY", "BOOK"}) {
            for (boolean desc : new boolean[] {false, true}) {
                for (String book : new String[] {null, "B1"}) {
                    List<String> want = expected(n, live, removed, sort, desc, book);
                    for (int offset : new int[] {0, 1, 2, 3, 37, 500, want.size() - 3, want.size() + 5}) {
                        Map<String, Object> query = q("columns", List.of("POS_ID"), "offset", (long) Math.max(0, offset), "limit", 25L);
                        query.put("sort", sort == null ? List.of(Map.of("col", "POS_ID")) : List.of(Map.of("col", sort, "dir", desc ? "desc" : "asc"),
                                Map.of("col", "POS_ID")));
                        if (book != null) {
                            query.put("filter", Map.of("op", "==", "args", List.of(Map.of("col", "BOOK"), Map.of("lit", book))));
                        }
                        Map<String, Object> p = lt.rows(POSITION, query, null);
                        int from = Math.min(Math.max(0, offset), want.size());
                        assertThat(col(p, 0)).as("sort %s desc %s book %s offset %d", sort, desc, book, offset)
                                .isEqualTo(want.subList(from, Math.min(want.size(), from + 25)));
                        assertThat(p.get("total")).isEqualTo((long) want.size());
                    }
                }
            }
        }
        Map<String, Object> byKey = q("filter", Map.of("op", "==", "args", List.of(Map.of("col", "POS_ID"), Map.of("lit", "P0000010"))));
        assertThat(values(lt.rows(POSITION, byKey, null))).isEmpty();                     // removed by the live side
        byKey.put("filter", Map.of("op", "==", "args", List.of(Map.of("col", "POS_ID"), Map.of("lit", "P0000005"))));
        assertThat(values(lt.rows(POSITION, byKey, null))).singleElement().satisfies(r -> assertThat(r).contains("B99", 5_000.0));
        lt.close();
    }

    @Test
    void aMillionRecordsPageWithinTheBudget() {
        int count = Integer.getInteger("drishti.test.live-rows", 1_000_000);
        if (count >= 1_000_000) {
            Assumptions.assumeTrue(availableGb() >= 15, "a million-record run needs 15 GB of memory free");
            Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= 2L << 30, "a million-record run needs a 2 GB heap");
        }
        Narrow n = new Narrow(count);
        LiveTables lt = positions(n);
        long t0 = System.nanoTime();
        Map<String, Object> first = lt.rows(POSITION, q("limit", 100L, "sort", List.of(Map.of("col", "POS_ID"))), null);
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        Map<String, Object> deep = lt.rows(POSITION, q("offset", (long) count / 2, "limit", 100L, "sort", List.of(Map.of("col", "POS_ID"))), null);
        long nextMs = (System.nanoTime() - t1) / 1_000_000;
        Map<String, Object> bySize = q("limit", 100L, "columns", List.of("POS_ID", "QTY"), "sort", List.of(Map.of("col", "QTY", "dir", "desc")));
        long t2 = System.nanoTime();
        lt.rows(POSITION, bySize, null);
        long sortMs = (System.nanoTime() - t2) / 1_000_000;
        n.tick("P0000001", "B1", 10_000);
        long t3 = System.nanoTime();
        Map<String, Object> after = lt.rows(POSITION, bySize, null);
        long liveMs = (System.nanoTime() - t3) / 1_000_000;
        System.out.printf("live tables at %,d records: first page %d ms, a page half-way %d ms, first sorted page %d ms, a page after a live"
                + " change %d ms; %d fetched%n", count, firstMs, nextMs, sortMs, liveMs, n.fetched.get());
        assertThat(first.get("total")).isEqualTo((long) count);
        assertThat(values(first)).hasSize(100);
        assertThat(values(deep)).hasSize(100);
        assertThat(col(after, 0).get(0)).isEqualTo("P0000001");
        assertThat(after.get("total")).isEqualTo((long) count);
        assertThat(n.fetched.get()).isLessThanOrEqualTo(400);              // the pages' rows only, never the store whole
        assertThat(firstMs).isLessThan(10_000);
        assertThat(nextMs).isLessThan(1_000);
        assertThat(liveMs).isLessThan(1_000);
        lt.close();
    }

    static long availableGb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / (1024 * 1024);
                }
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    @Test
    void aBlotterHoldsTheLakesTradesAndTheStreamedOnesMergedByKeyAndVersionNewestFirst() {
        Bindings lake = new Bindings();
        Map<String, DataNode> docs = new LinkedHashMap<>();
        List<List<String>> asked = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            String id = String.format("L%05d", i);
            docs.put(id, DataNode.of(new LinkedHashMap<>(Map.of("TRADE_ID", id, "TRADE_VERSION", 1L, "TRADE_STATUS", "NEW", "SOURCE_UPDATE_TIME",
                    "2026-11-13T09:00:00Z"))));
        }
        lake.store = new KindBindings.Store() {          // the lake keeps TRADE_ID and TRADE_STATUS as columns, the update time only in documents
            public String version() { return "v1"; }
            public boolean column(String field) { return field.equals("TRADE_ID") || field.equals("TRADE_STATUS"); }
            public boolean project(List<String> fields, KindBindings.Projection sink) {
                asked.add(fields);
                docs.forEach((id, d) -> sink.row(id, fields.stream().map(f -> column(f) ? d.get(f).unwrap() : null).toArray()));
                return true;
            }
            public Map<String, DataNode> fetch(Collection<String> want) {
                Map<String, DataNode> out = new LinkedHashMap<>();
                want.forEach(id -> {
                    if (docs.containsKey(id)) {
                        out.put(id, docs.get(id));
                    }
                });
                return out;
            }
        };
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            public SnapshotSource snapshot(String kind) { return new KindSnapshot(kind, lake, List.of("TRADE_ID")); }
            public LiveStream stream(String kind) { return new KindStream(kind, lake, List.of("TRADE_ID")); }
        }, LiveTables.Options.DEFAULTS.withUpkeepMs(0), Clock.systemUTC()).openReads();
        lt.declare(LiveTableSpec.read("""
                rachana: 1
                live-table: trade
                kind: trade
                snapshot: trade
                stream: trade
                where: "@.TRADE_STATUS != 'CANCELLED'"
                key: TRADE_ID
                version: TRADE_VERSION
                changed-at: SOURCE_UPDATE_TIME
                columns: [ { name: TRADE_ID }, { name: TRADE_VERSION, type: integer }, { name: TRADE_STATUS }, { name: SOURCE_UPDATE_TIME, type: timestamp } ]
                sort: { col: SOURCE_UPDATE_TIME, dir: desc }
                """, "trade.live.yaml", PROFILE, f -> true));
        java.util.function.BiConsumer<String, Object[]> stream = (id, va) -> lake.live(new EntityDocument(EntityRef.of("trade", id),
                DataNode.of(new LinkedHashMap<>(Map.of("TRADE_ID", id, "TRADE_VERSION", va[0], "TRADE_STATUS", "NEW", "SOURCE_UPDATE_TIME", va[1]))),
                new Provenance("trade-kafka", (Long) va[0], Instant.now(), true)));
        lt.rows("trade", q("limit", 1L), null);
        stream.accept("S00001", new Object[] {1L, "2026-11-14T10:00:01Z"});     // streamed: not in the lake
        stream.accept("S00002", new Object[] {1L, "2026-11-14T10:00:03Z"});
        stream.accept("L00500", new Object[] {2L, "2026-11-14T10:00:02Z"});     // a lake trade amended on the stream: one row, its new version
        stream.accept("L00500", new Object[] {1L, "2026-11-14T09:59:00Z"});     // an older version arriving late never goes back
        Map<String, Object> p = lt.rows("trade", q("limit", 6L, "columns", List.of("TRADE_ID", "TRADE_VERSION")), null);
        assertThat(col(p, 0)).containsExactly("S00002", "L00500", "S00001", "L00000", "L00001", "L00002");
        assertThat(p.get("total")).isEqualTo(1_002L);                           // the lake's thousand and the two new ones
        assertThat(values(p).get(1).get(1)).isEqualTo(2L);
        assertThat(col(lt.rows("trade", q("limit", 2_000L, "columns", List.of("TRADE_ID")), null), 0)).doesNotHaveDuplicates().hasSize(1_002);
        assertThat(asked).allSatisfy(f -> assertThat(f).doesNotContain("SOURCE_UPDATE_TIME"));   // the lake read by its columns only
        lt.close();
    }
}
