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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The row rule {@code related_rows('alias')}: a table limited to the rows related to the rows of another table of the model that the reader sees
 * (the owner's decision of 2026-11-07, BI_DESIGN.md 8.5: nobody sees more data after the upgrade; a desk head's rule on the trades also limits the
 * related rows of another fact). Related means sharing a key: a dimension both reference many-to-one (the same key column of it), a reference
 * from one table to the other, or, for a live table's history, the history's group columns. The rule is written as {@code EXISTS} over the other
 * table at the query's dates with the other table's own rules for the reader; a rule that cannot be honoured (no such table, no shared key, a
 * question written without the compiler) refuses the question rather than answering without it.
 */
final class RelatedRows {

    /** The function's name in Rachana's catalogue. */
    static final String FUNCTION = "related_rows";

    private RelatedRows() {}

    /** The rule's tree with each {@code related_rows('alias')} told the table the rule belongs to: {@code related_rows(table, alias)}. */
    static Node on(Node n, String table) {
        return switch (n) {
            case Node.Call c when FUNCTION.equals(c.function()) && c.args().size() == 1 -> new Node.Call(FUNCTION, List.of(new Node.Str(table), c.args().get(0)));
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> on(a, table)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), on(b.left(), table), on(b.right(), table));
            case Node.Un u -> new Node.Un(u.op(), on(u.operand(), table));
            case Node.Cond c -> new Node.Cond(on(c.test(), table), on(c.yes(), table), on(c.no(), table));
            default -> n;
        };
    }

    /** True when a rule's tree asks for related rows. */
    static boolean in(Node n) {
        return Node.anyMatch(n, x -> x instanceof Node.Call c && FUNCTION.equals(c.function()));
    }

    static DrishtiException refused(Node.Call c, String where) {
        String[] t = names(c);
        return new DrishtiException(ErrorCode.QUERY_INVALID, "your row rule on " + t[0] + " limits it to the rows related to the " + t[1]
                + " you see (related_rows), which " + where + " cannot work out: the question is refused rather than answered without the rule");
    }

    private static String[] names(Node.Call c) {
        List<String> s = c.args().stream().map(a -> a instanceof Node.Str x ? x.value() : String.valueOf(a)).toList();
        return s.size() >= 2 ? new String[] {s.get(0), s.get(1)} : new String[] {"a table", s.isEmpty() ? "another table" : s.get(0)};
    }

    /**
     * The writer the compiler gives the emitter of one query's row rules: {@code EXISTS (SELECT 1 FROM <other> WHERE <shared keys equal> AND <other's
     * rules>)}, the other table read through {@code more} at the query's dates.
     */
    static java.util.function.BiFunction<SqlEmitter, Node.Call, String> writer(Dataset ds, SqlCompiler.Reader reader, SqlCompiler.MoreSources more,
            LocalDate from, LocalDate to) {
        return new java.util.function.BiFunction<>() {
            private final Set<String> open = new LinkedHashSet<>();

            @Override
            public String apply(SqlEmitter e, Node.Call c) {
                String[] t = names(c);
                String self = t[0];
                String other = t[1];
                if (more == null || ds.tables().get(other) == null || ds.tables().get(self) == null) {
                    throw refused(c, ds.tables().get(other) == null ? "this model, which has no table '" + other + "'," : "this question");
                }
                if (!open.add(self + ">" + other)) {
                    throw refused(c, "a circle of related_rows rules");
                }
                try {
                    List<String[]> keys = keys(ds, self, other);
                    if (keys.isEmpty()) {
                        throw refused(c, "this model, where " + self + " and " + other + " share no key,");
                    }
                    TableRules.Bound b = TableRules.bind(ds, Set.of(other), reader);
                    if (b.denied()) {
                        return "FALSE";
                    }
                    SqlCompiler.Source src = more.of(other, from, to);
                    StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM ").append(src.sql());
                    e.params().addAll(src.params());
                    List<String> conds = new ArrayList<>();
                    for (String[] k : keys) {
                        conds.add(Dialect.quote(other) + "." + Dialect.quote(k[1]) + " = " + Dialect.quote(self) + "." + Dialect.quote(k[0]));
                    }
                    for (Node rule : b.rules()) {
                        conds.add(e.emit(rule, false));
                    }
                    return sql.append(" WHERE ").append(String.join(" AND ", conds)).append(')').toString();
                } finally {
                    open.remove(self + ">" + other);
                }
            }
        };
    }

    /** The key columns {@code self} and {@code other} share, as pairs (self's column, other's column); empty when none. */
    static List<String[]> keys(Dataset ds, String self, String other) {
        List<String[]> out = new ArrayList<>();
        Dataset.Table s = ds.tables().get(self);
        Dataset.Table o = ds.tables().get(other);
        if (s.isHistory() && other.equals(s.of()) && o.live() != null && o.live().history() != null) {
            o.live().history().by().forEach(by -> out.add(new String[] {by, by}));
            return out;
        }
        for (Dataset.Relationship r : ds.relationships()) {
            if (!r.active() || "one-to-many".equals(r.cardinality())) {
                continue;
            }
            if (r.fromTable().equals(self) && r.toTable().equals(other)) {
                out.add(new String[] {r.fromColumn(), r.toColumn()});
            } else if (r.fromTable().equals(other) && r.toTable().equals(self)) {
                out.add(new String[] {r.toColumn(), r.fromColumn()});
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (Dataset.Relationship a : ds.relationships()) {                    // a dimension both reference, at the same key
            if (!a.active() || "one-to-many".equals(a.cardinality()) || !a.fromTable().equals(self)) {
                continue;
            }
            for (Dataset.Relationship b : ds.relationships()) {
                if (b.active() && !"one-to-many".equals(b.cardinality()) && b.fromTable().equals(other) && b.toTable().equals(a.toTable())
                        && b.toColumn().equals(a.toColumn())) {
                    out.add(new String[] {a.fromColumn(), b.fromColumn()});
                }
            }
        }
        return out;
    }
}
