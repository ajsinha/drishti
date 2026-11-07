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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.ScopeExpr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The basket as a sheet (VAR_GUIDE.md section 3.5): a column per kind of part (trade, position, portfolio, book, desk, each configured
 * hierarchy's word such as the limit letter, counterparty), the Op column ({@code +} or {@code −}) of each row, and <i>Start from the
 * whole bank</i>. The parts travel and are kept as lists, one per column, aligned by row, never as one long scope text; the scope the
 * engine computes is composed from them: everything added (or {@code BANK} when the whole bank is ticked), then everything taken away
 * ({@code A + B - C}).
 *
 * <p>A cell holds one id. A leading {@code -} (or {@code −}) takes it away whatever its row's Op says; a leading {@code KIND:} names its
 * kind in any column ({@code NODE:MGMT~DESK-RATES} in the Desk column, {@code TRD:MX-1} in the Trade column).
 */
final class VarBasketSheet {

    /** The most rows a sheet holds: every column takes 10,000 ids at least. */
    static final int MAX_ROWS = 20_000;
    /** The longest id a cell may hold. */
    static final int MAX_ID = 200;
    /** The Op column's key in a sheet. */
    static final String OP = "op";
    /** The words a cell may name with a prefix ({@code DESK:RATES}), besides the configured hierarchies' words. */
    static final Set<String> CELL_WORDS = Set.of("TRD", "POS", "PORT", "BOOK", "DESK", "NODE", "DIV", "LE", "CPTY", "BASKET");
    /** The words whose ids the scope text lists together ({@code TRD:a,b}). */
    static final Set<String> LISTED = Set.of("TRD", "POS", "CPTY");
    private static final Pattern PREFIX = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*):(.*)$", Pattern.DOTALL);
    private static final Pattern BAD = Pattern.compile("[\\s,()]");

    /** One filled cell: where it is, what it says, the kind and id it names, whether it takes away, and why it cannot be read (or null). */
    record Cell(String column, int row, String text, String word, String id, boolean minus, String problem) {
        String ref() {
            return word + ":" + id;
        }
    }

    private final boolean wholeBank;
    private final List<String> columns;
    private final Map<String, List<String>> values;
    private final List<String> ops;
    private final int rows;
    private final Set<String> configured;

    private VarBasketSheet(boolean wholeBank, List<String> columns, Map<String, List<String>> values, List<String> ops, Set<String> configured) {
        this.wholeBank = wholeBank;
        this.columns = columns;
        this.values = values;
        this.configured = configured;
        int n = ops.size();
        for (List<String> v : values.values()) {
            n = Math.max(n, v.size());
        }
        while (n > 0 && emptyRow(values, n - 1)) {
            n--;                                                      // trailing empty rows are not kept
        }
        this.rows = n;
        this.ops = new ArrayList<>(ops.subList(0, Math.min(ops.size(), n)));
    }

    private static boolean emptyRow(Map<String, List<String>> values, int r) {
        for (List<String> v : values.values()) {
            if (r < v.size() && !v.get(r).isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** The sheet's columns in order: trade, position, portfolio, book, desk, the configured hierarchies' words (sorted), counterparty. */
    static List<String> columns(Collection<String> configured) {
        List<String> out = new ArrayList<>(List.of("TRD", "POS", "PORT", "BOOK", "DESK"));
        for (String w : new TreeSet<>(configured)) {
            if (!out.contains(w) && !CELL_WORDS.contains(w)) {
                out.add(w);
            }
        }
        out.add("CPTY");
        return out;
    }

    /** A column's heading: Trade, Position, ..., a configured hierarchy's title in the singular (Limit letters: Limit letter). */
    static String title(String column, java.util.function.Function<String, String> hierarchyTitle) {
        return switch (column) {
            case "TRD" -> "Trade";
            case "POS" -> "Position";
            case "PORT" -> "Portfolio";
            case "BOOK" -> "Book";
            case "DESK" -> "Desk";
            case "CPTY" -> "Counterparty";
            default -> {
                String t = hierarchyTitle == null ? null : hierarchyTitle.apply(column);
                yield t == null || t.isBlank() ? column : t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
            }
        };
    }

    /**
     * Reads a sheet as sent or stored: {@code {"wholeBank": false, "op": ["+", "-"], "TRD": ["MX-1", "MX-2"], "DESK": ["", "DESK-FX"]}}.
     * A column the sheet does not have is refused in words; more than {@link #MAX_ROWS} rows is too large.
     */
    @SuppressWarnings("unchecked")
    static VarBasketSheet read(Object sheet, Set<String> configured) {
        if (!(sheet instanceof Map<?, ?> m)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a sheet is an object: {\"wholeBank\": false, \"op\": [...], \"TRD\": [...], ...}");
        }
        Set<String> words = configured == null ? Set.of() : configured;
        List<String> cols = columns(words);
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<String> ops = List.of();
        boolean bank = false;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.equals("wholeBank")) {
                bank = Boolean.TRUE.equals(e.getValue()) || "true".equalsIgnoreCase(String.valueOf(e.getValue()));
                continue;
            }
            if (k.equals("columns") || k.equals("rows")) {
                continue;                                             // what an answer adds; not part of the sheet
            }
            List<String> list = list(k, e.getValue());
            if (k.equals(OP)) {
                ops = list;
                continue;
            }
            String col = k.toUpperCase(Locale.ROOT);
            if (!cols.contains(col)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the sheet has no column " + k + ": its columns are " + OP + ", "
                        + String.join(", ", cols));
            }
            values.put(col, list);
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (String c : cols) {
            if (values.containsKey(c)) {
                ordered.put(c, values.get(c));
            }
        }
        return new VarBasketSheet(bank, cols, ordered, ops, words);
    }

    private static List<String> list(String column, Object v) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the column " + column + " is a list of ids, one a row");
        }
        if (l.size() > MAX_ROWS) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the column " + column + " holds " + l.size() + " rows: a sheet holds at most "
                    + MAX_ROWS + " (split the basket in two)");
        }
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            out.add(o == null ? "" : String.valueOf(o));
        }
        return out;
    }

    boolean wholeBank() {
        return wholeBank;
    }

    int rows() {
        return rows;
    }

    List<String> columns() {
        return columns;
    }

    /** A row's Op: {@code -} when it takes away, else {@code +}. */
    String op(int row) {
        return row < ops.size() && minus(ops.get(row)) ? "-" : "+";
    }

    private static boolean minus(String s) {
        String t = s == null ? "" : s.strip();
        return t.startsWith("-") || t.startsWith("−") || t.startsWith("–");
    }

    /** Every filled cell, column by column, each read: its kind, its id, + or −, and why it cannot be read. */
    List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : values.entrySet()) {
            List<String> v = e.getValue();
            for (int r = 0; r < Math.min(v.size(), rows); r++) {
                String raw = v.get(r);
                if (!raw.isBlank()) {
                    out.add(cell(e.getKey(), r, raw, op(r), configured));
                }
            }
        }
        return out;
    }

    /** How many cells are filled. */
    int parts() {
        int n = 0;
        for (List<String> v : values.values()) {
            for (int r = 0; r < Math.min(v.size(), rows); r++) {
                if (!v.get(r).isBlank()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** One cell read: a leading minus takes away, a leading plus adds, a leading {@code KIND:} names its kind. */
    static Cell cell(String column, int row, String raw, String op, Set<String> configured) {
        String t = raw.replace(' ', ' ').strip();
        boolean minus = "-".equals(op);
        if (minus(t)) {
            minus = true;
            t = t.substring(1).strip();
        } else if (t.startsWith("+")) {
            t = t.substring(1).strip();
        }
        String word = column;
        Matcher m = PREFIX.matcher(t);
        if (m.matches()) {
            String w = m.group(1).toUpperCase(Locale.ROOT);
            if (CELL_WORDS.contains(w) || configured.contains(w)) {
                word = w;
                t = m.group(2).strip();
            }
        }
        String problem = null;
        if (t.isEmpty()) {
            problem = "names nothing";
        } else if (BAD.matcher(t).find()) {
            problem = "one id a cell, without spaces, commas or brackets (paste a column to fill many rows)";
        } else if (t.length() > MAX_ID) {
            problem = "an id is at most " + MAX_ID + " characters";
        }
        return new Cell(column, row, raw, word, t, minus, problem);
    }

    /** The scope the sheet stands for, from every readable cell; empty when nothing is added and the whole bank is not ticked. */
    String scope() {
        return compose(cells(), wholeBank);
    }

    /**
     * Scope text from cells: the parts added joined by {@code +} (a list per listed kind: {@code TRD:a,b}), or {@code BANK} when the
     * whole bank is ticked, then each part taken away after a {@code -}. Cells that cannot be read are left out.
     */
    static String compose(List<Cell> cells, boolean wholeBank) {
        List<String> plus = terms(cells, false);
        List<String> minus = terms(cells, true);
        if (wholeBank) {
            plus = List.of("BANK");
        }
        if (plus.isEmpty()) {
            return "";
        }
        StringBuilder s = new StringBuilder(String.join(" + ", plus));
        for (String m : minus) {
            s.append(" - ").append(m);
        }
        return s.toString();
    }

    private static List<String> terms(List<Cell> cells, boolean minus) {
        Map<String, Set<String>> lists = new LinkedHashMap<>();
        Set<String> refs = new LinkedHashSet<>();
        for (Cell c : cells) {
            if (c.problem() != null || c.minus() != minus) {
                continue;
            }
            if (LISTED.contains(c.word())) {
                lists.computeIfAbsent(c.word(), k -> new LinkedHashSet<>()).add(c.id());
            } else {
                refs.add(c.ref());
            }
        }
        List<String> out = new ArrayList<>();
        lists.forEach((w, ids) -> out.add(w + ":" + String.join(",", ids)));
        out.addAll(refs);
        return out;
    }

    /** The sheet as it is kept and sent: whole bank, the Op column and each filled column, cut to the filled rows. */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wholeBank", wholeBank);
        List<String> op = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            op.add(op(r));
        }
        m.put(OP, op);
        values.forEach((c, v) -> {
            if (v.stream().anyMatch(x -> !x.isBlank())) {
                List<String> cut = new ArrayList<>(v.subList(0, Math.min(v.size(), rows)));
                while (cut.size() < rows) {
                    cut.add("");
                }
                m.put(c, cut);
            }
        });
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------------- from a scope

    /**
     * A scope's top-level terms laid into a sheet (an ad-hoc scope opened in it, a portfolio's definition copied into it): each kind in
     * its column, the parts added first and those taken away below them, column by column; a {@code BANK} added ticks the whole bank.
     * Terms the sheet has no column for (a rule, a capture, a nested combination) go to {@code unplaced}.
     */
    static VarBasketSheet fromTerms(List<VarSelection.Term> terms, Set<String> configured, List<String> unplaced) {
        Set<String> words = configured == null ? Set.of() : configured;
        List<String> cols = columns(words);
        boolean bank = false;
        Map<String, List<String>> plus = new LinkedHashMap<>();
        Map<String, List<String>> minus = new LinkedHashMap<>();
        for (VarSelection.Term t : terms) {
            boolean add = t.op().equals("+");
            if (!(t.expr() instanceof ScopeExpr.Ref r)) {
                unplaced.add((add ? "+ " : "- ") + t.expr());
                continue;
            }
            if (r.word().equals("BANK") && add) {
                bank = true;
                continue;
            }
            String col = columnOf(r.word(), words);
            if (col == null) {
                unplaced.add((add ? "+ " : "- ") + r);
                continue;
            }
            Map<String, List<String>> side = add ? plus : minus;
            List<String> ids = LISTED.contains(r.word()) ? r.ids() : List.of(r.arg());
            for (String id : ids) {
                side.computeIfAbsent(col, k -> new ArrayList<>()).add(r.word().equals(col) ? id : r.word() + ":" + id);
            }
        }
        int top = plus.values().stream().mapToInt(List::size).max().orElse(0);
        int low = minus.values().stream().mapToInt(List::size).max().orElse(0);
        if (top + low > MAX_ROWS) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the scope needs " + (top + low) + " rows: a sheet holds at most " + MAX_ROWS);
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String c : cols) {
            List<String> a = plus.getOrDefault(c, List.of());
            List<String> b = minus.getOrDefault(c, List.of());
            if (a.isEmpty() && b.isEmpty()) {
                continue;
            }
            List<String> v = new ArrayList<>(top + low);
            v.addAll(a);
            while (v.size() < top) {
                v.add("");
            }
            v.addAll(b);
            values.put(c, v);
        }
        List<String> ops = new ArrayList<>(top + low);
        for (int i = 0; i < top + low; i++) {
            ops.add(i < top ? "+" : "-");
        }
        return new VarBasketSheet(bank, cols, values, ops, words);
    }

    /** The column a scope word's ids go in: its own, a portfolio's for a basket, the desk's for a node, a division or a legal entity. */
    static String columnOf(String word, Set<String> configured) {
        return switch (word) {
            case "TRD", "POS", "PORT", "BOOK", "DESK", "CPTY" -> word;
            case "BASKET" -> "PORT";
            case "NODE", "DIV", "LE" -> "DESK";
            default -> configured.contains(word) ? word : null;
        };
    }
}
