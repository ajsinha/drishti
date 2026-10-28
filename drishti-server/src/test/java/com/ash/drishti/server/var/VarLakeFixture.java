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
 * three books of two desks, a position netting a long of 100 and a short of 40 units of one security, the MGMT and LEGAL trees, and a
 * RULE and a BASKET portfolio. Every vector is a multiple of the unit path {@link #U}. Beside them, rows the engine must not count: a
 * trade of no risk booked by another legal entity than its book's (a warning for {@code LE:}), a cancelled trade, a vector one value
 * short and a vector with a null in it (both rejected, DRS-5201). Positions are not in the lake: the engine derives them by the rules
 * the test sets (Fixed income by book and SECURITY_ID; everything else one position per trade).
 */
final class VarLakeFixture {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final int N = 20;
    /** The instrument's P&amp;L per unit: scenario 7 is the worst (-10), scenario 3 the second worst (-8). */
    static final double[] U = {1, 2, -1, -8, 3, 0, 1, -10, 2, 4, -2, 1, 0, 5, -3, 2, 1, -1, 6, -4};
    /** A second instrument, unrelated: worst on scenario 12. */
    static final double[] V = {0, 1, 2, -1, 0, 3, -2, 1, 0, -1, 2, 0, -9, 1, 0, -1, 2, 1, 0, -3};
    /** The positions the rules derive. */
    static final String POS_A = "POS-BOOK-1~SEC-A";
    static final String POS_B = "POS-BOOK-1~SEC-B";
    static final String POS_C = "POS-BOOK-2~T4";
    static final String POS_D = "POS-BOOK-3~SEC-D";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** trade id, security (null: OTC), book, desk, legal entity, currency, counterparty, status, quantity, its vector (a list may hold null) */
    record Trade(String id, String security, String book, String desk, String le, String ccy, String cpty, String status, double qty, Object v) {}

    static List<Trade> trades() {
        List<Object> withNull = new ArrayList<>();
        for (double x : U) {
            withNull.add(x);
        }
        withNull.set(4, null);
        return List.of(
                new Trade("T1", "SEC-A", "BOOK-1", "DESK-X", "LE-A", "USD", "CP-1", "VALIDATED", 100, scale(U, 100)),
                new Trade("T2", "SEC-A", "BOOK-1", "DESK-X", "LE-A", "USD", "CP-1", "AMENDED", -40, scale(U, -40)),
                new Trade("T3", "SEC-B", "BOOK-1", "DESK-X", "LE-A", "EUR", "CP-1", "NEW", 10, scale(V, 10)),
                new Trade("T4", null, "BOOK-2", "DESK-X", "LE-A", "EUR", "CP-1", "VALIDATED", -20, scale(U, -20)),
                new Trade("T5", "SEC-D", "BOOK-3", "DESK-Y", "LE-B", "USD", "CP-2", "VALIDATED", 50, scale(V, 50)),
                new Trade("T9", null, "BOOK-2", "DESK-X", "LE-B", "USD", "CP-1", "VALIDATED", 0, scale(U, 0)),
                new Trade("T6", null, "BOOK-2", "DESK-X", "LE-A", "USD", "CP-1", "CANCELLED", 1000, scale(U, 1000)),
                new Trade("T7", null, "BOOK-2", "DESK-X", "LE-A", "USD", "CP-1", "VALIDATED", 5, java.util.Arrays.copyOf(scale(U, 5), N - 1)),
                new Trade("T8", null, "BOOK-2", "DESK-X", "LE-A", "USD", "CP-1", "VALIDATED", 5, withNull));
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

    /** Writes the business date {@link #DAY} under {@code root}/risk/; returns root. */
    /** Sets a field the row does not name at all (a row that names it as null keeps the null: a record missing it). */
    private static void putIfMissing(Map<String, Object> r, String k, Object v) {
        if (!r.containsKey(k)) {
            r.put(k, v);
        }
    }

    static Path write(Path root) throws Exception {
        return write(root, DAY, 1.0, false, false);
    }

    /**
     * One business date: every vector times {@code factor} (a restated batch: the data change under a capture); {@code mixedRuns}: the
     * vectors come from two batch runs (the date is refused, DRS-5203); {@code duplicate}: T1 has a second vector (DRS-5202).
     */
    static Path write(Path root, LocalDate day, double factor, boolean mixedRuns, boolean duplicate) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        List<Map<String, Object>> vectors = new ArrayList<>();
        int i = 0;
        List<Trade> trades = new ArrayList<>(trades());
        if (duplicate) {
            trades.add(new Trade("T1", "SEC-A", "BOOK-1", "DESK-X", "LE-A", "USD", "CP-1", "VALIDATED", 1, scale(U, 1)));
        }
        for (Trade t : trades) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VECTOR_ID", "PV-" + t.id() + (vectors.stream().anyMatch(v -> t.id().equals(v.get("TRADE_ID"))) ? "-again" : ""));
            d.put("TRADE_ID", t.id());
            d.put("BUSINESS_DATE", day.toString());
            d.put("SCENARIO_SET_ID", "HS-20");
            d.put("BATCH_ID", mixedRuns && i++ % 2 == 1 ? "B-" + day + "-02" : "B-" + day + "-01");
            d.put("LEGAL_ENTITY", t.le());
            d.put("DIVISION", "DIV-M");
            d.put("DESK", t.desk());
            d.put("TT_BOOK", t.book());
            d.put("PORTFOLIO", t.book());
            d.put("PRODUCT_GROUP", t.security() == null ? "Rates" : "Fixed income");
            d.put("PRODUCT_TYPE", t.security() == null ? "IRS" : "GOVT_BOND");
            d.put("SECURITY_ID", t.security());
            d.put("CURRENCY", t.ccy());
            d.put("COUNTERPARTY", t.cpty());
            d.put("DIRECTION", t.qty() >= 0 ? "LONG" : "SHORT");
            d.put("QUANTITY", t.qty());
            d.put("PV", 1000.0);
            d.put("TRADE_STATUS", t.status());
            d.put("MATURITY_DATE", "2030-01-15");
            d.put("STATUS", "ok");
            d.put("REPORTING_CCY", "USD");
            d.put("PNL", t.v() instanceof double[] a ? scale(a, factor) : t.v());
            vectors.add(d);
        }
        table(root, work, "pnl-vector", "VECTOR_ID", vectors, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO",
                "PRODUCT_GROUP", "PRODUCT_TYPE", "SECURITY_ID", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS",
                "MATURITY_DATE", "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        table(root, work, "scenario-set", "SCENARIO_SET_ID",
                List.of(Map.of("SCENARIO_SET_ID", "HS-20", "BUSINESS_DATE", day.toString(), "SCENARIO_DATES", scenarioDates())), List.of(), null);
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node(day, "MGMT~BANK", "MGMT", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3"), 5000));
        nodes.add(node(day, "MGMT~DESK-X", "MGMT", "desk", "MGMT~BANK", List.of("BOOK-1", "BOOK-2"), 1000));
        nodes.add(node(day, "MGMT~DESK-Y", "MGMT", "desk", "MGMT~BANK", List.of("BOOK-3"), 600));
        nodes.add(node(day, "MGMT~BOOK-1", "MGMT", "book", "MGMT~DESK-X", List.of("BOOK-1"), 800));
        nodes.add(node(day, "MGMT~BOOK-2", "MGMT", "book", "MGMT~DESK-X", List.of("BOOK-2"), 500));
        nodes.add(node(day, "MGMT~BOOK-3", "MGMT", "book", "MGMT~DESK-Y", List.of("BOOK-3"), 600));
        nodes.add(node(day, "LEGAL~BANK", "LEGAL", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3"), 5000));
        nodes.add(node(day, "LEGAL~LE-A", "LEGAL", "legal-entity", "LEGAL~BANK", List.of("BOOK-1", "BOOK-2"), 1000));
        nodes.add(node(day, "LEGAL~LE-B", "LEGAL", "legal-entity", "LEGAL~BANK", List.of("BOOK-3"), 600));
        table(root, work, "risk-hierarchy", "NODE_ID", nodes, List.of("NODE_ID", "LIMIT_VAR"), null);
        List<Map<String, Object>> ports = List.of(
                Map.of("PORTFOLIO_ID", "PF-EUR", "BUSINESS_DATE", day.toString(), "NAME", "Euro", "TYPE", "RULE", "OWNER", "risk",
                        "DEFINITION", Map.of("RULE", "CURRENCY == 'EUR'"), "LIMIT_VAR", 400.0),
                Map.of("PORTFOLIO_ID", "PF-MIX", "BUSINESS_DATE", day.toString(), "NAME", "Mix", "TYPE", "BASKET", "OWNER", "risk",
                        "DEFINITION", Map.of("MEMBERS", List.of(Map.of("POSITION_ID", POS_B), Map.of("TRADE_ID", "T4"), Map.of("TRADE_ID", "T5")))));
        table(root, work, "portfolio", "PORTFOLIO_ID", ports, List.of("NAME", "TYPE"), null);
        return root;
    }

    private static Map<String, Object> node(LocalDate day, String id, String h, String level, String parent, List<String> books, double limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("NODE_ID", id);
        m.put("HIERARCHY", h);
        m.put("LEVEL", level);
        m.put("PARENT_ID", parent);
        m.put("NAME", id.substring(id.indexOf('~') + 1));
        m.put("BOOKS", books);
        m.put("LIMIT_VAR", limit);
        m.put("BUSINESS_DATE", day.toString());
        return m;
    }

    /** One table: staged rows {@code [id, doc, {promoted}]} by business date, written with {@code lake write}. */
    static void table(Path root, Path work, String kind, String key, List<Map<String, Object>> docs, List<String> promoted, String vector)
            throws Exception {
        table(root, work, kind, key, docs, promoted, vector, "overwrite-dates");
    }

    /** The same, in a write mode of the lake writer ({@code append}: an intraday batch beside the morning run). */
    static void table(Path root, Path work, String kind, String key, List<Map<String, Object>> docs, List<String> promoted, String vector,
            String mode) throws Exception {
        if (kind.equals("pnl-vector") || kind.equals("scenario-pv")) {    // every PV record says what it is (VAR_GUIDE.md 15.13)
            List<Map<String, Object>> said = new ArrayList<>();
            for (Map<String, Object> d : docs) {
                Map<String, Object> r = new LinkedHashMap<>(d);
                putIfMissing(r, "VALUE_TYPE", kind.equals("pnl-vector") ? "PNL" : "PV");
                putIfMissing(r, "GRAIN", r.get("POSITION_ID") != null && r.get("TRADE_ID") == null ? "POSITION" : "TRADE");
                putIfMissing(r, "SOURCE_SYSTEM", kind.equals("pnl-vector") ? "MUREX" : "ENDUR");
                putIfMissing(r, "CURRENCY", "USD");
                // a P&L row's CURRENCY is its values' (2.9.4): the fixture's trade currency moves to TRADE_CURRENCY
                if (kind.equals("pnl-vector") && !r.containsKey("TRADE_CURRENCY") && r.get("REPORTING_CCY") != null
                        && !r.get("REPORTING_CCY").equals(r.get("CURRENCY"))) {
                    putIfMissing(r, "TRADE_CURRENCY", r.get("CURRENCY"));
                    r.put("CURRENCY", r.get("REPORTING_CCY"));
                }
                said.add(r);
            }
            docs = said;
            List<String> p = new ArrayList<>(promoted);
            for (String c : List.of("VALUE_TYPE", "GRAIN", "POSITION_ID", "SOURCE_SYSTEM", "TRADE_IDS", "CURRENCY", "TRADE_CURRENCY")) {
                if (!p.contains(c)) {
                    p.add(c);
                }
            }
            promoted = p;
        }
        Map<String, List<String>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> d : docs) {
            Map<String, Object> doc = new LinkedHashMap<>(d);
            Map<String, Object> cols = new LinkedHashMap<>();
            for (String c : promoted) {
                Object x = d.get(c);
                cols.put(c, x instanceof java.util.Collection<?> ? JSON.writeValueAsString(x) : x);   // a list (TRADE_IDS) as JSON text
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
            Path f = work.resolve(kind + "-" + e.getKey() + "-" + System.nanoTime() + ".rows");
            Files.write(f, e.getValue(), StandardCharsets.UTF_8);
            parts.add(Map.of("file", f.toString(), "values", Map.of("business_date", e.getKey())));
        }
        List<Map<String, Object>> columns = new ArrayList<>(List.of(Map.of("name", "id", "type", "string"), Map.of("name", "doc", "type", "string"),
                Map.of("name", "business_date", "type", "date")));
        for (String c : promoted) {
            boolean num = docs.stream().anyMatch(d -> d.get(c) instanceof Number) && docs.stream().allMatch(d -> d.get(c) == null
                    || d.get(c) instanceof Number);                                    // a column with no value yet (POSITION_ID) is text
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
        m.put("mode", mode);
        m.put("batchRows", 1000);
        m.put("statsColumns", List.of("id", "business_date"));
        m.put("parts", parts);
        Path mf = work.resolve(kind + "-" + System.nanoTime() + ".manifest.json");
        Files.writeString(mf, JSON.writeValueAsString(m));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        if (code != 0) {
            throw new IllegalStateException(kind + ": " + err.toString(StandardCharsets.UTF_8));
        }
        com.ash.drishti.server.IcebergMirror.table(kind, byDay, mode);    // the same lines into Iceberg too, inside writeIceberg (2.9.5)
    }

    /** {@link #write(Path)} into {@code deltaRoot} and the same tables into the Iceberg root {@code icebergRoot} (2.9.5). */
    static Path writeIceberg(Path deltaRoot, Path icebergRoot) throws Exception {
        return com.ash.drishti.server.IcebergMirror.into(icebergRoot, () -> write(deltaRoot));
    }

    /** {@link #write(Path, LocalDate, double, boolean, boolean)} into both formats. */
    static Path writeIceberg(Path deltaRoot, Path icebergRoot, LocalDate day, double factor, boolean mixedRuns, boolean duplicate) throws Exception {
        return com.ash.drishti.server.IcebergMirror.into(icebergRoot, () -> write(deltaRoot, day, factor, mixedRuns, duplicate));
    }
}
