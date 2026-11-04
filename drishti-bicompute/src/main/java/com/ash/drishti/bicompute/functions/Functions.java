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

import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.PivotProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The function catalogue (PIVOT_ENGINE.md section 5.2) and the parser of a measure's text: {@code sum(NOTIONAL_USD)}, {@code count()},
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
            new Info("avg_once", "holistic", "x, p", "the average of x over the parents p, each once", false));

    private Functions() {}

    /** A built-in by name and arguments (fields, then numbers), or empty when there is none of that name. */
    public static Optional<AggFunction<Object>> builtIn(String name, List<String> fields, List<Double> numbers) {
        String n = name.toLowerCase(Locale.ROOT);
        AggFunction<Object> f = switch (n) {
            case "sum" -> new Builtins.Sum();
            case "count" -> new Builtins.Count(fields.isEmpty());
            case "avg", "mean" -> new Builtins.Avg();
            case "min" -> new Builtins.MinMax(false);
            case "max" -> new Builtins.MinMax(true);
            case "wavg", "weighted_avg" -> new Builtins.WeightedAvg();
            case "var", "variance", "var_samp" -> new Builtins.Welford("var_samp");
            case "var_pop" -> new Builtins.Welford("var_pop");
            case "stddev", "stddev_samp" -> new Builtins.Welford("stddev_samp");
            case "stddev_pop" -> new Builtins.Welford("stddev_pop");
            case "median" -> new Builtins.Quantile("median", 0.5);
            case "percentile", "quantile" -> new Builtins.Quantile("percentile", numbers.isEmpty() ? -1 : numbers.get(0));
            case "count_distinct", "distinct" -> new Builtins.Distinct();
            case "sum_once", "count_once", "avg_once" -> new OncePerParent(n);
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
     * Finds the functions a view names beyond the built-ins and the provider's natives (PIVOT_ENGINE.md section 8): custom functions
     * ({@code fn:owner/name@v(FIELD, ...)}), resolved for the reader. The host sets one on the core.
     */
    @FunctionalInterface
    public interface Resolver {
        /** The call as a function call for this reader, or empty when the text is not one of its functions. */
        Optional<FunctionCall> resolve(com.ash.drishti.bicompute.spi.Viewer v, String text, String label);
    }

    /**
     * As {@link #parse(String, String, PivotProvider)}, with custom functions ({@code resolver}) and formulas of the built-ins
     * ({@code sum(PNL) / sum(abs(PNL))}, DEL's syntax; section 8.2) on a provider that evaluates built-ins.
     */
    public static FunctionCall parse(String text, String label, PivotProvider provider, Resolver resolver, com.ash.drishti.bicompute.spi.Viewer v) {
        if (resolver != null && text != null && text.strip().startsWith("fn:")) {
            if (!provider.capabilities().builtIns()) {
                throw PivotView.bad(text + ": custom functions run over a source's rows; " + provider.id() + "'s measures are its own");
            }
            return resolver.resolve(v, text, label).orElseThrow(() -> PivotView.bad("no custom function " + text.strip()));
        }
        try {
            return simple(text, label, provider);
        } catch (com.ash.drishti.common.DrishtiException e) {
            if (!provider.capabilities().builtIns() || text == null || !com.ash.drishti.bicompute.formula.FormulaParser.isFormula(text)) {
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
        List<String> canon = new ArrayList<>();
        String args = m.group(2).strip();
        if (!args.isEmpty()) {
            for (String a : args.split(",")) {
                String s = a.strip();
                if (FIELD.matcher(s).matches()) {
                    if (!numbers.isEmpty()) {
                        throw PivotView.bad(text + ": the fields come before the numbers");
                    }
                    fields.add(s);
                } else {
                    try {
                        numbers.add(Double.parseDouble(s));
                    } catch (NumberFormatException e) {
                        throw PivotView.bad(text + ": '" + s + "' is neither a field nor a number");
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
                f = builtIn(name, fields, numbers);
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
