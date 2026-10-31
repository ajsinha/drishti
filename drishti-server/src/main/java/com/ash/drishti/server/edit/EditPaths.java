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
package com.ash.drishti.server.edit;

import com.ash.drishti.api.DataNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The paths a change names and how a value is laid at one (WRITE_BACK.md › The design and its reasons, section 6): {@code $.NOTIONAL}, {@code $.LEGS[0].NOTIONAL}, and a
 * row of a page picked by its key, {@code $.TRADES[TRADE_ID=MX-1].NOTIONAL} (a form's record shown as a row of another kind's page).
 * Documents are immutable: {@link #set} returns a new tree with the value laid, and the same tree when the path does not reach a place
 * (a keyed row that is not there is not invented).
 */
public final class EditPaths {

    /** {@code $.A.B[0].C[KEY=v]}: names in upper or lower snake, indexes, and key selectors. */
    private static final Pattern STEP = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)]|\\[([A-Za-z_][A-Za-z0-9_]*)=([^\\]]*)]");
    private static final Pattern VALID = Pattern.compile("^\\$(\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+]|\\[[A-Za-z_][A-Za-z0-9_]*=[^\\]]*])+$");

    private EditPaths() {}

    /** One step: a field name, an index, or a key selector. */
    record Step(String field, int index, String key, String value) {}

    public static boolean valid(String path) {
        return path != null && path.length() <= 512 && VALID.matcher(path).matches();
    }

    /** {@code $.} and the field, for a form field's {@code bind}. */
    public static String ofField(String bind) {
        return "$." + bind;
    }

    /** The path of a field of a page's row: {@code $.TRADES[TRADE_ID=MX-1].NOTIONAL}. */
    public static String ofRow(String rows, String keyField, String key, String field) {
        String base = rows.startsWith("$") ? rows : "$." + rows;
        return base + "[" + keyField + "=" + key.replace("]", "") + "]." + field;
    }

    static List<Step> steps(String path) {
        if (!valid(path)) {
            throw new IllegalArgumentException("a path is $.FIELD, $.A[0].B or $.ROWS[KEY=value].FIELD, not '" + path + "'");
        }
        List<Step> out = new ArrayList<>();
        Matcher m = STEP.matcher(path.substring(1));
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(new Step(m.group(1), -1, null, null));
            } else if (m.group(2) != null) {
                out.add(new Step(null, Integer.parseInt(m.group(2)), null, null));
            } else {
                out.add(new Step(null, -1, m.group(3), m.group(4)));
            }
        }
        return out;
    }

    /** The node at a path ({@link DataNode#missing()} when it does not reach one). */
    public static DataNode get(DataNode root, String path) {
        DataNode n = root;
        for (Step s : steps(path)) {
            n = step(n, s);
            if (n.isMissing()) {
                return n;
            }
        }
        return n;
    }

    private static DataNode step(DataNode n, Step s) {
        if (s.field() != null) {
            return n.get(s.field());
        }
        if (s.index() >= 0) {
            return n.get(s.index());
        }
        if (n instanceof DataNode.Arr a) {
            for (DataNode e : a.elements()) {
                if (s.value().equals(e.get(s.key()).asText())) {
                    return e;
                }
            }
        }
        return DataNode.missing();
    }

    /** The tree with {@code value} laid at {@code path}; a field that is absent is added, a row or index that is absent is not. */
    public static DataNode set(DataNode root, String path, Object value) {
        return set(root, steps(path), 0, DataNode.of(value));
    }

    private static DataNode set(DataNode n, List<Step> steps, int i, DataNode value) {
        if (i == steps.size()) {
            return value;
        }
        Step s = steps.get(i);
        if (s.field() != null) {
            if (!(n instanceof DataNode.Obj o)) {
                return n;
            }
            Map<String, DataNode> f = new LinkedHashMap<>(o.fields());
            DataNode child = f.getOrDefault(s.field(), DataNode.missing());
            if (child.isMissing() && i + 1 < steps.size()) {
                return n;                                        // a field below one that is absent: nowhere to lay it
            }
            f.put(s.field(), set(child, steps, i + 1, value));
            return new DataNode.Obj(f);
        }
        if (!(n instanceof DataNode.Arr a)) {
            return n;
        }
        List<DataNode> els = new ArrayList<>(a.elements());
        for (int k = 0; k < els.size(); k++) {
            DataNode e = els.get(k);
            boolean hit = s.index() >= 0 ? k == s.index() : s.value().equals(e.get(s.key()).asText());
            if (hit) {
                els.set(k, set(e, steps, i + 1, value));
                return new DataNode.Arr(els);
            }
        }
        return n;
    }

    /** The record's field a path ends in ({@code NOTIONAL} for {@code $.LEGS[0].NOTIONAL}), for masks. */
    public static String field(String path) {
        List<Step> st = steps(path);
        for (int i = st.size() - 1; i >= 0; i--) {
            if (st.get(i).field() != null) {
                return st.get(i).field();
            }
        }
        return "";
    }
}
