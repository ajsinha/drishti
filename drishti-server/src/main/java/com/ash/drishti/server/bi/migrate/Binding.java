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
package com.ash.drishti.server.bi.migrate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a consumer of a converted dataset reads instead (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, sections 4.1 and 8.2): the model reference, the fields and measures
 * renamed on the way ({@code joined.name} to {@code counterparties.name}, {@code Desk P&L} to {@code P&L 1d}), and for a composed dataset whose join kept only
 * the rows of its left source, the measure a consumer filters on ({@code Trades > 0}) to keep that meaning.
 *
 * @param dataset the old dataset's name
 * @param model the model reference a consumer binds to ({@code trading-risk@1})
 * @param fields old field (or its prefix) to new field
 * @param measures old measure to new measure
 * @param having a measure that must be above zero, or null
 * @param live the dataset has a live table: its reports stay on it until a report on a model follows live data (standing queries, wave W3)
 */
record Binding(String dataset, String model, Map<String, String> fields, Map<String, String> measures, String having, boolean live) {

    Binding(String dataset, String model, Map<String, String> fields, Map<String, String> measures, String having) {
        this(dataset, model, fields, measures, having, false);
    }

    Binding {
        fields = Map.copyOf(fields);
        measures = Map.copyOf(measures);
    }

    static Binding plain(String dataset, String model) {
        return new Binding(dataset, model, Map.of(), Map.of(), null);
    }

    /** The new name of a field or measure as a consumer wrote it ({@code joined.tradeDate.year} follows {@code joined.tradeDate}), or null when unchanged. */
    String rename(String name) {
        if (measures.containsKey(name)) {
            return measures.get(name);
        }
        if (fields.containsKey(name)) {
            return fields.get(name);
        }
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (name.startsWith(e.getKey() + ".")) {
                return e.getValue() + name.substring(e.getKey().length());
            }
        }
        return null;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", dataset);
        m.put("model", model);
        m.put("fields", fields);
        m.put("measures", measures);
        m.put("having", having);
        return m;
    }
}
