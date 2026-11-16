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
package com.ash.drishti.server.data;

import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Data view's simple selection, deliberately not SQL (docs/guides/DATA_GUIDE.md "The Data view"): the columns to take (all when none
 * is named) and filter lines {@code attribute operator value}, every line joined by AND. Operators: {@code =} (equals), {@code !=} (not
 * equals), {@code in} (one of, values separated by commas), {@code contains}, {@code starts} (starts with), {@code >}, {@code <},
 * {@code between} ({@code a and b} or {@code a,b}), {@code is empty}, {@code is not empty}. No joins, no grouping, no expressions.
 *
 * <p>The same text is typed in the CLI ({@code --where "DESK = DESK-COMM"}), carried in the console's address ({@code where=...}) and read
 * here. A selection reads back in plain words ({@link #words}) and as a SQL-like line ({@link #sql}) shown for reference only. The lines a
 * connector can apply in its column read ({@link #pushable}) go to it as {@link ScanRequest.Filter}s; every line is checked again on each
 * row ({@link #matches}), so a connector that applies none still answers exactly.
 */
public final class DataSelection {

    /** The operators, in the words the screen shows. */
    public enum Op {
        EQ("equals", "="), NE("not equals", "!="), IN("one of", "in"), CONTAINS("contains", "contains"), STARTS("starts with", "starts"),
        GT("greater than", ">"), LT("less than", "<"), BETWEEN("between", "between"), EMPTY("is empty", "is empty"),
        NOT_EMPTY("is not empty", "is not empty");

        public final String words;
        public final String token;

        Op(String words, String token) {
            this.words = words;
            this.token = token;
        }

        /** True for the operators that take no value. */
        public boolean unary() {
            return this == EMPTY || this == NOT_EMPTY;
        }
    }

    /** One filter line: the attribute, the operator and its values (none, one, two for between, any number for one of). */
    public record Line(String field, Op op, List<String> values) {
        public Line {
            values = List.copyOf(values);
        }

        /** The line in the form it is typed ({@code DESK = DESK-COMM}). */
        public String text() {
            return switch (op) {
                case EMPTY, NOT_EMPTY -> field + " " + op.token;
                case IN -> field + " in " + String.join(",", values);
                case BETWEEN -> field + " between " + values.get(0) + " and " + values.get(1);
                default -> field + " " + op.token + " " + values.get(0);
            };
        }
    }

    /** The most filter lines one selection may carry. */
    public static final int MAX_LINES = 50;

    private static final Pattern LINE = Pattern.compile(
            "(?i)^\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*(is\\s+not\\s+empty|is\\s+empty|not\\s+equals|equals|one\\s+of|starts\\s+with|starts|contains"
                    + "|between|greater\\s+than|less\\s+than|!=|<>|=|>|<|in\\b)\\s*(.*)$");

    private final List<String> columns;
    private final List<Line> lines;

    public DataSelection(List<String> columns, List<Line> lines) {
        this.columns = List.copyOf(columns);
        this.lines = List.copyOf(lines);
    }

    /** The selection from the request: {@code columns} (comma-separated, or repeated) and each {@code where} line. */
    public static DataSelection parse(List<String> columns, List<String> where) {
        Set<String> cols = new LinkedHashSet<>();
        for (String c : columns == null ? List.<String>of() : columns) {
            for (String one : c.split(",")) {
                if (!one.isBlank()) {
                    cols.add(one.strip());
                }
            }
        }
        List<Line> lines = new ArrayList<>();
        for (String w : where == null ? List.<String>of() : where) {
            if (w != null && !w.isBlank()) {
                lines.add(line(w));
            }
        }
        if (lines.size() > MAX_LINES) {
            throw bad("at most " + MAX_LINES + " filter lines");
        }
        return new DataSelection(List.copyOf(cols), lines);
    }

    /** One line as typed; a {@code 400 DRS-5001} naming what is wrong with it. */
    public static Line line(String text) {
        Matcher m = LINE.matcher(text);
        if (!m.matches()) {
            throw bad("cannot read the filter line '" + text.strip() + "': write attribute operator value (DESK = DESK-COMM, MTM > 1000000, "
                    + "PRODUCT_TYPE in ComSwap,ComFut, NAME contains bank, MTM between 1 and 5, COUNTERPARTY is empty)");
        }
        String field = m.group(1);
        String word = m.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String rest = m.group(3).strip();
        Op op = switch (word) {
            case "=", "equals" -> Op.EQ;
            case "!=", "<>", "not equals" -> Op.NE;
            case "in", "one of" -> Op.IN;
            case "contains" -> Op.CONTAINS;
            case "starts", "starts with" -> Op.STARTS;
            case ">", "greater than" -> Op.GT;
            case "<", "less than" -> Op.LT;
            case "between" -> Op.BETWEEN;
            case "is empty" -> Op.EMPTY;
            default -> Op.NOT_EMPTY;
        };
        if (op.unary()) {
            if (!rest.isEmpty()) {
                throw bad("'" + field + " " + op.token + "' takes no value");
            }
            return new Line(field, op, List.of());
        }
        if (rest.isEmpty()) {
            throw bad("the filter line '" + text.strip() + "' has no value");
        }
        List<String> values = new ArrayList<>();
        if (op == Op.IN) {
            for (String v : rest.split(",")) {
                if (!v.isBlank()) {
                    values.add(unquote(v.strip()));
                }
            }
        } else if (op == Op.BETWEEN) {
            String[] two = rest.split("(?i)\\s+and\\s+|\\s*,\\s*");
            if (two.length != 2 || two[0].isBlank() || two[1].isBlank()) {
                throw bad("'" + field + " between' takes two values: " + field + " between 1 and 5");
            }
            values.add(unquote(two[0].strip()));
            values.add(unquote(two[1].strip()));
        } else {
            values.add(unquote(rest));
        }
        if (values.isEmpty()) {
            throw bad("the filter line '" + text.strip() + "' has no value");
        }
        return new Line(field, op, values);
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, why);
    }

    /** The columns named (empty: every column the caller may see). */
    public List<String> columns() {
        return columns;
    }

    public List<Line> lines() {
        return lines;
    }

    /** The fields the filter lines read. */
    public Set<String> filterFields() {
        Set<String> out = new LinkedHashSet<>();
        lines.forEach(l -> out.add(l.field()));
        return out;
    }

    // ---- read back -----------------------------------------------------------------------------------------------------------------

    /** The selection in plain words: "From trade-valuation on 2026-09-30: 9 columns, rows where DESK is DESK-COMM, and MTM is greater than 1,000,000." */
    public String words(String dataset, String date, int columnCount, boolean allColumns) {
        StringBuilder b = new StringBuilder("From ").append(dataset).append(date == null ? "" : " on " + date).append(": ");
        b.append(allColumns ? "every column (" + columnCount + ")" : columnCount + (columnCount == 1 ? " column" : " columns"));
        if (lines.isEmpty()) {
            return b.append(", every row.").toString();
        }
        b.append(", rows where ");
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            b.append(i == 0 ? "" : i == lines.size() - 1 ? ", and " : ", ").append(l.field()).append(' ').append(switch (l.op()) {
                case EQ -> "is " + l.values().get(0);
                case NE -> "is not " + l.values().get(0);
                case IN -> "is one of " + orList(l.values());
                case CONTAINS -> "contains " + l.values().get(0);
                case STARTS -> "starts with " + l.values().get(0);
                case GT -> "is greater than " + l.values().get(0);
                case LT -> "is less than " + l.values().get(0);
                case BETWEEN -> "is between " + l.values().get(0) + " and " + l.values().get(1);
                case EMPTY -> "is empty";
                case NOT_EMPTY -> "is not empty";
            });
        }
        return b.append('.').toString();
    }

    private static String orList(List<String> values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        return String.join(", ", values.subList(0, values.size() - 1)) + " or " + values.get(values.size() - 1);
    }

    /** The SQL-like line, for reference only: it is never run. */
    public String sql(String dataset, String date, List<String> chosen) {
        StringBuilder b = new StringBuilder("SELECT ").append(chosen.isEmpty() ? "*" : String.join(", ", chosen));
        b.append("\nFROM ").append(dataset).append(date == null ? "" : "  -- " + date);
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            b.append(i == 0 ? "\nWHERE " : " AND ").append(l.field()).append(switch (l.op()) {
                case EQ -> " = " + lit(l.values().get(0));
                case NE -> " <> " + lit(l.values().get(0));
                case IN -> " IN (" + String.join(", ", l.values().stream().map(DataSelection::lit).toList()) + ")";
                case CONTAINS -> " LIKE " + lit("%" + l.values().get(0) + "%");
                case STARTS -> " LIKE " + lit(l.values().get(0) + "%");
                case GT -> " > " + lit(l.values().get(0));
                case LT -> " < " + lit(l.values().get(0));
                case BETWEEN -> " BETWEEN " + lit(l.values().get(0)) + " AND " + lit(l.values().get(1));
                case EMPTY -> " IS NULL";
                case NOT_EMPTY -> " IS NOT NULL";
            });
        }
        return b.toString();
    }

    private static String lit(String v) {
        return number(v) != null ? v : "'" + v.replace("'", "''") + "'";
    }

    // ---- the connector's part and the row check ------------------------------------------------------------------------------------

    /**
     * The lines a connector's column read applies ({@code =}, {@code !=}, one of, {@code >}, {@code <}, between); a value is given as a number
     * only for a field in {@code numeric} (the contract's number fields), as text otherwise, so an id of digits still matches its text.
     */
    public List<ScanRequest.Filter> pushable(Set<String> numeric) {
        List<ScanRequest.Filter> out = new ArrayList<>();
        for (Line l : lines) {
            java.util.function.Function<String, Object> t = numeric.contains(l.field()) ? DataSelection::typed : v -> v;
            switch (l.op()) {
                case EQ -> out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.EQ, List.of(t.apply(l.values().get(0)))));
                case NE -> out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.NE, List.of(t.apply(l.values().get(0)))));
                case IN -> out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.IN, l.values().stream().map(t).toList()));
                case GT -> out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.GT, List.of(t.apply(l.values().get(0)))));
                case LT -> out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.LT, List.of(t.apply(l.values().get(0)))));
                case BETWEEN -> {
                    out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.GE, List.of(t.apply(l.values().get(0)))));
                    out.add(new ScanRequest.Filter(l.field(), ScanRequest.Op.LE, List.of(t.apply(l.values().get(1)))));
                }
                default -> { }
            }
        }
        return out;
    }

    /** True when every line holds for this row ({@code value} gives a field's value: a Number, a String, a Boolean or null). */
    public boolean matches(java.util.function.Function<String, Object> value) {
        for (Line l : lines) {
            if (!holds(l, value.apply(l.field()))) {
                return false;
            }
        }
        return true;
    }

    static boolean holds(Line l, Object v) {
        boolean empty = v == null || v instanceof String s && s.isEmpty();
        if (l.op() == Op.EMPTY) {
            return empty;
        }
        if (l.op() == Op.NOT_EMPTY) {
            return !empty;
        }
        if (v == null) {
            return false;                                         // as the connectors' column filter: a missing value matches no comparison
        }
        return switch (l.op()) {
            case EQ -> compare(v, l.values().get(0)) == 0;
            case NE -> compare(v, l.values().get(0)) != 0;
            case IN -> l.values().stream().anyMatch(x -> compare(v, x) == 0);
            case CONTAINS -> text(v).toLowerCase(Locale.ROOT).contains(l.values().get(0).toLowerCase(Locale.ROOT));
            case STARTS -> text(v).toLowerCase(Locale.ROOT).startsWith(l.values().get(0).toLowerCase(Locale.ROOT));
            case GT -> compare(v, l.values().get(0)) > 0;
            case LT -> compare(v, l.values().get(0)) < 0;
            case BETWEEN -> compare(v, l.values().get(0)) >= 0 && compare(v, l.values().get(1)) <= 0;
            default -> false;
        };
    }

    /** Numbers as numbers (when both sides are), anything else as text: the rule of the connectors' column filter. */
    static int compare(Object v, String typed) {
        Double n = number(typed);
        if (n != null) {
            Double x = v instanceof Number num ? Double.valueOf(num.doubleValue()) : number(text(v));
            if (x != null) {
                return Double.compare(x, n);
            }
        }
        return text(v).compareTo(typed);
    }

    static String text(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(v);
    }

    private static Object typed(String v) {
        Double n = number(v);
        return n == null ? v : n;
    }

    static Double number(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip().replace(",", "").replace("_", "");
        if (!s.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
