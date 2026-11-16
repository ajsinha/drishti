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
package com.ash.drishti.server.dist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** How a cell of an export reads on a page (PDF, PNG, email): grouped numbers, trimmed decimals, plain dates. Excel and CSV keep the typed value instead. */
final class DocFmt {

    private DocFmt() {}

    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Long || v instanceof Integer) {
            return grouped(new BigDecimal(((Number) v).longValue()), 0);
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return "";
            }
            BigDecimal b = BigDecimal.valueOf(d);
            int scale = Math.abs(d) >= 1000 ? 2 : Math.abs(d) >= 1 ? 3 : 6;
            return grouped(b.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros(), scale);
        }
        return v.toString();
    }

    private static String grouped(BigDecimal b, int maxScale) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setMaximumFractionDigits(Math.max(0, Math.min(maxScale, Math.max(0, b.scale()))));
        return f.format(b);
    }
}
