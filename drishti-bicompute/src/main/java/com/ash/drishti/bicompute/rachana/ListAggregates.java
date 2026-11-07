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
package com.ash.drishti.bicompute.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.bicompute.functions.Builtins;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The engine's aggregates over a Rachana list (ONE_GRAMMAR.md part 2): the list's values made a {@link Batch} and run through the very
 * {@link AggFunction} a pivot cell runs (start, add, finish), so a sum in a Sutra and a sum in a pivot are one implementation with one
 * treatment of nulls and masks. Written {@code f(list)} (the list's values), {@code f(list, 'FIELD', ...)} (a field of each element, a
 * dotted path), or {@code f(xs, ys)} (parallel lists, for the functions of two inputs: {@code wavg(prices, quantities)},
 * {@code sum_once(quantities, tradeIds)}); a number argument is the function's parameter ({@code percentile(list, 0.95)}).
 *
 * <p>Rules (FUNCTION_CATALOGUE.md): a value that is not a number (text that is not number-like, a boolean, an object) is not counted;
 * a masked value makes the result masked (it is never added up); {@code sum} and {@code count} of nothing are 0 (as Excel's), every
 * other aggregate of nothing is empty. A whole result is a whole number ({@code 1 + 1} prints 2).
 */
final class ListAggregates {

    private ListAggregates() {}

    /** Rachana's name to the engine's built-in name. */
    static final Map<String, String> ENGINE_NAME = Map.ofEntries(Map.entry("sum", "sum"), Map.entry("count", "count"),
            Map.entry("avg", "avg"), Map.entry("min", "min"), Map.entry("max", "max"), Map.entry("median", "median"),
            Map.entry("percentile", "percentile"), Map.entry("distinct", "count_distinct"), Map.entry("wavg", "wavg"),
            Map.entry("variance", "var_samp"), Map.entry("stddev", "stddev_samp"), Map.entry("variance_pop", "var_pop"),
            Map.entry("stddev_pop", "stddev_pop"), Map.entry("sum_once", "sum_once"), Map.entry("count_once", "count_once"),
            Map.entry("avg_once", "avg_once"), Map.entry("nmiss", "nmiss"), Map.entry("range", "range"), Map.entry("mode", "mode"),
            Map.entry("iqr", "iqr"), Map.entry("stderr", "stderr"), Map.entry("cv", "cv"), Map.entry("skewness", "skewness"),
            Map.entry("kurtosis", "kurtosis"), Map.entry("geomean", "geomean"), Map.entry("harmean", "harmean"),
            Map.entry("trimmean", "trimmean"), Map.entry("corr", "corr"), Map.entry("covar", "covar"), Map.entry("slope", "slope"),
            Map.entry("intercept", "intercept"), Map.entry("rsq", "rsq"), Map.entry("kth_largest", "kth_largest"),
            Map.entry("kth_smallest", "kth_smallest"), Map.entry("string_agg", "string_agg"), Map.entry("skewness_pop", "skewness_pop"),
            Map.entry("devsq", "devsq"), Map.entry("covar_pop", "covar_pop"), Map.entry("steyx", "steyx"),
            Map.entry("sumproduct", "sumproduct"), Map.entry("sumsq", "sumsq"), Map.entry("product", "product"),
            Map.entry("avedev", "avedev"), Map.entry("percentile_exc", "percentile_exc"), Map.entry("rank_avg", "rank_avg"),
            Map.entry("percent_rank_of", "percent_rank_of"), Map.entry("mode_multi", "mode_multi"));

    /** {@code name} over the evaluated arguments of a call. */
    static Object apply(String name, List<Object> args) {
        if (args.isEmpty()) {
            throw error(name, "count() counts the rows of a pivot cell or of a measure's table; over a list write count(list)");
        }
        if (!(Values.simplify(args.get(0)) instanceof DataNode.Arr list)) {
            return empty(name);
        }
        List<String> fields = new ArrayList<>();
        List<DataNode.Arr> parallels = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int sep = "string_agg".equals(name) ? separator(args) : -1;
        for (int i = 1; i < args.size(); i++) {
            Object a = Values.simplify(args.get(i));
            if (i == sep) {
                texts.add(Values.text(a));
            } else if (a instanceof Boolean b && "string_agg".equals(name)) {
                numbers.add(b ? 1.0 : 0.0);
            } else if (a instanceof DataNode.Arr arr) {
                parallels.add(arr);
            } else if (a instanceof Number n) {
                numbers.add(n.doubleValue());
            } else if (a instanceof String s && !s.isBlank()) {
                if (!numbers.isEmpty()) {
                    throw error(name, "the fields come before the numbers");
                }
                fields.add(s);
            } else if (a != null) {
                throw error(name, "argument " + (i + 1) + " is neither a field, a list nor a number");
            }
        }
        AggFunction<Object> f = function(name, numbers, texts);
        int inputs = "count".equals(name) ? 1 : f.inputs();
        List<List<DataNode>> columns = columns(name, list, fields, parallels, inputs);
        Object[] batch = new Object[inputs];
        for (int c = 0; c < inputs; c++) {
            List<DataNode> col = columns.get(c);
            if (f.numeric()) {
                double[] d = new double[col.size()];
                for (int r = 0; r < d.length; r++) {
                    DataNode x = col.get(r);
                    if (x.isMasked()) {
                        return Values.mask();                     // a masked value is never added up: the result is masked
                    }
                    d[r] = number(x);
                }
                batch[c] = d;
            } else {
                String[] s = new String[col.size()];
                for (int r = 0; r < s.length; r++) {
                    DataNode x = col.get(r);
                    if (x.isMasked()) {
                        return Values.mask();
                    }
                    s[r] = x.isNull() || x.isMissing() ? null : Values.text(x);
                }
                batch[c] = s;
            }
        }
        Object state = f.add(f.start(), new Batch(list.size(), batch));
        if (f instanceof com.ash.drishti.bicompute.functions.MoreAggregates.ModeMulti mm) {
            List<Object> values = mm.values(state);                // a Rachana list gets mode_multi's values as a list
            if (values == null) {
                return null;
            }
            List<DataNode> out = new ArrayList<>();
            values.forEach(x -> out.add(DataNode.of(x instanceof Double d ? Values.normalise(d) : x)));
            return new DataNode.Arr(List.copyOf(out));
        }
        Object v = f.result(state);
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            v = null;
        }
        return v == null ? empty(name) : v instanceof Double d ? Values.normalise(d) : v;
    }

    /** {@code string_agg}'s separator: its last text argument after the list ({@code string_agg($.LEGS, 'CURRENCY', ', ')}). */
    private static int separator(List<Object> args) {
        for (int i = args.size() - 1; i >= 1; i--) {
            if (Values.simplify(args.get(i)) instanceof String) {
                return i;
            }
        }
        throw error("string_agg", "write the separator: string_agg($.LEGS[*].CURRENCY, ', ')");
    }

    /** The engine's function of that name and parameters; a parameter out of range is a problem in words. */
    private static AggFunction<Object> function(String name, List<Double> numbers, List<String> texts) {
        if ("count".equals(name)) {
            return new Builtins.Count(false);
        }
        try {
            return Functions.builtIn(ENGINE_NAME.get(name), List.of(), numbers, texts)
                    .orElseThrow(() -> error(name, "the pivot engine has no such aggregate"));
        } catch (IllegalArgumentException e) {
            throw error(name, e.getMessage());
        }
    }

    /** The function's input columns, one value per element: the elements, fields of them, or parallel lists. */
    private static List<List<DataNode>> columns(String name, DataNode.Arr list, List<String> fields, List<DataNode.Arr> parallels,
            int inputs) {
        List<List<DataNode>> out = new ArrayList<>();
        if (!parallels.isEmpty()) {
            if (!fields.isEmpty()) {
                throw error(name, "write either fields of one list or parallel lists, not both");
            }
            out.add(list.elements());
            for (DataNode.Arr p : parallels) {
                if (p.size() != list.size()) {
                    throw error(name, "the lists differ in length (" + list.size() + " and " + p.size() + ")");
                }
                out.add(p.elements());
            }
        } else if (!fields.isEmpty()) {
            for (String field : fields) {
                List<DataNode> col = new ArrayList<>(list.size());
                list.elements().forEach(e -> col.add(e.at(field)));
                out.add(col);
            }
        } else {
            out.add(list.elements());
        }
        if (out.size() != inputs) {
            throw error(name, "takes " + inputs + " input" + (inputs == 1 ? "" : "s") + " (a field each, or as many lists), got " + out.size());
        }
        return out;
    }

    /** A value as a number: numbers, and number-like text (spaces around allowed); anything else is not a number. */
    static double number(DataNode x) {
        Object s = Values.simplify(x);
        if (s instanceof Number n) {
            return n.doubleValue();
        }
        if (s instanceof String str && !str.isBlank()) {
            try {
                return Double.parseDouble(str.strip());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** What an aggregate of nothing is: 0 for a sum, a count and nmiss (as Excel's), empty for the rest. */
    static Object empty(String name) {
        return "sum".equals(name) || "count".equals(name) || "nmiss".equals(name) || "sumproduct".equals(name) || "sumsq".equals(name)
                ? (Object) 0L : null;
    }

    static ElException error(String name, String why) {
        return new ElException(ErrorCode.EL_EVAL, name + ": " + why);
    }
}
