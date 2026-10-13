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

import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one way the server reads an expression (docs/architecture/BI_DESIGN.md section 3.3): DEL v2 first; while the old (W1, Rachana-EL-flavoured) syntax is
 * still read ({@code drishti.bi.expr.legacy: warn}, the default until the end of wave A), an expression that is not DEL v2 but parses in the old syntax is
 * read as before and marked {@link Parsed#legacy()} so Check can warn {@code DRS-9100} "old expression syntax: run the converter". With
 * {@code legacy: refuse} it is refused ({@code DRS-9100}) with the DEL v2 text in the fix. Parses are cached by text (the L0 parse cache of section 6.1,
 * bounded), so the many places that read the same measure pay once. Thread-safe.
 */
public final class Expressions {

    /** How the old syntax is treated. */
    public enum Legacy {
        /** Read with a {@code DRS-9100} warning (until the end of wave A). */
        WARN,
        /** Refused with {@code DRS-9100} and the DEL v2 text as the fix. */
        REFUSE
    }

    public static final String OLD_SYNTAX = "DRS-9100";
    public static final String NOT_CONVERTIBLE = "DRS-9190";

    /** A parsed expression: the compiler's tree, the DEL v2 tree (null for the old syntax), and whether it was the old syntax. */
    public record Parsed(Node node, DelAst del, boolean legacy, String text) {}

    private static volatile Legacy legacy = Legacy.WARN;
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

    /** The compiler's tree of an expression in either syntax (the old one only while it is read); a refusal is a {@link DelException}. */
    public static Node parse(String text) {
        return read(text).node();
    }

    /** The expression read: DEL v2, or (while allowed) the old syntax. */
    public static Parsed read(String text) {
        Object hit = text == null ? null : CACHE.get(text);
        if (hit instanceof Parsed p) {
            return p;
        }
        if (hit instanceof DelException e) {
            throw e;
        }
        Object r;
        try {
            r = readUncached(text);
        } catch (DelException e) {
            r = e;
        }
        if (text != null && cacheSize > 0) {
            if (CACHE.size() >= cacheSize) {
                CACHE.clear();                                                  // simple and bounded: parses are cheap to redo
            }
            CACHE.put(text, r);
        }
        if (r instanceof DelException e) {
            throw e;
        }
        return (Parsed) r;
    }

    private static Parsed readUncached(String text) {
        DelAst tree;
        try {
            tree = DelParser.parse(text);
        } catch (DelException v2) {
            return old(text, v2);
        }
        DelChecker.Checked c = DelChecker.check(tree, null, Fields.NONE);
        java.util.Optional<DelException.Problem> bad = c.firstError();
        if (bad.isPresent()) {
            return old(text, new DelException(bad.get()));
        }
        return new Parsed(DelLowering.lower(tree), tree, false, text);
    }

    /** The old syntax: read (with the mark) while allowed, refused with the DEL v2 text otherwise; when it is not the old syntax either, the v2 error. */
    private static Parsed old(String text, DelException v2) {
        Node n;
        try {
            n = ExprParser.parse(text);
        } catch (ExprParser.ExprException e) {
            throw v2;
        }
        if (legacy == Legacy.REFUSE) {
            String converted = null;
            try {
                converted = DelPrinter.print(n);
            } catch (RuntimeException ignored) {
                // no mechanical fix
            }
            throw new DelException(OLD_SYNTAX, "old expression syntax (refused since wave A): run the converter, drishti.py bi migrate --expressions",
                    converted == null ? null : "write: " + converted, 0, text.length());
        }
        return new Parsed(n, null, true, text);
    }

    /**
     * The {@code DRS-9100} note for an expression in the old syntax (read while allowed): "old expression syntax: run the converter", with the DEL v2 text;
     * null when the text is DEL v2 (or does not parse: the parse error is the caller's problem).
     */
    public static String oldSyntaxNote(String text) {
        if (!isLegacy(text)) {
            return null;
        }
        String to;
        try {
            to = convert(text).to();
        } catch (RuntimeException e) {
            to = null;
        }
        return "old expression syntax (read until the end of wave A, then refused): run the converter, drishti.py bi migrate --expressions"
                + (to == null ? "" : "; in DEL v2: " + to);
    }

    /** True when the text is the old syntax (read while allowed). */
    public static boolean isLegacy(String text) {
        try {
            return read(text).legacy();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The text with every {@code measure('m_...')} that names a measure by its stable id written with the measure's name (section 3.3.2: a reference by id
     * survives a rename). Unchanged when nothing names an id, or the text is not DEL v2.
     */
    public static String withMeasureNames(String text, Map<String, String> idToName) {
        if (text == null || idToName.isEmpty() || !text.contains("measure(")) {
            return text;
        }
        Parsed p;
        try {
            p = read(text);
        } catch (RuntimeException e) {
            return text;
        }
        if (p.legacy() || !com.ash.drishti.server.bi.dataset.Node.anyMatch(p.node(), n -> n instanceof Node.MeasureRef m && idToName.containsKey(m.name()))) {
            return text;
        }
        return DelPrinter.print(renamed(p.node(), idToName));
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

    // ---- the converter -----------------------------------------------------------------------------------------------

    /** What the converter made of one expression: the DEL v2 text, and whether it changed. */
    public record Converted(String from, String to, boolean changed) {}

    /**
     * Converts an expression to DEL v2: unchanged when it already is; else the old parser's tree printed as DEL v2, re-parsed and lowered, and checked to give
     * the same compiler tree (so the same SQL). A text that is neither is refused with the v2 problem; a conversion that would change the meaning with
     * {@code DRS-9190}.
     */
    public static Converted convert(String text) {
        try {
            DelChecker.check(DelParser.parse(text), null, Fields.NONE).firstError().ifPresent(p -> {
                throw new DelException(p);
            });
            return new Converted(text, text, false);
        } catch (DelException v2) {
            Node old;
            try {
                old = ExprParser.parse(text);
            } catch (ExprParser.ExprException e) {
                throw v2;
            }
            String printed = DelPrinter.print(old);
            Node again;
            try {
                again = DelLowering.lower(DelParser.parse(printed));
            } catch (DelException e) {
                throw new DelException(NOT_CONVERTIBLE, "the converter wrote '" + printed + "', which does not read back: " + e.getMessage(), null, 0, text.length());
            }
            if (!normal(old).equals(normal(again))) {
                throw new DelException(NOT_CONVERTIBLE, "the converter could not keep the meaning of '" + text + "' (it wrote '" + printed + "')", null, 0, text.length());
            }
            return new Converted(text, printed, true);
        }
    }

    /** A compiler tree with the old spellings that compile alike made one ({@code if(c, a, b)} is a conditional, {@code 'DESC'} is {@code 'desc'}). */
    static Node normal(Node n) {
        return switch (n) {
            case Node.Call c when "if".equals(c.function()) && c.args().size() == 3 -> new Node.Cond(normal(c.args().get(0)), normal(c.args().get(1)), normal(c.args().get(2)));
            case Node.Call c -> new Node.Call(c.function(), c.args().stream().map(a -> a instanceof Node.Str s && java.util.Set.of("asc", "desc").contains(s.value().toLowerCase(
                    java.util.Locale.ROOT)) ? new Node.Str(s.value().toLowerCase(java.util.Locale.ROOT)) : normal(a)).toList());
            case Node.Bin b -> new Node.Bin(b.op(), normal(b.left()), normal(b.right()));
            case Node.Un u -> new Node.Un(u.op(), normal(u.operand()));
            case Node.In i -> new Node.In(normal(i.value()), i.items().stream().map(Expressions::normal).toList(), i.negated());
            case Node.Cond c -> new Node.Cond(normal(c.test()), normal(c.yes()), normal(c.no()));
            default -> n;
        };
    }
}
