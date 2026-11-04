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
package com.ash.drishti.compute.saccr;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * A delivered {@code saccr-trade} row (docs/contracts/SACCR.md section 3.3) as the engine's {@link SaccrInput.Trade}: the dates turned into
 * years ACT/365 from the business date, the notional basis ({@code NOTIONAL_USD}, else {@code QUANTITY} × {@code UNDERLYING_PRICE}).
 * A row that carries a computed figure (an add-on, an EAD, a supervisory delta…) is refused: the formulas are the engine's
 * ({@link #computedField}).
 */
public final class SaccrRows {

    /** Fields a delivered row must not carry: they are computed (SACCR.md section 3.3, "not delivered"). */
    public static final List<String> COMPUTED = List.of("SUPERVISORY_DURATION", "SUPERVISORY_DELTA", "MATURITY_FACTOR", "ADJUSTED_NOTIONAL",
            "EFFECTIVE_NOTIONAL", "MATURITY_BUCKET", "SUPERVISORY_FACTOR", "ADD_ON", "REPLACEMENT_COST", "MULTIPLIER", "PFE", "EAD", "RWA");

    private SaccrRows() {}

    /** The first computed field the row carries, or null. */
    public static String computedField(Map<String, ?> row) {
        for (String f : COMPUTED) {
            if (row.containsKey(f) && row.get(f) != null) {
                return f;
            }
        }
        return null;
    }

    static String text(Map<String, ?> row, String f) {
        Object v = row.get(f);
        return v == null ? null : String.valueOf(v);
    }

    static double num(Map<String, ?> row, String f) {
        Object v = row.get(f);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null || String.valueOf(v).isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static boolean bool(Map<String, ?> row, String f) {
        Object v = row.get(f);
        return v instanceof Boolean b ? b : v != null && String.valueOf(v).strip().equalsIgnoreCase("true");
    }

    static double years(LocalDate cob, String d) {
        if (d == null || d.isBlank()) {
            return Double.NaN;
        }
        try {
            return SaccrInput.years(cob, LocalDate.parse(d.strip().substring(0, Math.min(10, d.strip().length()))));
        } catch (DateTimeParseException e) {
            return Double.NaN;
        }
    }

    /** The engine's trade of one row on the business date {@code cob}, with its MTM ({@code NaN}: none). */
    public static SaccrInput.Trade trade(Map<String, Object> row, LocalDate cob, double mtm) {
        double notional = num(row, "NOTIONAL_USD");
        if (!(notional > 0)) {
            double q = num(row, "QUANTITY");
            double p = num(row, "UNDERLYING_PRICE");
            notional = Math.abs(q * p);
        }
        double driver = num(row, "DRIVER");
        return new SaccrInput.Trade(text(row, "TRADE_ID"), Double.isFinite(driver) ? (int) driver : 0, text(row, "ASSET_CLASS"), text(row, "HEDGING_SET"),
                text(row, "TRANSACTION_TYPE"), text(row, "REFERENCE"), bool(row, "IS_INDEX"), text(row, "CREDIT_QUALITY"), text(row, "DIRECTION"), notional,
                years(cob, text(row, "START_DATE")), years(cob, text(row, "END_DATE")), years(cob, text(row, "MATURITY_DATE")),
                years(cob, text(row, "EXERCISE_DATE")), text(row, "OPTION_TYPE"), text(row, "BOUGHT_SOLD"), num(row, "STRIKE"), num(row, "UNDERLYING_FORWARD"),
                num(row, "SHIFT"), num(row, "ATTACHMENT"), num(row, "DETACHMENT"), mtm, row);
    }
}
