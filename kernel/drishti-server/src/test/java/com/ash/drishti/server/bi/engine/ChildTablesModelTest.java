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

import com.ash.drishti.server.bi.Layered;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The BI files {@code pack new} writes for a kind with a child kind (tools/packgen/childkinds.py; the fixture is its output for the book of
 * tools/test_pack_new_children.py, which checks the two stay the same): the parent and child tables and the model relating each leg to its
 * deal, many-to-one and both ways. Sums over the legs by a leg's or a deal's field, and the deals having any leg where ..., each against a
 * worked example over the rows below; the sources are inline tables, so the SQL the compiler writes is what runs.
 */
class ChildTablesModelTest {

    /** Three deals; T1 has a USD and a EUR leg, T2 two EUR legs, T3 one GBP leg. */
    static final Map<String, String> SOURCES = Map.of(
            "deal", "(SELECT * FROM (VALUES ('T1','IRS','ENDUR',100.0),('T2','CCS','ENDUR',200.0),('T3','IRS','ENDUR',300.0))"
                    + " AS v(\"TRADE_ID\",\"PRODUCT_TYPE\",\"SOURCE_SYSTEM\",\"NOTIONAL\")) AS \"deal\"",
            "deal_leg", "(SELECT * FROM (VALUES ('T1:1','T1',1,'FIXED','USD',100.0),('T1:2','T1',2,'FLOAT','EUR',100.0),"
                    + "('T2:1','T2',1,'FIXED','EUR',200.0),('T2:2','T2',2,'FLOAT','EUR',50.0),('T3:1','T3',1,'FIXED','GBP',300.0))"
                    + " AS v(\"LEG_KEY\",\"TRADE_ID\",\"LEG_ID\",\"LEG_TYPE\",\"CURRENCY\",\"NOTIONAL\")) AS \"deal_leg\"");

    static final SqlCompiler.Reader ALL = new SqlCompiler.Reader(false, Set.of("risk"), Map.of(), SqlCompiler.Masks.NONE);
    private final ObjectMapper json = new ObjectMapper();

    private static String fixture(String name) throws IOException {
        try (InputStream in = ChildTablesModelTest.class.getResourceAsStream("/childtables/deals/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Dataset model() throws IOException {
        DatasetParser.Result r = Layered.flatten(fixture("deals.model.yaml"), List.of(fixture("deals.table.yaml"), fixture("deal_legs.table.yaml")));
        assertThat(r.problems().stream().filter(p -> "error".equals(p.level())).toList()).isEmpty();
        return r.dataset();
    }

    private List<Map<String, Object>> run(String q) throws Exception {
        Dataset d = model();
        SqlCompiler c = new SqlCompiler(d, Dialect.DUCKDB);
        SqlCompiler.Plan p = c.plan(QueryRequest.of(json.readTree(q)), ALL);
        Map<String, SqlCompiler.Source> src = new LinkedHashMap<>();
        for (String t : p.tables()) {
            src.put(t, new SqlCompiler.Source(SOURCES.get(t), List.of()));
        }
        SqlCompiler.Compiled compiled = c.compile(p, ALL, src, 1000, null, TimeWindows.Context.weekends(java.time.LocalDate.of(2026, 11, 3)),
                (t, from, to) -> new SqlCompiler.Source(SOURCES.get(t), List.of()));
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); PreparedStatement s = db.prepareStatement(compiled.sql())) {
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

    private Map<Object, Double> by(String field, String measure, String filters) throws Exception {
        Map<Object, Double> out = new LinkedHashMap<>();
        run("{\"fields\":[\"" + field + "\"],\"measures\":[\"" + measure + "\"]" + filters + "}")
                .forEach(r -> out.put(r.get(field), ((Number) r.get(measure)).doubleValue()));
        return out;
    }

    private double one(String measure, String filters) throws Exception {
        List<Map<String, Object>> rows = run("{\"measures\":[\"" + measure + "\"]" + filters + "}");
        assertThat(rows).hasSize(1);
        return ((Number) rows.get(0).get(measure)).doubleValue();
    }

    @Test
    void theModelRelatesEachLegToItsDealBothWays() throws Exception {
        Dataset d = model();
        assertThat(d.relationships()).hasSize(1);
        Dataset.Relationship r = d.relationships().get(0);
        assertThat(List.of(r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), r.cardinality(), r.direction()))
                .containsExactly("deal_leg", "TRADE_ID", "deal", "TRADE_ID", "many-to-one", "both");
    }

    @Test
    void notionalByLegCurrencySumsEveryLegOnce() throws Exception {
        assertThat(by("deal_leg.CURRENCY", "Deal leg NOTIONAL", "")).containsOnly(Map.entry("USD", 100.0), Map.entry("EUR", 350.0), Map.entry("GBP", 300.0));
        assertThat(one("Deal leg NOTIONAL", "")).isEqualTo(750.0);
        assertThat(one("Deal legs", "")).isEqualTo(5.0);
    }

    @Test
    void legNotionalByTheDealsProductTypeJoinsManyToOne() throws Exception {
        assertThat(by("deal.PRODUCT_TYPE", "Deal leg NOTIONAL", "")).containsOnly(Map.entry("IRS", 500.0), Map.entry("CCS", 250.0));
    }

    @Test
    void dealsHavingAnyLegWhereCountEachDealOnce() throws Exception {
        String eur = ",\"filters\":[{\"field\":\"deal_leg.CURRENCY\",\"op\":\"eq\",\"values\":[\"EUR\"]}]";
        assertThat(one("Deals", eur)).as("T1 and T2, not one per EUR leg").isEqualTo(2.0);
        assertThat(one("Deal NOTIONAL", eur)).as("T1 100 + T2 200, each once (a plain join would say 500)").isEqualTo(300.0);
        assertThat(one("Deals", "")).isEqualTo(3.0);
        assertThat(one("Deal NOTIONAL", "")).isEqualTo(600.0);
    }
}
