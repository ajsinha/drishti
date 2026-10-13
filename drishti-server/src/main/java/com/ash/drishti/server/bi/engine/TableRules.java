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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Security bound per table, the data model's form (docs/architecture/BI_MODEL_SPEC.md section 2.6). A table's rules apply only where the table is read, before
 * anything is aggregated: its {@code filters} always, then each layer as the OR of the reader's matching rules, the layers ANDed. A dimension's rules also reach
 * every fact that references it many-to-one: the dimension is joined into that fact's query (a fact row whose key points at a row the reader may not see is not
 * counted). A fact's rules never filter a dimension or another fact. The planner calls {@link #reached} to add those dimensions and {@link #bind} for the
 * predicate of the tables a query (or one fact part of it) reads.
 */
public final class TableRules {

    /** The predicate of a set of tables: one condition (empty when nothing restricts them) and whether the reader may see no rows at all. */
    record Bound(List<Node> rules, boolean denied) {}

    private TableRules() {}

    /** The dimensions with security that a table of {@code from} reaches many-to-one along active relationships and that are not in it yet. */
    static Set<String> reached(Dataset ds, Set<String> from) {
        Set<String> out = new LinkedHashSet<>();
        if (!ds.security().perTable()) {
            return out;
        }
        for (Map.Entry<String, Dataset.TableSecurity> e : ds.security().tables().entrySet()) {
            String dim = e.getKey();
            if (!e.getValue().dimension() || e.getValue().open() || from.contains(dim)) {
                continue;
            }
            for (String start : from) {
                if (reaches(ds, start, dim)) {
                    out.add(dim);
                    break;
                }
            }
        }
        return out;
    }

    /** True when {@code to} is reached from {@code from} along active relationships, many-to-one (or one-to-one) at every step. */
    public static boolean reaches(Dataset ds, String from, String to) {
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(from));
        Set<String> seen = new LinkedHashSet<>(queue);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            for (Dataset.Relationship r : ds.relationships()) {
                if (!r.active()) {
                    continue;
                }
                List<String> next = new ArrayList<>();
                if ("one-to-many".equals(r.cardinality())) {
                    if (r.toTable().equals(at)) {
                        next.add(r.fromTable());
                    }
                } else if ("one-to-one".equals(r.cardinality())) {
                    if (r.fromTable().equals(at)) {
                        next.add(r.toTable());
                    }
                    if (r.toTable().equals(at)) {
                        next.add(r.fromTable());
                    }
                } else if (r.fromTable().equals(at)) {
                    next.add(r.toTable());
                }
                for (String n : next) {
                    if (n.equals(to)) {
                        return true;
                    }
                    if (seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return false;
    }

    /** The tables of a query a layer of whose row rules names none of the reader's roles: the reader sees none of their rows. */
    static List<String> deniedTables(Dataset ds, Set<String> tables, SqlCompiler.Reader reader) {
        List<String> out = new ArrayList<>();
        if (!reader.enforceRows()) {
            return out;
        }
        for (String t : tables) {
            Dataset.TableSecurity s = ds.security().tables().get(t);
            if (s != null && s.layers().stream().anyMatch(l -> !l.isEmpty() && l.stream().noneMatch(r -> reader.roles().contains(r.role())))) {
                out.add(t);
            }
        }
        return out;
    }

    /** The condition for the tables a query reads, as this reader. */
    static Bound bind(Dataset ds, Set<String> tables, SqlCompiler.Reader reader) {
        List<Node> all = new ArrayList<>();
        boolean denied = false;
        for (String t : tables) {
            Dataset.TableSecurity s = ds.security().tables().get(t);
            if (s == null) {
                continue;
            }
            s.filters().forEach(f -> all.add(com.ash.drishti.server.bi.expr.Expressions.parse(f)));
            if (!reader.enforceRows()) {
                continue;
            }
            for (List<Dataset.RowRule> layer : s.layers()) {
                if (layer.isEmpty()) {
                    continue;
                }
                Node or = null;
                for (Dataset.RowRule r : layer) {
                    if (reader.roles().contains(r.role())) {
                        Node n = com.ash.drishti.server.bi.expr.Expressions.parse(r.where());
                        or = or == null ? n : new Node.Bin("||", or, n);
                    }
                }
                if (or == null) {
                    denied = true;
                } else {
                    all.add(or);
                }
            }
        }
        if (all.isEmpty()) {
            return new Bound(List.of(), denied);
        }
        Node and = all.get(0);
        for (int i = 1; i < all.size(); i++) {
            and = new Node.Bin("&&", and, all.get(i));
        }
        return new Bound(List.of(and), denied);
    }

    /** The matched rules of every table for this reader, for the result cache's key (two readers with the same matches share answers). */
    static String fingerprint(Dataset ds, Set<String> roles) {
        if (!ds.security().perTable()) {
            return "";
        }
        StringBuilder b = new StringBuilder("|tables:");
        ds.security().tables().forEach((t, s) -> {
            b.append(t).append(s.filters());
            for (List<Dataset.RowRule> layer : s.layers()) {
                b.append('[');
                layer.stream().filter(r -> roles.contains(r.role())).forEach(r -> b.append(r.role()).append('=').append(r.where()).append(';'));
                b.append(layer.isEmpty() ? "open" : "").append(']');
            }
        });
        return b.toString();
    }
}
