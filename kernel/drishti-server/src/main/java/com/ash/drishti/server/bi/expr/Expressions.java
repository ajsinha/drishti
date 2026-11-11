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
package com.ash.drishti.server.bi.expr;

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.NodeLowering;
import com.ash.drishti.server.bi.dataset.NodeText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one way BI reads an expression (HOW_IT_FITS.md §A28 section 2.6): Rachana's one parser ({@link CheckedMode#parse}), lowered to BI's
 * compiler tree by {@link NodeLowering}. A text in DEL or the old syntax is not read: {@code drishti.py tables migrate} rewrites it. Parses are
 * cached by text (the L0 parse cache, bounded), so the many places that read the same measure pay once. Thread-safe.
 */
public final class Expressions {

    /** The setting {@code drishti.bi.expr.legacy}: DEL and the old syntax are no longer read, whatever it says. */
    public enum Legacy {
        /** Formerly: the old syntax read with a warning. */
        WARN,
        /** Refused: the only behaviour. */
        REFUSE
    }

    public static final String OLD_SYNTAX = "DRS-9100";
    public static final String NOT_CONVERTIBLE = "DRS-9190";

    /** A parsed expression: the compiler's tree, Rachana's tree with where each node was written, and the text. */
    public record Parsed(Node node, CheckedMode.Parsed rachana, String text) {}

    private static volatile Legacy legacy = Legacy.REFUSE;
    private static volatile int cacheSize = 10_000;
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private Expressions() {}

    /** Set from {@code drishti.bi.expr.*} at start-up. */
    public static void configure(Legacy mode, int maxCached) {
        legacy = Objects.requireNonNull(mode);
        cacheSize = Math.max(0, maxCached);
        CACHE.clear();
    }

    public static Legacy legacyMode() {
        return legacy;
    }

    /** The compiler's tree of a Rachana expression; a refusal is a {@link ExpressionException}. */
    public static Node node(String text) {
        return read(text).node();
    }

    /** The expression read (cached by text). */
    public static Parsed read(String text) {
        Object hit = text == null ? null : CACHE.get(text);
        if (hit instanceof Parsed p) {
            return p;
        }
        if (hit instanceof ExpressionException e) {
            throw e;
        }
        Object r;
        try {
            r = readUncached(text);
        } catch (ExpressionException e) {
            r = e;
        }
        if (text != null && cacheSize > 0) {
            if (CACHE.size() >= cacheSize) {
                CACHE.clear();                                                  // simple and bounded: parses are cheap to redo
            }
            CACHE.put(text, r);
        }
        if (r instanceof ExpressionException e) {
            throw e;
        }
        return (Parsed) r;
    }

    private static Parsed readUncached(String text) {
        if (text == null || text.isBlank()) {
            throw new ExpressionException(NodeLowering.CODE, "an expression is expected here", null, 0, 0);
        }
        CheckedMode.Parsed p;
        try {
            p = CheckedMode.parse(text);
        } catch (ElException e) {
            int at = Math.max(0, Math.min(text.length(), e.position()));
            throw new ExpressionException(NodeLowering.CODE, e.getMessage(), null, at, Math.min(text.length(), at + 1));
        }
        return new Parsed(NodeLowering.lower(p), p, text);
    }

    /**
     * The text with every {@code measure('m_...')} that names a measure by its stable id written with the measure's name (a reference by id survives a
     * rename). Unchanged when nothing names an id, or the text does not parse.
     */
    public static String withMeasureNames(String text, Map<String, String> idToName) {
        if (text == null || idToName.isEmpty() || !text.contains("measure(")) {
            return text;
        }
        Node n;
        try {
            n = node(text);
        } catch (RuntimeException e) {
            return text;
        }
        if (!Node.anyMatch(n, x -> x instanceof Node.MeasureRef m && idToName.containsKey(m.name()))) {
            return text;
        }
        return NodeText.print(renamed(n, idToName));
    }

    /**
     * The text with tables renamed ({@code tables}: the first part of a dotted name, {@code count(trades)}) and measures renamed ({@code measures}), printed
     * in Rachana. Unchanged when nothing is renamed. A text that does not parse is returned as it is.
     */
    public static String withRenames(String text, Map<String, String> tables, Map<String, String> measures) {
        if (text == null || tables.isEmpty() && measures.isEmpty()) {
            return text;
        }
        Node n;
        try {
            n = node(text);
        } catch (RuntimeException e) {
            return text;
        }
        Node out = renamedRefs(n, tables, measures);
        return out.equals(n) ? text : NodeText.print(out);
    }

    private static Node renamedRefs(Node n, Map<String, String> tables, Map<String, String> measures) {
        return switch (n) {
            case Node.MeasureRef m -> new Node.MeasureRef(measures.getOrDefault(m.name(), m.name()));
            case Node.Ref r -> {
                List<String> parts = new ArrayList<>(r.parts());
                parts.set(0, tables.getOrDefault(parts.get(0), parts.get(0)));
                yield new Node.Ref(List.copyOf(parts));
            }
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> renamedRefs(a, tables, measures)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), renamedRefs(b.left(), tables, measures), renamedRefs(b.right(), tables, measures));
            case Node.Un u -> new Node.Un(u.op(), renamedRefs(u.operand(), tables, measures));
            case Node.In i -> new Node.In(renamedRefs(i.value(), tables, measures), i.items().stream().map(x -> renamedRefs(x, tables, measures)).toList(), i.negated());
            case Node.Cond c -> new Node.Cond(renamedRefs(c.test(), tables, measures), renamedRefs(c.yes(), tables, measures), renamedRefs(c.no(), tables, measures));
            default -> n;
        };
    }

    private static Node renamed(Node n, Map<String, String> ids) {
        return switch (n) {
            case Node.MeasureRef m -> new Node.MeasureRef(ids.getOrDefault(m.name(), m.name()));
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> renamed(a, ids)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), renamed(b.left(), ids), renamed(b.right(), ids));
            case Node.Un u -> new Node.Un(u.op(), renamed(u.operand(), ids));
            case Node.In i -> new Node.In(renamed(i.value(), ids), i.items().stream().map(x -> renamed(x, ids)).toList(), i.negated());
            case Node.Cond c -> new Node.Cond(renamed(c.test(), ids), renamed(c.yes(), ids), renamed(c.no(), ids));
            default -> n;
        };
    }
}
