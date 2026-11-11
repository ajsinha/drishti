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
package com.ash.drishti.plugin.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dotted paths into a plain-map document, with the extras a coherent walk needs: {@code *} (every element of a list),
 * a negative index ({@code history.-1.close} is the last row), and a selector ({@code points.tenor=10Y.zeroRate} is the
 * point whose tenor is 10Y). A path that leaves the document finds nothing.
 */
final class DemoPaths {

    private DemoPaths() {
    }

    /**
     * One place a path reached: a map entry or a list element. {@code pos} and {@code count} say where it sat among the
     * elements a {@code *} fanned out over (0 and 1 when the path has none), so a twist can weight by position.
     */
    record Slot(Object container, Object key, int pos, int count) {

        Object get() {
            if (container instanceof Map<?, ?> m) {
                return m.get(key);
            }
            return ((List<?>) container).get((Integer) key);
        }

        @SuppressWarnings("unchecked")
        void set(Object value) {
            if (container instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).put((String) key, value);
            } else {
                ((List<Object>) container).set((Integer) key, value);
            }
        }

        /** The map this slot lives in (the element a {@code points.*.df} derive is evaluated against), or null for a list slot. */
        @SuppressWarnings("unchecked")
        Map<String, Object> holder() {
            return container instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
    }

    /** Every slot the path reaches (the last step must exist for a map; a list slot is its element). Empty when the path leaves the document. */
    static List<Slot> find(Object root, String path) {
        List<Slot> out = new ArrayList<>();
        walk(root, path.split("\\."), 0, 0, 1, out);
        return out;
    }

    /** The first slot, or null. */
    static Slot first(Object root, String path) {
        List<Slot> all = find(root, path);
        return all.isEmpty() ? null : all.get(0);
    }

    private static void walk(Object at, String[] steps, int i, int pos, int count, List<Slot> out) {
        String step = steps[i];
        if (at instanceof Map<?, ?> m) {
            if (!m.containsKey(step)) {
                return;
            }
            if (i == steps.length - 1) {
                out.add(new Slot(m, step, pos, count));
            } else {
                walk(m.get(step), steps, i + 1, pos, count, out);
            }
        } else if (at instanceof List<?> list) {
            int n = list.size();
            if ("*".equals(step)) {
                for (int k = 0; k < n; k++) {
                    next(list, k, steps, i, k, n, out);
                }
            } else if (step.matches("-?\\d{1,6}")) {
                int k = Integer.parseInt(step);
                k = k < 0 ? n + k : k;
                if (k >= 0 && k < n) {
                    next(list, k, steps, i, pos, count, out);
                }
            } else if (step.indexOf('=') > 0) {
                String field = step.substring(0, step.indexOf('='));
                String want = step.substring(step.indexOf('=') + 1);
                for (int k = 0; k < n; k++) {
                    if (list.get(k) instanceof Map<?, ?> e && want.equals(String.valueOf(e.get(field)))) {
                        next(list, k, steps, i, pos, count, out);
                        return;
                    }
                }
            }
        }
    }

    private static void next(List<?> list, int k, String[] steps, int i, int pos, int count, List<Slot> out) {
        if (i == steps.length - 1) {
            out.add(new Slot(list, k, pos, count));
        } else {
            walk(list.get(k), steps, i + 1, pos, count, out);
        }
    }
}
