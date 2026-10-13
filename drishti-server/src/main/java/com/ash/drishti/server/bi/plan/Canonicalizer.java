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
package com.ash.drishti.server.bi.plan;

import java.util.List;
import java.util.Map;

/**
 * The canonical text of a plan and its hash (docs/architecture/BI_DESIGN.md section 3.6): every node as {@code op{sorted props}[inputs]}, inputs of a
 * {@code Meet} and the scans under an aggregate sorted (their order changes no answer), the model at its fingerprint and the resolved time. The same
 * question in any language produces the same text, so caches and standing queries are shared across SQL, MQ and the designer. Security is never part of it.
 */
public final class Canonicalizer {

    private Canonicalizer() {}

    public static String hash(LogicalPlan p) {
        return "p_" + SecuritySignature.sha(text(p)).substring(0, 16);
    }

    public static String text(LogicalPlan p) {
        return p.model() + "@" + p.version() + "|" + p.time() + "|totals=" + named(p.totals(), p.ids()) + "|" + text(p.root(), p.ids());
    }

    public static String text(PlanNode n) {
        return text(n, Map.of());
    }

    static String text(PlanNode n, Map<String, String> ids) {
        StringBuilder b = new StringBuilder(n.op()).append(render(n.props(), ids));
        List<String> children = n.inputs().stream().map(i -> text(i, ids)).toList();
        if (n instanceof PlanNode.Meet || n instanceof PlanNode.Aggregate || n instanceof PlanNode.Filter) {
            children = children.stream().sorted().toList();
        }
        if (!children.isEmpty()) {
            b.append('[').append(String.join(",", children)).append(']');
        }
        return b.toString();
    }

    private static String render(Map<String, Object> props, Map<String, String> ids) {
        StringBuilder b = new StringBuilder("{");
        props.forEach((k, v) -> b.append(k).append('=').append(named(v, ids)).append(';'));
        return b.append('}').toString();
    }

    /** A value with every field or measure name written as its stable id (lists and maps followed). */
    static Object named(Object v, Map<String, String> ids) {
        if (ids.isEmpty()) {
            return v;
        }
        return switch (v) {
            case String s -> ids.getOrDefault(s, s);
            case List<?> l -> l.stream().map(x -> named(x, ids)).toList();
            case Map<?, ?> m -> {
                Map<Object, Object> out = new java.util.TreeMap<>();
                m.forEach((k, x) -> out.put(named(k, ids), named(x, ids)));
                yield out;
            }
            default -> v;
        };
    }
}
