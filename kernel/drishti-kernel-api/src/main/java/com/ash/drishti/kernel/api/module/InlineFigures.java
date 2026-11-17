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
package com.ash.drishti.kernel.api.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Figures of rows a module read, computed by the server's pivot engine ({@code POST /api/v1/pivot/inline}, as the person): the rows go
 * in with the measures wanted ({@code sum(MTM)}, {@code median(S)}, {@code stddev_pop(H)}, a formula of aggregates) and the engine's
 * cells come back. The engine aggregates (compensated sums, exact medians, Welford's deviations); nothing here adds up a figure.
 *
 * <p>{@link #ask} answers {@link Figures}: {@link Figures#get} a cell's figure by its {@code as} name (a null member is the total along
 * that axis), {@link Figures#members} the row members in the engine's order. No rows: nothing is asked and every figure is null.
 */
public final class InlineFigures {

    /** The engine's most inline rows ({@code InlinePivot.MAX_ROWS} of drishti-bicompute). */
    public static final int MAX_ROWS = 100_000;
    /** The member of rows with no value of the dimension ({@code CellKey.BLANK}). */
    public static final String BLANK = "(blank)";
    /** The member that stands for the rows a top N left out. */
    public static final String OTHERS = "\u0000Others";
    private static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private InlineFigures() {
    }

    /** One field of the rows: its name and {@code string} or {@code number}. */
    public record Field(String name, String type) {
        public Field {
            if (name == null || !FIELD.matcher(name).matches() || !("string".equals(type) || "number".equals(type))) {
                throw new IllegalArgumentException("an inline field is letters, digits and _, a string or a number: " + name + " " + type);
            }
        }
    }

    /** One measure: {@code {measure, as[, showAs]}}. */
    public static Map<String, Object> measure(String measure, String as) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measure", measure);
        m.put("as", as);
        return m;
    }

    /** One measure shown as another figure ({@code pct-grand}). */
    public static Map<String, Object> measure(String measure, String as, String showAs) {
        Map<String, Object> m = measure(measure, as);
        m.put("showAs", showAs);
        return m;
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
    public static Figures ask(KernelCalls.Caller caller, List<Field> fields, List<List<Object>> rows, List<Map<String, Object>> values, String by,
            String across, Map<String, Object> top) {
        if (rows.isEmpty()) {
            return new Figures(null);
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("the engine takes at most " + MAX_ROWS + " rows inline (" + rows.size() + " were read)");
        }
        return new Figures(caller.post("/pivot/inline", body(fields, rows, values, by, across, top), Map.of()));
    }

    /** The engine's answer, by member. */
    public static final class Figures {

        private final List<Object> rows = new ArrayList<>();
        private final Map<List<Object>, Map<String, Object>> at = new HashMap<>();
        private final boolean answered;

        public Figures(Map<String, Object> answer) {
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
            return "grand".equals(kind) ? null : "others".equals(kind) ? OTHERS : e.get("value");
        }

        /** Whether the engine was asked (false: there were no rows). */
        public boolean answered() {
            return answered;
        }

        /** A figure by its {@code as} name at a row and column member (null: the total along that axis); null when the engine has none. */
        public Double get(String name, Object row, Object column) {
            Map<String, Object> cell = at.get(java.util.Arrays.asList(row, column));
            Object v = cell == null ? null : cell.get(name);
            return v instanceof Number n && !(v instanceof Boolean) ? n.doubleValue() : null;
        }

        /** The grand total's figure. */
        public Double get(String name) {
            return get(name, null, null);
        }

        /** The row members the engine answered, in its order (the total and Others left out). */
        public List<Object> members() {
            return List.copyOf(rows.stream().filter(Objects::nonNull).toList());
        }
    }
}
