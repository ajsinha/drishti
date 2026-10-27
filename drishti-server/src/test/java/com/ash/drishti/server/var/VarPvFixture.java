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
 * Scenario PVs beside {@link VarLakeFixture}'s P&amp;L vectors (VAR_GUIDE.md section 15.9), whose answers are known by hand, all in
 * BOOK-3 of DESK-Y (LE-B), the same run and scenario set:
 *
 * <ul>
 *   <li>{@code P1}: a EUR trade worth 1,000 EUR, {@code 1000 + 10 U(s)} under the scenarios. The supplied EUR rate is 1.20 USD today and
 *       under every scenario but scenario 7 (1.00, the euro falls on the instrument's worst day) and 12 (1.30). Its USD P&amp;L is
 *       {@code (1000 + 10 U(s)) × FX(s) − 1200}: -300 on scenario 7, its USD VaR 300; in EUR its P&amp;L is {@code 10 U(s)}, VaR 100.</li>
 *   <li>{@code P2}: a CAD trade worth 2,000 CAD, {@code 2000 + 5 V(s)}. No CAD rates are supplied: they are derived from the FX history
 *       of {@code FX-USDCAD} (1.25 CAD per USD every day but scenario 5's day, 1.00): USD per CAD 0.80 today, 1.00 under scenario 5, 0.64
 *       under scenario 6 (the day after, back to 1.25). Its USD P&amp;L on scenario 6 is {@code 1990 × 0.64 − 1600 = -326.4}, its VaR.</li>
 *   <li>{@code P3}: a EUR trade whose scenario 4 failed to price (a null): rejected, {@code DRS-5206}.</li>
 *   <li>{@code P4}: a CHF trade, and the date has no CHF FX at all: rejected, {@code DRS-5207}.</li>
 *   <li>{@code P5}: a EUR trade whose scenario PVs are valued on the day before: rejected, {@code DRS-5201}.</li>
 * </ul>
 *
 * GBP is supplied at 1.25 under every scenario (a reporting currency that only rescales).
 */
final class VarPvFixture {

    static final double[] U = VarLakeFixture.U;
    static final double[] V = VarLakeFixture.V;
    static final int N = VarLakeFixture.N;

    static double[] eurRates() {
        double[] r = new double[N];
        java.util.Arrays.fill(r, 1.20);
        r[7] = 1.00;
        r[12] = 1.30;
        return r;
    }

    /** Writes the date's scenario PVs, supplied FX and FX history under {@code root}/risk/ (beside VarLakeFixture's tables). */
    static void write(Path root, LocalDate day) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(day, "P1", "EUR", 1000, plus(1000, U, 10), day));
        rows.add(row(day, "P2", "CAD", 2000, plus(2000, V, 5), day));
        List<Object> withNull = new ArrayList<>();
        for (double x : plus(1000, U, 1)) {
            withNull.add(x);
        }
        withNull.set(4, null);
        rows.add(row(day, "P3", "EUR", 1000, withNull, day));
        rows.add(row(day, "P4", "CHF", 1000, plus(1000, U, 1), day));
        rows.add(row(day, "P5", "EUR", 1000, plus(1000, U, 1), day.minusDays(1)));
        VarLakeFixture.table(root, work, "scenario-pv", "SCENARIO_PV_ID", rows, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "BASE_PV", "VALUATION_DATE",
                "BASE_VALUATION_DATE", "TRADE_STATUS", "MATURITY_DATE", "STATUS", "SCENARIO_SET_ID", "BATCH_ID"), "SCENARIO_PV");
        List<Map<String, Object>> fx = new ArrayList<>();
        fx.add(rate(day, "EUR", 1.20, eurRates()));
        double[] gbp = new double[N];
        java.util.Arrays.fill(gbp, 1.25);
        fx.add(rate(day, "GBP", 1.25, gbp));
        VarLakeFixture.table(root, work, "fx-scenario", "FX_SCENARIO_ID", fx, List.of("CURRENCY", "RATE", "SCENARIO_SET_ID"), null);
        // the FX history: USD/CAD 1.25 every day but scenario 5's (1.00); from the business day before the first scenario day
        List<String> days = VarLakeFixture.scenarioDates();
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(Map.of("DATE", "2026-08-28", "MID", 1.25));
        for (int j = 0; j < days.size(); j++) {
            history.add(Map.of("DATE", days.get(j), "MID", j == 5 ? 1.00 : 1.25));
        }
        Map<String, Object> spot = new LinkedHashMap<>();
        spot.put("PAIR", "FX-USDCAD");
        spot.put("BUSINESS_DATE", day.toString());
        spot.put("MID", 1.25);
        spot.put("CONVENTIONS", Map.of("BASE", "USD", "QUOTE", "CAD"));
        spot.put("HISTORY", history);
        Map<String, Object> cross = new LinkedHashMap<>();
        cross.put("PAIR", "FX-EURGBP");                                    // a cross pair: never read (every cross goes through USD)
        cross.put("BUSINESS_DATE", day.toString());
        cross.put("MID", 0.5);
        cross.put("CONVENTIONS", Map.of("BASE", "EUR", "QUOTE", "GBP"));
        cross.put("HISTORY", List.of());
        VarLakeFixture.table(root, work, "fx-spot", "PAIR", List.of(spot, cross), List.of("MID"), null);
    }

    static double[] plus(double base, double[] unit, double f) {
        double[] out = new double[unit.length];
        for (int i = 0; i < unit.length; i++) {
            out[i] = base + f * unit[i];
        }
        return out;
    }

    static Map<String, Object> row(LocalDate day, String id, String ccy, double basePv, Object pvs, LocalDate valued) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("SCENARIO_PV_ID", "SPV-" + id + "-HS-20");
        d.put("TRADE_ID", id);
        d.put("BUSINESS_DATE", day.toString());
        d.put("SCENARIO_SET_ID", "HS-20");
        d.put("BATCH_ID", "B-" + day + "-01");
        d.put("LEGAL_ENTITY", "LE-B");
        d.put("DIVISION", "DIV-M");
        d.put("DESK", "DESK-Y");
        d.put("TT_BOOK", "BOOK-3");
        d.put("PORTFOLIO", "BOOK-3");
        d.put("PRODUCT_GROUP", "Commodity");
        d.put("PRODUCT_TYPE", "COMSWAP");
        d.put("CURRENCY", ccy);
        d.put("COUNTERPARTY", "CP-2");
        d.put("DIRECTION", "LONG");
        d.put("QUANTITY", 1.0);
        d.put("BASE_PV", basePv);
        d.put("VALUATION_DATE", valued.toString());
        d.put("BASE_VALUATION_DATE", day.toString());
        d.put("TRADE_STATUS", "VALIDATED");
        d.put("MATURITY_DATE", "2030-01-15");
        d.put("STATUS", "ok");
        d.put("SCENARIO_PV", pvs);
        return d;
    }

    private static Map<String, Object> rate(LocalDate day, String ccy, double base, double[] rates) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("FX_SCENARIO_ID", "FXS-" + ccy + "-HS-20");
        d.put("BUSINESS_DATE", day.toString());
        d.put("SCENARIO_SET_ID", "HS-20");
        d.put("CURRENCY", ccy);
        d.put("QUOTE", "USD per 1 " + ccy);
        d.put("RATE", base);
        List<Double> r = new ArrayList<>();
        for (double x : rates) {
            r.add(x);
        }
        d.put("RATES", r);
        return d;
    }
}
