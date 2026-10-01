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
package com.ash.drishti.rachana.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The values an option of a panel kind may take, where the kind restricts them: {@code agg: sum|count|avg|min|max}
 * on {@code pivot}, {@code layout: tree|force} on {@code graph}, a whole {@code bins} count on {@code histogram},
 * {@code markers} as a list of mappings with a {@code value}. The parser reports a value outside them as
 * {@code DRS-2029}, so a Sutra is rejected before it meets data rather than drawing something unexpected.
 */
public final class PanelOptions {

    /** Aggregations a pivot computes over its rows. */
    public static final List<String> AGGREGATIONS = List.of("sum", "count", "avg", "min", "max");
    /** How a graph places its nodes. */
    public static final List<String> GRAPH_LAYOUTS = List.of("tree", "force");
    /** The most bins a histogram may ask for. */
    public static final int MAX_BINS = 200;

    private static final Map<PanelKind, Map<String, List<String>>> CHOICES = Map.of(
            PanelKind.PIVOT, Map.of("agg", AGGREGATIONS),
            PanelKind.GRAPH, Map.of("layout", GRAPH_LAYOUTS));
    private static final Map<PanelKind, Set<String>> BOOLEANS = Map.of(PanelKind.PIVOT, Set.of("heat", "totals"));
    private static final Set<String> MARKER_KEYS = Set.of("label", "value", "tone");

    private PanelOptions() {}

    /** The allowed values of {@code option} on {@code kind}; empty when any text is accepted. */
    public static List<String> choices(PanelKind kind, String option) {
        return CHOICES.getOrDefault(kind, Map.of()).getOrDefault(option, List.of());
    }

    /** Why {@code value} is not valid for {@code option} of {@code kind} panels; empty when it is. */
    public static Optional<String> problem(PanelKind kind, String option, Object value) {
        List<String> allowed = choices(kind, option);
        if (!allowed.isEmpty() && !allowed.contains(String.valueOf(value))) {
            return Optional.of("option '" + option + "' of '" + kind.id() + "' panels must be one of " + String.join(", ", allowed)
                    + ", not '" + value + "'");
        }
        if (BOOLEANS.getOrDefault(kind, Set.of()).contains(option) && !(value instanceof Boolean)) {
            return Optional.of("option '" + option + "' of '" + kind.id() + "' panels must be true or false, not '" + value + "'");
        }
        if (kind == PanelKind.HISTOGRAM && option.equals("bins")
                && !(value instanceof Long n && n >= 1 && n <= MAX_BINS)) {
            return Optional.of("option 'bins' of 'histogram' panels must be a whole number from 1 to " + MAX_BINS + ", not '" + value + "'");
        }
        if (kind == PanelKind.HISTOGRAM && option.equals("markers")) {
            return markers(value);
        }
        return Optional.empty();
    }

    private static Optional<String> markers(Object value) {
        if (!(value instanceof List<?> list)) {
            return Optional.of("option 'markers' of 'histogram' panels must be a list of { label, value, tone }");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || m.get("value") == null) {
                return Optional.of("each histogram marker must be a mapping with a 'value' expression (and optionally 'label' and 'tone')");
            }
            for (Object k : m.keySet()) {
                if (!MARKER_KEYS.contains(String.valueOf(k))) {
                    return Optional.of("unknown key '" + k + "' in a histogram marker; expected label, value, tone");
                }
            }
        }
        return Optional.empty();
    }
}
