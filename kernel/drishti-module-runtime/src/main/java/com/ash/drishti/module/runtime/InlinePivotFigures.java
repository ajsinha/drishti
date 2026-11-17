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
package com.ash.drishti.module.runtime;

import com.ash.drishti.kernel.api.module.Answers;
import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.module.InlineFigures.Field;
import com.ash.drishti.kernel.api.module.KernelCalls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The kernel's {@link KernelCalls.Caller#figures}: the request to the pivot engine ({@code POST /api/v1/pivot/inline}, as the person) and
 * the reading of its cells into {@link InlineFigures.Figures}. The engine aggregates; nothing here adds up a figure.
 */
public final class InlinePivotFigures {

    private InlinePivotFigures() {
    }

    /**
     * The request: the fields, the rows (a number as {@link Answers#num} reads it, a member as text), the measures and the arrangement
     * (one level of rows {@code by} and of columns {@code across}, a top N).
     */
    public static Map<String, Object> body(List<Field> fields, List<List<Object>> rows, List<Map<String, Object>> values, String by, String across,
            Map<String, Object> top) {
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Field f : fields) {
            fs.add(Map.of("name", f.name(), "type", f.type()));
        }
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (List<Object> r : rows) {
            List<Object> row = new ArrayList<>(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                Object v = i < r.size() ? r.get(i) : null;
                if ("number".equals(fields.get(i).type())) {
                    row.add(Answers.num(v));
                } else {
                    row.add(v == null || "".equals(v) ? null : String.valueOf(v));
                }
            }
            out.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rows", by == null ? List.of() : List.of(by));
        view.put("columns", across == null ? List.of() : List.of(across));
        view.put("values", values);
        view.put("expandTo", Map.of("rows", 1, "columns", 1));
        if (top != null && !top.isEmpty()) {
            view.put("top", top);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fs);
        body.put("rows", out);
        body.put("view", view);
        return body;
    }

    /**
     * The engine's figures of {@code rows}, as the person (a refusal is the server's {@link KernelCalls.Refused}, the pivot engine switched
     * off among them). No rows: nothing is asked.
     *
     * @throws IllegalArgumentException more rows than the engine takes inline
     */
    public static InlineFigures.Figures ask(KernelCalls.Caller caller, List<Field> fields, List<List<Object>> rows, List<Map<String, Object>> values,
            String by, String across, Map<String, Object> top) {
        return ask(b -> caller.post("/pivot/inline", b, Map.of()), fields, rows, values, by, across, top);
    }

    /**
     * The engine's figures of {@code rows}, the body ({@link #body}) answered by {@code engine}: the kernel's typed
     * {@code Pivots.figures} passes its own in-process {@code Pivots.inline}. No rows: nothing is asked.
     *
     * @throws IllegalArgumentException more rows than the engine takes inline
     */
    public static InlineFigures.Figures ask(java.util.function.UnaryOperator<Map<String, Object>> engine, List<Field> fields, List<List<Object>> rows,
            List<Map<String, Object>> values, String by, String across, Map<String, Object> top) {
        if (rows.isEmpty()) {
            return new Figures(null);
        }
        if (rows.size() > InlineFigures.MAX_ROWS) {
            throw new IllegalArgumentException("the engine takes at most " + InlineFigures.MAX_ROWS + " rows inline (" + rows.size() + " were read)");
        }
        return new Figures(engine.apply(body(fields, rows, values, by, across, top)));
    }

    /** The engine's answer, by member. */
    static final class Figures implements InlineFigures.Figures {

        private final List<Object> rows = new ArrayList<>();
        private final Map<List<Object>, Map<String, Object>> at = new HashMap<>();
        private final boolean answered;

        Figures(Map<String, Object> answer) {
            this.answered = answer != null;
            Map<String, Object> cells = Answers.map(answer, "cells");
            List<Map<String, Object>> heads = new ArrayList<>();
            for (Object e : Answers.list(answer, "rows")) {
                if (isHead(e)) {
                    heads.add(Answers.map(e));
                }
            }
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Object e : Answers.list(answer, "columns")) {
                if (isHead(e)) {
                    cols.add(Answers.map(e));
                }
            }
            if (cols.isEmpty()) {
                cols.add(Map.of("key", "*", "kind", "grand"));
            }
            for (Map<String, Object> r : heads) {
                if ("node".equals(r.get("kind"))) {
                    rows.add(r.get("value"));
                }
                for (Map<String, Object> c : cols) {
                    Object cell = cells.get(Answers.str(r, "key") + "|" + Answers.str(c, "key"));
                    if (cell != null) {
                        at.put(java.util.Arrays.asList(member(r), member(c)), Answers.map(cell, "values"));
                    }
                }
            }
            if (heads.isEmpty() && cells.containsKey("*|*")) {
                at.put(java.util.Arrays.asList(null, null), Answers.map(cells.get("*|*"), "values"));
            }
        }

        private static boolean isHead(Object e) {
            Object kind = Answers.get(e, "kind");
            return "node".equals(kind) || "grand".equals(kind) || "others".equals(kind);
        }

        private static Object member(Map<String, Object> e) {
            Object kind = e.get("kind");
            return "grand".equals(kind) ? null : "others".equals(kind) ? InlineFigures.OTHERS : e.get("value");
        }

        /** Whether the engine was asked (false: there were no rows). */
        @Override
        public boolean answered() {
            return answered;
        }

        /** A figure by its {@code as} name at a row and column member (null: the total along that axis); null when the engine has none. */
        @Override
        public Double get(String name, Object row, Object column) {
            Map<String, Object> cell = at.get(java.util.Arrays.asList(row, column));
            Object v = cell == null ? null : cell.get(name);
            return v instanceof Number n && !(v instanceof Boolean) ? n.doubleValue() : null;
        }

        /** The row members the engine answered, in its order (the total and Others left out). */
        @Override
        public List<Object> members() {
            return List.copyOf(rows.stream().filter(Objects::nonNull).toList());
        }
    }
}
