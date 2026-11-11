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
package com.ash.drishti.bicompute.functions;

import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The function catalogue (HOW_IT_FITS.md section A26.3) and the parser of a measure's text: {@code sum(NOTIONAL_USD)}, {@code count()},
 * {@code wavg(PRICE, QUANTITY)}, {@code percentile(PNL_1D, 0.99)}, {@code var()} (a provider's native measure). A provider's natives are
 * looked up first; the built-ins next, when the provider evaluates them.
 */
public final class Functions {

    private static final Pattern CALL = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    /** A built-in's name, kind and what it does, for the field list and Help. */
    public record Info(String name, String kind, String args, String description, boolean plainSum) {}

    /** Every built-in, in the order the screen offers them. */
    public static final List<Info> BUILT_INS = List.of(
            new Info("sum", "mergeable", "x", "Σx (compensated); a total is the sum of its cells", true),
            new Info("count", "mergeable", "[x]", "the rows (count()) or the values of x", true),
            new Info("avg", "mergeable", "x", "Σx / n, from the cell's own sum and count (never an average of averages)", false),
            new Info("min", "mergeable", "x", "the smallest value", false),
            new Info("max", "mergeable", "x", "the largest value", false),
            new Info("wavg", "mergeable", "x, w", "Σwx / Σw, the weighted average", false),
            new Info("var_samp", "mergeable", "x", "sample variance (Welford, merged by Chan's formula)", false),
            new Info("var_pop", "mergeable", "x", "population variance", false),
            new Info("stddev_samp", "mergeable", "x", "sample standard deviation", false),
            new Info("stddev_pop", "mergeable", "x", "population standard deviation", false),
            new Info("median", "holistic", "x", "the exact median, from the cell's own values", false),
            new Info("percentile", "holistic", "x, q", "the exact q-quantile (linear interpolation), from the cell's own values", false),
            new Info("count_distinct", "holistic", "x", "the exact number of distinct values, from the cell's own values", false),
            new Info("sum_once", "holistic", "x, p", "Σx counting each parent p once (a trade's quantity in a leg-grain view)", false),
            new Info("count_once", "holistic", "x, p", "the parents p with a value of x, each once", false),
            new Info("avg_once", "holistic", "x, p", "the average of x over the parents p, each once", false),
            new Info("nmiss", "mergeable", "x", "the rows whose x is missing; a total is the sum of its cells", true),
            new Info("range", "mergeable", "x", "max − min, from the two extremes", false),
            new Info("stderr", "mergeable", "x", "the standard error of the mean, s / √n (moments)", false),
            new Info("cv", "mergeable", "x", "the coefficient of variation, s / mean (moments; SAS's CV is 100 times it)", false),
            new Info("skewness", "mergeable", "x", "the sample skewness, Excel's SKEW (moments to the third order, merged by Pébay's formulas)",
                    false),
            new Info("kurtosis", "mergeable", "x", "the sample excess kurtosis, Excel's KURT (moments to the fourth order)", false),
            new Info("geomean", "mergeable", "x", "exp(Σ ln x / n), positive values only (Excel's GEOMEAN)", false),
            new Info("harmean", "mergeable", "x", "n / Σ(1/x), positive values only (Excel's HARMEAN)", false),
            new Info("corr", "mergeable", "x, y", "Pearson's correlation over the pairs present (co-moments)", false),
            new Info("covar", "mergeable", "x, y", "the sample covariance, Excel's COVARIANCE.S (co-moments)", false),
            new Info("slope", "mergeable", "y, x", "the least-squares slope of y on x (co-moments)", false),
            new Info("intercept", "mergeable", "y, x", "the least-squares intercept of y on x (co-moments)", false),
            new Info("rsq", "mergeable", "y, x", "r², the square of the correlation (co-moments)", false),
            new Info("mode", "holistic", "x", "the most frequent value, a number or a name; ties to the smallest; none when no value repeats",
                    false),
            new Info("iqr", "holistic", "x", "p75 − p25 (Excel's QUARTILE.INC), from the cell's own values", false),
            new Info("trimmean", "holistic", "x, pct", "the mean without ⌊n·pct/2⌋ values at each end (Excel's TRIMMEAN)", false),
            new Info("kth_largest", "holistic", "x, k", "the k-th largest value (Excel's LARGE)", false),
            new Info("kth_smallest", "holistic", "x, k", "the k-th smallest value (Excel's SMALL)", false),
            new Info("string_agg", "holistic", "x, 'sep', [true]", "the values joined by sep in text order (true: each once), at most "
                    + Holistics.STRING_AGG_MAX + " values", false),
            new Info("skewness_pop", "mergeable", "x", "the population skewness, Excel's SKEW.P (moments to the third order)", false),
            new Info("devsq", "mergeable", "x", "Σ(x − mean)², Excel's DEVSQ, SAS's CSS (moments)", false),
            new Info("covar_pop", "mergeable", "x, y", "the population covariance, Excel's COVARIANCE.P (co-moments)", false),
            new Info("steyx", "mergeable", "y, x", "the standard error of y predicted from x, Excel's STEYX (co-moments)", false),
            new Info("sumproduct", "mergeable", "x, y", "Σ x·y over the rows where both are present, Excel's SUMPRODUCT", true),
            new Info("sumsq", "mergeable", "x", "Σ x², Excel's SUMSQ, SAS's USS", true),
            new Info("product", "mergeable", "x", "Π x, Excel's PRODUCT (mantissa and exponent kept apart)", false),
            new Info("avedev", "holistic", "x", "the mean absolute deviation from the mean, Excel's AVEDEV", false),
            new Info("percentile_exc", "holistic", "x, p", "the p-quantile at rank p·(n + 1), Excel's PERCENTILE.EXC (QUARTILE.EXC at k/4)",
                    false),
            new Info("rank_avg", "holistic", "x, value, [ascending]", "the value's rank among the cell's, ties averaged, Excel's RANK.AVG",
                    false),
            new Info("percent_rank_of", "holistic", "x, value, [digits]", "the value's rank as a fraction 0..1, Excel's PERCENTRANK.INC",
                    false),
            new Info("mode_multi", "holistic", "x", "every most frequent value, ascending, Excel's MODE.MULT (at most "
                    + MoreAggregates.MODE_MULTI_MAX + " different values)", false));

    private Functions() {}

    /** A built-in by name and arguments (fields, then numbers), or empty when there is none of that name. */
    public static Optional<AggFunction<Object>> builtIn(String name, List<String> fields, List<Double> numbers) {
        return builtIn(name, fields, numbers, List.of());
    }

    /**
     * As {@link #builtIn(String, List, List)}, with the text arguments a function takes ({@code string_agg}'s separator). A boolean is a
     * number, 1 or 0 ({@code string_agg(CURRENCY, ', ', true)}).
     */
    public static Optional<AggFunction<Object>> builtIn(String name, List<String> fields, List<Double> numbers, List<String> texts) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!texts.isEmpty() && !"string_agg".equals(n)) {
            throw new IllegalArgumentException(n + " takes no text argument ('" + texts.get(0) + "')");
        }
        AggFunction<Object> f = switch (n) {
            case "sum" -> new Builtins.Sum();
            case "count" -> new Builtins.Count(fields.isEmpty());
            case "avg", "mean" -> new Builtins.Avg();
            case "min" -> new Builtins.MinMax(false);
            case "max" -> new Builtins.MinMax(true);
            case "wavg", "weighted_avg" -> new Builtins.WeightedAvg();
            case "var", "variance", "var_samp" -> // domain-strings: generic (the statistics function)
                new Builtins.Welford("var_samp");
            case "var_pop" -> new Builtins.Welford("var_pop");
            case "stddev", "stddev_samp" -> new Builtins.Welford("stddev_samp");
            case "stddev_pop" -> new Builtins.Welford("stddev_pop");
            case "median" -> new Builtins.Quantile("median", 0.5);
            case "percentile", "quantile" -> new Builtins.Quantile("percentile", numbers.isEmpty() ? -1 : numbers.get(0));
            case "count_distinct", "distinct" -> new Builtins.Distinct();
            case "sum_once", "count_once", "avg_once" -> new OncePerParent(n);
            case "nmiss" -> new Moments.NMiss();
            case "range" -> new Moments.Range();
            case "stderr", "cv", "skewness", "kurtosis" -> new Moments(n);
            case "geomean", "harmean" -> new Moments.PositiveMean(n);
            case "corr", "covar", "slope", "intercept", "rsq" -> new Comoments(n);
            case "mode" -> new Holistics.Mode();
            case "iqr" -> new Holistics.Iqr();
            case "trimmean" -> new Holistics.TrimMean(numbers.isEmpty() ? -1 : numbers.get(0));
            case "kth_largest", "kth_smallest" -> new Holistics.Kth(n, numbers.isEmpty() ? -1 : numbers.get(0));
            case "string_agg" -> new Holistics.StringAgg(texts.isEmpty() ? null : texts.get(0), !numbers.isEmpty() && numbers.get(0) != 0);
            case "skewness_pop", "devsq" -> new Moments(n);
            case "covar_pop", "steyx" -> new Comoments(n);
            case "sumproduct" -> new MoreAggregates.SumProduct();
            case "sumsq" -> new MoreAggregates.SumSq();
            case "product" -> new MoreAggregates.Product();
            case "avedev" -> new MoreAggregates.AveDev();
            case "percentile_exc" -> new MoreAggregates.PercentileExc(numbers.isEmpty() ? -1 : numbers.get(0));
            case "rank_avg" -> new MoreAggregates.RankAvg(numbers.isEmpty() ? Double.NaN : numbers.get(0), numbers.size() > 1 ? numbers.get(1) : 0);
            case "percent_rank_of" -> new MoreAggregates.PercentRankOf(numbers.isEmpty() ? Double.NaN : numbers.get(0),
                    numbers.size() > 1 ? numbers.get(1) : Double.NaN);
            case "mode_multi" -> new MoreAggregates.ModeMulti();
            default -> null;
        };
        return Optional.ofNullable(f);
    }

    /**
     * A measure's text as a function call: the provider's native measure of that name, else a built-in when the provider evaluates
     * built-ins; refused in words otherwise ({@code DRS-5701}). {@code label}: the column header (null: the text).
     */
    public static FunctionCall parse(String text, String label, PivotProvider provider) {
        return parse(text, label, provider, null, null);
    }

    /**
     * Finds the functions a view names beyond the built-ins and the provider's natives (HOW_IT_FITS.md section A26.5): custom functions
     * ({@code fn:owner/name@v(FIELD, ...)}), resolved for the reader. The host sets one on the core.
     */
    @FunctionalInterface
    public interface Resolver {
        /** The call as a function call for this reader, or empty when the text is not one of its functions. */
        Optional<FunctionCall> resolve(com.ash.drishti.kernel.api.pivot.Viewer v, String text, String label);
    }

    /**
     * As {@link #parse(String, String, PivotProvider)}, with custom functions ({@code resolver}) and formulas of the built-ins
     * ({@code sum(PNL) / sum(abs(PNL))}, Rachana; section 8.2) on a provider that evaluates built-ins.
     */
    public static FunctionCall parse(String text, String label, PivotProvider provider, Resolver resolver, com.ash.drishti.kernel.api.pivot.Viewer v) {
        if (resolver != null && text != null && text.strip().startsWith("fn:")) {
            if (!provider.capabilities().builtIns()) {
                throw PivotView.bad(text + ": custom functions run over a source's rows; " + provider.id() + "'s measures are its own");
            }
            return resolver.resolve(v, text, label).orElseThrow(() -> PivotView.bad("no custom function " + text.strip()));
        }
        try {
            return simple(text, label, provider);
        } catch (com.ash.drishti.common.DrishtiException e) {
            if (!provider.capabilities().builtIns() || text == null || !com.ash.drishti.bicompute.formula.FormulaLowering.isFormula(text)) {
                throw e;
            }
            com.ash.drishti.bicompute.formula.FormulaFunction f = com.ash.drishti.bicompute.formula.FormulaFunction.of(text);
            return new FunctionCall(f.text(), label == null || label.isBlank() ? f.text() : label.strip(), f, f.fields());
        }
    }

    private static FunctionCall simple(String text, String label, PivotProvider provider) {
        Matcher m = CALL.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw PivotView.bad("'" + text + "' is not a measure: write a function and its fields, sum(NOTIONAL_USD), count(), var()");
        }
        String name = m.group(1).toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> canon = new ArrayList<>();
        String args = m.group(2).strip();
        if (!args.isEmpty()) {
            for (String a : arguments(args)) {
                String s = a.strip();
                if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
                    texts.add(s.substring(1, s.length() - 1).replace("''", "'"));
                } else if (!texts.isEmpty() && ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))) {
                    numbers.add("true".equalsIgnoreCase(s) ? 1.0 : 0.0);
                } else if (FIELD.matcher(s).matches()) {
                    if (!numbers.isEmpty() || !texts.isEmpty()) {
                        throw PivotView.bad(text + ": the fields come before the numbers and texts");
                    }
                    fields.add(s);
                } else {
                    try {
                        numbers.add(Double.parseDouble(s));
                    } catch (NumberFormatException e) {
                        throw PivotView.bad(text + ": '" + s + "' is neither a field, a number nor a quoted text");
                    }
                }
                canon.add(s);
            }
        }
        Optional<AggFunction<Object>> f = provider.nativeFunction(name, fields);
        if (f.isEmpty()) {
            if (!provider.capabilities().builtIns()) {
                throw PivotView.bad(text + ": the source's measures are its own (" + provider.id() + "); built-in functions over fields are not "
                        + "offered on it");
            }
            try {
                f = builtIn(name, fields, numbers, texts);
            } catch (IllegalArgumentException e) {
                throw PivotView.bad(text + ": " + e.getMessage());
            }
        }
        if (f.isEmpty()) {
            throw PivotView.bad(text + ": no function '" + name + "'; the built-ins: " + String.join(", ", BUILT_INS.stream().map(Info::name).toList()));
        }
        AggFunction<Object> fn = f.get();
        if (fields.size() != fn.inputs()) {
            throw PivotView.bad(text + ": " + name + " takes " + fn.inputs() + " field" + (fn.inputs() == 1 ? "" : "s") + ", not " + fields.size());
        }
        String id = name + "(" + String.join(", ", canon) + ")";
        return new FunctionCall(id, label == null || label.isBlank() ? id : label.strip(), fn, fields);
    }

    /** A call's arguments split at the commas outside quotes ({@code CURRENCY, ', '} is two). */
    static List<String> arguments(String args) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            }
            if (ch == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** The built-ins as maps (the catalogue's answer). */
    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Info i : BUILT_INS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.name());
            m.put("kind", i.kind());
            m.put("args", i.args());
            m.put("description", i.description());
            m.put("plainSum", i.plainSum());
            out.add(m);
        }
        return out;
    }
}
