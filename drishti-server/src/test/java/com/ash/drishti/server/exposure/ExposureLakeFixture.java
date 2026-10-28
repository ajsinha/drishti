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
package com.ash.drishti.server.exposure;

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
 * A tiny exposure lake written with Drishti's lake writer, whose answers are known by hand: EXPOSURE_GUIDE.md section 2.1's worked example in USD
 * millions. Netting set NS-X (counterparty CP-1, legal entity LE-A, a CSA with thresholds of 1m) holds trades A and B on 4 paths; its
 * look-back values are the contract's L. Primary dates 73, 146 and 365 days after the business date (t = 0.2, 0.4, 1.0); EE 1.0m, 1.5m,
 * 0.75m; PFE 75% 1m, 2m, 1m; EPE 0.95m, EEPE 1.4m. NS-Y (CP-1 too, no CSA) holds trade C. NS-Z (CP-2) is known to the netting-set
 * records but has no paths. Beside them: a row of another run (left out), a vector one value short (rejected, DRS-5444).
 */
final class ExposureLakeFixture {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final int P = 4;
    static final double M = 1_000_000;
    // per trade, per primary date (t1, t2, t3), the 4 paths (EXPOSURE_GUIDE.md section 2.1)
    static final double[][] A = {{3, -2, 3, 0}, {5, -1, 4, -2}, {2, -3, 6, -1}};
    static final double[][] B = {{-1, 1, -2, 1}, {-1, 2, 0, 0}, {1, 1, -1, -2}};
    static final double[][] C = {{1, 0, 0, 0}, {0, 2, 0, 0}, {0, 0, 1, 0}};
    /** The set NS-X's look-back values at L1, L2, L3, carried by trade A (trade B is 0 there). */
    static final double[][] L = {{1, -1, 0, 1}, {2, 0, 3, -1}, {3, -1, 4, -3}};
    private static final ObjectMapper JSON = new ObjectMapper();

    static List<String> grid() {
        return List.of(DAY.plusDays(60).toString(), DAY.plusDays(73).toString(), DAY.plusDays(130).toString(), DAY.plusDays(146).toString(),
                DAY.plusDays(350).toString(), DAY.plusDays(365).toString());
    }

    static Path write(Path root) throws Exception {
        return write(root, DAY, 1.0);
    }

    /** One business date; every value times {@code factor} (a restated run). */
    static Path write(Path root, LocalDate day, double factor) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        String run = "XR-" + day + "-01";
        String batch = "XB-" + day + "-01";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("RUN_ID", run);
        r.put("BUSINESS_DATE", day.toString());
        r.put("MODEL", "hand-made");
        r.put("MODEL_VERSION", "1");
        r.put("CALIBRATION", "REAL_WORLD");
        r.put("PATHS", P);
        r.put("SEED", "none");
        List<String> g = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            g.add(LocalDate.parse(grid().get(k)).plusDays(day.toEpochDay() - DAY.toEpochDay()).toString());
        }
        r.put("GRID_DATES", g);
        r.put("GRID_ROLE", List.of("LOOKBACK", "PRIMARY", "LOOKBACK", "PRIMARY", "LOOKBACK", "PRIMARY"));
        r.put("LOOKBACK_OF", List.of(1, -1, 3, -1, 5, -1));
        r.put("LOOKBACK_DAYS", 10);
        r.put("VALUE_BASIS", "FUTURE");
        r.put("CURRENCY", "USD");
        r.put("PRECISION", "float64");
        r.put("TRADES", 3);
        r.put("ROWS", 18);
        r.put("BATCH_ID", batch);
        r.put("COMPUTED_AT", day + "T23:40:00Z");
        r.put("STATUS", "complete");
        table(root, work, "simulation-run", "RUN_ID", List.of(r), List.of(), null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            int i = k / 2;
            boolean primary = k % 2 == 1;
            rows.add(path(day, run, batch, "A", "NS-X", "CP-1", "LE-A", "DESK-A", "BOOK-A", k, scale(primary ? A[i] : L[i], factor * M)));
            rows.add(path(day, run, batch, "B", "NS-X", "CP-1", "LE-A", "DESK-B", "BOOK-B", k, scale(primary ? B[i] : new double[P], factor * M)));
            rows.add(path(day, run, batch, "C", "NS-Y", "CP-1", "LE-A", "DESK-A", "BOOK-A", k, scale(primary ? C[i] : new double[P], factor * M)));
        }
        rows.add(path(day, "XR-" + day + "-00", batch, "A", "NS-X", "CP-1", "LE-A", "DESK-A", "BOOK-A", 1, scale(A[0], 1000 * M)));
        Map<String, Object> shortRow = path(day, run, batch, "C", "NS-Y", "CP-1", "LE-A", "DESK-A", "BOOK-A", 1, new double[P - 1]);
        shortRow.put("PATH_ROW_ID", run + "~C~1-again");
        rows.add(shortRow);
        table(root, work, "exposure-path", "PATH_ROW_ID", rows, List.of("TRADE_ID", "NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK",
                "ASSET_CLASS", "PRODUCT", "SOURCE_SYSTEM", "MATURITY_DATE", "NOTIONAL", "GRID_INDEX", "GRID_DATE", "RUN_ID", "BATCH_ID", "STATUS"), "MTM");
        List<Map<String, Object>> sets = List.of(
                set(day, "NS-X", "CP-1", "LE-A", "CSA-1", "COLL-X", List.of(member("A", 0), member("B", 0))),
                set(day, "NS-Y", "CP-1", "LE-A", null, null, List.of(member("C", 0))),
                set(day, "NS-Z", "CP-2", "LE-A", null, null, List.of(member("D", 5 * M))));
        table(root, work, "netting-set", "NETTING_SET_ID", sets, List.of(), null);
        table(root, work, "csa", "CSA_ID", List.of(Map.of("CSA_ID", "CSA-1", "BUSINESS_DATE", day.toString(), "MARGIN_TYPE", "VM", "THRESHOLD_THEM", M,
                "THRESHOLD_US", M, "MTA", 0, "INDEPENDENT_AMOUNT", 0, "FREQUENCY", "Daily", "MPOR", "10 business days", "COUNTERPARTY", "CP-1")),
                List.of(), null);
        table(root, work, "collateral-balance", "BALANCE_ID", List.of(Map.of("BALANCE_ID", "COLL-X", "BUSINESS_DATE", day.toString(), "NET", 0,
                "HELD", 0, "POSTED", 0, "NETTING_SET", "NS-X")), List.of(), null);
        table(root, work, "counterparty", "COUNTERPARTY_ID", List.of(Map.of("COUNTERPARTY_ID", "CP-1", "BUSINESS_DATE", day.toString(), "NAME",
                "Alpha Fund"), Map.of("COUNTERPARTY_ID", "CP-2", "BUSINESS_DATE", day.toString(), "NAME", "Beta Bank")), List.of(), null);
        table(root, work, "counterparty-group", "GROUP_ID", List.of(Map.of("GROUP_ID", "GRP-1", "BUSINESS_DATE", day.toString(), "MEMBERS",
                List.of(Map.of("ID", "CP-1")))), List.of(), null);
        return root;
    }

    private static Map<String, Object> member(String id, double mtm) {
        return Map.of("TRADE_ID", id, "MTM", mtm, "MATURITY", "2028-12-31");
    }

    private static Map<String, Object> set(LocalDate day, String id, String cp, String le, String csa, String coll, List<Map<String, Object>> trades) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("NETTING_SET_ID", id);
        m.put("BUSINESS_DATE", day.toString());
        m.put("COUNTERPARTY", cp);
        m.put("LEGAL_ENTITY", le);
        m.put("CSA", csa);
        m.put("COLLATERAL_BALANCE", coll);
        m.put("TRADES", trades);
        m.put("TRADE_COUNT", trades.size());
        return m;
    }

    private static Map<String, Object> path(LocalDate day, String run, String batch, String trade, String set, String cp, String le, String desk,
            String book, int k, double[] mtm) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("PATH_ROW_ID", run + "~" + trade + "~" + k);
        d.put("BUSINESS_DATE", day.toString());
        d.put("RUN_ID", run);
        d.put("TRADE_ID", trade);
        d.put("SOURCE_SYSTEM", "TEST");
        d.put("NETTING_SET", set);
        d.put("COUNTERPARTY", cp);
        d.put("LEGAL_ENTITY", le);
        d.put("DESK", desk);
        d.put("TT_BOOK", book);
        d.put("ASSET_CLASS", trade.equals("C") ? "FX" : "Rates");
        d.put("PRODUCT", "swap");
        d.put("NOTIONAL", 10 * M);
        d.put("MATURITY_DATE", "2028-12-31");
        d.put("GRID_INDEX", (double) k);
        d.put("GRID_DATE", grid().get(k).equals("") ? null : LocalDate.parse(grid().get(k)).plusDays(day.toEpochDay() - DAY.toEpochDay()).toString());
        d.put("STATUS", "ok");
        d.put("BATCH_ID", batch);
        d.put("MTM", mtm);
        return d;
    }

    static double[] scale(double[] a, double f) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] * f;
        }
        return out;
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
        com.ash.drishti.server.IcebergMirror.table(kind, byDay, "overwrite-dates");   // the same lines into Iceberg too (2.9.5)
    }

    /** {@link #write(Path)} into {@code deltaRoot} and the same tables into the Iceberg root {@code icebergRoot} (2.9.5). */
    static Path writeIceberg(Path deltaRoot, Path icebergRoot) throws Exception {
        return com.ash.drishti.server.IcebergMirror.into(icebergRoot, () -> write(deltaRoot));
    }
}
