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
package com.ash.drishti.inference;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.sutra.model.Column;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers columns for a table from an array of objects, and fields for a kv panel from an object. Column
 * order follows the source's field order; numeric money-like columns get totals. Pure and thread-safe.
 */
public final class ColumnInference {

    private final Semantics semantics;

    public ColumnInference(Semantics semantics) {
        this.semantics = semantics;
    }

    /** Columns for rows: fields present in at least 60% of rows, scalar only, capped by {@code tableMaxColumns}. */
    public List<Column> forRows(DataNode rows) {
        Map<String, DataNode> samples = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int n = rows.size();
        for (int i = 0; i < n; i++) {
            if (rows.get(i) instanceof DataNode.Obj o) {
                o.fields().forEach((k, v) -> {
                    counts.merge(k, 1, Integer::sum);
                    if (v.type().isScalar() && v.type() != NodeType.NULL) {
                        samples.putIfAbsent(k, v);
                    }
                });
            }
        }
        int max = semantics.limit("tableMaxColumns", 9);
        List<Column> out = new ArrayList<>();
        for (Map.Entry<String, DataNode> e : samples.entrySet()) {
            if (counts.get(e.getKey()) * 10 < n * 6 || out.size() >= max) {
                continue;
            }
            Role r = semantics.role(e.getKey(), e.getValue());
            boolean total = "signed-money".equals(r.name()) || ("money".equals(r.name()) && e.getKey().toLowerCase().contains("amount"));
            out.add(new Column(Semantics.humanize(e.getKey()), "@." + e.getKey(), r.fmt(), r.tone(), total, false));
        }
        return out;
    }

    /** Fields for a kv panel over {@code object}: every scalar field, relative to {@code bindPrefix}. */
    public List<Column> forObject(DataNode object, String bindPrefix) {
        List<Column> out = new ArrayList<>();
        if (!(object instanceof DataNode.Obj o)) {
            return out;
        }
        int max = semantics.limit("kvMaxFields", 16);
        for (Map.Entry<String, DataNode> e : o.fields().entrySet()) {
            DataNode v = e.getValue();
            if (out.size() >= max) {
                break;
            }
            if (v.type().isScalar()) {
                Role r = semantics.role(e.getKey(), v);
                out.add(new Column(Semantics.humanize(e.getKey()), bindPrefix + e.getKey(), r.fmt(), r.tone(), false, false));
            } else if (v instanceof DataNode.Obj inner && inner.fields().containsKey("name")) {
                out.add(new Column(Semantics.humanize(e.getKey()), bindPrefix + e.getKey() + ".name", null, null, false, false));
            }
        }
        return out;
    }
}
