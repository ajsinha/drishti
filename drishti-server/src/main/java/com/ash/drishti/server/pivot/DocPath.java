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

import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Expr;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A pivot field of an entity document as a Rachana path (ARCHITECTURE.md section 23.6.7): parsed by Rachana's own parser
 * ({@link ElCompiler}), never a second dialect, and compiled to DuckDB's JSON functions over the document. The grain comes from the path:
 * each {@code [*]} steps into a list ({@code $.LEGS[*].NOTIONAL} is a leg field, {@code $.LEGS[*].CASH_FLOWS[*].AMOUNT} a cash-flow one),
 * a filter before it keeps some elements ({@code $.LEGS[?@.LEG_TYPE == 'FIXED'][*].NOTIONAL}: the fixed legs'), a filter before an index
 * picks one ({@code $.LEGS[?@.LEG_TYPE == 'FLOAT'][0].INDEX.NAME}), and {@code size(...)} counts a list. Anything with no SQL form is
 * refused in words.
 *
 * @param text the path as written
 * @param id the field id the engine uses: the dotted form of a plain path ({@code LEGS.NOTIONAL}, {@code LEGS.0.CURRENCY}), otherwise
 *     {@code path_} and a short hash
 * @param levels the lists the path steps into, outermost first; empty for a field of the entity itself
 * @param leaf the steps after the last list (or from the document when there is none)
 * @param size true for {@code size(...)}: the number of elements of the list the steps name
 */
record DocPath(String text, String id, List<Level> levels, List<Step> leaf, boolean size) {

    /** One list the path steps into: how to reach it from the element above (or the document), and the elements it keeps. */
    record Level(List<Step> reach, Expr keep, String name) {}

    /** One step inside a level: a field, an index, or a filter followed by an index (the first matching element). */
    record Step(String field, Integer index, Expr filter) {}

    private static final ElCompiler EL = new ElCompiler(2_000);

    /** The grain the path's lists make: {@code ""} for the entity, {@code LEGS}, {@code LEGS.CASH_FLOWS}. */
    String grain() {
        return String.join(".", grainLevels().stream().map(Level::name).toList());
    }

    /** The lists that make the grain (for {@code size(...)}, all but the counted list). */
    List<Level> grainLevels() {
        return size ? levels.subList(0, levels.size() - 1) : levels;
    }

    /** {@code size(...)}'s count from the deepest grain element (or the document). */
    String count(String element) {
        return "len(" + list(levels.get(levels.size() - 1), element, text) + ")";
    }

    /** True when the path is a plain top-level field ({@code $.PRODUCT_TYPE}): a promoted column may hold it. */
    boolean topLevel() {
        return levels.isEmpty() && !size && leaf.size() == 1 && leaf.get(0).field() != null;
    }

    /** True when a text names a document path rather than a plain field id. */
    static boolean looksLikePath(String s) {
        return s != null && (s.startsWith("$") || s.contains("[") || s.contains("(") || s.startsWith("@"));
    }

    static DocPath parse(String text) {
        Expr e;
        try {
            e = EL.compile(text.strip());
        } catch (ElException ex) {
            throw PivotView.bad("'" + text + "' is not a Rachana path: " + ex.getMessage());
        }
        boolean size = false;
        if (e instanceof Expr.Call c) {
            if (!"size".equals(c.name()) || c.args().size() != 1) {
                throw PivotView.bad("'" + text + "': " + c.name() + "() has no SQL form for a pivot; a field may be a path or size(path)");
            }
            size = true;
            e = c.args().get(0);
        }
        List<Object> steps = new ArrayList<>();
        linear(e, steps, text);
        List<Level> levels = new ArrayList<>();
        List<Step> current = new ArrayList<>();
        Expr pendingFilter = null;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Object s = steps.get(i);
            if (s instanceof Expr.Filter f) {
                if (pendingFilter != null) {
                    throw PivotView.bad("'" + text + "': write one filter per list");
                }
                pendingFilter = f.predicate();
                continue;
            }
            if ("*".equals(s)) {
                String name = current.stream().map(st -> st.field() != null ? st.field() : String.valueOf(st.index())).reduce((a, b) -> b).orElse("");
                levels.add(new Level(List.copyOf(current), pendingFilter, name));
                names.add(name);
                current.clear();
                pendingFilter = null;
                continue;
            }
            if (s instanceof Integer n) {
                current.add(new Step(null, n, pendingFilter));
                pendingFilter = null;
                continue;
            }
            if (pendingFilter != null) {
                throw PivotView.bad("'" + text + "': a filter is followed by [*] (every kept element) or [n] (one of them), not by a field");
            }
            current.add(new Step((String) s, null, null));
        }
        if (pendingFilter != null && !size) {
            throw PivotView.bad("'" + text + "': a filtered list is many values; end it with [*] (a finer grain), [n] or wrap it in size()");
        }
        if (size) {
            if (!levels.isEmpty() && current.isEmpty()) {
                throw PivotView.bad("'" + text + "': size() counts a list; write size($.LEGS), not size($.LEGS[*])");
            }
            // the counted list is the last steps; a filter on it (size($.LEGS[?...])) is kept as the level's filter
            levels.add(new Level(List.copyOf(current), pendingFilter, "size"));
            return new DocPath(text.strip(), id(text, levels.subList(0, levels.size() - 1), List.of(), true), List.copyOf(levels), List.of(), true);
        }
        if (current.isEmpty() && levels.isEmpty()) {
            throw PivotView.bad("'" + text + "' names the whole document: name a field ($.PRODUCT_TYPE)");
        }
        return new DocPath(text.strip(), id(text, levels, current, false), List.copyOf(levels), List.copyOf(current), false);
    }

    /** The path as a list: field names, {@code "*"} for each, integers for an index, and the filters. */
    private static void linear(Expr e, List<Object> out, String text) {
        switch (e) {
            case Expr.Root r -> { }
            case Expr.RowOrRoot r -> { }
            case Expr.Field f -> {
                linear(f.target(), out, text);
                out.add(f.name());
            }
            case Expr.Pluck p -> {
                linear(p.list(), out, text);
                out.add(p.name());
            }
            case Expr.Each x -> {
                linear(x.target(), out, text);
                out.add("*");
            }
            case Expr.At a -> {
                linear(a.target(), out, text);
                if (a.index() instanceof Expr.Lit l && l.value() instanceof Number n) {
                    out.add(n.intValue());
                } else if (a.index() instanceof Expr.Lit l && l.value() instanceof String s) {
                    out.add(s);
                } else {
                    throw PivotView.bad("'" + text + "': an index is a number ([0]) or a name (['NAME'])");
                }
            }
            case Expr.Filter f -> {
                linear(f.target(), out, text);
                out.add(f);
            }
            default -> throw PivotView.bad("'" + text + "' is not a path a pivot can read: write $.FIELD, $.LIST[*].FIELD, $.LIST[0].FIELD, "
                    + "$.LIST[?@.F == 'V'][*].FIELD or size($.LIST)");
        }
    }

    private static String id(String text, List<Level> levels, List<Step> leaf, boolean size) {
        boolean plain = !size && levels.stream().allMatch(l -> l.keep() == null && l.reach().stream().allMatch(DocPath::plainStep))
                && leaf.stream().allMatch(DocPath::plainStep);
        if (plain) {
            List<String> parts = new ArrayList<>();
            levels.forEach(l -> l.reach().forEach(s -> parts.add(s.field() != null ? s.field() : String.valueOf(s.index()))));
            leaf.forEach(s -> parts.add(s.field() != null ? s.field() : String.valueOf(s.index())));
            String dotted = String.join(".", parts);
            if (dotted.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                return dotted;
            }
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.strip().getBytes(StandardCharsets.UTF_8));
            return "path_" + HexFormat.of().formatHex(h).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean plainStep(Step s) {
        return s.filter() == null && (s.field() == null || s.field().matches("[A-Za-z_][A-Za-z0-9_]*"));
    }

    // ---- SQL ------------------------------------------------------------------------------------------------------------------

    static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String key(String name) {
        return "'$.\"" + name.replace("'", "''").replace("\"", "\\\"") + "\"'";
    }

    /** The JSON value the steps reach from {@code base} (a JSON expression). */
    static String reach(String base, List<Step> steps, String text) {
        String e = base;
        for (Step s : steps) {
            if (s.filter() != null) {
                e = "(list_filter(CAST(" + e + " AS JSON[]), x -> " + predicate(s.filter(), "x", text) + "))[" + (s.index() + 1) + "]";
            } else if (s.field() != null) {
                e = "json_extract(" + e + ", " + key(s.field()) + ")";
            } else {
                e = "json_extract(" + e + ", '$[" + s.index() + "]')";
            }
        }
        return e;
    }

    /** A list level's elements as a SQL list (JSON[]) from {@code base}, its filter applied. */
    static String list(Level l, String base, String text) {
        String arr = "CAST(" + reach(base, l.reach(), text) + " AS JSON[])";
        return l.keep() == null ? arr : "list_filter(" + arr + ", x -> " + predicate(l.keep(), "x", text) + ")";
    }

    /** A condition over one element ({@code @}) bound to the SQL variable {@code v}. */
    static String predicate(Expr p, String v, String text) {
        return switch (p) {
            case Expr.And a -> "(" + predicate(a.l(), v, text) + " AND " + predicate(a.r(), v, text) + ")";
            case Expr.Or o -> "(" + predicate(o.l(), v, text) + " OR " + predicate(o.r(), v, text) + ")";
            case Expr.Not n -> "(NOT " + predicate(n.e(), v, text) + ")";
            case Expr.Binary b when List.of("==", "!=", "<", "<=", ">", ">=").contains(b.op()) -> compare(b, v, text);
            default -> throw PivotView.bad("'" + text + "': a filter compares the element's fields with values (@.LEG_TYPE == 'FIXED', "
                    + "@.NOTIONAL > 1000000), joined by && and ||");
        };
    }

    private static String compare(Expr.Binary b, String v, String text) {
        String l = operand(b.l(), v, text);
        String r = operand(b.r(), v, text);
        boolean numeric = b.l() instanceof Expr.Lit ll && ll.value() instanceof Number || b.r() instanceof Expr.Lit rl && rl.value() instanceof Number;
        if (numeric) {
            l = b.l() instanceof Expr.Lit ? l : "TRY_CAST(" + l + " AS DOUBLE)";
            r = b.r() instanceof Expr.Lit ? r : "TRY_CAST(" + r + " AS DOUBLE)";
        }
        String op = switch (b.op()) {
            case "==" -> "=";
            case "!=" -> "<>";
            default -> b.op();
        };
        return "(" + l + " " + op + " " + r + ")";
    }

    private static String operand(Expr e, String v, String text) {
        if (e instanceof Expr.Lit l) {
            Object x = l.value();
            return x instanceof Number n ? n.toString() : x == null ? "NULL" : lit(String.valueOf(x));
        }
        List<String> names = new ArrayList<>();
        Expr at = e;
        while (at instanceof Expr.Field f) {
            names.add(0, f.name());
            at = f.target();
        }
        if (!(at instanceof Expr.Row) || names.isEmpty()) {
            throw PivotView.bad("'" + text + "': inside a filter, name the element's fields with @ (@.LEG_TYPE)");
        }
        String expr = v;
        for (String n : names) {
            expr = "json_extract(" + expr + ", " + key(n) + ")";
        }
        return "json_extract_string(" + expr + ", '$')";
    }

    /** The leaf's value from the deepest element (or the document), as text or as a number. */
    String value(String element, boolean number) {
        String json = reach(element, leaf, text);
        String s = "json_extract_string(" + json + ", '$')";
        return number ? "TRY_CAST(" + s + " AS DOUBLE)" : s;
    }

    /** A short readable label: {@code Legs › Notional}. */
    String label() {
        if (!id.startsWith("path_")) {
            List<String> parts = new ArrayList<>();
            for (String p : id.split("\\.")) {
                parts.add(p.isEmpty() ? p : p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            return String.join(" › ", parts);
        }
        return text;
    }
}
