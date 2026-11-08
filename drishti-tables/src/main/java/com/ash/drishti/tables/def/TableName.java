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
package com.ash.drishti.tables.def;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Names of tables and models (docs/architecture/BI_DESIGN.md Q11). A table is {@code <domain>.<name>}: both parts lower case, letters, digits and
 * underscores, starting with a letter, so SQL clients and Flight read them unquoted ({@code trading.trade_legs}). A migrated BI dataset
 * goes to the domain {@code bi} with its hyphens as underscores ({@code trading-risk-trades} is {@code banking.trading_risk_trades}); a pack's
 * generated dataset {@code pack.trading.trade} is {@code trading.trade}. A model keeps BI's name ({@code trading-risk}: lower case,
 * hyphens), since reports, boards and bookmarks name it with its major ({@code trading-risk@1}).
 */
public final class TableName {

    private static final Pattern PART = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern MODEL = Pattern.compile("[a-z][a-z0-9_-]{0,79}(\\.[a-z][a-z0-9_-]{0,62})?");

    /** The domain every migrated BI dataset goes to. */
    public static final String BI_DOMAIN = "bi";

    private TableName() {}

    /** True when {@code name} is a table's name: {@code <domain>.<name>}. */
    public static boolean isTable(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.indexOf('.');
        return dot > 0 && dot == name.lastIndexOf('.') && PART.matcher(name.substring(0, dot)).matches() && PART.matcher(name.substring(dot + 1)).matches();
    }

    /** True when {@code name} is a model's name ({@code trading-risk}, or {@code trading.positions-and-legs} with a domain). */
    public static boolean isModel(String name) {
        return name != null && MODEL.matcher(name).matches();
    }

    /** The domain of a table's name ({@code trading} of {@code trading.trade_legs}), or null. */
    public static String domain(String name) {
        int dot = name == null ? -1 : name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }

    /** A BI dataset's table name: {@code trading-risk-trades} is {@code banking.trading_risk_trades}; {@code pack.trading.trade} is {@code trading.trade}. */
    public static String ofDataset(String dataset) {
        if (dataset.startsWith("pack.") && dataset.chars().filter(c -> c == '.').count() == 2) {
            return dataset.substring("pack.".length()).toLowerCase(Locale.ROOT).replace('-', '_');
        }
        String n = dataset.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        if (!n.isEmpty() && !Character.isLetter(n.charAt(0))) {
            n = "t_" + n;
        }
        return BI_DOMAIN + "." + n;
    }

    /**
     * A reference to a table or model, with an optional version range: {@code trading.trade}, {@code trading.trade@^1}, {@code trading-risk@1}.
     *
     * @param name the name
     * @param major the major version asked for, or null for any
     */
    public record Ref(String name, Integer major) {

        public static Ref parse(String text) {
            String t = text == null ? "" : text.strip();
            int at = t.indexOf('@');
            if (at < 0) {
                return new Ref(t, null);
            }
            String v = t.substring(at + 1).replace("^", "").strip();
            int dot = v.indexOf('.');
            String major = dot < 0 ? v : v.substring(0, dot);
            try {
                return new Ref(t.substring(0, at), Integer.parseInt(major));
            } catch (NumberFormatException e) {
                return new Ref(t.substring(0, at), null);
            }
        }

        @Override
        public String toString() {
            return major == null ? name : name + "@^" + major;
        }
    }
}
