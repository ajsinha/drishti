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

import com.ash.drishti.rachana.model.Column;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How each chart and table kind is configured from the facts the rules find (rows path, axis, measures, label and value
 * fields). Runtime inference ({@link Rules}) and the Screen Builder's auto-design call the same recipes, so the options a
 * panel gets never depend on which of the two chose it. Pure and thread-safe.
 */
public final class PanelRecipes {

    private static final String[] TONES = {"link", "accent", "pos", "neg"};

    private PanelRecipes() {}

    /** A table over {@code path}; a {@code totalLabel} when a column totals. */
    public static Map<String, Object> table(String path, List<Column> cols) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("rows", path);
        if (cols.stream().anyMatch(Column::total)) {
            opts.put("totalLabel", "Total");
        }
        return opts;
    }

    public static Map<String, Object> kv(String path) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("rows", path);
        return opts;
    }

    /** Several measures along one axis (a term structure, an exposure profile): an area chart, up to four series. */
    public static Map<String, Object> area(String path, String x, List<String> measures) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("rows", path);
        opts.put("x", x);
        List<Object> series = new ArrayList<>();
        for (int i = 0; i < Math.min(measures.size(), TONES.length); i++) {
            series.add(Map.of("label", Semantics.humanize(measures.get(i)), "value", measures.get(i), "tone", TONES[i]));
        }
        opts.put("series", series);
        return opts;
    }

    public static Map<String, Object> line(String path, String x, String y) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("rows", path);
        opts.put("x", x);
        opts.put("y", y);
        return opts;
    }

    /** Label and amount bars; the format and tone follow the value's semantic role. */
    public static Map<String, Object> hbar(String path, String label, String value, Role role) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("rows", path);
        opts.put("label", label);
        opts.put("value", value);
        opts.put("fmt", role.fmt() == null ? "amount0" : role.fmt());
        if (role.tone() != null) {
            opts.put("tone", role.tone());
        }
        return opts;
    }

    /** Dated rows: a ladder that highlights the latest row. */
    public static Map<String, Object> ladder(String path, int rows) {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("rows", path);
        opts.put("highlight", "#index == " + Math.max(0, rows - 1));
        return opts;
    }
}
