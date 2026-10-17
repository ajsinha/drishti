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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The filter context of wave B (docs/architecture/BI_DESIGN.md section 3.3.5) on the engine: {@code calc} with {@code where=}, {@code ignore=},
 * {@code ignore=ALL}, {@code keep=}, {@code share_of}, {@code parent_of} and {@code avg_by}, each checked against an independent computation over the same
 * trades (acceptance (1): "% of total, ignore-a-slicer ... equal an independent computation"), for random fields, filters and readers (a property test: the
 * security of each reader is applied to the total too, so a share is always of what that reader may see).
 */
class FilterContextTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;

    static final String MEASURES = """
            measures:
              Total: { expr: "calc(measure('MTM'), ignore=ALL)" }
              AllDesks: { expr: "calc(measure('MTM'), ignore=[trades.desk])" }
              Pct: { expr: "share_of(measure('MTM'), over=ALL)" }
              DeskShare: { expr: "share_of(sum(trades.mtm), over=[trades.desk])" }
              EurOnly: { expr: "calc(measure('MTM'), where=trades.currency == 'EUR')" }
              Region: { expr: "calc(measure('MTM'), keep=[desks.region])" }
              PerCpty: { expr: "avg_by(sum(trades.mtm), by=[trades.cpty])" }
              OfRegion: { expr: "parent_of(measure('MTM'), hierarchy='Geo')" }
              EurTotal: { expr: "calc(calc(measure('MTM'), ignore=ALL), where=trades.currency == 'EUR')" }
            """;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        String yaml = TestLake.yaml().replace("measures:\n", MEASURES).replace("security:\n", "hierarchies:\n  Geo: [desks.region, trades.desk]\nsecurity:\n");
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-ctx", Layers.key("file:dataset:lake-ctx"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    record Reader(String name, QueryService.Caller caller, Predicate<TestLake.Trade> sees) {}

    static Reader risk() {
        return new Reader("risk", new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE), t -> true);
    }

    static Reader auditor() {
        return new Reader("auditor", new QueryService.Caller("al", Set.of("auditor"), true, UserAttributes.of("al", List.of("auditor"), "", null), SqlCompiler.Masks.NONE),
                t -> !t.desk().equals("DESK-A"));
    }

    static Reader head(String desks) {
        return new Reader("desk-head " + desks, new QueryService.Caller("dh", Set.of("desk-head"), true, UserAttributes.of("dh", List.of("desk-head"), desks, null),
                SqlCompiler.Masks.NONE), t -> List.of(desks.split(",")).contains(t.desk()));
    }

    static String region(String desk) {
        return "DESK-A".equals(desk) ? "EMEA" : "AMER";
    }

    /** The value of a field for a trade. */
    static Object value(TestLake.Trade t, String field) {
        return switch (field) {
            case "trades.desk" -> t.desk();
            case "trades.currency" -> t.ccy();
            case "trades.cpty" -> t.cpty();
            case "desks.region" -> region(t.desk());
            default -> throw new IllegalArgumentException(field);
        };
    }

    QueryService.Result run(List<String> fields, List<String> measures, List<QueryRequest.Filter> filters, Reader who) {
        return service.query(ds, new QueryRequest(fields, measures, filters, TestLake.DAY2, TestLake.DAY2, List.of(), null, "auto"), who.caller(), true);
    }

    /** The answer as group key -> value of one measure. */
    static Map<List<Object>, Object> byGroup(QueryService.Result r, int fields, String measure) {
        Map<List<Object>, Object> out = new LinkedHashMap<>();
        int col = -1;
        for (int i = 0; i < r.columns().size(); i++) {
            if (r.columns().get(i).name().equals(measure)) {
                col = i;
            }
        }
        for (int row = 0; row < r.rows(); row++) {
            List<Object> key = new ArrayList<>();
            for (int i = 0; i < fields; i++) {
                key.add(r.columns().get(i).values()[row]);
            }
            out.put(key, r.columns().get(col).values()[row]);
        }
        return out;
    }

    static boolean passes(TestLake.Trade t, List<QueryRequest.Filter> filters) {
        for (QueryRequest.Filter f : filters) {
            Object v = value(t, f.field());
            boolean in = f.values().stream().anyMatch(x -> Objects.equals(String.valueOf(x), String.valueOf(v)));
            if ("in".equals(f.op()) != in) {
                return false;
            }
        }
        return true;
    }

    static double sum(List<TestLake.Trade> ts) {
        return ts.stream().mapToDouble(TestLake.Trade::mtm).sum();
    }

    /** A sum as SQL has it: None over no rows. */
    static Double nsum(List<TestLake.Trade> ts) {
        return ts.isEmpty() ? null : sum(ts);
    }

    static void close(Object actual, Double expected) {
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isNotNull();
            assertThat(((Number) actual).doubleValue()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6 * Math.max(1, Math.abs(expected))));
        }
    }

    @Test
    void percentOfTotalAndIgnoreASlicerEqualAnIndependentComputationForRandomQueriesAndReaders() {
        Random rnd = new Random(42);
        List<String> all = List.of("trades.desk", "trades.currency", "desks.region");
        List<Reader> readers = List.of(risk(), auditor(), head("DESK-A"), head("DESK-B,DESK-C"));
        List<TestLake.Trade> book = lake.trades.get(TestLake.DAY2);
        for (int round = 0; round < 40; round++) {
            List<String> fields = all.stream().filter(f -> rnd.nextBoolean()).toList();
            List<QueryRequest.Filter> filters = new ArrayList<>();
            if (rnd.nextBoolean()) {
                filters.add(new QueryRequest.Filter("trades.desk", "in", new ArrayList<>(TestLake.DESKS.stream().filter(d -> rnd.nextInt(3) > 0).toList())));
            }
            if (rnd.nextInt(3) == 0) {
                filters.add(new QueryRequest.Filter("trades.currency", "in", List.of(rnd.nextBoolean() ? "USD" : "EUR")));
            }
            Reader who = readers.get(rnd.nextInt(readers.size()));
            QueryService.Result r = run(fields, List.of("MTM", "Total", "AllDesks", "Pct", "EurOnly", "Region", "EurTotal"), filters, who);
            List<TestLake.Trade> visible = book.stream().filter(who.sees()).toList();
            List<TestLake.Trade> shown = visible.stream().filter(t -> passes(t, filters)).toList();
            Function<TestLake.Trade, List<Object>> key = t -> fields.stream().map(f -> value(t, f)).toList();
            Map<List<Object>, List<TestLake.Trade>> groups = shown.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
            if (fields.isEmpty()) {
                groups.putIfAbsent(List.of(), List.of());                                 // a grand total is one row, empty or not
            }
            assertThat(r.rows()).as("round %d %s %s %s", round, fields, filters, who.name()).isEqualTo(groups.size());
            List<QueryRequest.Filter> noDesk = filters.stream().filter(f -> !f.field().equals("trades.desk")).toList();
            List<QueryRequest.Filter> regionOnly = List.of();
            double total = sum(visible);
            for (Map.Entry<List<Object>, List<TestLake.Trade>> g : groups.entrySet()) {
                List<Object> k = g.getKey();
                double mtm = sum(g.getValue());
                boolean none = g.getValue().isEmpty();
                close(byGroup(r, fields.size(), "MTM").get(k), none ? null : mtm);
                close(byGroup(r, fields.size(), "Total").get(k), none || visible.isEmpty() ? null : total);   // a context is read on the rows of the group: an empty grand total has none
                close(byGroup(r, fields.size(), "Pct").get(k), none || total == 0 ? null : mtm / total);
                // ignore the desk: the desk slicer and the desk of the row are dropped, every other filter and group stays
                Predicate<TestLake.Trade> sameOthers = t -> {
                    for (int i = 0; i < fields.size(); i++) {
                        if (!fields.get(i).equals("trades.desk") && !Objects.equals(value(t, fields.get(i)), k.get(i))) {
                            return false;
                        }
                    }
                    return true;
                };
                if (none) {
                    continue;
                }
                close(byGroup(r, fields.size(), "AllDesks").get(k), nsum(visible.stream().filter(t -> passes(t, noDesk)).filter(sameOthers).toList()));
                List<TestLake.Trade> eur = g.getValue().stream().filter(t -> t.ccy().equals("EUR")).toList();
                close(byGroup(r, fields.size(), "EurOnly").get(k), eur.isEmpty() ? null : sum(eur));
                List<TestLake.Trade> eurAll = visible.stream().filter(t -> t.ccy().equals("EUR")).toList();
                close(byGroup(r, fields.size(), "EurTotal").get(k), eurAll.isEmpty() ? null : sum(eurAll));
                // keep the region: every filter but the region's is dropped, and the row's region stays
                int ri = fields.indexOf("desks.region");
                List<TestLake.Trade> region = visible.stream().filter(t -> passes(t, regionOnly)).filter(t -> ri < 0 || Objects.equals(region(t.desk()), k.get(ri))).toList();
                close(byGroup(r, fields.size(), "Region").get(k), nsum(region));
            }
        }
    }

    @Test
    void shareWithinTheRestOfTheGroupAndTheParentLevelOfAHierarchy() {
        QueryService.Result r = run(List.of("desks.region", "trades.desk"), List.of("MTM", "DeskShare", "OfRegion"), List.of(), risk());
        List<TestLake.Trade> book = lake.trades.get(TestLake.DAY2);
        for (int row = 0; row < r.rows(); row++) {
            String region = (String) r.columns().get(0).values()[row];
            String desk = (String) r.columns().get(1).values()[row];
            double mtm = sum(book.stream().filter(t -> t.desk().equals(desk)).toList());
            double ofRegion = sum(book.stream().filter(t -> region(t.desk()).equals(region)).toList());
            close(r.columns().get(3).values()[row], ofRegion == 0 ? null : mtm / ofRegion);
            close(r.columns().get(4).values()[row], ofRegion == 0 ? null : mtm / ofRegion);
        }
        QueryService.Result top = run(List.of("desks.region"), List.of("OfRegion"), List.of(), risk());
        assertThat(top.columns().get(1).values()).containsOnlyNulls();                     // the top level has no parent
        assertThat(r.meta().warnings()).anyMatch(w -> w.contains("filter context"));
    }

    @Test
    void anAverageOverAnInnerGroupingIsTheMeanOfTheInnerAggregates() {
        QueryService.Result r = run(List.of("trades.desk"), List.of("PerCpty"), List.of(), auditor());
        List<TestLake.Trade> book = lake.trades.get(TestLake.DAY2).stream().filter(auditor().sees()).toList();
        Map<String, Double> expected = new LinkedHashMap<>();
        book.stream().collect(Collectors.groupingBy(TestLake.Trade::desk)).forEach((desk, ts) -> expected.put(desk,
                ts.stream().collect(Collectors.groupingBy(TestLake.Trade::cpty, Collectors.summingDouble(TestLake.Trade::mtm))).values().stream().mapToDouble(d -> d).average().orElseThrow()));
        assertThat(r.rows()).isEqualTo(expected.size());
        for (int row = 0; row < r.rows(); row++) {
            close(r.columns().get(1).values()[row], expected.get((String) r.columns().get(0).values()[row]));
        }
    }

    @Test
    void aContextNeverReachesBeyondTheReadersRowsAndNoRollupAnswersIt() {
        QueryService.Result r = run(List.of(), List.of("Total"), List.of(), head("DESK-B"));
        close(r.columns().get(0).values()[0], sum(lake.trades.get(TestLake.DAY2).stream().filter(t -> t.desk().equals("DESK-B")).toList()));
        assertThat(r.meta().rollup()).isNull();
        assertThat(r.meta().sql()).contains("LEFT JOIN (SELECT");
    }

    @Test
    void aNamedRelationshipThatDoesNotExistIsRefused() {
        String yaml = TestLake.yaml().replace("measures:\n", "measures:\n  Via: { expr: \"calc(measure('MTM'), via='r_9')\" }\n");
        QueryService.Loaded other = new QueryService.Loaded("file:dataset:lake-via", Layers.key("file:dataset:lake-via"), DatasetParser.parse(yaml).dataset(), "h2");
        assertThatThrownBy(() -> service.query(other, new QueryRequest(List.of("trades.desk"), List.of("Via"), List.of(), TestLake.DAY2, TestLake.DAY2, List.of(), null, "auto"),
                risk().caller(), false)).isInstanceOf(DrishtiException.class).hasMessageContaining("via='r_9'");
    }
}
