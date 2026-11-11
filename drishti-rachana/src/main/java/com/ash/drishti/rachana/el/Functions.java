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
package com.ash.drishti.rachana.el;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The one table of Rachana-EL functions, filled by the families: {@link LogicFunctions} and {@link InfoFunctions}, {@link MathFunctions}
 * and {@link NumberFunctions}, {@link TextFunctions} and {@link TextParts}, {@link DateFunctions} and {@link DateParts}, {@link ListFunctions}
 * and {@link ListMore}, {@link Distributions} and {@link FinanceFunctions} (RACHANA_REFERENCE.md sections F1, 2, 3, 6, 8 and 9).
 * Rachana's own functions are scalars (one value from values). Every aggregate ({@code sum}, {@code count}, {@code avg},
 * {@code min} and {@code max} over one list, {@code median}, {@code percentile} …), the grouping behind {@code group(...)}, and
 * {@code measure(...)} and {@code cell(...)} come from the pivot engine, registered into this table by a {@link FunctionProvider}
 * ({@link Aggregates}; HOW_IT_FITS.md parts 1 to 3). Functions are pure and cheap; an empty argument gives an empty result unless the
 * function says otherwise, a masked one a masked result, and a value of the wrong type (text where a number is needed)
 * raises {@code DRS-2102} naming the function and the value, which {@code iferror(...)} turns into a fallback.
 *
 * <p>Three kinds of entry: an <b>eager</b> function ({@link ElFunction}) gets its arguments evaluated; a <b>form</b>
 * ({@link ElForm}) gets them unevaluated, to evaluate lazily or once per list element with {@code @} bound
 * ({@code switch}, {@code iferror}, {@code map}, {@code reduce}, {@code sort}); a <b>syntax</b> entry is built by the
 * parser itself ({@code if}, {@code ifs}, {@code let}). An old spelling ({@code startsWith}, {@code days} …) is read for
 * existing Sutras and is the same entry as its one name.
 */
public final class Functions {

    /** Name to {min args, max args}, every kind of entry, old spellings included. */
    static final Map<String, int[]> ARITY;
    private static final Map<String, ElFunction> FUNCTIONS;
    private static final Map<String, ElForm> FORMS;
    private static final Set<String> SYNTAX;
    private static final Set<String> PASS_MASK;
    private static final Map<String, Function<List<Expr>, String>> CHECKS;
    private static final Map<String, String> OLD_SPELLINGS;
    private static final Map<String, String> FAMILY;

    static {
        Table t = new Table();
        LogicFunctions.register(t.family("logic"));
        InfoFunctions.register(t.family("logic"));
        MathFunctions.register(t.family("math"));
        NumberFunctions.register(t.family("math"));
        TextFunctions.register(t.family("text"));
        TextParts.register(t.family("text"));
        DateFunctions.register(t.family("dates"));
        DateParts.register(t.family("dates"));
        ListFunctions.register(t.family("lists"));
        ListMore.register(t.family("lists"));
        Distributions.register(t.family("statistics"));
        FinanceFunctions.register(t.family("finance"));
        Aggregates.install(t);
        ARITY = Collections.unmodifiableMap(t.arity);
        FUNCTIONS = Map.copyOf(t.functions);
        FORMS = Map.copyOf(t.forms);
        SYNTAX = Set.copyOf(t.syntax);
        PASS_MASK = Set.copyOf(t.passMask);
        CHECKS = Map.copyOf(t.checks);
        OLD_SPELLINGS = Collections.unmodifiableMap(new TreeMap<>(t.old));
        FAMILY = Map.copyOf(t.family);
    }

    /**
     * One function of the table as the function catalogue (Help → Functions, {@code FUNC}) lists it.
     *
     * @param name its one name
     * @param family {@code logic}, {@code math}, {@code text}, {@code dates}, {@code lists} (Rachana's own), or {@code aggregates}
     * @param runs {@code R} (Rachana, a scalar) or {@code E} (the pivot engine, through a provider)
     * @param source who implements it: {@code Rachana}, or the provider's own words; {@code none on the class path} when refused
     * @param args its arguments as a provider writes them (empty for Rachana's own: the reference documents them)
     * @param form a provided aggregate's {@code mergeable} or {@code holistic}, or {@code measure}; empty for a scalar
     * @param description a provided function's one line; empty for Rachana's own
     */
    public record Entry(String name, String family, String runs, String source, String args, String form, String description) {}

    /**
     * An expression that is one call of a provided aggregate over expressions of {@code @}, as a Sutra pivot panel's {@code value}
     * writes it ({@code sum(@.MTM)}, {@code percentile(@.PNL, 0.99)}, {@code count(@)}): the pivot engine aggregates the rows, Rachana
     * reads each row's input.
     *
     * @param name the aggregate's name
     * @param input the expression read from each row (the call's first argument)
     * @param options the call's further arguments, each written as a literal ({@code 0.99})
     */
    public record AggregateCall(String name, Expr input, List<Object> options) {}

    /**
     * {@code e} as an aggregate call, or empty when it is not one (a field, a path, a scalar function); a call whose further arguments
     * are not literals is not one either.
     */
    public static java.util.Optional<AggregateCall> aggregateCall(Expr e) {
        if (!(e instanceof Expr.Call call) || call.args().isEmpty() || "measure".equals(call.name()) || "cell".equals(call.name())
                || !(Aggregates.EXPECTED.contains(call.name()) || Aggregates.all().containsKey(call.name()))) {
            return java.util.Optional.empty();
        }
        List<Object> options = new java.util.ArrayList<>();
        for (Expr a : call.args().subList(1, call.args().size())) {
            if (!(a instanceof Expr.Lit lit)) {
                return java.util.Optional.empty();
            }
            options.add(lit.value());
        }
        return java.util.Optional.of(new AggregateCall(call.name(), call.args().get(0), Collections.unmodifiableList(options)));
    }

    /**
     * Every function of the table under its one name (old spellings left out), in name order: what the generated function catalogue
     * lists. {@code min} and {@code max} are listed once, as the engine's aggregates over one list; {@code group} as the engine's.
     */
    public static List<Entry> catalogue() {
        List<Entry> out = new java.util.ArrayList<>();
        for (String name : ARITY.keySet()) {
            if (OLD_SPELLINGS.containsKey(name)) {
                continue;
            }
            FunctionProvider.Provided p = Aggregates.all().get(name);
            boolean engine = p != null || Aggregates.EXPECTED.contains(name) || Aggregates.MEASURE_LEVEL.contains(name) || "group".equals(name);
            if (!engine) {
                out.add(new Entry(name, FAMILY.getOrDefault(name, ""), "R", "Rachana", "", "", ""));
                continue;
            }
            String source = Aggregates.source(name);
            if ("group".equals(name)) {
                out.add(new Entry(name, "aggregates", "E", source == null ? "none on the class path" : source, "list, key, [aggregate], [by]",
                        "the engine's grouping", "one row {label, value, count} per key, the aggregate over each group (sum of a quoted field, "
                        + "or any aggregate over @)"));
                continue;
            }
            String family = Aggregates.MEASURE_LEVEL.contains(name) ? "measures"
                    : p != null && "list".equals(p.form()) ? "lists" : "aggregates";
            out.add(new Entry(name, family, "E", source == null ? "none on the class path" : source,
                    p == null ? "" : p.args(), p == null ? "" : p.form(), p == null ? "" : p.description()));
        }
        return out;
    }

    private Functions() {}

    /** The functions of loaded modules (KERNEL_AND_MODULES.md section 12.3): name to the function and its provider's source. */
    private static final Map<String, FunctionProvider.Provided> LIVE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> LIVE_SOURCE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Adds a loaded module's functions to the table while it is loaded; the returned action takes them out again. A name Rachana or another
     * provider already has is refused (nothing is added). An expression compiled while the function was there and evaluated after it left is
     * refused with the reason, the way a missing aggregate is.
     */
    public static synchronized Runnable register(String source, List<FunctionProvider.Provided> functions) {
        List<FunctionProvider.Provided> fs = List.copyOf(functions);
        for (FunctionProvider.Provided f : fs) {
            if (ARITY.containsKey(f.name()) || LIVE.containsKey(f.name())) {
                throw new IllegalStateException("Rachana-EL function " + f.name() + " is provided twice ("
                        + (LIVE.containsKey(f.name()) ? LIVE_SOURCE.get(f.name()) : "Rachana") + ", " + source + ")");
            }
        }
        for (FunctionProvider.Provided f : fs) {
            LIVE.put(f.name(), f);
            LIVE_SOURCE.put(f.name(), source);
        }
        return () -> {
            synchronized (Functions.class) {
                for (FunctionProvider.Provided f : fs) {
                    LIVE.remove(f.name(), f);
                    if (!LIVE.containsKey(f.name())) {
                        LIVE_SOURCE.remove(f.name());
                    }
                }
            }
        };
    }

    /** The source of a loaded module's function, or null. */
    public static String liveSource(String name) {
        return LIVE_SOURCE.get(name);
    }

    /** {min, max} arguments of a function of the table or of a loaded module, or null when there is none of the name. */
    static int[] arityOf(String name) {
        int[] a = ARITY.get(name);
        if (a != null) {
            return a;
        }
        FunctionProvider.Provided f = LIVE.get(name);
        return f == null ? null : new int[] {f.minArgs(), f.maxArgs()};
    }

    /** A loaded module's function, looked up by name on every call (an expression never holds the module's code). */
    private static ElFunction live(String name) {
        return (args, context) -> {
            FunctionProvider.Provided f = LIVE.get(name);
            if (f == null) {
                throw new ElException(name + "(...) is not available: the module that provides it is not loaded", 0);
            }
            return f.function().apply(args, context);
        };
    }

    /** Every function of Rachana-EL with its argument count: {name: [min, max]}, old spellings included. */
    public static Map<String, int[]> arity() {
        if (LIVE.isEmpty()) {
            return ARITY;
        }
        Map<String, int[]> all = new HashMap<>(ARITY);
        LIVE.forEach((n, f) -> all.putIfAbsent(n, new int[] {f.minArgs(), f.maxArgs()}));
        return Collections.unmodifiableMap(all);
    }

    /**
     * The spellings read for compatibility with existing Sutras, each with the one name to write instead
     * ({@code startsWith} → {@code starts_with}); never offered for new work.
     */
    public static Map<String, String> oldSpellings() {
        return OLD_SPELLINGS;
    }

    /** The one name of {@code name}: itself, or the name an old spelling stands for ({@code startsWith} is {@code starts_with}). */
    public static String oneName(String name) {
        return OLD_SPELLINGS.getOrDefault(name, name);
    }

    static ElFunction get(String name) {
        ElFunction f = FUNCTIONS.get(name);
        return f != null || !LIVE.containsKey(name) ? f : live(name);
    }

    static ElForm form(String name) {
        return FORMS.get(name);
    }

    /** True for a name the parser builds itself ({@code if}, {@code ifs}, {@code let}). */
    static boolean syntax(String name) {
        return SYNTAX.contains(name);
    }

    /** True for a function given a masked argument as it is ({@code coalesce}, {@code nonblank}), not masked for it. */
    static boolean passesMask(String name) {
        return PASS_MASK.contains(name);
    }

    /** A problem with the written (literal) arguments of a call, found when it compiles; null when there is none. */
    static String check(String name, List<Expr> args) {
        Function<List<Expr>, String> c = CHECKS.get(name);
        return c == null ? null : c.apply(args);
    }

    /** The value of a literal argument, or {@link #NOT_LITERAL} for anything computed. */
    static Object literal(List<Expr> args, int i) {
        return i < args.size() && args.get(i) instanceof Expr.Lit lit ? lit.value() : NOT_LITERAL;
    }

    /** What {@link #literal} gives for an argument that is not written as a literal. */
    static final Object NOT_LITERAL = new Object();

    /** What the families register into; each name once. */
    static final class Table {
        private final Map<String, int[]> arity = new TreeMap<>();
        private final Map<String, ElFunction> functions = new HashMap<>();
        private final Map<String, ElForm> forms = new HashMap<>();
        private final Set<String> syntax = new HashSet<>();
        private final Set<String> passMask = new HashSet<>();
        private final Map<String, Function<List<Expr>, String>> checks = new HashMap<>();
        private final Map<String, String> old = new HashMap<>();
        private final Map<String, String> family = new HashMap<>();
        private String current = "";

        /** The family the names registered next belong to (the catalogue's sections). */
        Table family(String name) {
            current = name;
            return this;
        }

        private void name(String name, int min, int max) {
            if (arity.putIfAbsent(name, new int[] {min, max}) != null) {
                throw new IllegalStateException("Rachana-EL function " + name + " is registered twice");
            }
            family.put(name, current);
        }

        /** An eager function: its arguments evaluated, a masked one making the result masked. */
        Table fn(String name, int min, int max, ElFunction f) {
            name(name, min, max);
            functions.put(name, f);
            return this;
        }

        /** A form: its arguments unevaluated. */
        Table form(String name, int min, int max, ElForm f) {
            name(name, min, max);
            forms.put(name, f);
            return this;
        }

        /** A name the parser builds itself. */
        Table syntax(String name, int min, int max) {
            name(name, min, max);
            syntax.add(name);
            return this;
        }

        /** {@code name} sees a masked argument as it is (it decides itself what a mask means). */
        Table passesMask(String name) {
            passMask.add(name);
            return this;
        }

        /** A check of the literal arguments, run when an expression compiles. */
        Table check(String name, Function<List<Expr>, String> c) {
            checks.put(name, c);
            return this;
        }

        /** {@code oldName} read for compatibility: the same entry as {@code name}, with its own argument count. */
        Table old(String oldName, String name, int min, int max) {
            name(oldName, min, max);
            if (functions.containsKey(name)) {
                functions.put(oldName, functions.get(name));
            }
            if (checks.containsKey(name)) {
                checks.put(oldName, checks.get(name));
            }
            old.put(oldName, name);
            return this;
        }

        /** {@code oldName} read for compatibility with the results it always gave ({@code f}, sharing the one name's core). */
        Table old(String oldName, String name, int min, int max, ElFunction f) {
            name(oldName, min, max);
            functions.put(oldName, f);
            old.put(oldName, name);
            return this;
        }
    }
}
