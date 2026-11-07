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
package com.ash.drishti.bicompute.custom;

import com.ash.drishti.bicompute.spi.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One version of a custom aggregation function (PIVOT_ENGINE.md sections 8.2 and 8.7): its language, code, declared kind, inputs, state
 * bound and tolerance; who wrote it and when; its harness report; its sharing (private, submitted, approved for groups or everyone,
 * withdrawn) with every approval and withdrawal. A version never changes once saved except for its sharing: a view names the version it
 * uses, so a new version never changes a saved view's figures.
 *
 * @param owner the author's user name
 * @param name the function's name ({@code wavg_return})
 * @param version {@code 1, 2, ...}
 * @param language formula (Rachana), sql (a DuckDB macro), python or java (a plug-in)
 * @param kind as declared (formula and SQL: as the engine finds it)
 * @param code the formula, the macro, the Python source; for Java the class name
 * @param entry Python: the class decorated with {@code @function}; Java: the jar's file name in the plug-in folder
 * @param sha256 Java: the jar's SHA-256 pinned at approval
 * @param inputs the named inputs and their types ({@code number} or {@code text}), bound by position to the fields a view passes
 * @param params the function's fixed parameters (a trim fraction)
 * @param tolerance the relative difference the harness allows (default 1e-12)
 * @param stateBytes the most bytes a state may hold (0: the engine's default)
 * @param sketchError a sketch's stated relative error
 * @param description a sentence for the field list and Help
 * @param createdAt when this version was saved
 * @param harness the harness report of this version (passed, worst run, cost)
 * @param status {@code private}, {@code submitted}, {@code approved} or {@code withdrawn}
 * @param approvals every approval of this version: {@code {scope, by, at, reason}} ({@code scope}: {@code everyone} or {@code group:<name>})
 * @param withdrawn the withdrawal {@code {by, at, reason}} (null: not withdrawn)
 */
public record FunctionDef(String owner, String name, int version, Language language, Kind kind, String code, String entry, String sha256,
        List<Input> inputs, Map<String, Object> params, double tolerance, long stateBytes, double sketchError, String description,
        String createdAt, Map<String, Object> harness, String status, List<Map<String, Object>> approvals, Map<String, Object> withdrawn) {

    /** The languages, in the order the playground offers them (formula first: most custom measures are formulas). */
    public enum Language {
        FORMULA, SQL, PYTHON, JAVA;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Language of(String s) {
            try {
                return valueOf(String.valueOf(s).strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CustomFunctions.invalid("a function's language is formula, sql, python or java, not '" + s + "'");
            }
        }
    }

    /** A named input and its type. */
    public record Input(String name, String type) {
        public boolean text() {
            return "text".equals(type);
        }
    }

    public FunctionDef {
        inputs = List.copyOf(inputs == null ? List.of() : inputs);
        params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
        approvals = List.copyOf(approvals == null ? List.of() : approvals);
        harness = harness == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(harness));
        status = status == null || status.isBlank() ? "private" : status;
        tolerance = tolerance > 0 ? tolerance : 1e-12;
    }

    /** {@code owner/name}. */
    public String id() {
        return owner + "/" + name;
    }

    /** {@code fn:owner/name@version}: what a view writes and every figure's label carries. */
    public String ref() {
        return "fn:" + id() + "@" + version;
    }

    public List<String> inputNames() {
        return inputs.stream().map(Input::name).toList();
    }

    public boolean isWithdrawn() {
        return withdrawn != null && !withdrawn.isEmpty();
    }

    /** The same version with other sharing. */
    public FunctionDef sharing(String newStatus, List<Map<String, Object>> newApprovals, Map<String, Object> newWithdrawn) {
        return new FunctionDef(owner, name, version, language, kind, code, entry, sha256, inputs, params, tolerance, stateBytes, sketchError,
                description, createdAt, harness, newStatus, newApprovals, newWithdrawn);
    }

    /** As stored and answered. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id());
        m.put("ref", ref());
        m.put("owner", owner);
        m.put("name", name);
        m.put("version", version);
        m.put("language", language.word());
        m.put("kind", kind.word());
        m.put("code", code);
        m.put("entry", entry);
        m.put("sha256", sha256);
        List<Map<String, Object>> ins = new ArrayList<>();
        inputs.forEach(i -> ins.add(Map.of("name", i.name(), "type", i.type())));
        m.put("inputs", ins);
        m.put("params", params);
        m.put("tolerance", tolerance);
        m.put("stateBytes", stateBytes);
        m.put("sketchError", sketchError);
        m.put("description", description);
        m.put("createdAt", createdAt);
        m.put("harness", harness);
        m.put("status", status);
        m.put("approvals", approvals);
        m.put("withdrawn", withdrawn);
        m.put("label", ref() + " by " + owner);
        return m;
    }

    /** From what {@link #toMap} wrote. */
    @SuppressWarnings("unchecked")
    public static FunctionDef fromMap(Map<String, Object> m) {
        List<Input> ins = new ArrayList<>();
        if (m.get("inputs") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> im) {
                    ins.add(new Input(String.valueOf(im.get("name")), String.valueOf(im.get("type") == null ? "number" : im.get("type"))));
                } else {
                    ins.add(new Input(String.valueOf(o), "number"));
                }
            }
        }
        List<Map<String, Object>> appr = new ArrayList<>();
        if (m.get("approvals") instanceof List<?> l) {
            l.forEach(o -> appr.add((Map<String, Object>) o));
        }
        return new FunctionDef(str(m.get("owner")), str(m.get("name")), num(m.get("version"), 1).intValue(), Language.of(str(m.get("language"))),
                kind(str(m.get("kind"))), str(m.get("code")), str(m.get("entry")), str(m.get("sha256")), ins,
                m.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of(), num(m.get("tolerance"), 1e-12).doubleValue(),
                num(m.get("stateBytes"), 0).longValue(), num(m.get("sketchError"), 0).doubleValue(), str(m.get("description")),
                str(m.get("createdAt")), m.get("harness") instanceof Map<?, ?> hm ? (Map<String, Object>) hm : Map.of(), str(m.get("status")), appr,
                m.get("withdrawn") instanceof Map<?, ?> wm ? (Map<String, Object>) wm : null);
    }

    static Kind kind(String s) {
        if (s == null || s.isBlank()) {
            return Kind.MERGEABLE;
        }
        try {
            return Kind.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CustomFunctions.invalid("a function's kind is mergeable, sketch or holistic, not '" + s + "'");
        }
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Number num(Object o, Number d) {
        if (o instanceof Number n) {
            return n;
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o));
            } catch (NumberFormatException e) {
                return d;
            }
        }
        return d;
    }
}
