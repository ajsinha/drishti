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
package com.ash.drishti.kernel.api.pivot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shares, ranks and windows over the cells of an answer (RACHANA_REFERENCE.md section F5): a value written around an aggregate,
 * {@code pct_of_total(sum(NOTIONAL))}, is the aggregate computed as every other value, then shown transformed from the finished figures of
 * the answer's cells (ARCHITECTURE.md section 26.11, "show values as"; the computation is {@code core.ShowAs}, beside Top N and Others, never
 * a second pass over rows). This class is the catalogue of those functions and the reading of the written form into the aggregate and its
 * show-as code.
 *
 * <p>The show-as codes: {@code pct-grand}, {@code pct-row}, {@code pct-column}, {@code pct-parent-row}, {@code rank} (as before), and
 * {@code ntile:<n>}, {@code diff:<n>:<order>}, {@code pct-change:<n>:<order>}.
 */
public final class Windows {

    /**
     * A function over an answer's cells, as the catalogue page lists it.
     *
     * @param name its one name
     * @param args its arguments
     * @param description what it means, with its null rule
     * @param example an example on the demo data
     * @param excel the nearest Excel equivalent
     * @param sas the nearest SAS equivalent
     */
    public record Info(String name, String args, String description, String example, String excel, String sas) {}

    /** Every function over an answer's cells, in the order the catalogue lists them. */
    public static final List<Info> FUNCTIONS = List.of(
            new Info("pct_of_total", "agg", "the cell's share of the grand total; empty where the total is 0 or empty",
                    "pct_of_total(sum(NOTIONAL))", "PivotTable: % of Grand Total", "PROC TABULATE PCTSUM"),
            new Info("pct_of_row", "agg", "the cell's share of its row's total; empty where that total is 0 or empty",
                    "pct_of_row(sum(NOTIONAL))", "PivotTable: % of Row Total", "PROC TABULATE ROWPCTSUM"),
            new Info("pct_of_column", "agg", "the cell's share of its column's total; empty where that total is 0 or empty",
                    "pct_of_column(sum(NOTIONAL))", "PivotTable: % of Column Total", "PROC TABULATE COLPCTSUM"),
            new Info("pct_of_parent", "agg", "the cell's share of its parent on the rows (the level above); the grand total's is 1",
                    "pct_of_parent(sum(NOTIONAL))", "PivotTable: % of Parent Row Total", "PROC TABULATE PCTSUM<parent>"),
            new Info("ntile", "agg, n", "the bucket 1..n of the cell among its siblings (the rows under the same parent, in the same column), "
                    + "ascending, ties at their mean rank: SAS's GROUPS=n plus one; empty for the grand total, Others and an empty figure",
                    "ntile(sum(NOTIONAL), 4)", "none (QUARTILE as bounds)", "PROC RANK GROUPS=n TIES=MEAN (0-based)"),
            new Info("diff", "agg, [n], order", "the cell's figure less the figure n members before along the order dimension (rows or "
                    + "columns, members in their natural order); empty for the first n and where either figure is empty",
                    "diff(sum(PNL), 1, TRADE_DATE:month)", "=B3-B2", "DIF<n>"),
            new Info("pct_change", "agg, [n], order", "(figure − figure n members before) / figure n members before along the order "
                    + "dimension; empty for the first n, where either figure is empty and where the earlier is 0",
                    "pct_change(sum(NOTIONAL), 1, TRADE_DATE:month)", "=B3/B2-1", "DIF<n>(x)/LAG<n>(x)"));

    /** A value read around its aggregate: the aggregate's text and the show-as code. */
    public record Wrapped(String inner, String showAs) {}

    private static final Pattern CALL = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*", Pattern.DOTALL);
    private static final Pattern WHOLE = Pattern.compile("[0-9]+");

    private Windows() {}

    /**
     * The value's text read as a function over the answer's cells ({@code pct_of_total(sum(NOTIONAL))}), or null when it is not one; a
     * wrong form is refused in words ({@code DRS-5701}).
     */
    public static Wrapped unwrap(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CALL.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).toLowerCase(Locale.ROOT);
        if (FUNCTIONS.stream().noneMatch(i -> i.name().equals(name))) {
            return null;
        }
        List<String> a = split(m.group(2));
        if (a.isEmpty() || a.get(0).isBlank()) {
            throw PivotView.bad(text.strip() + ": " + name + " takes an aggregate first: " + example(name));
        }
        String inner = a.get(0).strip();
        if (unwrap(inner) != null) {
            throw PivotView.bad(text.strip() + ": one share, rank or window around an aggregate, not one around another");
        }
        return switch (name) {
            case "pct_of_total", "pct_of_row", "pct_of_column" -> {
                one(text, name, a);
                yield new Wrapped(inner, switch (name) {
                    case "pct_of_total" -> "pct-grand";
                    case "pct_of_row" -> "pct-row";
                    default -> "pct-column";
                });
            }
            case "pct_of_parent" -> {
                if (a.size() > 1) {
                    throw PivotView.bad(text.strip() + ": on a pivot the parent is the level above on the rows; a named hierarchy "
                            + "(DEL's parent_of) comes with DEL's move into Rachana (2.9.11.e)");
                }
                yield new Wrapped(inner, "pct-parent-row");
            }
            case "ntile" -> {
                if (a.size() != 2 || !WHOLE.matcher(a.get(1).strip()).matches() || Integer.parseInt(a.get(1).strip()) < 1) {
                    throw PivotView.bad(text.strip() + ": ntile takes an aggregate and a whole number of buckets: " + example(name));
                }
                yield new Wrapped(inner, "ntile:" + Integer.parseInt(a.get(1).strip()));
            }
            default -> {
                int n = 1;
                String order;
                if (a.size() == 3 && WHOLE.matcher(a.get(1).strip()).matches()) {
                    n = Integer.parseInt(a.get(1).strip());
                    order = a.get(2).strip();
                } else if (a.size() == 2) {
                    order = a.get(1).strip();
                } else {
                    throw PivotView.bad(text.strip() + ": " + name + " takes an aggregate, how many members back (1 when left out) and "
                            + "the order dimension: " + example(name));
                }
                if (n < 1 || order.isEmpty() || order.contains(",")) {
                    throw PivotView.bad(text.strip() + ": " + name + " goes 1 or more members back along one dimension: " + example(name));
                }
                yield new Wrapped(inner, ("diff".equals(name) ? "diff:" : "pct-change:") + n + ":" + unquote(order));
            }
        };
    }

    /** Refuses a show-as code the engine does not have, in words. */
    public static void check(String showAs) {
        String s = showAs.toLowerCase(Locale.ROOT);
        boolean ok = switch (s) {
            case "pct-grand", "pct-row", "pct-column", "pct-parent-row", "rank" -> true;
            default -> s.matches("ntile:[1-9][0-9]*") || s.matches("(diff|pct-change):[1-9][0-9]*:.+");
        };
        if (!ok) {
            throw PivotView.bad("show-as '" + showAs + "': pct-grand, pct-row, pct-column, pct-parent-row, rank, ntile:<n>, "
                    + "diff:<n>:<dimension> or pct-change:<n>:<dimension>");
        }
    }

    /** The bucket 1..n of the value at mean rank {@code r} (1-based, ascending) among {@code count}: SAS's ⌊r·n/(count+1)⌋, plus one. */
    public static int ntile(double r, int count, int n) {
        return (int) Math.floor(r * n / (count + 1)) + 1;
    }

    /** {@code now} less {@code before} ({@code pct}: over {@code before}); null where either is null or, for a change, {@code before} is 0. */
    public static Double change(Double now, Double before, boolean pct) {
        if (now == null || before == null) {
            return null;
        }
        if (!pct) {
            return now - before;
        }
        return before == 0 ? null : (now - before) / before;
    }

    private static void one(String text, String name, List<String> a) {
        if (a.size() != 1) {
            throw PivotView.bad(text.strip() + ": " + name + " takes one aggregate: " + example(name));
        }
    }

    private static String example(String name) {
        return FUNCTIONS.stream().filter(i -> i.name().equals(name)).map(Info::example).findFirst().orElse(name);
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))
                ? s.substring(1, s.length() - 1) : s;
    }

    /** The arguments at the top level: commas inside parentheses or quotes do not split. */
    static List<String> split(String args) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            } else if (!quoted && ch == '(') {
                depth++;
            } else if (!quoted && ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0 && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty() || !out.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
