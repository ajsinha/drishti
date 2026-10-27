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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The intraday tests' lake (VAR_GUIDE.md section 8.4): {@link VarLakeFixture}'s date with its trades' tables written again with the run
 * columns ({@code RUN_KIND}, {@code COMPUTED_AT}), and intraday batches appended to them: P&amp;L vectors and scenario PVs, amended,
 * new and cancelled trades, each batch its own {@code BATCH_ID}.
 */
final class VarIntradayLake {

    static final List<String> VECTOR_COLUMNS = List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP",
            "PRODUCT_TYPE", "SECURITY_ID", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE", "STATUS",
            "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID", "RUN_KIND", "COMPUTED_AT");
    static final List<String> PV_COLUMNS = List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP",
            "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "BASE_PV", "VALUATION_DATE", "BASE_VALUATION_DATE", "TRADE_STATUS",
            "MATURITY_DATE", "STATUS", "SCENARIO_SET_ID", "BATCH_ID", "RUN_KIND", "COMPUTED_AT");
    static final String MORNING_AT = "T06:00:00Z";

    private VarIntradayLake() {}

    /** A P&amp;L-vector row. */
    static Map<String, Object> vector(LocalDate day, String id, String book, String desk, String le, String ccy, double qty, double[] pnl,
            String status, String runKind, String batch, String at) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("VALUE_TYPE", "PNL");                                   // what the record is (VAR_GUIDE.md section 15.13)
        d.put("GRAIN", "TRADE");
        d.put("TRADE_ID", id);
        d.put("CURRENCY", ccy);
        d.put("SOURCE_SYSTEM", "MUREX");
        d.put("VECTOR_ID", "PV-" + id + "-HS-20" + ("INTRADAY".equals(runKind) ? "-" + batch : ""));
        d.put("BUSINESS_DATE", day.toString());
        d.put("SCENARIO_SET_ID", "HS-20");
        d.put("BATCH_ID", batch);
        d.put("LEGAL_ENTITY", le);
        d.put("DIVISION", "DIV-M");
        d.put("DESK", desk);
        d.put("TT_BOOK", book);
        d.put("PORTFOLIO", book);
        d.put("PRODUCT_GROUP", "Rates");
        d.put("PRODUCT_TYPE", "IRS");
        d.put("SECURITY_ID", null);
        d.put("CURRENCY", ccy);
        d.put("COUNTERPARTY", "CP-1");
        d.put("DIRECTION", qty >= 0 ? "LONG" : "SHORT");
        d.put("QUANTITY", qty);
        d.put("PV", 1000.0);
        d.put("TRADE_STATUS", status);
        d.put("MATURITY_DATE", "2030-01-15");
        d.put("STATUS", "ok");
        d.put("REPORTING_CCY", "USD");
        d.put("RUN_KIND", runKind);
        d.put("COMPUTED_AT", at);
        d.put("PNL", pnl);
        return d;
    }

    /** The morning run's P&amp;L vectors: {@link VarLakeFixture#trades()} as the fixture writes them, with the run columns. */
    static List<Map<String, Object>> morningVectors(LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VarLakeFixture.Trade t : VarLakeFixture.trades()) {
            Map<String, Object> d = vector(day, t.id(), t.book(), t.desk(), t.le(), t.ccy(), t.qty(), null, t.status(), "FULL", "B-" + day + "-01",
                    day + MORNING_AT);
            d.put("VECTOR_ID", "PV-" + t.id());
            d.put("PRODUCT_GROUP", t.security() == null ? "Rates" : "Fixed income");
            d.put("PRODUCT_TYPE", t.security() == null ? "IRS" : "GOVT_BOND");
            d.put("SECURITY_ID", t.security());
            d.put("PNL", t.v());
            out.add(d);
        }
        return out;
    }

    /** The morning run's scenario PVs: {@link VarPvFixture}'s P1 to P5. */
    static List<Map<String, Object>> morningPvs(LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(pv(day, "P1", "EUR", 1000, VarPvFixture.plus(1000, VarLakeFixture.U, 10), "FULL", "B-" + day + "-01", day + MORNING_AT, "HS-20"));
        out.add(pv(day, "P2", "CAD", 2000, VarPvFixture.plus(2000, VarLakeFixture.V, 5), "FULL", "B-" + day + "-01", day + MORNING_AT, "HS-20"));
        return out;
    }

    /** A scenario-PV row (null PVs and a status other than live: a cancel). */
    static Map<String, Object> pv(LocalDate day, String id, String ccy, double base, double[] pvs, String runKind, String batch, String at,
            String set) {
        Map<String, Object> d = VarPvFixture.row(day, id, ccy, base, pvs, day);
        d.put("SCENARIO_PV_ID", "SPV-" + id + "-HS-20" + ("INTRADAY".equals(runKind) ? "-" + batch : ""));
        d.put("BATCH_ID", batch);
        d.put("SCENARIO_SET_ID", set);
        d.put("RUN_KIND", runKind);
        d.put("COMPUTED_AT", at);
        return d;
    }

    static void writeVectors(Path root, List<Map<String, Object>> rows, String mode) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        VarLakeFixture.table(root, work, "pnl-vector", "VECTOR_ID", rows, VECTOR_COLUMNS, "PNL", mode);
    }

    static void writePvs(Path root, List<Map<String, Object>> rows, String mode) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        VarLakeFixture.table(root, work, "scenario-pv", "SCENARIO_PV_ID", rows, PV_COLUMNS, "SCENARIO_PV", mode);
    }

    /**
     * Dates with their morning run (both forms, the run columns), their hierarchy, scenario set, portfolios and FX. The fixture's trades'
     * tables are written again from scratch, so their schema has the run columns.
     */
    static void morning(Path root, LocalDate... days) throws Exception {
        for (LocalDate day : days) {
            VarLakeFixture.write(root, day, 1.0, false, false);
            VarPvFixture.write(root, day);
        }
        for (String t : List.of("pnl-vector", "scenario-pv")) {
            Path dir = root.resolve("risk").resolve(t);
            try (var s = Files.walk(dir)) {
                for (Path f : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(f);
                }
            }
        }
        for (LocalDate day : days) {
            writeVectors(root, morningVectors(day), "overwrite-dates");
            writePvs(root, morningPvs(day), "overwrite-dates");
        }
    }
}
