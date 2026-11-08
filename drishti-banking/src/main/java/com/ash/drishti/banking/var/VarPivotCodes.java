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
package com.ash.drishti.banking.var;

import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.banking.compute.var.Columns;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The VaR provider's dimensions as codes per trade (PIVOT_GUIDE.md section 29.3): for one book and one dimension, each trade ordinal's value
 * as an index into the dimension's values ({@code -1}: no value, the blank member). The dimensions are the hierarchy levels
 * ({@code MGMT.DESK}, {@code LEGAL.LEGAL_ENTITY}, a configured {@code LL.LIMIT_LETTER}: a node's code, its trades), the positions' columns (a
 * whole position in one value, as a {@code WHERE:} rule selects it), the trades' own columns, {@code RISK_CLASS} (a position's primary class,
 * derived from its product group by {@code drishti.cube.mr.risk-classes}), {@code POSITION} and {@code TRADE}. A value never depends on who
 * asks; who sees which trades is the member set the provider walks. Codes are kept per book (a few, least recently used first out).
 *
 * <p>Each value is also a term of VaR's scope grammar ({@link #term}), so every cell has its scope text: {@code NODE:MGMT~DESK-RATES},
 * {@code WHERE:CURRENCY == 'USD'}, {@code POS:...}, {@code TRD:...}, {@code CPTY:...}; null when the grammar cannot name it (a blank value,
 * a trade column other than the counterparty).
 */
final class VarPivotCodes {

    static final String POSITION = "POSITION";
    static final String TRADE = "TRADE";
    static final String RISK_CLASS = "RISK_CLASS";
    static final String OTHER = "OTHER";

    /** One dimension on one book: a code per ordinal and the values. */
    record Codes(String dim, int[] code, List<String> values, Map<String, Integer> index, Map<String, String> nodeIds) {

        /** The value of an ordinal: its text, or the blank member. */
        String value(int ordinal) {
            int c = ordinal < code.length ? code[ordinal] : -1;
            return c < 0 ? CellKey.BLANK : values.get(c);
        }

        /** The code of a value ({@code -1}: the blank member; {@code -2}: no trade has it). */
        int codeOf(String value) {
            if (CellKey.BLANK.equals(value)) {
                return -1;
            }
            Integer i = index.get(value);
            return i == null ? -2 : i;
        }

        long bytes() {
            return 64L + 4L * code.length + 48L * values.size();
        }
    }

    private final MrCubeProperties props;
    /** The kept codes, least recently used first (guarded by itself; a plain map, no subclass: nothing here is Serializable). */
    private final LinkedHashMap<String, Codes> kept = new LinkedHashMap<>(16, 0.75f, true);
    private static final int KEPT = 12;

    VarPivotCodes(MrCubeProperties props) {
        this.props = props;
    }

    /** The hierarchy levels of a book, {@code H.LEVEL}, each hierarchy's levels from the root down. */
    static List<String> levels(VarBook book) {
        Map<String, Map<String, Integer>> depth = new LinkedHashMap<>();
        for (VarBook.Node n : book.nodes().values()) {
            int d = 0;
            for (String up = n.parent(); up != null && d < 64; d++) {
                VarBook.Node pn = book.node(up);
                if (pn == null) {
                    break;
                }
                up = pn.parent();
            }
            depth.computeIfAbsent(n.hierarchy(), k -> new LinkedHashMap<>()).merge(n.level().toUpperCase(Locale.ROOT), d, Math::min);
        }
        List<String> out = new ArrayList<>();
        depth.forEach((h, ls) -> ls.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> out.add(h + "." + e.getKey())));
        return out;
    }

    /** True when {@code dim} names a hierarchy level ({@code H.LEVEL}). */
    static boolean isLevel(String dim) {
        return dim.indexOf('.') > 0;
    }

    /** A node's value in its level: the id without its hierarchy ({@code MGMT~DESK-RATES} is {@code DESK-RATES}). */
    static String nodeCode(VarBook.Node n) {
        int i = n.id().indexOf('~');
        return i < 0 ? n.id() : n.id().substring(i + 1);
    }

    /** The codes of a dimension on a book (kept by the book's key). */
    Codes codes(VarBook book, String bookKey, String dim) {
        String key = bookKey + "|" + System.identityHashCode(book) + "|" + dim;
        Codes c;
        synchronized (kept) {
            c = kept.get(key);
        }
        if (c == null) {
            c = build(book, dim);
            synchronized (kept) {
                kept.put(key, c);
                java.util.Iterator<String> eldest = kept.keySet().iterator();
                while (kept.size() > KEPT && eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                }
            }
        }
        return c;
    }

    /** Releases every kept code (the engine switched off). */
    void clear() {
        synchronized (kept) {
            kept.clear();
        }
    }

    long bytes() {
        synchronized (kept) {
            return kept.values().stream().mapToLong(Codes::bytes).sum();
        }
    }

    private static final class Values {
        final List<String> values = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();

        int of(String v) {
            if (v == null || v.isBlank()) {
                return -1;
            }
            Integer i = index.get(v);
            if (i == null) {
                i = values.size();
                values.add(v);
                index.put(v, i);
            }
            return i;
        }
    }

    private Codes build(VarBook book, String dim) {
        int n = book.tradeCount();
        int[] code = new int[n];
        Arrays.fill(code, -1);
        Values vals = new Values();
        Map<String, String> nodeIds = new HashMap<>();
        Columns pc = book.positions();
        if (isLevel(dim)) {
            String h = dim.substring(0, dim.indexOf('.'));
            String level = dim.substring(dim.indexOf('.') + 1);
            for (VarBook.Node node : book.nodes().values()) {
                if (node.hierarchy().equalsIgnoreCase(h) && node.level().equalsIgnoreCase(level)) {
                    String v = nodeCode(node);
                    nodeIds.put(v, node.id());
                    int k = vals.of(v);
                    node.members().forEach(o -> {
                        if (o < n) {
                            code[o] = k;
                        }
                    });
                }
            }
        } else if (POSITION.equals(dim)) {
            for (int pi = 0; pi < book.positionCount(); pi++) {
                int k = vals.of(book.positionId(pi));
                book.forEachPositionMember(pi, o -> set(code, o, k));
            }
        } else if (TRADE.equals(dim)) {
            for (int o = 0; o < n; o++) {
                code[o] = vals.of(book.tradeId(o));
            }
        } else if (RISK_CLASS.equals(dim)) {
            Map<String, String> classOf = new HashMap<>();
            props.riskClasses().forEach((c, gs) -> gs.forEach(g -> classOf.put(g.toUpperCase(Locale.ROOT), c)));
            props.riskClasses().keySet().forEach(vals::of);
            vals.of(OTHER);
            for (int pi = 0; pi < book.positionCount(); pi++) {
                String g = pc == null || !pc.has("PRODUCT_GROUP") ? null : pc.text("PRODUCT_GROUP", pi);
                int k = vals.of(g == null ? OTHER : classOf.getOrDefault(g.toUpperCase(Locale.ROOT), OTHER));
                book.forEachPositionMember(pi, o -> set(code, o, k));
            }
        } else if (pc != null && pc.has(dim)) {
            for (int pi = 0; pi < book.positionCount(); pi++) {
                int k = vals.of(pc.text(dim, pi));
                if (k >= 0) {
                    book.forEachPositionMember(pi, o -> set(code, o, k));
                }
            }
        } else if (book.trades().has(dim)) {
            Columns tc = book.trades();
            for (int o = 0; o < n; o++) {
                code[o] = vals.of(tc.text(dim, o));
            }
        } else {
            throw new IllegalArgumentException(dim);
        }
        return new Codes(dim, code, List.copyOf(vals.values), Map.copyOf(vals.index), Map.copyOf(nodeIds));
    }

    private static void set(int[] code, int o, int k) {
        if (o >= 0 && o < code.length) {
            code[o] = k;
        }
    }

    // ---- scope text ----------------------------------------------------------------------------------------------------------------

    /** One coordinate in the scope grammar, or null when the grammar cannot name it. */
    String term(VarBook book, Codes codes, String dim, String value) {
        if (CellKey.BLANK.equals(value) || CellKey.OTHERS.equals(value)) {
            return null;
        }
        if (isLevel(dim)) {
            String id = codes == null ? null : codes.nodeIds().get(value);
            return id == null ? null : "NODE:" + id;
        }
        Columns pc = book.positions();
        return switch (dim) {
            case POSITION -> "POS:" + value;
            case TRADE -> "TRD:" + value;
            case RISK_CLASS -> {
                List<String> all = new ArrayList<>();
                props.riskClasses().values().forEach(all::addAll);
                if (value.equals(OTHER)) {
                    yield all.isEmpty() ? "BANK" : "BANK - (WHERE:PRODUCT_GROUP in (" + quoted(all) + "))";
                }
                List<String> gs = props.riskClasses().get(value);
                yield gs == null ? null : "WHERE:PRODUCT_GROUP in (" + quoted(gs) + ")";
            }
            default -> {
                if (pc != null && pc.has(dim)) {
                    yield "WHERE:" + dim + " == " + quote(value);
                }
                yield dim.equals("COUNTERPARTY") ? "CPTY:" + value : null;
            }
        };
    }

    /** Any of several values of a dimension, as one scope term (a rule's {@code in}, or a union), or null when one cannot be named. */
    String anyOf(VarBook book, Codes codes, String dim, List<String> values) {
        Columns pc = book.positions();
        if (!isLevel(dim) && !Set.of(POSITION, TRADE, RISK_CLASS).contains(dim) && pc != null && pc.has(dim)) {
            if (values.contains(CellKey.BLANK)) {
                return null;
            }
            return "WHERE:" + dim + " in (" + quoted(values) + ")";
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String v : values) {
            String t = term(book, codes, dim, v);
            if (t == null) {
                return null;
            }
            terms.add(t);
        }
        if (terms.size() == 1) {
            return terms.iterator().next();
        }
        return String.join(" + ", terms.stream().map(t -> "(" + t + ")").toList());
    }

    static String quote(String v) {
        return "'" + v.replace("'", "''") + "'";
    }

    static String quoted(List<String> vs) {
        return String.join(", ", vs.stream().map(VarPivotCodes::quote).toList());
    }

    /** Terms intersected, then terms taken away: {@code (A) & (B) - (C)}; {@code BANK} when nothing narrows it. */
    static String compose(List<String> and, List<String> minus) {
        String base;
        if (and.isEmpty()) {
            base = "BANK";
        } else if (and.size() == 1) {
            base = and.get(0);
        } else {
            base = String.join(" & ", and.stream().map(t -> "(" + t + ")").toList());
        }
        if (minus.isEmpty()) {
            return base;
        }
        StringBuilder b = new StringBuilder(and.size() == 1 && !minus.isEmpty() ? "(" + base + ")" : base);
        for (String m : minus) {
            b.append(" - (").append(m).append(')');
        }
        return b.toString();
    }

    /** A set of ordinals made in ascending order, kept as runs (a cell of a few trades in a large date costs its runs, not a bitmap). */
    static final class Runs {
        private int[] pairs = new int[8];
        private int len;
        private int count;

        void add(int o) {
            if (len > 0 && pairs[len - 1] == o) {
                pairs[len - 1] = o + 1;
            } else {
                if (len + 2 > pairs.length) {
                    pairs = Arrays.copyOf(pairs, pairs.length * 2);
                }
                pairs[len++] = o;
                pairs[len++] = o + 1;
            }
            count++;
        }

        int size() {
            return count;
        }

        OrdinalSet build() {
            return count == 0 ? OrdinalSet.empty() : OrdinalSet.ofRuns(Arrays.copyOf(pairs, len));
        }
    }
}
