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
import com.ash.drishti.bicompute.functions.OncePerParent;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.rachana.el.ElException;
import com.ash.rachana.el.Values;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's own list aggregates, the {@code _once} family ({@code sum_once}, {@code count_once}, {@code avg_once}: each parent once),
 * over a Rachana list: the list's values made a {@link Batch} and run through the very {@link OncePerParent} a pivot cell runs, so a
 * Sutra and a pivot agree. Every other list aggregate is Core's ({@code com.ash.rachana.agg}, SPLIT_PLAN 8.3h). Written
 * {@code f(list, 'FIELD', 'PARENT')} (fields of each element, dotted paths) or {@code f(xs, parents)} (parallel lists). A masked value
 * makes the result masked; an aggregate of nothing is empty.
 */
final class OnceAggregates {

    private OnceAggregates() {}

    /** {@code name} over the evaluated arguments of a call. */
    static Object apply(String name, List<Object> args) {
        if (!(Values.simplify(args.get(0)) instanceof DataNode.Arr list)) {
            return empty(name);
        }
        List<String> fields = new ArrayList<>();
        List<DataNode.Arr> parallels = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            Object a = Values.simplify(args.get(i));
            if (a instanceof DataNode.Arr arr) {
                parallels.add(arr);
            } else if (a instanceof String s && !s.isBlank()) {
                fields.add(s);
            } else if (a != null) {
                throw error(name, "argument " + (i + 1) + " is neither a field nor a list");
            }
        }
        OncePerParent f = new OncePerParent(name);
        List<List<DataNode>> columns = columns(name, list, fields, parallels, f.inputs());
        Object[] batch = new Object[columns.size()];
        for (int c = 0; c < batch.length; c++) {
            List<DataNode> col = columns.get(c);
            String[] s = new String[col.size()];
            for (int r = 0; r < s.length; r++) {
                DataNode x = col.get(r);
                if (x.isMasked()) {
                    return Values.mask();                         // a masked value is never added up: the result is masked
                }
                s[r] = x.isNull() || x.isMissing() ? null : Values.text(x);
            }
            batch[c] = s;
        }
        Object v = f.result(f.add(f.start(), new Batch(list.size(), batch)));
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            v = null;
        }
        return v == null ? empty(name) : v instanceof Double d ? Values.normalise(d) : v;
    }

    /** The function's input columns, one value per element: fields of the elements, or the list and parallel lists. */
    private static List<List<DataNode>> columns(String name, DataNode.Arr list, List<String> fields, List<DataNode.Arr> parallels,
            int inputs) {
        List<List<DataNode>> out = new ArrayList<>();
        if (!parallels.isEmpty()) {
            if (!fields.isEmpty()) {
                throw error(name, "write either fields of one list or parallel lists, not both");
            }
            out.add(list.elements());
            for (DataNode.Arr p : parallels) {
                if (p.size() != list.size()) {
                    throw error(name, "the lists differ in length (" + list.size() + " and " + p.size() + ")");
                }
                out.add(p.elements());
            }
        } else if (!fields.isEmpty()) {
            for (String field : fields) {
                List<DataNode> col = new ArrayList<>(list.size());
                list.elements().forEach(e -> col.add(e.at(field)));
                out.add(col);
            }
        } else {
            out.add(list.elements());
        }
        if (out.size() != inputs) {
            throw error(name, "takes " + inputs + " input" + (inputs == 1 ? "" : "s") + " (a field each, or as many lists), got " + out.size());
        }
        return out;
    }

    /** What an aggregate of nothing is: empty (as before the move). */
    static Object empty(String name) {
        return null;
    }

    static ElException error(String name, String why) {
        return new ElException(com.ash.rachana.RachanaCode.EL_EVAL, name + ": " + why);
    }
}
