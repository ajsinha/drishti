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
package com.ash.drishti.tables.lang;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lowering of the two functions that read another source (docs/architecture/ARCHITECTURE.md §28 Q15 and Q29), which Rachana parses and checks but
 * leaves to the layer: {@code fx(ccy, to)} reads the installation's one FX source at the row's business date, and
 * {@code related(domain.table.COLUMN, on = key)} reads one column of the row another table holds under that key (one many-to-one hop).
 * Each call becomes a value of the row, worked out per row before the expression runs ({@link #values}), and the expression reads it as a
 * column ({@code __l0}, {@code __l1} ...): so Rachana's evaluator and the one compiler see a plain column, and every row evaluator and
 * aggregate reads the same value. In a model, {@code related(alias.COLUMN)} is that column through the model's join and needs no lookup.
 */
public final class LateBound {

    /** The installation's FX source (Q15): the rate that turns one unit of {@code from} into {@code to} at a date, or null when it has none. */
    @FunctionalInterface
    public interface Fx {
        Double rate(String from, String to, LocalDate at);

        /** No source: every conversion is missing. */
        Fx NONE = (from, to, at) -> from != null && from.equals(to) ? 1.0 : null;
    }

    /** Another table's row by its key ({@code related}); null when it holds none. */
    @FunctionalInterface
    public interface Lookup {
        DataNode row(String table, Object key);

        Lookup NONE = (table, key) -> null;
    }

    /** One call bound to a column of the row: {@code fn} with its arguments, read as {@code column}. */
    public record Slot(String column, String fn, List<Expr> args, Map<String, Expr> named) {}

    /** An expression with its late-bound calls replaced by columns, and those calls. */
    public record Bound(Expr expr, List<Slot> slots) {
        public boolean none() {
            return slots.isEmpty();
        }
    }

    private LateBound() {}

    /**
     * Binds {@code e}: each {@code fx(...)} and each {@code related(table.COLUMN, on = key)} becomes a column of the row; in a model
     * ({@code model} true) {@code related(alias.COLUMN)} becomes the column itself.
     */
    public static Bound bind(Expr e, boolean model) {
        List<Slot> slots = new ArrayList<>();
        Expr out = Trees.map(e, n -> {
            if (n instanceof Expr.Call c && c.name().equals("related") && !c.args().isEmpty() && (model || c.arg("on") == null)) {
                return c.args().get(0);                                     // through the model's join: the column itself
            }
            if (n instanceof Expr.Call c && (c.name().equals("fx") || c.name().equals("related"))) {
                String column = "__l" + slots.size();
                slots.add(new Slot(column, c.name(), c.args(), c.named()));
                return new Expr.Field(new Expr.RowOrRoot(), column, null);
            }
            return n;
        });
        return new Bound(out, slots);
    }

    /** The values of the bound calls for one row, by column: {@code context} reads the row (as the expression would). */
    public static Map<String, DataNode> values(Bound b, EvalContext context, LocalDate at, Fx fx, Lookup lookup) {
        Map<String, DataNode> out = new LinkedHashMap<>();
        for (Slot s : b.slots()) {
            Object v;
            if (s.fn().equals("fx")) {
                Object from = value(s.args().isEmpty() ? null : s.args().get(0), context);
                Object to = value(s.args().size() > 1 ? s.args().get(1) : s.named().get("to"), context);
                Double r = from == null || to == null ? null : fx.rate(Values.text(from), Values.text(to), at);
                v = r;
            } else {
                String name = Trees.name(s.args().get(0));
                int dot = name == null ? -1 : name.lastIndexOf('.');
                Object key = value(s.named().get("on"), context);
                DataNode row = dot < 0 || key == null ? null : lookup.row(name.substring(0, dot), Values.simplify(key));
                v = row == null ? null : row.get(name.substring(dot + 1));
            }
            out.put(s.column(), v instanceof DataNode d ? d : DataNode.of(v));
        }
        return out;
    }

    private static Object value(Expr e, EvalContext c) {
        if (e == null) {
            return null;
        }
        Object v = e.eval(c);
        return Values.masked(v) || Values.isNull(v) ? null : Values.simplify(v);
    }
}
