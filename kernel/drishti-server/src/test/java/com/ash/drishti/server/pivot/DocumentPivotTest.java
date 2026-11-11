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
package com.ash.drishti.server.pivot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.rows.RowsProvider;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pivots over entity documents (ARCHITECTURE.md section 26.4) on 400 synthetic trades whose documents hold LEGS (one to three, each with
 * an INDEX object and CASH_FLOWS) and EVENTS: every figure at the leg grain equals the same view over the legs flattened in Java (the
 * in-memory provider); a trade's quantity in a leg-grain view is counted once per trade, also in a cell holding one trade with three legs;
 * two separate lists are never crossed; a filter on a list keeps the trades with any (or all) of its elements matching; a path read from
 * the documents gives the figures its promoted column gives.
 */
class DocumentPivotTest {

    static final String[] DESKS = {"RATES", "FX", "CREDIT"};
    static final String[] CCY = {"USD", "EUR", "JPY", "GBP"};
    static DuckDbExecutor exec;
    static List<Map<String, Object>> trades = new ArrayList<>();
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;
    /** The legs as a BI dataset table's cached layer (one Parquet row per leg: id, index and the leg's columns). */
    static String legsLayer;

    @BeforeAll
    static void documents() throws Exception {
        Random r = new Random(11);
        for (int i = 0; i < 400; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("TRADE_ID", "T-" + i);
            t.put("DESK", DESKS[i % 3]);
            t.put("PRODUCT_TYPE", i % 5 == 0 ? "FX_SWAP" : i % 2 == 0 ? "IRS" : "XCCY");
            t.put("QUANTITY", 1_000.0 * (1 + r.nextInt(50)));
            List<Map<String, Object>> legs = new ArrayList<>();
            int n = i == 7 ? 3 : 1 + r.nextInt(3);
            for (int l = 0; l < n; l++) {
                Map<String, Object> leg = new LinkedHashMap<>();
                leg.put("LEG_ID", String.valueOf(l + 1));
                leg.put("LEG_TYPE", l % 2 == 0 ? "FIXED" : "FLOAT");
                leg.put("CURRENCY", CCY[r.nextInt(4)]);
                leg.put("NOTIONAL", 1_000_000.0 * (1 + r.nextInt(20)));
                leg.put("INDEX", Map.of("NAME", l % 2 == 0 ? "" : r.nextBoolean() ? "SOFR" : "EURIBOR"));
                List<Map<String, Object>> flows = new ArrayList<>();
                for (int c = 0; c < 1 + r.nextInt(3); c++) {
                    flows.add(Map.of("FLOW_TYPE", c == 0 ? "Principal" : "Interest", "AMOUNT", 100.0 * (1 + r.nextInt(90))));
                }
                leg.put("CASH_FLOWS", flows);
                legs.add(leg);
            }
            t.put("LEGS", legs);
            List<Map<String, Object>> events = new ArrayList<>();
            for (int e = 0; e < 1 + r.nextInt(4); e++) {
                events.add(Map.of("EVENT_TYPE", e == 0 ? "BOOKED" : r.nextBoolean() ? "AMENDED" : "SETTLED", "EVENT_DATE", "2026-10-" + (10 + e)));
            }
            t.put("EVENTS", events);
            trades.add(t);
        }
        exec = new DuckDbExecutor(DuckDbExecutor.Settings.small());
        ObjectMapper json = new ObjectMapper();
        exec.stage("tdocs", List.of(new DuckDbExecutor.Column("TRADE_ID", "text"), new DuckDbExecutor.Column("DESK", "text"),
                new DuckDbExecutor.Column("PRODUCT_TYPE", "text"), new DuckDbExecutor.Column("_id", "text"), new DuckDbExecutor.Column("_doc", "text")),
                sink -> {
                    for (Map<String, Object> t : trades) {
                        sink.row(new Object[] {t.get("TRADE_ID"), t.get("DESK"), t.get("PRODUCT_TYPE"), t.get("TRADE_ID"), json.writeValueAsString(t)});
                    }
                    return trades.size();
                });
        exec.stage("tlegs", List.of(new DuckDbExecutor.Column("id", "text"), new DuckDbExecutor.Column("index", "number"),
                new DuckDbExecutor.Column("LEG_TYPE", "text"), new DuckDbExecutor.Column("CURRENCY", "text"), new DuckDbExecutor.Column("NOTIONAL", "number")),
                sink -> {
                    long n = 0;
                    for (Map<String, Object> t : trades) {
                        List<?> legs = (List<?>) t.get("LEGS");
                        for (int i = 0; i < legs.size(); i++) {
                            Map<?, ?> l = (Map<?, ?>) legs.get(i);
                            sink.row(new Object[] {t.get("TRADE_ID"), i, l.get("LEG_TYPE"), l.get("CURRENCY"), l.get("NOTIONAL")});
                            n++;
                        }
                    }
                    return n;
                });
        java.nio.file.Path f = tmp.resolve("legs.parquet");
        exec.execute("COPY \"tlegs\" TO '" + f + "' (FORMAT PARQUET)");
        legsLayer = "read_parquet(['" + f + "'])";
    }

    @AfterAll
    static void close() {
        exec.close();
    }

    /** A document source over the staged trades; {@code promoted}: the fields read as columns (the rest from the documents). */
    static PivotCore core(Set<String> promoted) {
        return core(promoted, false, new String[1]);
    }

    /** As above; {@code dataset}: the legs' LEG_TYPE, CURRENCY and NOTIONAL also held by a BI dataset table; {@code route}: the last route. */
    static PivotCore core(Set<String> promoted, boolean dataset, String[] route) {
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> sample = (List<Map<?, ?>>) (List<?>) trades;
        Map<String, DocPath> docs = new LinkedHashMap<>();
        Map<String, String> types = new HashMap<>();
        DocFields.of(sample, promoted, n -> false).forEach((p, seen) -> {
            DocPath dp = DocPath.parse(p);
            docs.put(dp.id(), dp);
            types.put(dp.id(), seen.type());
        });
        TabularSource.Resolver resolver = new TabularSource.Resolver() {
            @Override
            public Map<String, String> sources(Viewer v) {
                return Map.of("trade", "Trades");
            }

            @Override
            public boolean serves(String s) {
                return "trade".equals(s);
            }

            @Override
            public TabularSource open(Viewer v, String s, String date) {
                List<PivotProvider.Field> fields = new ArrayList<>();
                promoted.forEach(f -> fields.add(new PivotProvider.Field(f, "string", f, "")));
                docs.values().forEach(d -> fields.add(new PivotProvider.Field(d.id(), d.size() ? "number" : types.getOrDefault(d.id(), "string"),
                        d.label(), d.text())));
                fields.addAll(DocView.engineFields(docs, types));
                DocView view = new DocView(docs, promoted, text -> docs.computeIfAbsent(DocPath.parse(text).id(), k -> DocPath.parse(text)));
                return new TabularSource() {
                    public String id() {
                        return "trade";
                    }

                    public String title() {
                        return "Trades";
                    }

                    public List<PivotProvider.Field> fields() {
                        return fields;
                    }

                    public int masked() {
                        return 0;
                    }

                    public String date() {
                        return "2026-10-30";
                    }

                    public List<String> dates() {
                        return List.of("2026-10-30");
                    }

                    public String version() {
                        return "v1|" + promoted;
                    }

                    public long rows() {
                        return trades.size();
                    }

                    public long bytes() {
                        return -1;
                    }

                    public Map<String, Object> notes() {
                        return Map.of();
                    }

                    public Relation relation(DuckDbExecutor e) {
                        return new Relation("\"tdocs\"", List.of(), null, List.of());
                    }

                    @Override
                    public Relation relation(DuckDbExecutor e, AggregationPlan plan) {
                        Map<String, String> t = new HashMap<>();
                        fields.forEach(f -> t.put(f.name(), f.type()));
                        List<String> names = new ArrayList<>(fields.stream().map(PivotProvider.Field::name).toList());
                        plan.dims().values().stream().map(x -> x.field()).filter(f -> f.endsWith(DocView.ALL) && !names.contains(f)).forEach(names::add);
                        java.util.function.Function<String, DocView.Layer> layers = g -> dataset && "LEGS".equals(g) ? new DocView.Layer(legsLayer, "id",
                                "index", Map.of("LEGS.LEG_TYPE", "LEG_TYPE", "LEGS.CURRENCY", "CURRENCY", "LEGS.NOTIONAL", "NOTIONAL"), "trading-cashflows-legs/legs")
                                : null;
                        String sql = view.relation("\"tdocs\"", plan, names, t, layers);
                        route[0] = view.route;
                        return new Relation(sql, List.of(), null, List.of());
                    }

                    @Override
                    public PivotView prepare(PivotView v2) {
                        return view.prepare(v2);
                    }
                };
            }
        };
        return new PivotCore(List.of(new TabularProvider(exec, resolver)), StateCache.none(), PivotCore.Settings.defaults());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> cells(PivotCore core, Map<String, Object> view) {
        return (Map<String, Map<String, Object>>) core.query(Viewer.of("rita"), PivotView.parse(view)).get("cells");
    }

    static double value(Map<String, Map<String, Object>> cells, String key, String m) {
        Object v = ((Map<?, ?>) cells.get(key).get("values")).get(m);
        return ((Number) v).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyFigureAtTheLegGrainEqualsTheLegsFlattenedInJava() {
        PivotCore doc = core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE"));
        Map<String, Object> view = Map.of("source", "trade", "rows", List.of("$.DESK"), "columns", List.of("$.LEGS[*].LEG_TYPE"),
                "values", List.of(Map.of("measure", "sum($.LEGS[*].NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "Legs"),
                        Map.of("measure", "median($.LEGS[*].NOTIONAL)", "as", "Med")));
        Map<String, Map<String, Object>> a = cells(doc, view);
        List<Object[]> legs = new ArrayList<>();
        for (Map<String, Object> t : trades) {
            for (Map<String, Object> l : (List<Map<String, Object>>) t.get("LEGS")) {
                legs.add(new Object[] {t.get("DESK"), l.get("LEG_TYPE"), l.get("NOTIONAL")});
            }
        }
        PivotCore flat = new PivotCore(List.of(new RowsProvider("trade", "Legs", List.of(new PivotProvider.Field("DESK", "string", "", ""),
                new PivotProvider.Field("LEGS.LEG_TYPE", "string", "", ""), new PivotProvider.Field("LEGS.NOTIONAL", "number", "", "")), legs)),
                StateCache.none(), PivotCore.Settings.defaults());
        Map<String, Map<String, Object>> b = cells(flat, Map.of("source", "trade", "rows", List.of("DESK"), "columns", List.of("LEGS.LEG_TYPE"),
                "values", List.of(Map.of("measure", "sum(LEGS.NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "Legs"),
                        Map.of("measure", "median(LEGS.NOTIONAL)", "as", "Med"))));
        assertThat(a.keySet()).isEqualTo(b.keySet());
        for (String k : b.keySet()) {
            for (String m : List.of("N", "Legs", "Med")) {
                assertThat(value(a, k, m)).as(k + " " + m).isCloseTo(value(b, k, m), offset(1e-6));
            }
        }
        assertThat(value(a, "*|*", "Legs")).isEqualTo(legs.size());
        // a top N ranks with a plan of fewer fields: every plan of the view still reads one row per leg
        Map<String, Map<String, Object>> top = cells(doc, Map.of("source", "trade", "rows", List.of("$.DESK"), "columns", List.of("$.LEGS[*].LEG_TYPE"),
                "top", Map.of("$.DESK", Map.of("n", 1)), "values", List.of(Map.of("measure", "count()", "as", "Legs"))));
        assertThat(value(top, "*|*", "Legs")).isEqualTo(legs.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTradesQuantityInALegGrainViewIsCountedOncePerTrade() {
        PivotCore doc = core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE"));
        Map<String, Map<String, Object>> c = cells(doc, Map.of("source", "trade", "rows", List.of("DESK"), "values",
                List.of(Map.of("measure", "sum($.QUANTITY)", "as", "Q"), Map.of("measure", "count()", "as", "Legs"),
                        Map.of("measure", "sum($.LEGS[*].NOTIONAL)", "as", "N"))));
        for (String desk : DESKS) {
            double q = trades.stream().filter(t -> desk.equals(t.get("DESK"))).mapToDouble(t -> (Double) t.get("QUANTITY")).sum();
            assertThat(value(c, "DESK=" + desk + "|*", "Q")).as(desk).isCloseTo(q, offset(1e-6));
        }
        double all = trades.stream().mapToDouble(t -> (Double) t.get("QUANTITY")).sum();
        assertThat(value(c, "*|*", "Q")).isCloseTo(all, offset(1e-6));
        // one trade with three legs: its quantity once, its legs three times
        Map<String, Map<String, Object>> one = cells(doc, Map.of("source", "trade", "rows", List.of("TRADE_ID"), "filters",
                Map.of("TRADE_ID", Map.of("in", List.of("T-7"))), "values", List.of(Map.of("measure", "sum($.QUANTITY)", "as", "Q"),
                        Map.of("measure", "count($.LEGS[*].LEG_ID)", "as", "Legs"))));
        assertThat(value(one, "TRADE_ID=T-7|*", "Legs")).isEqualTo(3.0);
        assertThat(value(one, "TRADE_ID=T-7|*", "Q")).isEqualTo((Double) trades.get(7).get("QUANTITY"));
        // per leg type a trade with a fixed and a floating leg is in both cells, once in each
        Map<String, Map<String, Object>> byType = cells(doc, Map.of("source", "trade", "rows", List.of("$.LEGS[*].LEG_TYPE"), "values",
                List.of(Map.of("measure", "sum($.QUANTITY)", "as", "Q"))));
        double floatQ = trades.stream().filter(t -> ((List<Map<String, Object>>) t.get("LEGS")).stream().anyMatch(l -> "FLOAT".equals(l.get("LEG_TYPE"))))
                .mapToDouble(t -> (Double) t.get("QUANTITY")).sum();
        assertThat(value(byType, "LEGS.LEG_TYPE=FLOAT|*", "Q")).isCloseTo(floatQ, offset(1e-6));
    }

    @Test
    void twoSeparateListsAreNeverCrossed() {
        PivotCore doc = core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE"));
        assertThatThrownBy(() -> cells(doc, Map.of("source", "trade", "rows", List.of("$.LEGS[*].CURRENCY"), "columns", List.of("$.EVENTS[*].EVENT_TYPE"),
                "values", List.of(Map.of("measure", "count()", "as", "N"))))).hasMessageContaining("separate lists");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFilterOnAListKeepsTheTradesWithAnyOrAllOfItsElementsMatching() {
        PivotCore doc = core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE"));
        Map<String, Map<String, Object>> any = cells(doc, Map.of("source", "trade", "rows", List.of("$.PRODUCT_TYPE"), "filters",
                Map.of("$.LEGS[*].CURRENCY", Map.of("in", List.of("JPY"))), "values", List.of(Map.of("measure", "count()", "as", "Trades"))));
        long expect = trades.stream().filter(t -> ((List<Map<String, Object>>) t.get("LEGS")).stream().anyMatch(l -> "JPY".equals(l.get("CURRENCY")))).count();
        assertThat(value(any, "*|*", "Trades")).isEqualTo((double) expect);
        Map<String, Map<String, Object>> all = cells(doc, Map.of("source", "trade", "rows", List.of("$.PRODUCT_TYPE"), "filters",
                Map.of("$.LEGS[*].CURRENCY~all", Map.of("in", List.of("JPY", "USD"))), "values", List.of(Map.of("measure", "count()", "as", "Trades"))));
        long expectAll = trades.stream().filter(t -> ((List<Map<String, Object>>) t.get("LEGS")).stream().allMatch(l -> Set.of("JPY", "USD")
                .contains(l.get("CURRENCY")))).count();
        assertThat(value(all, "*|*", "Trades")).isEqualTo((double) expectAll);
        // a filtered path and size()
        Map<String, Map<String, Object>> fixed = cells(doc, Map.of("source", "trade", "rows", List.of("size($.LEGS)"), "values",
                List.of(Map.of("measure", "sum($.LEGS[?@.LEG_TYPE == 'FIXED'][*].NOTIONAL)", "as", "Fixed"))));
        double f = trades.stream().flatMap(t -> ((List<Map<String, Object>>) t.get("LEGS")).stream()).filter(l -> "FIXED".equals(l.get("LEG_TYPE")))
                .mapToDouble(l -> (Double) l.get("NOTIONAL")).sum();
        assertThat(value(fixed, "*|*", "Fixed")).isCloseTo(f, offset(1e-6));
        assertThat(fixed.keySet()).anyMatch(k -> k.matches("path_[0-9a-f]+=3\\|\\*"));
    }

    @Test
    void aListsFieldsReadFromADatasetsCachedLayerGiveTheFiguresOfTheDocuments() {
        Map<String, Object> view = Map.of("source", "trade", "rows", List.of("$.DESK", "$.LEGS[*].LEG_TYPE"), "expandTo", Map.of("rows", 2),
                "values", List.of(Map.of("measure", "sum($.LEGS[*].NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "Legs"),
                        Map.of("measure", "sum($.QUANTITY)", "as", "Q")));
        String[] route = new String[1];
        Map<String, Map<String, Object>> layered = cells(core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE"), true, route), view);
        assertThat(route[0]).startsWith("dataset trading-cashflows-legs/legs");
        Map<String, Map<String, Object>> adHoc = cells(core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE"), false, route), view);
        assertThat(route[0]).startsWith("the documents unnested");
        assertThat(layered.keySet()).isEqualTo(adHoc.keySet());
        for (String k : adHoc.keySet()) {
            for (String m : List.of("N", "Legs", "Q")) {
                assertThat(value(layered, k, m)).as(k + " " + m).isCloseTo(value(adHoc, k, m), offset(1e-6));
            }
        }
    }

    @Test
    void aPathReadFromTheDocumentsGivesTheFiguresOfItsPromotedColumn() {
        Map<String, Object> view = Map.of("source", "trade", "rows", List.of("$.DESK", "$.PRODUCT_TYPE"), "expandTo", Map.of("rows", 2),
                "values", List.of(Map.of("measure", "sum($.QUANTITY)", "as", "Q"), Map.of("measure", "count()", "as", "N")));
        Map<String, Map<String, Object>> promoted = cells(core(Set.of("TRADE_ID", "DESK", "PRODUCT_TYPE")), view);
        Map<String, Map<String, Object>> adHoc = cells(core(new LinkedHashSet<>(List.of("TRADE_ID"))), view);
        assertThat(adHoc.keySet()).isEqualTo(promoted.keySet());
        for (String k : promoted.keySet()) {
            assertThat(value(adHoc, k, "Q")).as(k).isCloseTo(value(promoted, k, "Q"), offset(1e-6));
            assertThat(value(adHoc, k, "N")).as(k).isEqualTo(value(promoted, k, "N"));
        }
    }
}
