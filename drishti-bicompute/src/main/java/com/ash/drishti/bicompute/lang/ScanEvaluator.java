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
package com.ash.drishti.bicompute.lang;

import com.ash.drishti.bicompute.spi.AggFunction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The SQL form of a compiled expression over a scan (docs/architecture/ARCHITECTURE.md §28 2.6): a row expression is its SQL per row; an aggregate is
 * the engine's {@link AggFunction} state computed by the database ({@link AggFunction#sqlState}, the input set to null where the
 * measure's condition is not true) and finished by the engine ({@link AggFunction#fromSql}), as the tabular provider computes a cell; a
 * {@link Lowered.Shape} is its SQL over its parts' values. The same results as {@link RowsEvaluator}'s, which the differential test
 * proves. It holds no connection: each call is one unit of work on the connection it is given.
 */
public final class ScanEvaluator {

    private final String from;

    /** {@code from}: the FROM text of the scan ({@code "t"}, a sub-query in parentheses with its alias). */
    public ScanEvaluator(String from) {
        this.from = from;
    }

    /** A row expression's value for each row of the scan, in {@code order}'s order. */
    public List<Object> rows(Connection db, Lowered.Row r, String order) throws SQLException {
        List<Object> out = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT " + r.sql() + " FROM " + from + " ORDER BY " + order)) {
            while (rs.next()) {
                out.add(value(rs.getObject(1)));
            }
        }
        return out;
    }

    /** A measure over the scan; {@code measures}: the values of the model's measures it names. */
    public Object measure(Connection db, Lowered l, Map<String, Object> measures) throws SQLException {
        return switch (l) {
            case Lowered.Aggregate a -> aggregate(db, a);
            case Lowered.Shape s -> {
                String sql = s.sql();
                for (int i = 0; i < s.parts().size(); i++) {
                    sql = sql.replace(Lowered.Shape.placeholder(i), "(" + SqlLowering.literal(measure(db, s.parts().get(i), measures)) + ")");
                }
                yield single(db, "SELECT " + sql);
            }
            case Lowered.Measure m -> {
                if (m.scope() != null || !measures.containsKey(m.name())) {
                    throw new IllegalArgumentException("measure('" + m.name() + "') is not known here");
                }
                yield measures.get(m.name());
            }
            default -> throw new IllegalArgumentException("a context, a window or a period is computed by the model's query, not by a scan alone");
        };
    }

    private Object aggregate(Connection db, Lowered.Aggregate a) throws SQLException {
        AggFunction<Object> f = RowsEvaluator.function(a);
        List<String> inputs = new ArrayList<>();
        String where = a.where() == null ? null : "coalesce(" + a.where().sql() + ", FALSE)";
        if (a.inputs().isEmpty() && where != null) {
            f = RowsEvaluator.function(new Lowered.Aggregate("count", List.of(new Lowered.Row(null, "1", false)), null, List.of()));
            inputs.add("CASE WHEN " + where + " THEN 1 END");                   // the rows where the condition holds
        }
        for (Lowered.Row r : a.inputs()) {
            String in = f.numeric() ? "CAST(" + r.sql() + " AS DOUBLE)" : "CAST(" + r.sql() + " AS VARCHAR)";
            inputs.add(where == null ? in : "CASE WHEN " + where + " THEN " + in + " END");
        }
        List<String> state = f.sqlState(inputs);
        if (state.isEmpty()) {
            throw new IllegalArgumentException(a.fn() + "() has no SQL form");
        }
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT " + String.join(", ", state) + " FROM " + from)) {
            rs.next();
            Object[] parts = new Object[state.size()];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = rs.getObject(i + 1);
            }
            return f.result(f.fromSql(parts));
        }
    }

    private static Object single(Connection db, String sql) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return value(rs.getObject(1));
        }
    }

    /** A database value as Java's: a date as its ISO text, a timestamp as ISO text with 'T'. */
    static Object value(Object v) {
        if (v instanceof java.time.LocalDate d) {
            return d.toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.time.LocalDateTime t) {
            return t.toString();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toString();
        }
        return v;
    }
}
