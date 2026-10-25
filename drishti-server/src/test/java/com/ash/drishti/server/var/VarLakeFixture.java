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
package com.ash.drishti.server.var;

import com.ash.drishti.deltalake.write.LakeCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny VaR lake written with Drishti's lake writer, whose answers are known by hand: 20 scenarios, five trades in four positions in
 * three books of two desks, a position netting a long of 100 and a short of 40 units of one instrument, the MGMT and LEGAL trees, a
 * node history with one exception, and a RULE and a BASKET portfolio. Every vector is a multiple of the unit path {@link #U}.
 */
final class VarLakeFixture {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final int N = 20;
    /** The instrument's P&amp;L per unit: scenario 7 is the worst (-10), scenario 3 the second worst (-8). */
    static final double[] U = {1, 2, -1, -8, 3, 0, 1, -10, 2, 4, -2, 1, 0, 5, -3, 2, 1, -1, 6, -4};
    /** A second instrument, unrelated: worst on scenario 12. */
    static final double[] V = {0, 1, 2, -1, 0, 3, -2, 1, 0, -1, 2, 0, -9, 1, 0, -1, 2, 1, 0, -3};
    private static final ObjectMapper JSON = new ObjectMapper();

    /** trade id, position, book, desk, legal entity, currency, quantity, its vector */
    record Trade(String id, String pos, String book, String desk, String le, String ccy, double qty, double[] v) {}

    static List<Trade> trades() {
        return List.of(
                new Trade("T1", "POS-A", "BOOK-1", "DESK-X", "LE-A", "USD", 100, scale(U, 100)),
                new Trade("T2", "POS-A", "BOOK-1", "DESK-X", "LE-A", "USD", -40, scale(U, -40)),
                new Trade("T3", "POS-B", "BOOK-1", "DESK-X", "LE-A", "EUR", 10, scale(V, 10)),
                new Trade("T4", "POS-C", "BOOK-2", "DESK-X", "LE-A", "EUR", -20, scale(U, -20)),
                new Trade("T5", "POS-D", "BOOK-3", "DESK-Y", "LE-B", "USD", 50, scale(V, 50)));
    }

    static double[] scale(double[] a, double f) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] * f;
        }
        return out;
    }

    static List<String> scenarioDates() {
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 8, 31);
        while (out.size() < N) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
            d = d.plusDays(1);
        }
        return out;
    }

    /** Writes the lake under {@code root}/risk/; returns root. */
    static Path write(Path root) throws Exception {
        return write(root, 1.0);
    }

    /** The lake with every vector times {@code factor} (a restated batch: the data change under a capture). */
    static Path write(Path root, double factor) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        List<Map<String, Object>> vectors = new ArrayList<>();
        Map<String, Map<String, Object>> positions = new LinkedHashMap<>();
        for (Trade t : trades()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VECTOR_ID", "PV-" + t.id());
            d.put("TRADE_ID", t.id());
            d.put("POSITION_ID", t.pos());
            d.put("BUSINESS_DATE", DAY.toString());
            d.put("LEGAL_ENTITY", t.le());
            d.put("DIVISION", "DIV-M");
            d.put("DESK", t.desk());
            d.put("TT_BOOK", t.book());
            d.put("PORTFOLIO", t.book());
            d.put("PRODUCT_GROUP", "Rates");
            d.put("PRODUCT_TYPE", "IRS");
            d.put("CURRENCY", t.ccy());
            d.put("COUNTERPARTY", "CP-1");
            d.put("DIRECTION", t.qty() > 0 ? "LONG" : "SHORT");
            d.put("INSTRUMENT_ID", "INS-" + t.pos());
            d.put("QUANTITY", t.qty());
            d.put("PV", 1000.0);
            d.put("PNL", scale(t.v(), factor));
            vectors.add(d);
            Map<String, Object> p = positions.computeIfAbsent(t.pos(), k -> new LinkedHashMap<>());
            p.put("POSITION_ID", t.pos());
            p.put("BUSINESS_DATE", DAY.toString());
            p.put("INSTRUMENT_ID", "INS-" + t.pos());
            p.put("INSTRUMENT", "Swap " + t.pos());
            p.put("LEGAL_ENTITY", t.le());
            p.put("DIVISION", "DIV-M");
            p.put("DESK", t.desk());
            p.put("TT_BOOK", t.book());
            p.put("PORTFOLIO", t.book());
            p.put("PRODUCT_GROUP", "Rates");
            p.put("PRODUCT_TYPE", "IRS");
            p.put("CURRENCY", t.ccy());
            p.put("QUANTITY", ((Number) p.getOrDefault("QUANTITY", 0.0)).doubleValue() + t.qty());
            p.put("MARKET_VALUE", 2_000_000.0);
            p.put("TAGS", List.of("hedge"));
            p.put("TRADE_COUNT", ((Number) p.getOrDefault("TRADE_COUNT", 0)).intValue() + 1);
        }
        positions.values().forEach(p -> p.put("DIRECTION", ((Number) p.get("QUANTITY")).doubleValue() >= 0 ? "LONG" : "SHORT"));
        table(root, work, "pnl-vector", "VECTOR_ID", vectors, List.of("TRADE_ID", "POSITION_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "INSTRUMENT_ID", "QUANTITY", "PV"), "PNL");
        table(root, work, "position", "POSITION_ID", new ArrayList<>(positions.values()), List.of("POSITION_ID", "DIRECTION", "TT_BOOK", "DESK",
                "CURRENCY", "QUANTITY", "MARKET_VALUE"), null);
        table(root, work, "scenario-set", "SCENARIO_SET_ID",
                List.of(Map.of("SCENARIO_SET_ID", "HS-20", "BUSINESS_DATE", DAY.toString(), "SCENARIO_DATES", scenarioDates())), List.of(), null);
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("MGMT~BANK", "MGMT", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3"), 5000));
        nodes.add(node("MGMT~DESK-X", "MGMT", "desk", "MGMT~BANK", List.of("BOOK-1", "BOOK-2"), 1000));
        nodes.add(node("MGMT~DESK-Y", "MGMT", "desk", "MGMT~BANK", List.of("BOOK-3"), 600));
        nodes.add(node("MGMT~BOOK-1", "MGMT", "book", "MGMT~DESK-X", List.of("BOOK-1"), 800));
        nodes.add(node("MGMT~BOOK-2", "MGMT", "book", "MGMT~DESK-X", List.of("BOOK-2"), 500));
        nodes.add(node("MGMT~BOOK-3", "MGMT", "book", "MGMT~DESK-Y", List.of("BOOK-3"), 600));
        nodes.add(node("LEGAL~BANK", "LEGAL", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3"), 5000));
        nodes.add(node("LEGAL~LE-A", "LEGAL", "legal-entity", "LEGAL~BANK", List.of("BOOK-1", "BOOK-2"), 1000));
        nodes.add(node("LEGAL~LE-B", "LEGAL", "legal-entity", "LEGAL~BANK", List.of("BOOK-3"), 600));
        table(root, work, "risk-hierarchy", "NODE_ID", nodes, List.of("NODE_ID", "LIMIT_VAR"), null);
        // DESK-X's official history: 4 days, a loss of 900 on the third beyond the VaR of 700 the day before
        List<Map<String, Object>> hist = new ArrayList<>();
        double[] var = {600, 700, 750, 640};
        double[] hypo = {100, -300, -900, 50};
        LocalDate[] days = {DAY.minusDays(5), DAY.minusDays(2), DAY.minusDays(1), DAY};
        for (int i = 0; i < 4; i++) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("VAR_NODE_ID", "VN-MGMT~DESK-X");
            h.put("NODE_ID", "MGMT~DESK-X");
            h.put("BUSINESS_DATE", days[i].toString());
            h.put("VAR_99", var[i]);
            h.put("ES_975", var[i] * 1.2);
            h.put("HYPO_PNL", hypo[i]);
            h.put("LIMIT", 1000.0);
            hist.add(h);
        }
        table(root, work, "var-node", "VAR_NODE_ID", hist, List.of("NODE_ID", "VAR_99", "ES_975", "HYPO_PNL", "LIMIT"), null);
        List<Map<String, Object>> ports = List.of(
                Map.of("PORTFOLIO_ID", "PF-EUR", "BUSINESS_DATE", DAY.toString(), "NAME", "Euro", "TYPE", "RULE", "OWNER", "risk",
                        "DEFINITION", Map.of("RULE", "CURRENCY == 'EUR'"), "LIMIT_VAR", 400.0),
                Map.of("PORTFOLIO_ID", "PF-MIX", "BUSINESS_DATE", DAY.toString(), "NAME", "Mix", "TYPE", "BASKET", "OWNER", "risk",
                        "DEFINITION", Map.of("MEMBERS", List.of(Map.of("POSITION_ID", "POS-B"), Map.of("TRADE_ID", "T4"), Map.of("TRADE_ID", "T5")))));
        table(root, work, "portfolio", "PORTFOLIO_ID", ports, List.of("NAME", "TYPE"), null);
        return root;
    }

    private static Map<String, Object> node(String id, String h, String level, String parent, List<String> books, double limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("NODE_ID", id);
        m.put("HIERARCHY", h);
        m.put("LEVEL", level);
        m.put("PARENT_ID", parent);
        m.put("NAME", id.substring(id.indexOf('~') + 1));
        m.put("BOOKS", books);
        m.put("LIMIT_VAR", limit);
        m.put("BUSINESS_DATE", DAY.toString());
        return m;
    }

    /** One table: staged rows {@code [id, doc, {promoted}]} by business date, written with {@code lake write}. */
    private static void table(Path root, Path work, String kind, String key, List<Map<String, Object>> docs, List<String> promoted, String vector)
            throws Exception {
        Map<String, List<String>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> d : docs) {
            Map<String, Object> doc = new LinkedHashMap<>(d);
            Map<String, Object> cols = new LinkedHashMap<>();
            for (String c : promoted) {
                cols.put(c, d.get(c));
            }
            if (vector != null) {
                cols.put(vector, d.get(vector));
                doc.put(vector, Map.of("$vector", vector));
            }
            String line = JSON.writeValueAsString(List.of(String.valueOf(d.get(key)), JSON.writeValueAsString(doc), cols));
            byDay.computeIfAbsent(String.valueOf(d.get("BUSINESS_DATE")), x -> new ArrayList<>()).add(line);
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byDay.entrySet()) {
            Path f = work.resolve(kind + "-" + e.getKey() + ".rows");
            Files.write(f, e.getValue(), StandardCharsets.UTF_8);
            parts.add(Map.of("file", f.toString(), "values", Map.of("business_date", e.getKey())));
        }
        List<Map<String, Object>> columns = new ArrayList<>(List.of(Map.of("name", "id", "type", "string"), Map.of("name", "doc", "type", "string"),
                Map.of("name", "business_date", "type", "date")));
        for (String c : promoted) {
            boolean num = docs.stream().allMatch(d -> d.get(c) == null || d.get(c) instanceof Number);
            columns.add(Map.of("name", c, "type", num ? "double" : "string"));
        }
        if (vector != null) {
            columns.add(Map.of("name", vector, "type", "vector"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", root.resolve("risk").resolve(kind).toAbsolutePath().toString());
        m.put("partitionColumns", List.of("business_date"));
        m.put("columns", columns);
        m.put("promoted", promoted);
        m.put("mode", "overwrite-dates");
        m.put("batchRows", 1000);
        m.put("statsColumns", List.of("id", "business_date"));
        m.put("parts", parts);
        Path mf = work.resolve(kind + ".manifest.json");
        Files.writeString(mf, JSON.writeValueAsString(m));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        if (code != 0) {
            throw new IllegalStateException(kind + ": " + err.toString(StandardCharsets.UTF_8));
        }
    }
}
