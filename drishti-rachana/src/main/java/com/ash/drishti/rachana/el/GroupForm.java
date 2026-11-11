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
package com.ash.drishti.rachana.el;

import com.ash.drishti.api.DataNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code group(list, key, [aggregate], [by])}: grouping inside one document (RACHANA_REFERENCE.md section F4). Rachana reads the
 * expressions (the key and the aggregate's inputs, with {@code @} each element); the pivot engine groups and aggregates, through
 * its {@link FunctionProvider.Grouping} (docs/architecture/ARCHITECTURE.md part 3); where the engine is not on the class path the call is refused
 * with the reason. Two ways to write it, one implementation:
 *
 * <ul>
 *   <li>{@code group($.LEGS, @.CURRENCY, sum(@.NOTIONAL))}: the key an expression over {@code @}, the aggregate any of the engine's
 *       over expressions of {@code @} ({@code avg(@.PRICE)}, {@code percentile(@.NOTIONAL, 0.95)}, {@code wavg(@.PRICE,
 *       @.QUANTITY)}, {@code count(@)}); a masked value makes its group's value masked, a masked key the whole result.</li>
 *   <li>{@code group($.TRADES, 'PRODUCT_TYPE', 'NOTIONAL', 'year')}: the key and the value as field paths in quotes (the form every
 *       shipped Sutra had): the value the sum of the field over the group (number-like text read as a number), the count of the
 *       elements when no value field is given; a group with a masked value has no value. {@code by} cuts an ISO date key to its
 *       {@code month} (2026-10) or {@code year} (2026) first, in either way of writing.</li>
 * </ul>
 *
 * One row {@code {label, value, count}} per distinct key, in key order ({@code count} the group's elements); elements without a
 * key are left out; at most {@value ListFunctions#MAX_GROUPS} groups; empty for anything but a list.
 */
final class GroupForm {

    private GroupForm() {}

    static Object group(List<Expr> args, EvalContext c) {
        Object v = args.get(0).eval(c);
        if (Values.masked(v)) {
            return Values.mask();
        }
        if (!(Values.simplify(v) instanceof DataNode.Arr arr)) {
            return null;
        }
        String by = "";
        if (args.size() > 3) {
            Object b = args.get(3).eval(c);
            if (Values.masked(b)) {
                return Values.mask();
            }
            by = Values.text(b);
        }
        Expr.Call call = args.size() > 2 && args.get(2) instanceof Expr.Call k && aggregate(k.name()) ? k : null;
        String field = null;
        if (call == null && args.size() > 2) {
            Object f = args.get(2).eval(c);
            if (Values.masked(f)) {
                return Values.mask();
            }
            field = Values.isNull(f) || Values.text(f).isBlank() ? null : Values.text(f);
        }
        Expr keyExpr = args.get(1);
        String keyPath = keyExpr instanceof Expr.Lit lit ? Values.text(lit.value()) : null;

        List<String> keys = new ArrayList<>(arr.size());
        List<List<Object>> columns = new ArrayList<>();
        List<Object> options = new ArrayList<>();
        List<Expr> perElement = new ArrayList<>();
        if (call != null) {
            for (Expr a : call.args()) {
                if (a instanceof Expr.Lit lit && !perElement.isEmpty()) {
                    options.add(lit.value());                      // percentile(@.X, 0.95): the 0.95
                } else {
                    perElement.add(a);
                }
            }
            perElement.forEach(e -> columns.add(new ArrayList<>(arr.size())));
        } else if (field != null) {
            columns.add(new ArrayList<>(arr.size()));
        }
        for (int i = 0; i < arr.size(); i++) {
            DataNode e = arr.get(i);
            String k;
            if (keyPath != null) {
                k = e.at(keyPath).asText();
            } else {
                Object kv = keyExpr.eval(c.withRow(e, i));
                if (Values.masked(kv)) {
                    return Values.mask();                            // a grouping by a hidden value would tell it
                }
                k = Values.isNull(kv) ? null : Values.text(kv);
            }
            if (k != null && !k.isBlank()) {
                k = "month".equals(by) && k.length() >= 7 ? k.substring(0, 7) : "year".equals(by) && k.length() >= 4 ? k.substring(0, 4) : k;
            }
            keys.add(k);
            if (call != null) {
                EvalContext row = c.withRow(e, i);
                for (int p = 0; p < perElement.size(); p++) {
                    columns.get(p).add(perElement.get(p).eval(row));
                }
            } else if (field != null) {
                columns.get(0).add(e.at(field));
            }
        }
        String aggregate = call != null ? call.name() : field != null ? "sum" : "count";
        List<FunctionProvider.Group> groups = Aggregates.grouping().group(keys, columns, aggregate, options, ListFunctions.MAX_GROUPS);
        List<DataNode> out = new ArrayList<>(groups.size());
        for (FunctionProvider.Group g : groups) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", g.key());
            Object value = g.value();
            row.put("value", call == null && Values.masked(value) ? null : value);   // the quoted form: a masked group has no value
            row.put("count", g.count());
            out.add(DataNode.of(row));
        }
        return new DataNode.Arr(List.copyOf(out));
    }

    /** True for an aggregate's name, as the third argument of the expression form. */
    private static boolean aggregate(String name) {
        return Aggregates.provided(name) || Aggregates.SHARED.contains(name) || Aggregates.EXPECTED.contains(name);
    }
}
