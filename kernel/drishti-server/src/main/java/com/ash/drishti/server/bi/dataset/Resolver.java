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
package com.ash.drishti.server.bi.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves dotted names in a dataset's expressions to a table, a column and an optional date part. Used by the validator and the SQL compiler,
 * so what validates is what compiles. Every table has the implicit columns {@code id} (the entity id) and {@code businessDate}.
 */
public final class Resolver {

    /** Date periods a date or timestamp column may be bucketed into: {@code trade.TRADE_DATE.month_start} is the first day of the month (a date). */
    public static final Set<String> BUCKETS = Set.of("week_start", "month_start", "quarter_start", "year_start");

    /** Date parts a date or timestamp column may be cut into: {@code trades.tradeDate.year} (a number), or a period's first day (a date). */
    public static final Set<String> PARTS = Set.of("year", "quarter", "month", "day", "week", "week_start", "month_start", "quarter_start", "year_start");

    /** Whether a part is a period bucket (a date) rather than a number. */
    public static boolean isBucket(String part) {
        return part != null && BUCKETS.contains(part);
    }

    /** The type a date part has: {@code date} for a period bucket, else {@code integer}. */
    public static String partType(String part) {
        return isBucket(part) ? "date" : "integer";
    }

    /** A resolved column. {@code column} is null for a bare table name (as in {@code count(trades)}). */
    public record ColRef(String table, String column, String part) {
        public String text() {
            return table + (column == null ? "" : "." + column + (part == null ? "" : "." + part));
        }
    }

    private final Dataset ds;
    /** Resolving a calculated column of a shaped table: its names are the columns before the shape. */
    private final boolean source;

    public Resolver(Dataset ds) {
        this(ds, false);
    }

    /** {@code source}: names are a shaped table's columns before the shape (what its derived columns are worked out from). */
    public Resolver(Dataset ds, boolean source) {
        this.ds = ds;
        this.source = source;
    }

    /** The declared or implicit column, or null. */
    public Dataset.Column column(String table, String name) {
        Dataset.Table t = ds.tables().get(table);
        if (t == null) {
            return null;
        }
        Dataset.Column c = source && t.shape() != null && !t.shape().source().isEmpty() ? t.shape().source().get(name) : t.columns().get(name);
        if (c != null) {
            return c;
        }
        if (Dataset.FRAME.equals(t.mode())) {
            return null;                                   // the one table of a composed or derived dataset is a frame: it has the columns it was built with, no entity id or business date
        }
        if (Dataset.ID.equals(name)) {
            return new Dataset.Column(Dataset.ID, Dataset.ID, "string", null, true, null, "the entity id");
        }
        if (Dataset.INDEX.equals(name) && t.each() != null) {
            return new Dataset.Column(Dataset.INDEX, Dataset.INDEX, "integer", null, false, null, "the element's position in the array");
        }
        if (Dataset.DATE.equals(name)) {
            return new Dataset.Column(Dataset.DATE, Dataset.DATE, "date", null, false, null, "the business date of the row");
        }
        if (t.isLive() && Dataset.SEQ.equals(name)) {
            return new Dataset.Column(Dataset.SEQ, Dataset.SEQ, "integer", null, false, null, "the order in which rows last changed (a counter that only grows)");
        }
        if (t.isLive() && Dataset.UPDATED.equals(name)) {
            return new Dataset.Column(Dataset.UPDATED, Dataset.UPDATED, "timestamp", null, false, null, "when the row last changed");
        }
        return null;
    }

    /**
     * @param parts the dotted name
     * @param current the table whose row is in context (a calculated column), or null
     * @throws IllegalArgumentException with a sentence saying what is wrong
     */
    public ColRef resolve(List<String> parts, String current) {
        return follow(resolveOwn(parts, current));
    }

    /** A column read from another table along a reference ({@code related}) resolves to that table's column. */
    private ColRef follow(ColRef c) {
        if (c == null || c.column() == null || source) {
            return c;
        }
        Dataset.Column col = column(c.table(), c.column());
        if (col == null || col.ref() == null) {
            return c;
        }
        int dot = col.ref().indexOf('.');
        return new ColRef(col.ref().substring(0, dot), col.ref().substring(dot + 1), c.part());
    }

    private ColRef resolveOwn(List<String> parts, String current) {
        String text = String.join(".", parts);
        if (parts.size() == 1) {
            String one = parts.get(0);
            if (current != null && column(current, one) != null) {
                return new ColRef(current, one, null);
            }
            if (ds.tables().containsKey(one)) {
                return new ColRef(one, null, null);
            }
            return unique(one, current, text);
        }
        if (parts.size() == 2) {
            if (ds.tables().containsKey(parts.get(0))) {
                if (column(parts.get(0), parts.get(1)) == null) {
                    throw new IllegalArgumentException("table '" + parts.get(0) + "' has no column '" + parts.get(1) + "'" + hint(parts.get(0)));
                }
                return new ColRef(parts.get(0), parts.get(1), null);
            }
            ColRef bare = tryPart(parts.get(0), parts.get(1), current);
            if (bare != null) {
                return bare;
            }
            throw new IllegalArgumentException("'" + text + "' is not a table.column of this dataset" + tables());
        }
        if (parts.size() == 3 && ds.tables().containsKey(parts.get(0))) {
            if (column(parts.get(0), parts.get(1)) == null) {
                throw new IllegalArgumentException("table '" + parts.get(0) + "' has no column '" + parts.get(1) + "'" + hint(parts.get(0)));
            }
            checkPart(parts.get(0), parts.get(1), parts.get(2));
            return new ColRef(parts.get(0), parts.get(1), parts.get(2));
        }
        throw new IllegalArgumentException("'" + text + "' is not a table.column or table.column.part (year, quarter, month, day, week, or a period: week_start, month_start, quarter_start, year_start)");
    }

    private ColRef tryPart(String column, String part, String current) {
        if (!PARTS.contains(part)) {
            return null;
        }
        if (current != null && column(current, column) != null) {
            checkPart(current, column, part);
            return new ColRef(current, column, part);
        }
        try {
            ColRef c = unique(column, current, column);
            checkPart(c.table(), c.column(), part);
            return new ColRef(c.table(), c.column(), part);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void checkPart(String table, String column, String part) {
        if (!PARTS.contains(part)) {
            throw new IllegalArgumentException("'" + part + "' is not a date part; use year, quarter, month, day, week, week_start, month_start, quarter_start or year_start");
        }
        String type = column(table, column).type();
        if (!"date".equals(type) && !"timestamp".equals(type)) {
            throw new IllegalArgumentException(table + "." + column + " is " + type + ", not a date: it has no " + part);
        }
    }

    private ColRef unique(String column, String current, String text) {
        List<String> found = new ArrayList<>();
        for (String t : ds.tables().keySet()) {
            if (column(t, column) != null) {
                found.add(t);
            }
        }
        if (found.size() == 1) {
            return new ColRef(found.get(0), column, null);
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("'" + text + "' is not a column or table of this dataset" + tables());
        }
        throw new IllegalArgumentException("'" + column + "' is in several tables (" + String.join(", ", found) + "): write table." + column);
    }

    private String hint(String table) {
        return " (it has " + String.join(", ", ds.tables().get(table).columns().keySet()) + ")";
    }

    private String tables() {
        return " (tables: " + String.join(", ", ds.tables().keySet()) + ")";
    }
}
