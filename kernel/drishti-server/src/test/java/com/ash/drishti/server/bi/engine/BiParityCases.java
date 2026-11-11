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

import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The BI parity cases (HOW_IT_FITS.md section A26.9): the levels, measures, filters, dates and readers BI's totals are asked with, on the
 * test lake, and the fixtures that hold the figures the code BI used before the switch gave for each level (one grouped query per level).
 * The recorder ({@link BiParityRecorder}) writes them from the code it runs on; {@code BiParityTest} compares the pivot engine with them.
 * This class uses only what both codes have ({@link QueryService#query}), so the recorder runs on the tree before the switch too.
 */
public final class BiParityCases {

    /** Where the fixtures live (the module's test resources). */
    public static final Path FIXTURES = Path.of("src/test/resources/bi/parity/lake-test.json");

    /** Every measure of the test lake that is an aggregate of its group's rows. */
    public static final List<String> MEASURES = List.of("Notional", "MTM", "Trades", "AvgMTM", "MTMperM", "Names", "P50", "EurMTM", "Biggest",
            "Share", "NotionalM");

    /** The levels a matrix of desk and currency by counterparty asks (grand, per desk, per counterparty, desk by counterparty, desk by currency). */
    public static final List<List<String>> LEVELS = List.of(List.of(), List.of("trades.desk"), List.of("trades.cpty"),
            List.of("trades.desk", "trades.cpty"), List.of("trades.desk", "trades.currency"));

    /**
     * One case.
     *
     * @param name its name in the fixture
     * @param from the first business date
     * @param to the last business date
     * @param filters the visual's filters
     * @param reader {@code risk} (sees everything), {@code masked} (the trades' mtm masked), {@code desk-head} (DESK-A and DESK-B by the row
     *        rule), {@code desk-head-none} (no desks: no rows) or {@code auditor} (DESK-B or DESK-C)
     */
    public record Case(String name, LocalDate from, LocalDate to, List<QueryRequest.Filter> filters, String reader) {}

    public static final List<Case> CASES = List.of(
            new Case("day2", TestLake.DAY2, TestLake.DAY2, List.of(), "risk"),
            new Case("day1", TestLake.DAY1, TestLake.DAY1, List.of(), "risk"),
            new Case("two-days", TestLake.DAY1, TestLake.DAY2, List.of(), "risk"),
            new Case("eur-usd", TestLake.DAY2, TestLake.DAY2, List.of(new QueryRequest.Filter("trades.currency", "in", List.<Object>of("EUR", "USD"))), "risk"),
            new Case("masked-mtm", TestLake.DAY2, TestLake.DAY2, List.of(), "masked"),
            // 2.9.11.d: the readers' row rules (a desk-head's strict in, with and without desks; an auditor's or-rule), a filter that excludes, a
            // slicer of one desk over the window, the masked reader over the window, a rule and a filter together, and a filter no row passes
            new Case("desk-head-two-desks", TestLake.DAY2, TestLake.DAY2, List.of(), "desk-head"),
            new Case("desk-head-no-desks", TestLake.DAY2, TestLake.DAY2, List.of(), "desk-head-none"),
            new Case("auditor", TestLake.DAY2, TestLake.DAY2, List.of(), "auditor"),
            new Case("not-eur", TestLake.DAY2, TestLake.DAY2, List.of(new QueryRequest.Filter("trades.currency", "not-in", List.<Object>of("EUR"))), "risk"),
            new Case("desk-a-two-days", TestLake.DAY1, TestLake.DAY2, List.of(new QueryRequest.Filter("trades.desk", "in", List.<Object>of("DESK-A"))), "risk"),
            new Case("masked-two-days", TestLake.DAY1, TestLake.DAY2, List.of(), "masked"),
            new Case("auditor-usd", TestLake.DAY2, TestLake.DAY2, List.of(new QueryRequest.Filter("trades.currency", "in", List.<Object>of("USD"))), "auditor"),
            new Case("no-rows", TestLake.DAY2, TestLake.DAY2, List.of(new QueryRequest.Filter("trades.desk", "in", List.<Object>of("NO-SUCH-DESK"))), "risk"));

    private BiParityCases() {}

    public static QueryService.Caller caller(String reader) {
        return switch (reader) {
            case "masked" -> new QueryService.Caller("ann", Set.of("risk"), true, UserAttributes.of("ann", List.of("risk"), "", null), (t, c) -> c.equals("mtm"));
            case "desk-head" -> new QueryService.Caller("hana", Set.of("desk-head"), true, UserAttributes.of("hana", List.of("desk-head"), "DESK-A,DESK-B", null),
                    SqlCompiler.Masks.NONE);
            case "desk-head-none" -> new QueryService.Caller("dina", Set.of("desk-head"), true, UserAttributes.of("dina", List.of("desk-head"), "", null),
                    SqlCompiler.Masks.NONE);
            case "auditor" -> new QueryService.Caller("otto", Set.of("auditor"), true, UserAttributes.of("otto", List.of("auditor"), "", null), SqlCompiler.Masks.NONE);
            default -> new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
        };
    }

    /** The case's question with no fields (each level adds its own). */
    public static QueryRequest base(Case c) {
        return new QueryRequest(List.of(), List.of(), c.filters(), c.from(), c.to(), List.of(), 5000, "auto");
    }

    /**
     * The figures the code being run gives each level of a case, one grouped query per level (how BI answered totals before the switch):
     * {@code [{by, rows: [[by values..., measure values...]]}]}, the rows in the order of their text, values as text and numbers.
     */
    public static List<Map<String, Object>> record(QueryService service, QueryService.Loaded ds, Case c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<String> by : LEVELS) {
            QueryRequest q = new QueryRequest(by, MEASURES, c.filters(), c.from(), c.to(), List.of(), 5000, "auto");
            QueryService.Result r = service.query(ds, q, caller(c.reader()), false);
            List<List<Object>> rows = new ArrayList<>();
            for (int i = 0; i < r.rows(); i++) {
                List<Object> row = new ArrayList<>();
                for (int col = 0; col < r.columns().size(); col++) {
                    Object v = r.columns().get(col).values()[i];
                    row.add(col < by.size() ? (v == null ? null : String.valueOf(v)) : v instanceof Number n ? n.doubleValue() : v);
                }
                rows.add(row);
            }
            rows.sort(java.util.Comparator.comparing((List<Object> x) -> String.valueOf(x.subList(0, by.size()))));
            Map<String, Object> level = new LinkedHashMap<>();
            level.put("by", by);
            level.put("measures", MEASURES);
            level.put("rows", rows);
            out.add(level);
        }
        return out;
    }

    public static void write(Map<String, Object> all) throws IOException {
        Files.createDirectories(FIXTURES.getParent());
        Files.writeString(FIXTURES, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(all) + "\n");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read() throws IOException {
        return new ObjectMapper().readValue(FIXTURES.toFile(), Map.class);
    }
}
