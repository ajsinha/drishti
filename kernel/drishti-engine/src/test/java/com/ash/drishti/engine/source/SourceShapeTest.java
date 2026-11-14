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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Several source systems feeding one canonical kind (DATA_LAKE_GUIDE.md, "Several source systems: shared or separate tables"): a shared
 * table split by {@code where}, separate tables, {@code set} stamping, {@code map} renames with Raw JSON's native record, two live
 * connectors, a system switched off and on, and the refusals ({@code DRS-5034}). The shape is applied where every connector meets the router,
 * whatever its plugin; {@code SourceShapeParityTest} (drishti-it) reads the same rows from Delta, Iceberg and files.
 */
class SourceShapeTest {

    /** The tables of the stand-in store, by name: rows by id. */
    static final Map<String, Map<String, Map<String, Object>>> TABLES = new ConcurrentHashMap<>();
    /** The live feeds of the stand-in store, by table. */
    static final Map<String, List<Consumer<EntityDocument>>> FEEDS = new ConcurrentHashMap<>();

    @TempDir
    Path tmp;

    /** A store that reads one table ({@code table} setting) as documents, columns, hits and a live feed: any plugin's stand-in. */
    public static final class Store implements SourcePlugin {
        private String table;
        private String name = "store";

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("store", "t", Set.of("trade"), new SourceCapabilities(true, false, true));
        }

        @Override
        public void start(SourceContext c) {
            table = c.setting("table", "trades");
            name = c.setting("source-name", "store");
        }

        private Map<String, Map<String, Object>> rows() {
            return TABLES.getOrDefault(table, Map.of());
        }

        private EntityDocument doc(String id, Map<String, Object> row) {
            return new EntityDocument(EntityRef.of("trade", id), DataNode.of(row), new Provenance(name, 1, Instant.now(), false));
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            Map<String, Object> r = rows().get(ref.id());
            return r == null ? Optional.empty() : Optional.of(doc(ref.id(), r));
        }

        @Override
        public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
            rows().forEach((id, r) -> sink.accept(doc(id, r)));
            return true;
        }

        @Override
        public List<EntityHit> search(String kind, String text, int limit) {
            return rows().keySet().stream().filter(id -> id.contains(text)).map(id -> new EntityHit(EntityRef.of("trade", id), id, "")).toList();
        }

        @Override
        public Set<String> columnar(String kind) {
            Set<String> out = new TreeSet<>();
            rows().values().forEach(r -> out.addAll(r.keySet()));
            return out;
        }

        @Override
        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            List<String> ids = new ArrayList<>(rows().keySet());
            Map<String, String[]> texts = new LinkedHashMap<>();
            Map<String, double[]> numbers = new LinkedHashMap<>();
            for (String p : paths) {
                boolean num = rows().values().stream().anyMatch(r -> r.get(p) instanceof Number);
                if (num) {
                    double[] v = new double[ids.size()];
                    for (int i = 0; i < v.length; i++) {
                        Object x = rows().get(ids.get(i)).get(p);
                        v[i] = x instanceof Number n ? n.doubleValue() : Double.NaN;
                    }
                    numbers.put(p, v);
                } else {
                    String[] v = new String[ids.size()];
                    for (int i = 0; i < v.length; i++) {
                        Object x = rows().get(ids.get(i)).get(p);
                        v[i] = x == null ? null : String.valueOf(x);
                    }
                    texts.put(p, v);
                }
            }
            return Optional.of(new ColumnSet(ids.toArray(String[]::new), numbers, texts, null));
        }

        @Override
        public Optional<com.ash.drishti.api.ArrowScan> scan(ScanRequest request) {
            throw new AssertionError("a shaped connector never scans its plugin directly");
        }

        @Override
        public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
            return subscribeKind(ref.kind(), listener);
        }

        @Override
        public Subscription subscribeKind(String kind, Consumer<EntityDocument> listener) {
            FEEDS.computeIfAbsent(table, t -> new CopyOnWriteArrayList<>()).add(listener);
            return () -> FEEDS.get(table).remove(listener);
        }

        @Override
        public boolean pushes(EntityRef ref) {
            return true;
        }

        @Override
        public Optional<String> dataVersion(String kind) {
            return Optional.of("v1");
        }
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void tables() {
        TABLES.clear();
        FEEDS.clear();
        Map<String, Map<String, Object>> shared = new ConcurrentHashMap<>();
        shared.put("E1", row("TRADE_ID", "E1", "SOURCE_SYSTEM", "ENDUR", "DESK", "RATES-NY", "NOTIONAL", 100.0));
        shared.put("E2", row("TRADE_ID", "E2", "SOURCE_SYSTEM", "ENDUR", "DESK", "FX-LDN", "NOTIONAL", 200.0));
        shared.put("C1", row("TRADE_ID", "C1", "SOURCE_SYSTEM", "CALYPSO", "DESK", "RATES-NY", "NOTIONAL", 300.0));
        TABLES.put("trades", shared);
        Map<String, Map<String, Object>> endur = new ConcurrentHashMap<>();
        endur.put("E9", row("TRAN_NUM", "E9", "PORTFOLIO", "RATES-NY", "NOTIONAL_AMT", "1,500")); // native names, no SOURCE_SYSTEM
        TABLES.put("trade_endur", endur);
        Map<String, Map<String, Object>> swap = new ConcurrentHashMap<>();
        swap.put("S1", row("TRADE_ID", "S1", "DESK", "FX-LDN", "NOTIONAL", 50.0));
        TABLES.put("trade_swapone", swap);
    }

    private SourceRouter router(Map<String, Map<String, String>> connectors) {
        var cfg = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        connectors.forEach((n, s) -> cfg.put(n, new SourcesProperties.ConnectorSettings("store", true, List.of("trade"), s)));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, cfg, tmp.toString(), null, null, null, null);
        SourceRegistry registry = new SourceRegistry(List.of(new Store()), props, new JsonCodec());
        registry.shapeBases(List.of(tmp));
        SourceRouter r = new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
        r.bind(Map.of("trade", List.copyOf(connectors.keySet())));
        return r;
    }

    private static Map<String, String> settings(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Map<String, Object>> all(SourceRouter r) {
        Map<String, Map<String, Object>> out = new java.util.TreeMap<>();
        r.documents("trade", AsOf.LATEST, d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) d.data().unwrap();
            assertThat(out.put(d.ref().id(), m)).as("each record once: " + d.ref().id()).isNull();
        });
        return out;
    }

    private SourceRouter sharedSplit() {
        Map<String, Map<String, String>> c = new LinkedHashMap<>();
        c.put("trade-endur", settings("table", "trades", "union", "true", "where", "SOURCE_SYSTEM = 'ENDUR'"));
        c.put("trade-calypso", settings("table", "trades", "union", "true", "where", "SOURCE_SYSTEM = 'CALYPSO'"));
        return router(c);
    }

    @Test
    void aSharedTableIsSplitBetweenTwoSystemsByWhere() throws Exception {
        SourceRouter r = sharedSplit();
        assertThat(all(r).keySet()).containsExactly("C1", "E1", "E2");
        EntityDocument c1 = r.fetch(EntityRef.of("trade", "C1")).get();
        assertThat(c1.provenance().source()).as("the record came from the Calypso connector").isEqualTo("trade-calypso");
        assertThat(r.fetch(EntityRef.of("trade", "E1")).get().provenance().source()).isEqualTo("trade-endur");
        assertThat(r.search("trade", "1", 10, Duration.ofSeconds(2)).stream().map(h -> h.ref().id()).toList())
                .as("each hit once, from its own system's connector").containsExactlyInAnyOrder("E1", "C1");
        ColumnSet cs = r.columns("trade", List.of("TRADE_ID", "SOURCE_SYSTEM", "NOTIONAL"), AsOf.LATEST, Duration.ofSeconds(2)).orElseThrow();
        Map<String, Object> bySystem = new java.util.TreeMap<>();
        for (int i = 0; i < cs.size(); i++) {
            bySystem.merge(String.valueOf(cs.value("SOURCE_SYSTEM", i)), cs.value("NOTIONAL", i), (a, b) -> (Double) a + (Double) b);
        }
        assertThat(bySystem).containsEntry("ENDUR", 300.0).containsEntry("CALYPSO", 300.0).hasSize(2);
        assertThat(r.registry().shape("trade-endur").orElseThrow().where().pushdown())
                .as("pushed down for partition and file pruning").containsExactly(new ScanRequest.Filter("SOURCE_SYSTEM", ScanRequest.Op.EQ, List.of("ENDUR")));
    }

    @Test
    void separateTablesWithSetStampingAndMapRenames() throws Exception {
        Files.createDirectories(tmp.resolve("mappings"));
        Files.writeString(tmp.resolve("mappings/endur-trade.yaml"), "format: 1\nfields:\n  TRAN_NUM: TRADE_ID\n  PORTFOLIO: DESK\n"
                + "  NOTIONAL_AMT: { to: NOTIONAL, cast: number }\n");
        Map<String, Map<String, String>> c = new LinkedHashMap<>();
        c.put("trade-endur", settings("table", "trade_endur", "union", "true", "set.SOURCE_SYSTEM", "ENDUR", "map", "mappings/endur-trade.yaml"));
        c.put("trade-swapone", settings("table", "trade_swapone", "union", "true", "set.SOURCE_SYSTEM", "SWAPONE"));
        SourceRouter r = router(c);
        Map<String, Map<String, Object>> rows = all(r);
        assertThat(rows.get("E9")).containsEntry("TRADE_ID", "E9").containsEntry("DESK", "RATES-NY").containsEntry("NOTIONAL", 1500.0)
                .containsEntry("SOURCE_SYSTEM", "ENDUR").doesNotContainKeys("TRAN_NUM", "PORTFOLIO", "NOTIONAL_AMT");
        assertThat(rows.get("S1")).containsEntry("SOURCE_SYSTEM", "SWAPONE");
        assertThat(r.registry().fetchNative("trade-endur", EntityRef.of("trade", "E9"), null).orElseThrow().data().unwrap())
                .as("Raw JSON: the source's native record, before map").isEqualTo(TABLES.get("trade_endur").get("E9"));
        assertThat(r.registry().systems().allowed()).isEmpty();
        assertThat(r.registry().connectorsBySystem()).containsEntry("ENDUR", List.of("trade-endur")).containsEntry("SWAPONE", List.of("trade-swapone"));
        ColumnSet cs = r.columns("trade", List.of("TRADE_ID", "SOURCE_SYSTEM", "DESK"), AsOf.LATEST, Duration.ofSeconds(2)).orElseThrow();
        assertThat(cs.size()).isEqualTo(2);
        assertThat(r.columnar("trade")).contains("TRADE_ID", "DESK", "SOURCE_SYSTEM");
    }

    @Test
    void theSourceDesignersMapFormReadsTheSame() throws Exception {
        // canonical: {from: native, cast, values: {native value: canonical value}} or canonical: {value: constant}; set: and keep: of the file
        Files.writeString(tmp.resolve("endur-designer.yaml"), "fields:\n  TRADE_ID: { from: TRAN_NUM }\n  DESK: { from: PORTFOLIO, values: { RATES-NY: "
                + "RATES-NEW-YORK } }\n  NOTIONAL: { from: NOTIONAL_AMT, cast: number }\n  ASSET_CLASS: { value: RATES }\nset:\n  SOURCE_SYSTEM: ENDUR\n"
                + "keep: false\n");
        Map<String, Map<String, String>> c = new LinkedHashMap<>();
        c.put("trade-endur", settings("table", "trade_endur", "map", "endur-designer.yaml"));
        SourceRouter r = router(c);
        assertThat(r.registry().failures()).isEmpty();
        assertThat(all(r).get("E9")).containsExactlyInAnyOrderEntriesOf(Map.of("TRADE_ID", "E9", "DESK", "RATES-NEW-YORK", "NOTIONAL", 1500.0,
                "ASSET_CLASS", "RATES", "SOURCE_SYSTEM", "ENDUR"));
        assertThat(r.registry().shape("trade-endur").orElseThrow().system()).contains("ENDUR");
        ColumnSet cs = r.columns("trade", List.of("TRADE_ID", "DESK", "ASSET_CLASS", "SOURCE_SYSTEM"), AsOf.LATEST, Duration.ofSeconds(2)).orElseThrow();
        assertThat(List.of(cs.value("TRADE_ID", 0), cs.value("DESK", 0), cs.value("ASSET_CLASS", 0), cs.value("SOURCE_SYSTEM", 0)))
                .containsExactly("E9", "RATES-NEW-YORK", "RATES", "ENDUR");
    }

    @Test
    void twoLiveConnectorsMergeIntoOneFeedEachPassingItsOwnSystem() {
        SourceRouter r = sharedSplit();
        List<String> got = new CopyOnWriteArrayList<>();
        Subscription s = r.subscribeKind("trade", d -> got.add(d.ref().id() + "@" + d.provenance().source()));
        assertThat(FEEDS.get("trades")).hasSize(2);
        for (Consumer<EntityDocument> feed : FEEDS.get("trades")) {   // one message of each system on the shared topic, seen by both
            feed.accept(new EntityDocument(EntityRef.of("trade", "E7"), DataNode.of(row("TRADE_ID", "E7", "SOURCE_SYSTEM", "ENDUR")),
                    new Provenance("k", 2, Instant.now(), true)));
            feed.accept(new EntityDocument(EntityRef.of("trade", "C7"), DataNode.of(row("TRADE_ID", "C7", "SOURCE_SYSTEM", "CALYPSO")),
                    new Provenance("k", 2, Instant.now(), true)));
        }
        assertThat(got).as("each message once, through its own system's connector").containsExactlyInAnyOrder("E7@k", "C7@k");
        s.close();
    }

    @Test
    void aSystemSwitchedOffLeavesEveryReadAndComesBackWhenOn() throws Exception {
        SourceRouter r = sharedSplit();
        SourceSystems systems = r.registry().systems();
        systems.installed(List.of(new SourceSystems.System("ENDUR", "source-endur", Set.of("trade"), true),
                new SourceSystems.System("CALYPSO", "source-calypso", Set.of("trade"), true)));
        assertThat(systems.allowed()).containsExactly("CALYPSO", "ENDUR");
        List<String> got = new CopyOnWriteArrayList<>();
        r.subscribeKind("trade", d -> got.add(d.ref().id()));
        String before = r.dataVersion("trade", AsOf.LATEST).orElse("");

        assertThat(systems.switchTo("ENDUR", false)).isTrue();
        assertThat(all(r).keySet()).containsExactly("C1");
        assertThat(r.fetch(EntityRef.of("trade", "E1")).handle((d, e) -> d).join()).isNull();
        assertThat(r.search("trade", "1", 10, Duration.ofSeconds(2)).stream().map(h -> h.ref().id()).toList()).containsExactly("C1");
        assertThat(r.columns("trade", List.of("TRADE_ID"), AsOf.LATEST, Duration.ofSeconds(2)).orElseThrow().size()).isEqualTo(1);
        assertThat(r.excluded("trade")).containsExactly("ENDUR");
        assertThat(r.excluded("scenario-pv")).as("a kind no source system feeds (VaR's scenario PVs) excludes nothing").isEmpty();
        assertThat(r.dataVersion("trade", AsOf.LATEST).orElse("")).as("a set paged from the store reads again").isNotEqualTo(before);
        FEEDS.get("trades").forEach(f -> f.accept(new EntityDocument(EntityRef.of("trade", "E8"), DataNode.of(row("SOURCE_SYSTEM", "ENDUR")),
                new Provenance("k", 3, Instant.now(), true))));
        assertThat(got).as("its live feed stops").isEmpty();

        systems.switchTo("CALYPSO", false);
        assertThat(all(r)).as("every system off: the kind is empty, with no error").isEmpty();

        systems.switchTo("ENDUR", true);
        systems.switchTo("CALYPSO", true);
        assertThat(all(r).keySet()).containsExactly("C1", "E1", "E2");
        assertThat(r.excluded("trade")).isEmpty();
    }

    @Test
    void aSharedTableReadWithoutWhereStillDropsTheRowsOfASystemOff() {
        Map<String, Map<String, String>> c = new LinkedHashMap<>();
        c.put("trade-all", settings("table", "trades"));
        SourceRouter r = router(c);
        r.registry().systems().installed(List.of(new SourceSystems.System("ENDUR", "source-endur", Set.of("trade"), false)));
        assertThat(all(r).keySet()).containsExactly("C1");
    }

    @Test
    void aPredicateOrAMapThatCannotBeUsedRefusesTheConnectorWithItsCode() throws Exception {
        Files.writeString(tmp.resolve("bad-cast.yaml"), "fields:\n  A: { to: B, cast: money }\n");
        Map<String, Map<String, String>> c = new LinkedHashMap<>();
        c.put("trade-bad-where", settings("table", "trades", "where", "SOURCE_SYSTEM = = 'X'"));
        c.put("trade-bad-map", settings("table", "trades", "map", "bad-cast.yaml"));
        c.put("trade-no-map", settings("table", "trades", "map", "nowhere.yaml"));
        c.put("trade-user", settings("table", "trades", "where", "DESK in $user.desks"));
        SourceRouter r = router(c);
        Map<String, String> failed = r.registry().failures();
        assertThat(failed.get("trade-bad-where")).startsWith("DRS-5034 connector trade-bad-where: where:");
        assertThat(failed.get("trade-bad-map")).contains("DRS-5034").contains("money");
        assertThat(failed.get("trade-no-map")).contains("DRS-5034").contains("nowhere.yaml is not found");
        assertThat(failed.get("trade-user")).contains("DRS-5034").contains("role's row rule");
    }

    @Test
    void rowPredicatesReadTheWayAFilterIsWritten() {
        Map<String, List<Object>> user = Map.of("desks", List.of("RATES-NY", "RATES-LDN"), "desk", List.of("RATES-NY"));
        RowPredicate p = RowPredicate.parse("ASSET_CLASS = 'RATES' and DESK in $user.desks", user);
        assertThat(p.test(DataNode.of(row("ASSET_CLASS", "RATES", "DESK", "RATES-LDN")))).isTrue();
        assertThat(p.test(DataNode.of(row("ASSET_CLASS", "RATES", "DESK", "FX-LDN")))).isFalse();
        assertThat(p.test(DataNode.of(row("ASSET_CLASS", "FX", "DESK", "RATES-NY")))).isFalse();
        assertThat(p.pushdown()).containsExactly(new ScanRequest.Filter("ASSET_CLASS", ScanRequest.Op.EQ, List.of("RATES")),
                new ScanRequest.Filter("DESK", ScanRequest.Op.IN, List.of("RATES-NY", "RATES-LDN")));
        assertThat(RowPredicate.parse("DESK = $user.desk", user).test(DataNode.of(row("DESK", "RATES-NY")))).isTrue();
        assertThat(RowPredicate.parse("DESK in $user.books", user).test(DataNode.of(row("DESK", "RATES-NY"))))
                .as("an attribute the person lacks matches nothing").isFalse();
        RowPredicate q = RowPredicate.parse("(BOOK in ('B1', 'B2') or NOTIONAL >= 1000000) and MATURITY is not null and not FLAG = true");
        assertThat(q.test(DataNode.of(row("BOOK", "B2", "MATURITY", "2030-01-01", "FLAG", false)))).isTrue();
        assertThat(q.test(DataNode.of(row("BOOK", "B9", "NOTIONAL", 2_000_000, "MATURITY", "2030-01-01")))).isTrue();
        assertThat(q.test(DataNode.of(row("BOOK", "B2")))).isFalse();
        assertThat(RowPredicate.parse("SOURCE_SYSTEM <> 'ENDUR'").test(DataNode.of(row("SOURCE_SYSTEM", "CALYPSO")))).isTrue();
        assertThat(RowPredicate.parse("SOURCE_SYSTEM = 'ENDUR'").fixes("SOURCE_SYSTEM")).contains("ENDUR");
        assertThat(RowPredicate.parse("SOURCE_SYSTEM = 'ENDUR'").fields()).containsExactly("SOURCE_SYSTEM");
        assertThat(RowPredicate.parse("SOURCE_SYSTEM = 'ENDUR'").test(DataNode.of(new LinkedHashMap<>(Map.of("SOURCE_SYSTEM", "ENDUR"))))).isTrue();
        assertThatThrownBy(() -> RowPredicate.parse("SOURCE_SYSTEM = 'ENDUR")).hasMessageContaining("quote");
    }

    @Test
    void theRicherMapReadsDottedPathsFirstOfScaleAndAs() throws Exception {
        Path f = Files.writeString(tmp.resolve("rich.yaml"), String.join("\n", "format: 1", "map:", "  TRADE_ID: swap_id", "  QUANTITY: pay_leg.notional",
                "  PRICE:", "    first-of: [pay_leg.rate, rec_leg.rate]", "    scale: 0.01", "  CURRENCY:", "    from: [pay_leg.ccy, ccy]", "  TRADE_DATE:",
                "    from: trade_dt", "    as: yyyymmdd", "  SOURCE_UPDATE_TIME:", "    from: upd", "    as: epoch-millis", "  TRADE_STATUS:", "    from: state",
                "    values: {LIVE: NEW}", ""));
        SourceShape shape = SourceShape.of("c", Map.of("map", f.toString()), List.of());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("swap_id", "S1");
        raw.put("pay_leg", new LinkedHashMap<>(Map.of("notional", 5000L, "ccy", "")));
        raw.put("rec_leg", new LinkedHashMap<>(Map.of("rate", 4.0)));
        raw.put("ccy", "CAD");
        raw.put("trade_dt", "20261103");
        raw.put("upd", 1793664000000L);
        raw.put("state", "LIVE");
        raw.put("extra", "kept");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) shape.canonical(DataNode.of(raw)).unwrap();
        assertThat(out).containsEntry("TRADE_ID", "S1").containsEntry("QUANTITY", 5000L).containsEntry("PRICE", 0.04).containsEntry("CURRENCY", "CAD")
                .containsEntry("TRADE_DATE", "2026-11-03").containsEntry("SOURCE_UPDATE_TIME", "2026-11-03T00:00:00Z").containsEntry("TRADE_STATUS", "NEW")
                .containsEntry("extra", "kept").doesNotContainKey("swap_id");
        assertThat(shape.needsDocuments("QUANTITY")).isTrue();
        assertThat(shape.needsDocuments("TRADE_ID")).isFalse();
        assertThatThrownBy(() -> SourceShape.of("c", Map.of("map", Files.writeString(tmp.resolve("bad.yaml"), "map:\n  A:\n    from: a\n    scale: x\n").toString()),
                List.of())).hasMessageContaining("DRS-5034").hasMessageContaining("scale");
    }
}
