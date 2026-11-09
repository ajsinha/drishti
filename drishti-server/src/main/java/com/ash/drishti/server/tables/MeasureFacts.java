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
package com.ash.drishti.server.tables;

import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.rachana.el.check.Signatures;
import com.ash.drishti.tables.lang.Trees;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the model editor says of a measure (docs/architecture/SHARED_SCREENS.md section 3): whether its total adds up, and its formula in plain words. "Adds up?" is the
 * engine's answer, never the author's: each aggregate is looked up in the pivot engine's own functions ({@link Functions#builtIn}), and a total
 * adds up only when every aggregate in it is one whose total is the plain sum of its parts ({@link AggFunction#plainSum()}: sum, count) and
 * they are only added, subtracted or scaled by a number. Everything else (an average, a ratio, VaR, a distinct count) is computed per total.
 */
public final class MeasureFacts {

    /** The engine's answer for one measure. */
    public record AddsUp(boolean adds, String word, String why) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("addsUp", adds);
            m.put("word", word);
            m.put("why", why);
            return m;
        }
    }

    private enum Sort { ADDS, CONSTANT, PER_TOTAL, RATIO }

    private MeasureFacts() {}

    /** Whether the total of {@code expr} adds up, with {@code measures} (name to expression) for the measures it names. */
    public static AddsUp addsUp(String expr, Map<String, String> measures) {
        Expr e;
        try {
            e = Trees.parse(expr);
        } catch (RuntimeException x) {
            return new AddsUp(false, "unknown", "the formula does not read yet");
        }
        List<String> reasons = new ArrayList<>();
        Sort s = sort(e, measures, reasons, new HashSet<>());
        return switch (s) {
            case ADDS -> new AddsUp(true, "yes", "a total is the sum of its parts (" + String.join("; ", reasons) + ")");
            case CONSTANT -> new AddsUp(false, "no", "a constant: the same figure on every total");
            case RATIO -> new AddsUp(false, "ratio", "computed per total: a ratio of totals is not the sum of the parts' ratios");
            case PER_TOTAL -> new AddsUp(false, "no", "computed per total (" + String.join("; ", reasons) + ")");
        };
    }

    private static Sort sort(Expr e, Map<String, String> measures, List<String> reasons, Set<String> seen) {
        if (e instanceof Expr.Lit) {
            return Sort.CONSTANT;
        }
        if (e instanceof Expr.Neg n) {
            return sort(n.e(), measures, reasons, seen);
        }
        if (e instanceof Expr.Call c) {
            if (c.name().equals("measure") && !c.args().isEmpty() && c.args().get(0) instanceof Expr.Lit l) {
                String name = String.valueOf(l.value());
                if (!seen.add(name) || !measures.containsKey(name)) {
                    reasons.add("measure '" + name + "' is not known here");
                    return Sort.PER_TOTAL;
                }
                try {
                    return sort(Trees.parse(measures.get(name)), measures, reasons, seen);
                } catch (RuntimeException x) {
                    return Sort.PER_TOTAL;
                }
            }
            Optional<Signatures.Signature> sig = Signatures.get().function(c.name());
            if (sig.isPresent() && sig.get().aggregate()) {
                return engine(c, reasons);
            }
            reasons.add(c.name() + " is worked out from each total's own figures");
            return Sort.PER_TOTAL;
        }
        if (e instanceof Expr.Binary b) {
            Sort l = sort(b.l(), measures, reasons, seen);
            Sort r = sort(b.r(), measures, reasons, seen);
            switch (b.op()) {
                case "+", "-" -> {
                    if ((l == Sort.ADDS || l == Sort.CONSTANT) && (r == Sort.ADDS || r == Sort.CONSTANT)) {
                        return l == Sort.CONSTANT && r == Sort.CONSTANT ? Sort.CONSTANT : l == Sort.ADDS && r == Sort.ADDS ? Sort.ADDS : Sort.PER_TOTAL;
                    }
                    return l == Sort.RATIO || r == Sort.RATIO ? Sort.RATIO : Sort.PER_TOTAL;
                }
                case "*" -> {
                    if (l == Sort.ADDS && r == Sort.CONSTANT || l == Sort.CONSTANT && r == Sort.ADDS) {
                        return Sort.ADDS;
                    }
                    return l == Sort.CONSTANT && r == Sort.CONSTANT ? Sort.CONSTANT : Sort.PER_TOTAL;
                }
                case "/" -> {
                    if (l == Sort.ADDS && r == Sort.CONSTANT) {
                        return Sort.ADDS;
                    }
                    return l == Sort.CONSTANT && r == Sort.CONSTANT ? Sort.CONSTANT : Sort.RATIO;
                }
                default -> {
                    return Sort.PER_TOTAL;
                }
            }
        }
        reasons.add("the formula is worked out from each total's own figures");
        return Sort.PER_TOTAL;
    }

    /** The pivot engine's function of an aggregate call, and what its total is. */
    private static Sort engine(Expr.Call c, List<String> reasons) {
        List<Double> numbers = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (Expr a : c.args()) {
            if (a instanceof Expr.Lit l && l.value() instanceof Number n) {
                numbers.add(n.doubleValue());
            } else {
                fields.add("x" + fields.size());
            }
        }
        Optional<AggFunction<Object>> fn;
        try {
            fn = Functions.builtIn(c.name(), fields, numbers);
        } catch (RuntimeException x) {
            fn = Optional.empty();
        }
        if (fn.isEmpty()) {
            reasons.add(c.name() + " is computed per total by its engine");
            return Sort.PER_TOTAL;
        }
        AggFunction<Object> f = fn.get();
        if (f.plainSum()) {
            reasons.add(c.name() + ": the engine adds the parts");
            return Sort.ADDS;
        }
        reasons.add(c.name() + ": " + (f.kind() == Kind.HOLISTIC ? "holistic, recomputed from each total's own rows" : f.kind() == Kind.SKETCH
                ? "a sketch, merged with a stated error" : "merged from the parts' states, not added"));
        return Sort.PER_TOTAL;
    }

    /** The formula in plain words, from the function catalogue's own descriptions. */
    public static String explain(String expr) {
        try {
            return words(Trees.parse(expr), 0);
        } catch (RuntimeException x) {
            return "the formula does not read yet";
        }
    }

    private static String words(Expr e, int depth) {
        if (depth > 6) {
            return Printer.print(e);
        }
        if (e instanceof Expr.Call c) {
            if (c.name().equals("measure") && !c.args().isEmpty() && c.args().get(0) instanceof Expr.Lit l) {
                return "the measure '" + l.value() + "'";
            }
            Optional<Signatures.Signature> sig = Signatures.get().function(c.name());
            StringBuilder b = new StringBuilder();
            String what = sig.map(Signatures.Signature::description).map(MeasureFacts::firstClause).orElse(c.name());
            b.append(what);
            if (!c.args().isEmpty()) {
                List<String> args = new ArrayList<>();
                for (Expr a : c.args()) {
                    args.add(Printer.print(a));
                }
                b.append(", x being ").append(String.join(", ", args));
            }
            Expr where = c.named() == null ? null : c.named().get("where");
            if (where != null) {
                b.append(", counting only the rows where ").append(Printer.print(where));
            }
            return b.toString();
        }
        if (e instanceof Expr.Binary bin) {
            String op = switch (bin.op()) {
                case "+" -> "plus";
                case "-" -> "minus";
                case "*" -> "times";
                case "/" -> "divided by";
                default -> bin.op();
            };
            return "(" + words(bin.l(), depth + 1) + ") " + op + " (" + words(bin.r(), depth + 1) + ")";
        }
        if (e instanceof Expr.Neg n) {
            return "minus " + words(n.e(), depth + 1);
        }
        return Printer.print(e);
    }

    private static String firstClause(String description) {
        String d = description == null ? "" : description.strip();
        int stop = d.indexOf(". ");
        d = stop > 0 ? d.substring(0, stop) : d.endsWith(".") ? d.substring(0, d.length() - 1) : d;
        return d.isEmpty() ? d : Character.toLowerCase(d.charAt(0)) + d.substring(1);
    }
}
