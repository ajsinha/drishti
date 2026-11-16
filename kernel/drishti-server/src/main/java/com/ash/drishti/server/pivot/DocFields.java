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
package com.ash.drishti.server.pivot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The field list of an entity document beyond its promoted columns (ARCHITECTURE.md section 26.4): from a sample of documents, every
 * nested object's values as path fields of their parent ({@code $.LEGS[*].INDEX.NAME}, shown as Legs › Index › Name) and every list of
 * objects as a grain of its own ({@code $.LEGS[*]}, {@code $.LEGS[*].CASH_FLOWS[*]}, {@code $.EVENTS[*]}), each typed by its values.
 */
final class DocFields {

    /** The most path fields one kind offers (a person may still type any other path). */
    static final int MAX = 400;

    private DocFields() {}

    /** A sampled field: its path and what its values were. */
    static final class Seen {
        final String path;
        int numbers;
        int dates;
        int texts;

        Seen(String path) {
            this.path = path;
        }

        String type() {
            if (numbers > 0 && texts == 0 && dates == 0) {
                return "number";
            }
            if (dates > 0 && texts == 0 && numbers == 0) {
                return "date";
            }
            return "string";
        }
    }

    /**
     * The path fields of the sampled documents, in the order first seen; top-level fields the kind promotes are left out (they are
     * columns), and so are names the person may not see.
     */
    static Map<String, Seen> of(List<Map<?, ?>> docs, Set<String> promoted, java.util.function.Predicate<String> hidden) {
        Map<String, Seen> out = new LinkedHashMap<>();
        for (Map<?, ?> d : docs) {
            walk(d, "$", true, promoted, hidden, out);
        }
        return out;
    }

    private static void walk(Map<?, ?> m, String prefix, boolean top, Set<String> promoted, java.util.function.Predicate<String> hidden,
            Map<String, Seen> out) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            boolean nested = e.getValue() instanceof Map || e.getValue() instanceof List;     // a promoted list is still walked: its elements
            if (!k.matches("[A-Za-z_][A-Za-z0-9_]*") || hidden.test(k) || top && promoted.contains(k) && !nested || out.size() >= MAX) {
                continue;
            }
            Object v = e.getValue();
            String p = prefix + "." + k;
            if (v instanceof Map<?, ?> mm) {
                walk(mm, p, false, promoted, hidden, out);
            } else if (v instanceof List<?> l) {
                for (Object el : l) {
                    if (el instanceof Map<?, ?> mm) {
                        walk(mm, p + "[*]", false, promoted, hidden, out);
                    }
                }
            } else if (v != null && !(v instanceof String s && s.isEmpty())) {
                Seen s = out.computeIfAbsent(p, Seen::new);
                if (v instanceof Number) {
                    s.numbers++;
                } else if (String.valueOf(v).matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?")) {
                    s.dates++;
                } else {
                    s.texts++;
                }
            }
        }
    }
}
