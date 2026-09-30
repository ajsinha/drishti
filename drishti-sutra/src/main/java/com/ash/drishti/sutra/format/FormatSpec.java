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
package com.ash.drishti.sutra.format;

import com.ash.drishti.sutra.el.Link;
import com.ash.drishti.sutra.el.Values;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * One named format. Immutable and thread-safe (no shared {@code DecimalFormat}).
 *
 * @param type {@code number}, {@code percent}, {@code compact}, {@code date} or {@code text}
 * @param decimals fraction digits
 * @param grouping insert thousands separators
 * @param sign {@code auto} (minus only) or {@code always} (plus for positives)
 * @param scale multiplier applied first (100 for percent of a fraction)
 * @param suffix appended text (for example {@code %})
 * @param pattern date pattern for {@code date}
 */
public record FormatSpec(String type, int decimals, boolean grouping, String sign, double scale, String suffix, String pattern) {

    static final char MINUS = '−';

    public String format(Object value) {
        Object v = Values.simplify(value);
        if (v == null) {
            return "";
        }
        if (v instanceof Link l) {
            return l.display();
        }
        return switch (type) {
            case "date" -> date(Values.text(v));
            case "text" -> Values.text(v);
            case "compact" -> compact(Values.number(v));
            default -> {
                double d = Values.number(v);
                yield Double.isNaN(d) ? Values.text(v) : number(d * scale) + (suffix == null ? "" : suffix);
            }
        };
    }

    private String number(double d) {
        BigDecimal b = BigDecimal.valueOf(Math.abs(d)).setScale(decimals, RoundingMode.HALF_UP);
        String digits = b.toPlainString();
        if (grouping) {
            digits = group(digits);
        }
        boolean zero = b.signum() == 0;
        if (d < 0 && !zero) {
            return MINUS + digits;
        }
        return "always".equals(sign) && !zero ? "+" + digits : digits;
    }

    static String group(String plain) {
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        StringBuilder sb = new StringBuilder(plain.length() + whole.length() / 3);
        int lead = whole.length() % 3;
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) {
                sb.append(',');
            }
            sb.append(whole.charAt(i));
        }
        if (dot >= 0) {
            sb.append(plain, dot, plain.length());
        }
        return sb.toString();
    }

    private String compact(double d) {
        if (Double.isNaN(d)) {
            return "";
        }
        double a = Math.abs(d);
        String unit;
        double v;
        if (a >= 1e9) {
            unit = "bn";
            v = d / 1e9;
        } else if (a >= 1e6) {
            unit = "m";
            v = d / 1e6;
        } else if (a >= 1e3) {
            unit = "k";
            v = d / 1e3;
        } else {
            unit = "";
            v = d;
        }
        return number(v) + unit;
    }

    private String date(String iso) {
        if (pattern == null || pattern.equals("yyyy-MM-dd") || iso.length() < 10) {
            return iso;
        }
        try {
            return LocalDate.parse(iso.substring(0, 10)).format(DateTimeFormatter.ofPattern(pattern));
        } catch (RuntimeException e) {
            return iso;
        }
    }
}
