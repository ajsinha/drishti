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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks of the Vega-Lite specification a {@code vega-lite} visual holds in {@code format.spec} (REPORTS.md "Vega-Lite visuals"). The page draws it with the
 * expression interpreter, under the strict content security policy, with a loader that refuses every URL; these checks say so at design time, before a
 * reader meets the refusal: no {@code url} or {@code loader}, a size limit, and a warning for a field the specification names that no well supplies.
 */
final class VegaSpecs {

    /** The most characters one specification may have as JSON. */
    static final int MAX_CHARS = 200_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private VegaSpecs() {}

    /** The path of the first {@code url} or {@code loader} that is a plain value anywhere in the specification, or null. */
    static String forbidden(Object node, String path) {
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String f = forbidden(list.get(i), path + "[" + i + "]");
                if (f != null) {
                    return f;
                }
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (("url".equals(k) || "loader".equals(k)) && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                    return path + "." + k;
                }
                String f = forbidden(v, path + "." + k);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    static int size(Object spec) {
        try {
            return JSON.writeValueAsString(spec).length();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The names the rows carry in the page: a field is the text after the table's dot (the table is put back, with an underscore, when two fields share a
     * name), a measure keeps its name. {@code bi-vega.js} {@code V.records} builds the same keys.
     */
    static Set<String> keys(Report.Visual v, Dataset ds) {
        List<String> names = new ArrayList<>();
        for (String well : List.of("axis", "legend", "values")) {
            names.addAll(v.well(well));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            String shortName = n.substring(n.lastIndexOf('.') + 1);
            long same = names.stream().filter(x -> x.substring(x.lastIndexOf('.') + 1).equals(shortName)).count();
            out.add(same > 1 ? n.replace('.', '_') : shortName);
        }
        return out;
    }

    /** The {@code field} names under any {@code encoding}; empty when the specification has a {@code transform} (it can make fields of its own). */
    static Set<String> fieldsOf(Object spec) {
        Set<String> out = new LinkedHashSet<>();
        boolean[] transform = {false};
        walk(spec, false, out, transform);
        return transform[0] ? Set.of() : out;
    }

    private static void walk(Object node, boolean inEncoding, Set<String> out, boolean[] transform) {
        if (node instanceof List<?> list) {
            list.forEach(x -> walk(x, inEncoding, out, transform));
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if ("transform".equals(k)) {
                    transform[0] = true;
                }
                if (inEncoding && "field".equals(k) && e.getValue() instanceof String s && !s.isBlank()) {
                    out.add(s.replace("\\.", "."));
                } else {
                    walk(e.getValue(), inEncoding || "encoding".equals(k), out, transform);
                }
            }
        }
    }
}
