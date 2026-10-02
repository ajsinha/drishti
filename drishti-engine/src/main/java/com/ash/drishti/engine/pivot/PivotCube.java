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
package com.ash.drishti.engine.pivot;

import com.ash.drishti.rachana.model.PivotSpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The aggregation behind a pivot: rows go in one at a time ({@link #add}); out comes a cube of every row group by every
 * column group, at every level (subtotals and grand totals included), for each value of the arrangement. The console's
 * client engine (pivot-engine.js) builds the same cube from a panel's rows, so one renderer draws both.
 *
 * <p>Cells are keyed {@code <row keys joined by U+001F>U+001E<column keys joined by U+001F>}; the empty prefix on either
 * side is a total. A group beyond {@code 4 ×} the shown limit is not created (its rows still count in the totals), so a
 * field with a million distinct values cannot exhaust the heap. One instance per request; not thread-safe.
 */
public final class PivotCube {

    /** What a missing value groups under. */
    public static final String BLANK = "(blank)";
    static final String US = "\u001f";
    static final String RS = "\u001e";
    /** Significant digits of a number written as a key: a double's 15 reliable ones. */
    static final int KEY_DIGITS = 15;

    /** A row: its value of a field (null when it has none). */
    @FunctionalInterface
    public interface Row {
        Object get(String field);
    }

    private final PivotSpec arrangement;
    private final Map<String, String> labels;
    private final int maxRowKeys;
    private final int maxColumnKeys;
    private final Map<String, Acc[]> cells = new HashMap<>();
    private final Set<String> leafRows = new HashSet<>();
    private final Set<String> leafColumns = new HashSet<>();
    private final Map<String, List<String>> rowKeys = new HashMap<>();
    private final Map<String, List<String>> columnKeys = new HashMap<>();
    private boolean rowsCut;
    private boolean columnsCut;
    private int count;

    /**
     * @param arrangement the rows, columns, values and filters
     * @param labels each field's label (a field without one is shown by its name)
     */
    public PivotCube(PivotSpec arrangement, Map<String, String> labels, int maxRowKeys, int maxColumnKeys) {
        this.arrangement = arrangement;
        this.labels = labels;
        this.maxRowKeys = maxRowKeys;
        this.maxColumnKeys = maxColumnKeys;
    }

    /** Every field the arrangement reads: rows, columns, values and filters, each once. */
    public static List<String> fieldsOf(PivotSpec a) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.addAll(a.rows());
        out.addAll(a.columns());
        a.values().forEach(v -> out.add(v.field()));
        a.filters().forEach(f -> out.add(f.field()));
        return List.copyOf(out);
    }

    /**
     * A value as a group key: text; numbers written plainly (no exponent, whole numbers without a decimal point, at most
     * {@value #KEY_DIGITS} significant digits so binary noise such as 0.30000000000000004 reads 0.3), the same text the
     * client engine (pivot-engine.js) makes of a number; missing or not-a-number as {@link #BLANK}.
     */
    public static String key(Object v) {
        if (v == null || (v instanceof String s && s.isEmpty())) {
            return BLANK;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) {
                return BLANK;
            }
            return plain(v instanceof Float f ? new BigDecimal(Float.toString(f)) : BigDecimal.valueOf(d));
        }
        if (v instanceof BigDecimal b) {
            return plain(b);
        }
        return String.valueOf(v);
    }

    private static String plain(BigDecimal b) {
        BigDecimal r = b.round(new MathContext(KEY_DIGITS, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        return r.signum() == 0 ? "0" : r.toPlainString();
    }

    /** Whether a row passes the arrangement's filters (values kept, or within a range). */
    public boolean accepts(Row row) {
        for (PivotSpec.Filter f : arrangement.filters()) {
            Object v = row.get(f.field());
            if (f.values() != null && !f.values().contains(key(v))) {
                return false;
            }
            boolean ranged = f.min() != null || f.max() != null;
            if (ranged && v == null) {
                return false;                          // a range keeps only rows that have a value
            }
            if ((f.min() != null && compare(v, f.min()) < 0) || (f.max() != null && compare(v, f.max()) > 0)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a row passes the filters and falls in the group given by row and column key prefixes (a drill-down). */
    public boolean matches(Row row, List<String> rowPrefix, List<String> columnPrefix) {
        if (!accepts(row)) {
            return false;
        }
        for (int i = 0; i < rowPrefix.size() && i < arrangement.rows().size(); i++) {
            if (!rowPrefix.get(i).equals(key(row.get(arrangement.rows().get(i))))) {
                return false;
            }
        }
        for (int i = 0; i < columnPrefix.size() && i < arrangement.columns().size(); i++) {
            if (!columnPrefix.get(i).equals(key(row.get(arrangement.columns().get(i))))) {
                return false;
            }
        }
        return true;
    }

    /** A value against a range end: numbers as numbers, anything else (ISO dates) as text; a missing value is out. */
    private static int compare(Object v, Object end) {
        if (v instanceof Number n && end instanceof Number e) {
            return Double.compare(n.doubleValue(), e.doubleValue());
        }
        if (end instanceof Number e) {
            try {
                return Double.compare(Double.parseDouble(String.valueOf(v).trim()), e.doubleValue());
            } catch (NumberFormatException x) {
                return -1;
            }
        }
        return key(v).compareTo(String.valueOf(end));
    }

    /** Adds a row (that passed {@link #accepts}) to every group it belongs to. */
    public void add(Row row) {
        count++;
        int nr = arrangement.rows().size();
        int nc = arrangement.columns().size();
        String[] r = new String[nr];
        String[] c = new String[nc];
        for (int i = 0; i < nr; i++) {
            r[i] = key(row.get(arrangement.rows().get(i)));
        }
        for (int i = 0; i < nc; i++) {
            c[i] = key(row.get(arrangement.columns().get(i)));
        }
        boolean rowOut = admit(r, leafRows, rowKeys, 4 * maxRowKeys);
        boolean colOut = admit(c, leafColumns, columnKeys, 4 * maxColumnKeys);
        rowsCut |= rowOut;
        columnsCut |= colOut;
        String[] rp = prefixes(r, rowOut ? 0 : nr);
        String[] cp = prefixes(c, colOut ? 0 : nc);
        Object[] vals = new Object[arrangement.values().size()];
        for (int v = 0; v < vals.length; v++) {
            vals[v] = row.get(arrangement.values().get(v).field());
        }
        for (String a : rp) {
            for (String b : cp) {
                Acc[] accs = cells.computeIfAbsent(a + RS + b, k -> fresh());
                for (int v = 0; v < vals.length; v++) {
                    accs[v].add(vals[v]);
                }
            }
        }
    }

    /** False when the leaf key is new and the hard cap is reached: the row then counts only in the totals. */
    private static boolean admit(String[] k, Set<String> leaves, Map<String, List<String>> keys, int cap) {
        if (k.length == 0) {
            return false;
        }
        String joined = String.join(US, k);
        if (leaves.contains(joined)) {
            return false;
        }
        if (leaves.size() >= cap) {
            return true;
        }
        leaves.add(joined);
        keys.put(joined, List.of(k));
        return false;
    }

    /** "", "a", "a␟b", … up to {@code depth} levels. */
    private static String[] prefixes(String[] k, int depth) {
        String[] out = new String[depth + 1];
        out[0] = "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) {
                sb.append(US);
            }
            sb.append(k[i]);
            out[i + 1] = sb.toString();
        }
        return out;
    }

    private Acc[] fresh() {
        Acc[] a = new Acc[arrangement.values().size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = new Acc("distinct".equals(arrangement.values().get(i).agg()));
        }
        return a;
    }

    /** Rows added so far. */
    public int count() {
        return count;
    }

    /**
     * The cube (pivot-engine.js reads the same shape).
     *
     * @param source where the rows came from: records, columns or documents
     * @param total rows considered (before filters)
     * @param partial true when rows may be missing (a scan limit, a source that did not answer)
     * @param masked fields the caller's role sees masked
     */
    public Map<String, Object> result(String source, int total, boolean partial, List<String> masked, double elapsedMs) {
        Comparator<List<String>> order = PivotCube::compareKeys;
        List<List<String>> rows = new ArrayList<>(rowKeys.values());
        rows.sort(order);
        List<List<String>> columns = new ArrayList<>(columnKeys.values());
        columns.sort(order);
        boolean moreRows = rowsCut || rows.size() > maxRowKeys;
        boolean moreColumns = columnsCut || columns.size() > maxColumnKeys;
        rows = rows.subList(0, Math.min(rows.size(), maxRowKeys));
        columns = columns.subList(0, Math.min(columns.size(), maxColumnKeys));
        Set<String> rowPrefixes = prefixSet(rows, arrangement.rows().size());
        Set<String> colPrefixes = prefixSet(columns, arrangement.columns().size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", arrangement.rows());
        out.put("columns", arrangement.columns());
        List<Map<String, Object>> values = new ArrayList<>();
        for (PivotSpec.Value v : arrangement.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", v.field());
            m.put("agg", v.agg());
            m.put("show", v.show());
            m.put("label", label(v));
            values.add(m);
        }
        out.put("values", values);
        out.put("rowKeys", rows);
        out.put("columnKeys", columns);
        Map<String, Object> cellOut = new LinkedHashMap<>();
        for (String a : rowPrefixes) {
            for (String b : colPrefixes) {
                Acc[] accs = cells.get(a + RS + b);
                if (accs == null) {
                    continue;
                }
                List<Object> vs = new ArrayList<>(accs.length);
                for (int i = 0; i < accs.length; i++) {
                    vs.add(accs[i].result(arrangement.values().get(i).agg()));
                }
                cellOut.put(a + RS + b, vs);
            }
        }
        out.put("cells", cellOut);
        out.put("count", count);
        out.put("total", total);
        out.put("partial", partial);
        out.put("moreRows", moreRows);
        out.put("moreColumns", moreColumns);
        out.put("masked", masked);
        out.put("source", source);
        out.put("elapsedMs", elapsedMs);
        return out;
    }

    private String label(PivotSpec.Value v) {
        String field = labels.getOrDefault(v.field(), v.field());
        String agg = switch (v.agg()) {
            case "count" -> "Count";
            case "avg" -> "Average";
            case "min" -> "Min";
            case "max" -> "Max";
            case "distinct" -> "Distinct";
            default -> "Sum";
        };
        return agg + " of " + field;
    }

    private static Set<String> prefixSet(List<List<String>> keys, int depth) {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add("");
        for (List<String> k : keys) {
            out.addAll(Arrays.asList(prefixes(k.toArray(String[]::new), Math.min(depth, k.size()))));
        }
        return out;
    }

    /** Keys level by level: numbers by value, text alphabetically (any case), blanks last. */
    public static int compareKeys(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = compareKey(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    static int compareKey(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (BLANK.equals(a) || BLANK.equals(b)) {
            return BLANK.equals(a) ? 1 : -1;
        }
        Double x = number(a);
        Double y = number(b);
        if (x != null && y != null) {
            return Double.compare(x, y);
        }
        if (x != null || y != null) {
            return x != null ? -1 : 1;
        }
        int c = natural(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
        return c != 0 ? c : a.compareTo(b);
    }

    private static final java.util.regex.Pattern RUNS = java.util.regex.Pattern.compile("\\d+|\\D+");

    /** Text in natural order: runs of digits by value, the rest as text ("2-5Y" before "10Y+"), as pivot-engine.js sorts. */
    static int natural(String a, String b) {
        java.util.regex.Matcher x = RUNS.matcher(a);
        java.util.regex.Matcher y = RUNS.matcher(b);
        while (true) {
            boolean hx = x.find();
            boolean hy = y.find();
            if (!hx || !hy) {
                return hx ? 1 : hy ? -1 : 0;
            }
            String p = x.group();
            String q = y.group();
            boolean dx = Character.isDigit(p.charAt(0));
            boolean dy = Character.isDigit(q.charAt(0));
            int c = dx && dy ? new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
    }

    private static Double number(String s) {
        if (s.isEmpty() || !(Character.isDigit(s.charAt(0)) || s.charAt(0) == '-' || s.charAt(0) == '+' || s.charAt(0) == '.')) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One value of one group: count, sum, extremes of its numbers, and its distinct values when asked for. */
    static final class Acc {
        long count;
        long numbers;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        final Set<String> distinct;

        Acc(boolean distinct) {
            this.distinct = distinct ? new HashSet<>() : null;
        }

        void add(Object v) {
            if (v == null) {
                return;
            }
            count++;
            if (distinct != null) {
                distinct.add(key(v));
            }
            if (v instanceof Number n && Double.isFinite(n.doubleValue())) {
                double d = n.doubleValue();
                numbers++;
                sum += d;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }

        Object result(String agg) {
            return switch (agg) {
                case "count" -> count;
                case "distinct" -> (long) distinct.size();
                case "avg" -> numbers == 0 ? null : round(sum / numbers);
                case "min" -> numbers == 0 ? null : round(min);
                case "max" -> numbers == 0 ? null : round(max);
                default -> numbers == 0 ? null : round(sum);
            };
        }

        private static Object round(double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        }
    }
}
