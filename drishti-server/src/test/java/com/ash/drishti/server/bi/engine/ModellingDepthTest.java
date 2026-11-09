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
import com.ash.drishti.rachana.el.MeasureFunctions;
import com.ash.drishti.server.bi.Layered;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.tables.check.FilterPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Many-to-many through a bridge, both-ways filtering, row rules across a bridge and the filter-context control of 2.9.11.i1b, each against a worked example computed
 * by hand over the rows below (a book in two groups; one bridge row repeated). Sources are inline tables, so the SQL the compiler writes is what runs.
 */
class ModellingDepthTest {

    static final String YAML = """
            rachana: 1
            dataset: mdl
            version: 1
            tables:
              trades:
                source: { connector: c, kind: trade }
                columns:
                  TID: { type: string, key: true }
                  BOOK: string
                  AMT: decimal
              books:
                source: { connector: c, kind: book }
                columns:
                  BOOK: { type: string, key: true }
                  DESK: string
              lg:
                source: { connector: c, kind: group }
                columns:
                  GID: { type: string, key: true }
                  NAME: string
                  REGION: string
              blg:
                source: { connector: c, kind: bridge }
                columns:
                  BOOK: string
                  GID: string
            relationships:
              - { from: trades.BOOK, to: books.BOOK, cardinality: many-to-one }
            measures:
              N: { expr: "sum(trades.AMT)" }
              Top: { expr: "max(trades.AMT)" }
              Books: { expr: "count(books)" }
              AllFilters: { expr: "calc(measure('N'), ignore=ALL)" }
              NoGroup: { expr: "calc(measure('N'), ignore=GROUP)" }
              WholeTable: { expr: "calc(measure('N'), ignore=[books])" }
              KeepDesk: { expr: "calc(measure('N'), ignore=[books], keep=[books.DESK])" }
              SumByDesk: { expr: "sum_by(max(trades.AMT), by=[books.DESK])" }
              MaxByDesk: { expr: "max_by(sum(trades.AMT), by=[books.DESK])" }
              MinByDesk: { expr: "min_by(sum(trades.AMT), by=[books.DESK])" }
              BigDesks: { expr: "calc(measure('N'), ignore=[books.DESK], by=[books.DESK], having=measure('N') > 320)" }
            """;

    static final Map<String, String> SOURCES = Map.of(
            "trades", "(SELECT * FROM (VALUES ('T1','B1',100.0),('T2','B2',200.0),('T3','B3',300.0),('T4','B1',50.0)) AS v(\"TID\",\"BOOK\",\"AMT\")) AS \"trades\"",
            "books", "(SELECT * FROM (VALUES ('B1','D1'),('B2','D1'),('B3','D2')) AS v(\"BOOK\",\"DESK\")) AS \"books\"",
            "lg", "(SELECT * FROM (VALUES ('G1','Liquid','EMEA'),('G2','Reg','EMEA'),('G3','Other','APAC')) AS v(\"GID\",\"NAME\",\"REGION\")) AS \"lg\"",
            "blg", "(SELECT * FROM (VALUES ('B1','G1'),('B1','G1'),('B1','G2'),('B2','G1'),('B3','G3')) AS v(\"BOOK\",\"GID\")) AS \"blg\"");

    private final ObjectMapper json = new ObjectMapper();

    private static Dataset model(boolean leftBoth) {
        Dataset d = Layered.dataset(YAML).dataset();
        List<Dataset.Relationship> rels = new ArrayList<>(d.relationships());
        rels.add(new Dataset.Relationship("books", "BOOK", "lg", "GID", "many-to-many", "single", false, "b", "blg-name",
                new Dataset.Bridge("blg", "BOOK", "GID", "bridge_books", "bridge_lg", leftBoth, false)));
        return d.withRelationships(rels);
    }

    private static Dataset withSecurity(Dataset d, Map<String, Dataset.TableSecurity> perTable) {
        return new Dataset(d.name(), d.version(), d.title(), d.description(), d.sample(), d.engine(), d.tables(), d.relationships(), d.measures(), d.hierarchies(),
                new Dataset.Security(List.of(), d.security().masked(), perTable), d.rollups(), d.refresh(), null, null, List.of(), d.model());
    }

    private List<Map<String, Object>> run(Dataset d, SqlCompiler.Reader reader, String q) throws Exception {
        SqlCompiler c = new SqlCompiler(d, Dialect.DUCKDB);
        SqlCompiler.Plan p = c.plan(QueryRequest.of(json.readTree(q)), reader);
        Map<String, SqlCompiler.Source> src = new LinkedHashMap<>();
        for (String t : p.tables()) {
            src.put(t, new SqlCompiler.Source(SOURCES.get(t), List.of()));
        }
        SqlCompiler.Compiled compiled = c.compile(p, reader, src, 1000, null, TimeWindows.Context.weekends(java.time.LocalDate.of(2026, 1, 1)),
                (t, from, to) -> new SqlCompiler.Source(SOURCES.get(t), List.of()));
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); java.sql.PreparedStatement s = db.prepareStatement(compiled.sql())) {
            int i = 1;
            for (Object o : compiled.params()) {
                s.setObject(i++, o);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int k = 1; k <= n; k++) {
                        row.put(r.getMetaData().getColumnLabel(k), r.getObject(k));
                    }
                    out.add(row);
                }
            }
            return out;
        }
    }

    static final SqlCompiler.Reader ALL = new SqlCompiler.Reader(false, Set.of("risk"), Map.of(), SqlCompiler.Masks.NONE);

    private static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    private double one(Dataset ds, String q, String measure) throws Exception {
        List<Map<String, Object>> rows = run(ds, ALL, q);
        assertThat(rows).hasSize(1);
        return d(rows.get(0).get(measure));
    }

    @Test
    void eachGroupItsOwnRowsAndTheTotalCountsATradeOnce() throws Exception {
        Dataset ds = model(true);
        List<Map<String, Object>> rows = run(ds, ALL, "{\"fields\":[\"lg.NAME\"],\"measures\":[\"N\"]}");
        Map<Object, Double> by = new LinkedHashMap<>();
        rows.forEach(r -> by.put(r.get("lg.NAME"), d(r.get("N"))));
        assertThat(by).containsEntry("Liquid", 350.0).containsEntry("Reg", 150.0).containsEntry("Other", 300.0);
        assertThat(by.get("Liquid") + by.get("Reg") + by.get("Other")).as("group rows do not add to the total").isEqualTo(800.0);
        assertThat(one(ds, "{\"measures\":[\"N\"]}", "N")).as("total: each trade once").isEqualTo(650.0);
    }

    @Test
    void aFilterAcrossTheBridgeIsASemiJoin() throws Exception {
        Dataset ds = model(true);
        double v = one(ds, "{\"measures\":[\"N\"],\"filters\":[{\"field\":\"lg.NAME\",\"op\":\"in\",\"values\":[\"Liquid\",\"Reg\"]}]}", "N");
        assertThat(v).as("T1, T2, T4 once each, not 500").isEqualTo(350.0);
    }

    @Test
    void aCoarserFarColumnDoesNotDoubleCount() throws Exception {
        Dataset ds = model(true);
        Map<Object, Double> by = new LinkedHashMap<>();
        run(ds, ALL, "{\"fields\":[\"lg.REGION\"],\"measures\":[\"N\"]}").forEach(r -> by.put(r.get("lg.REGION"), d(r.get("N"))));
        assertThat(by).containsEntry("EMEA", 350.0).containsEntry("APAC", 300.0);
    }

    @Test
    void offWithLeftOneWay() {
        Dataset ds = model(false);
        assertThatThrownBy(() -> run(ds, ALL, "{\"fields\":[\"lg.NAME\"],\"measures\":[\"N\"]}")).isInstanceOf(DrishtiException.class);
    }

    @Test
    void rowRulesAreAppliedInsideTheBridge() throws Exception {
        Dataset ds = model(true);
        Dataset.TableSecurity onlyApac = new Dataset.TableSecurity(List.of(), List.of(List.of(new Dataset.RowRule("desk", "lg.REGION == 'APAC'"))), true);
        Dataset sec = withSecurity(ds, Map.of("lg", onlyApac));
        SqlCompiler.Reader reader = new SqlCompiler.Reader(true, Set.of("desk"), Map.of(), SqlCompiler.Masks.NONE);
        List<Map<String, Object>> rows = run(sec, reader, "{\"fields\":[\"lg.NAME\"],\"measures\":[\"N\"]}");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("lg.NAME")).isEqualTo("Other");
        assertThat(d(rows.get(0).get("N"))).isEqualTo(300.0);
        List<Map<String, Object>> total = run(sec, reader, "{\"measures\":[\"N\"]}");
        assertThat(d(total.get(0).get("N"))).as("a restricted far table reaches the facts across the bridge").isEqualTo(300.0);
    }

    @Test
    void aRuleReadingBridgeColumnsCannotBeBypassed() throws Exception {
        Dataset ds = model(true);
        Dataset.TableSecurity none = new Dataset.TableSecurity(List.of(), List.of(List.of(new Dataset.RowRule("other", "true"))), true);
        Dataset sec = withSecurity(ds, Map.of("blg", none));
        SqlCompiler.Reader reader = new SqlCompiler.Reader(true, Set.of("desk"), Map.of(), SqlCompiler.Masks.NONE);
        assertThat(run(sec, reader, "{\"fields\":[\"lg.NAME\"],\"measures\":[\"N\"]}")).as("no rule for this role on the bridge: no rows").isEmpty();
    }

    @Test
    void aMaskedFarColumnIsNotAGroup() throws Exception {
        Dataset ds = model(true);
        SqlCompiler.Reader reader = new SqlCompiler.Reader(false, Set.of("desk"), Map.of(), (t, c) -> "lg".equals(t) && "NAME".equals(c));
        List<Map<String, Object>> rows = run(ds, reader, "{\"fields\":[\"lg.NAME\"],\"measures\":[\"N\"]}");
        assertThat(rows).hasSize(1);
        assertThat(d(rows.get(0).get("N"))).as("one masked row, each trade once").isEqualTo(650.0);
        assertThatThrownBy(() -> run(ds, reader, "{\"measures\":[\"N\"],\"filters\":[{\"field\":\"lg.NAME\",\"op\":\"eq\",\"values\":[\"Reg\"]}]}")).isInstanceOf(DrishtiException.class);
    }

    @Test
    void aMeasureOnTheFarSideIsRefused() {
        Dataset ds = model(true);
        Map<String, Dataset.Measure> m = new LinkedHashMap<>(ds.measures());
        m.put("Bad", new Dataset.Measure("Bad", "sum(trades.AMT) + count(lg)", null, null, null));
        Dataset bad = ds.withMeasures(m);
        assertThatThrownBy(() -> run(bad, ALL, "{\"measures\":[\"Bad\"]}")).isInstanceOf(DrishtiException.class).hasMessageContaining("many-to-many");
    }

    @Test
    void bothWaysReadsAOneSideMeasureBySemiJoin() throws Exception {
        Dataset d = Layered.dataset(YAML).dataset();
        List<Dataset.Relationship> rels = new ArrayList<>();
        for (Dataset.Relationship r : d.relationships()) {
            rels.add(new Dataset.Relationship(r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), r.cardinality(), "both", r.active(), r.id(), "trade_book", null));
        }
        Dataset ds = d.withRelationships(rels);
        double v = one(ds, "{\"measures\":[\"Books\"],\"filters\":[{\"field\":\"trades.AMT\",\"op\":\"ge\",\"values\":[200]}]}", "Books");
        assertThat(v).as("B2, B3 once each").isEqualTo(2.0);
    }

    @Test
    void ignoreATableAndGroupOnly() throws Exception {
        Dataset ds = model(true);
        String q = "{\"fields\":[\"books.DESK\"],\"measures\":[\"N\",\"WholeTable\",\"NoGroup\",\"KeepDesk\",\"AllFilters\"],"
                + "\"filters\":[{\"field\":\"trades.AMT\",\"op\":\"gt\",\"values\":[60]}]}";
        List<Map<String, Object>> rows = run(ds, ALL, q);
        Map<Object, Map<String, Object>> by = new LinkedHashMap<>();
        rows.forEach(r -> by.put(r.get("books.DESK"), r));
        Map<String, Object> d1 = by.get("D1");
        assertThat(d(d1.get("N"))).isEqualTo(300.0);          // T1 100 + T2 200
        assertThat(d(d1.get("WholeTable"))).isEqualTo(600.0); // ignore=[books]: both desks, the filter on trades stays
        assertThat(d(d1.get("NoGroup"))).isEqualTo(600.0);
        assertThat(d(d1.get("KeepDesk"))).as("keep narrows what ignore drops").isEqualTo(300.0);
        assertThat(d(d1.get("AllFilters"))).isEqualTo(650.0);
    }

    @Test
    void contextTransitionAgainstAWorkedExample() throws Exception {
        Dataset ds = model(true);
        assertThat(one(ds, "{\"measures\":[\"SumByDesk\"]}", "SumByDesk")).as("D1 max 200 + D2 max 300").isEqualTo(500.0);
        assertThat(one(ds, "{\"measures\":[\"MaxByDesk\"]}", "MaxByDesk")).as("D1 350 vs D2 300").isEqualTo(350.0);
        assertThat(one(ds, "{\"measures\":[\"MinByDesk\"]}", "MinByDesk")).isEqualTo(300.0);
    }

    @Test
    void havingKeepsTheGroupsThatPass() throws Exception {
        Dataset ds = model(true);
        assertThat(one(ds, "{\"measures\":[\"BigDesks\"]}", "BigDesks")).as("only D1 (350) passes > 320").isEqualTo(350.0);
    }

    @Test
    void sumOfANonAdditiveMeasureIsRefused() {
        Dataset d = model(true);
        Map<String, Dataset.Measure> m = new LinkedHashMap<>(d.measures());
        m.put("Bad", new Dataset.Measure("Bad", "sum_by(measure('Top'), by=[books.DESK])", null, null, null));
        Dataset base = d.withMeasures(m);
        Dataset ds = new Dataset(base.name(), base.version(), base.title(), base.description(), base.sample(), base.engine(), base.tables(), base.relationships(), base.measures(),
                base.hierarchies(), base.security(), base.rollups(), base.refresh(), null, null, List.of(), new Dataset.Model(null, 1, null, Map.of(), Set.of("Top")));
        assertThatThrownBy(() -> run(ds, ALL, "{\"measures\":[\"Bad\"]}")).isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-9127");
    }

    @Test
    void anAmbiguousPathIsRefusedWithBothPathsAndTheFix() {
        List<FilterPaths.Link> links = List.of(new FilterPaths.Link("trade_book", "trades", "books", false, false), new FilterPaths.Link("bridge_book", "blg", "books", true, true),
                new FilterPaths.Link("bridge_group", "blg", "lg", false, true), new FilterPaths.Link("trade_cpty", "trades", "cpty", false, false),
                new FilterPaths.Link("cpty_group", "cpty", "lg", false, false));
        assertThat(FilterPaths.ambiguous(links)).contains("Two filter paths from lg to trades").contains("lg -> blg -> books -> trades").contains("lg -> cpty -> trades")
                .contains("make 'cpty_group' inactive").contains("turn off both ways on 'bridge_book'");
        List<FilterPaths.Link> oneWay = new ArrayList<>(links);
        oneWay.set(1, new FilterPaths.Link("bridge_book", "blg", "books", false, true));
        assertThat(FilterPaths.ambiguous(oneWay)).as("the bridge one way removes the path").isNull();
    }

    @Test
    void daxConvertsCommonPatternsAndNamesTheRest() {
        var r = com.ash.drishti.server.bi.dax.DaxConverter.convert("Live = CALCULATE([MTM], trades[STATUS] = \"LIVE\")");
        assertThat(r.rachana()).isEqualTo("calc(measure('MTM'), ignore = [trades.STATUS], where = trades.STATUS == 'LIVE')");
        assertThat(com.ash.drishti.server.bi.dax.DaxConverter.convert("CALCULATE(SUM(trades[MTM]), ALL(desks))").rachana()).isEqualTo("calc(sum(trades.MTM), ignore = [desks])");
        assertThat(com.ash.drishti.server.bi.dax.DaxConverter.convert("CALCULATE([MTM], ALLEXCEPT(desks, desks[REGION]))").rachana()).isEqualTo("calc(measure('MTM'), ignore = [desks], keep = [desks.REGION])");
        assertThat(com.ash.drishti.server.bi.dax.DaxConverter.convert("SUMX(VALUES(desks[NAME]), CALCULATE(MAX(trades[MTM])))").rachana()).isEqualTo("sum_by(calc(max(trades.MTM)), by = [desks.NAME])");
        assertThat(com.ash.drishti.server.bi.dax.DaxConverter.convert("CALCULATE([MTM], FILTER(ALL(desks[NAME]), [VaR] > 1000000))").rachana())
                .isEqualTo("calc(measure('MTM'), ignore = [desks.NAME], by = [desks.NAME], having = measure('VaR') > 1000000)");
        var no = com.ash.drishti.server.bi.dax.DaxConverter.convert("RANKX(ALL(desks), [MTM])");
        assertThat(no.rachana()).isNull();
        assertThat(no.notConverted().get(0)).startsWith("RANKX");
    }

    @Test
    void measureFunctionsExpandBeforeTheChecks() {
        Map<String, MeasureFunctions.Def> f = Map.of("share", new MeasureFunctions.Def(List.of("a", "b"), "a / b"));
        assertThat(MeasureFunctions.expand("share(sum(t.X), sum(t.Y))", f)).isEqualTo("sum(t.X) / sum(t.Y)");
        assertThatThrownBy(() -> MeasureFunctions.expand("share(sum(t.X))", f)).hasMessageContaining("takes 2 arguments");
        Map<String, MeasureFunctions.Def> loop = Map.of("x", new MeasureFunctions.Def(List.of("a"), "x(a)"));
        assertThatThrownBy(() -> MeasureFunctions.expand("x(1)", loop)).hasMessageContaining("calls itself");
    }
}
