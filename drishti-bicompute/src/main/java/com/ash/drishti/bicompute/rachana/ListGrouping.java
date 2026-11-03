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
package com.ash.drishti.bicompute.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.FunctionProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The grouping behind Rachana's {@code group(list, key, [aggregate])} (ONE_GRAMMAR.md part 3): the elements grouped by their keys (the
 * first {@code maxGroups} keys met), each group's inputs aggregated by the engine's function of that name ({@link ListAggregates}), the
 * groups in key order. Rachana reads the keys and the inputs; this class never parses an expression.
 */
final class ListGrouping implements FunctionProvider.Grouping {

    @Override
    public List<FunctionProvider.Group> group(List<String> keys, List<List<Object>> columns, String aggregate, List<Object> options,
            int maxGroups) {
        Map<String, List<Integer>> members = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (k == null || k.isBlank()) {
                continue;
            }
            List<Integer> m = members.get(k);
            if (m == null) {
                if (members.size() >= maxGroups) {
                    continue;                                         // the first keys met; the work and the answer stay bounded
                }
                m = new ArrayList<>();
                members.put(k, m);
            }
            m.add(i);
        }
        List<FunctionProvider.Group> out = new ArrayList<>(members.size());
        new TreeMap<>(members).forEach((k, rows) -> out.add(new FunctionProvider.Group(k, value(rows, columns, aggregate, options),
                rows.size())));
        return out;
    }

    private static Object value(List<Integer> rows, List<List<Object>> columns, String aggregate, List<Object> options) {
        if (columns.isEmpty()) {
            return (long) rows.size();                                // no input: the group's elements, counted
        }
        List<Object> args = new ArrayList<>(columns.size() + options.size());
        for (List<Object> column : columns) {
            List<DataNode> part = new ArrayList<>(rows.size());
            rows.forEach(r -> part.add(DataNode.of(column.get(r))));
            args.add(new DataNode.Arr(List.copyOf(part)));
        }
        args.addAll(options);
        return ListAggregates.apply(aggregate, args);
    }
}
